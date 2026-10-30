package dev.bwworldloader.adapter

import com.andrei1058.bedwars.api.arena.IArena
import com.andrei1058.bedwars.api.configuration.ConfigPath
import com.andrei1058.bedwars.api.server.ISetupSession
import com.andrei1058.bedwars.api.server.RestoreAdapter
import com.andrei1058.bedwars.api.server.ServerType
import dev.bwworldloader.BwContext
import dev.bwworldloader.config.LoaderSettings
import dev.bwworldloader.pool.ArenaPool
import dev.bwworldloader.storage.FileOps
import dev.bwworldloader.storage.WorldStore
import dev.bwworldloader.world.IoExecutor
import dev.bwworldloader.world.RuntimeWorlds
import dev.bwworldloader.world.WorldActivator
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Replaces BedWars1058's built-in world restore system.
 *
 * Contract (from BedWars1058's `RestoreAdapter`): `onEnable` is called for the arena at the head of the enable
 * queue and the next one is only started after the arena has been initialised, so loading is sequential. This
 * adapter keeps that contract but (a) reads maps from the configured library folder, (b) may *park* an arena
 * instead of loading it (just-in-time mode) and (c) spaces world creation out over time.
 */
class LoaderRestoreAdapter(
    owner: Plugin,
    private val bw: BwContext,
    private val store: WorldStore,
    private val runtime: RuntimeWorlds,
    private val pool: ArenaPool,
    private val activator: WorldActivator,
    private val io: IoExecutor,
    private val settings: () -> LoaderSettings,
    private val serverRoot: File,
    private val log: Logger,
) : RestoreAdapter(owner) {

    // ---------------------------------------------------------------------------------------------
    // Arena lifecycle
    // ---------------------------------------------------------------------------------------------

    override fun onEnable(a: IArena) {
        handleEnable(a)
    }

    /**
     * Entry point for "this arena is now the head of the enable queue".
     * Parked arenas are dropped from the queue in a loop (not recursively) until one has to really load.
     */
    fun handleEnable(first: IArena) {
        var current: IArena? = first
        while (current != null) {
            pool.touch()
            if (!pool.shouldPark(current)) {
                activator.load(current)
                return
            }
            val wasHead = bw.queue.peekFirst() === current
            pool.park(current)
            if (!bw.detach(current)) {
                // This BedWars1058 build hands out a copy of its queue: use its own removal, one arena per tick.
                val parked = current
                bw.sync { bw.dequeueAndAdvance(parked) }
                return
            }
            current = if (wasHead) bw.queue.peekFirst() else null
        }
    }

    /** Used by the stall watchdog: drops the stuck head of the queue and continues with the next arena. */
    fun skipStuck(head: IArena) {
        runtime.deleteMarkedCopy(head.worldName)
        if (bw.detach(head)) {
            bw.queue.peekFirst()?.let { handleEnable(it) }
        } else {
            bw.dequeueAndAdvance(head)
        }
    }

    override fun onRestart(a: IArena) {
        val arenaName = a.arenaName
        val worldName = a.worldName
        bw.sync {
            if (bw.serverType == ServerType.BUNGEE) {
                if (bw.arenaUtil.gamesBeforeRestart == 0) {
                    if (bw.arenaUtil.arenas.isEmpty()) {
                        val cmd = bw.api.configs.mainConfig.getString(ConfigPath.GENERAL_CONFIGURATION_BUNGEE_OPTION_RESTART_CMD)
                        log.info("Dispatching command: $cmd")
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
                    }
                } else {
                    if (bw.arenaUtil.gamesBeforeRestart != -1) {
                        bw.arenaUtil.gamesBeforeRestart = bw.arenaUtil.gamesBeforeRestart - 1
                    }
                    runtime.unloadAndDelete(worldName)
                    if (bw.arenaUtil.canAutoScale(arenaName)) {
                        bw.syncLater(80L) { reenter(arenaName) }
                    }
                }
            } else {
                runtime.unloadAndDelete(worldName)
                bw.syncLater(80L) { reenter(arenaName) }
            }
        }
    }

    /** Creates a fresh arena object for [arenaName]; it will go through the enable queue (and the pool) again. */
    private fun reenter(arenaName: String) {
        if (bw.isShuttingDown()) return
        pool.expectParking(arenaName)
        bw.arenaUtil.loadArena(arenaName, null)
        pool.settleExpectation(arenaName)
    }

    override fun onDisable(a: IArena) {
        val worldName = a.worldName
        if (bw.isShuttingDown()) {
            runtime.unloadAndDelete(worldName)
            return
        }
        bw.sync { runtime.unloadAndDelete(worldName) }
    }

    // ---------------------------------------------------------------------------------------------
    // Setup sessions (/bw setupArena <name>)
    // ---------------------------------------------------------------------------------------------

    override fun onSetupSessionStart(s: ISetupSession) {
        val worldName = s.worldName
        val protectedNames = runtime.protectedNames()
        val deleteLevelDat = settings().world.deleteLevelDat
        io.submit {
            val result = store.materialize(worldName, worldName, protectedNames, deleteLevelDat)
            bw.sync { setupWorldReady(s, result) }
        }
    }

    private fun setupWorldReady(s: ISetupSession, result: WorldStore.Materialized) {
        val player = s.player
        val worldName = s.worldName
        when (result) {
            is WorldStore.Materialized.Ready ->
                player.sendMessage(ChatColor.GREEN.toString() + "Loading " + worldName + " from the map library.")
            is WorldStore.Materialized.Missing ->
                player.sendMessage(ChatColor.GREEN.toString() + "Creating a new void map: " + worldName)
            is WorldStore.Materialized.Failed -> {
                log.log(Level.SEVERE, "Could not prepare setup world '$worldName'", result.error)
                player.sendMessage(ChatColor.RED.toString() + "Could not load " + worldName + ": " + result.error.message)
                s.close()
                return
            }
        }
        runtime.markOwned(worldName)
        val world = try {
            activator.createWorld(worldName, autoSave = true)
        } catch (t: Throwable) {
            log.log(Level.SEVERE, "Could not create setup world '$worldName'", t)
            null
        }
        if (world == null) {
            player.sendMessage(ChatColor.RED.toString() + "Could not load the world " + worldName)
            s.close()
            return
        }
        bw.syncLater(20L) { s.teleportPlayer() }
    }

    override fun onSetupSessionClose(s: ISetupSession) {
        val worldName = s.worldName
        // Next tick: BedWars teleports the builder out of the world right after calling us.
        bw.sync { closeSetupWorld(worldName, 0) }
    }

    private fun closeSetupWorld(worldName: String, attempt: Int) {
        val world = Bukkit.getWorld(worldName)
        if (world != null) {
            world.save()
            val unloaded = try {
                Bukkit.unloadWorld(world, true)
            } catch (t: Throwable) {
                false
            }
            if (!unloaded && attempt < 10 && !bw.isShuttingDown()) {
                bw.syncLater(20L) { closeSetupWorld(worldName, attempt + 1) }
                return
            }
        }
        val saveJob: () -> Unit = {
            try {
                val t = store.saveFromRuntime(worldName, worldName)
                log.info("Saved map '$worldName' to ${t.path}")
                runtime.deleteNow(worldName)
            } catch (ex: Exception) {
                log.log(Level.SEVERE, "Could not save map '$worldName' to the library; the world was left in the server root.", ex)
            }
        }
        if (bw.isShuttingDown()) saveJob() else io.submit(saveJob)
    }

    // ---------------------------------------------------------------------------------------------
    // World library queries / admin commands
    // ---------------------------------------------------------------------------------------------

    override fun isWorld(name: String): Boolean = store.exists(name)

    override fun deleteWorld(name: String) {
        val protectedNames = runtime.protectedNames()
        io.submit {
            val removed = store.delete(name)
            val legacy = store.legacyRootWorld(name)
            if (legacy != null && name.lowercase(Locale.ROOT) !in protectedNames) {
                FileOps.deleteRecursively(legacy.toPath())
            }
            if (removed) log.info("Deleted map '$name' from the library.")
        }
    }

    override fun cloneArena(name1: String, name2: String) {
        io.submit {
            try {
                store.clone(name1, name2)
                log.info("Cloned map '$name1' to '$name2'.")
            } catch (ex: Exception) {
                log.log(Level.SEVERE, "Could not clone map '$name1' to '$name2'", ex)
            }
        }
    }

    override fun getWorldsList(): List<String> {
        val names = LinkedHashSet<String>(store.names())
        if (settings().storage.importFromRoot) names.addAll(store.legacyRootNames())
        return names.sortedBy { it.lowercase(Locale.ROOT) }
    }

    override fun convertWorlds() {
        // BedWars1058's stock implementation lower-cases arena names and removes leftover auto-scale worlds.
        // The only part still relevant here is the cleanup of leftovers from a previous run.
        runtime.sweepOrphans(bw.queue.map { it.worldName.lowercase(Locale.ROOT) }.toSet())
    }

    override fun getDisplayName(): String = "BedWarsWorldLoader (just-in-time map loading)"
}
