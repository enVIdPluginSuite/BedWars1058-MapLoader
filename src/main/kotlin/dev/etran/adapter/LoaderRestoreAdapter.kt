package dev.etran.adapter

import com.andrei1058.bedwars.api.arena.IArena
import com.andrei1058.bedwars.api.configuration.ConfigPath
import com.andrei1058.bedwars.api.server.ISetupSession
import com.andrei1058.bedwars.api.server.RestoreAdapter
import com.andrei1058.bedwars.api.server.ServerType
import dev.etran.BwContext
import dev.etran.config.LoaderSettings
import dev.etran.pool.ArenaPool
import dev.etran.storage.FileOps
import dev.etran.storage.WorldStore
import dev.etran.world.IoExecutor
import dev.etran.world.RuntimeWorlds
import dev.etran.world.WorldActivator
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
    //
    // A setup world holds the builder's work, so it is handled much more carefully than a disposable arena copy:
    //  - it carries a SETUP marker; after a crash the next start saves it into the library instead of deleting it
    //  - it is only written back if the session really got going (a failed or cancelled start can never overwrite
    //    the stored map with a half-copied folder)
    //  - the library entry is replaced atomically and the previous version is kept as a backup
    //  - the world is unloaded (saved) before it is copied, and its folder is only deleted after a successful save
    //  - it is also saved periodically and when the server stops
    // ---------------------------------------------------------------------------------------------

    private class SetupState(val worldName: String) {
        @Volatile var active = false
        @Volatile var cancelled = false
        @Volatile var closing = false
    }

    private val setups = java.util.concurrent.ConcurrentHashMap<String, SetupState>()

    private fun key(worldName: String) = worldName.lowercase(Locale.ROOT)

    /** True while a setup session owns the world called [worldName]. */
    fun isSetupWorld(worldName: String): Boolean = setups.containsKey(key(worldName))

    override fun onSetupSessionStart(s: ISetupSession) {
        val worldName = s.worldName
        val state = SetupState(worldName)
        if (setups.putIfAbsent(key(worldName), state) != null) {
            s.player.sendMessage(ChatColor.RED.toString() + "A setup session for " + worldName + " is already running.")
            return
        }
        beginSetup(s, state, 0)
    }

    private fun beginSetup(s: ISetupSession, state: SetupState, attempt: Int) {
        val worldName = state.worldName
        if (state.cancelled) {
            setups.remove(key(worldName), state)
            return
        }
        // The arena of the same name was just disabled and its world is still being unloaded: wait for it.
        if (Bukkit.getWorld(worldName) != null) {
            if (attempt < 30 && !bw.isShuttingDown()) {
                if (attempt == 0) s.player.sendMessage(ChatColor.GRAY.toString() + "Waiting for the arena world to unload...")
                bw.syncLater(10L) { beginSetup(s, state, attempt + 1) }
            } else {
                abortSetup(s, state, "The world $worldName is still loaded. Disable the arena first, then try again.")
            }
            return
        }
        val protectedNames = runtime.protectedNames()
        io.submit {
            // keep level.dat (deleteLevelDat = false) so the map round-trips exactly
            val result = store.materialize(worldName, worldName, protectedNames, false, setup = true)
            bw.sync { setupWorldReady(s, state, result) }
        }
    }

    private fun abortSetup(s: ISetupSession, state: SetupState, message: String) {
        setups.remove(key(state.worldName), state) // first: the close callback below must find nothing to save
        s.player.sendMessage(ChatColor.RED.toString() + message)
        s.close()
    }

    private fun setupWorldReady(s: ISetupSession, state: SetupState, result: WorldStore.Materialized) {
        val player = s.player
        val worldName = state.worldName
        if (state.cancelled) {
            // The builder left before the world was ready: nothing was edited, so the pristine copy can go.
            setups.remove(key(worldName), state)
            if (result is WorldStore.Materialized.Ready) runtime.deleteMarkedCopy(worldName, includeSetup = true)
            return
        }
        when (result) {
            is WorldStore.Materialized.Ready ->
                player.sendMessage(ChatColor.GREEN.toString() + "Loading " + worldName + " from the map library.")
            is WorldStore.Materialized.Missing ->
                player.sendMessage(ChatColor.GREEN.toString() + "Creating a new void map: " + worldName)
            is WorldStore.Materialized.Failed -> {
                log.log(Level.SEVERE, "Could not prepare setup world '$worldName'", result.error)
                // A refusal means something else sits there (unsaved work, a foreign world): never touch it.
                if (result.error !is dev.etran.storage.UnsafeTargetException) runtime.deleteMarkedCopy(worldName, includeSetup = true)
                abortSetup(s, state, "Could not load " + worldName + ": " + result.error.message)
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
            // Nothing could have been edited yet, so the copy is disposable.
            runtime.deleteNow(worldName, allowSetup = true)
            abortSetup(s, state, "Could not load the world $worldName")
            return
        }
        try {
            // a brand new map has no marker yet (Bukkit just created the folder)
            val marker = File(world.worldFolder, FileOps.SETUP_MARKER)
            if (!marker.exists()) marker.writeText(System.currentTimeMillis().toString())
        } catch (ex: Exception) {
            log.warning("Could not mark setup world '$worldName': ${ex.message}")
        }
        state.active = true
        bw.syncLater(20L) { s.teleportPlayer() }
    }

    override fun onSetupSessionClose(s: ISetupSession) {
        val worldName = s.worldName
        val state = setups[key(worldName)] ?: return // aborted start: nothing to save
        if (!state.active) {
            state.cancelled = true // still starting; setupWorldReady cleans up
            return
        }
        if (state.closing) return
        state.closing = true
        // Arenas of this name must not start before the new version is in the library.
        store.beginPendingSave(worldName)
        // Next tick: BedWars teleports the builder out of the world right after calling us.
        bw.sync { closeSetupWorld(state, 0) }
    }

    private fun closeSetupWorld(state: SetupState, attempt: Int) {
        val worldName = state.worldName
        val world = Bukkit.getWorld(worldName)
        var unloaded = true
        if (world != null) {
            try {
                world.save()
            } catch (t: Throwable) {
                log.log(Level.WARNING, "Could not save world '$worldName'", t)
            }
            unloaded = try {
                Bukkit.unloadWorld(world, true)
            } catch (t: Throwable) {
                false
            }
            if (!unloaded && attempt < 10 && !bw.isShuttingDown()) {
                bw.syncLater(20L) { closeSetupWorld(state, attempt + 1) }
                return
            }
        }
        val finalUnloaded = unloaded
        val saveJob: () -> Unit = {
            var saved = false
            try {
                val t = store.saveFromRuntime(worldName, worldName)
                log.info("Saved map '$worldName' to ${t.path}")
                saved = true
            } catch (ex: Exception) {
                log.log(Level.SEVERE, "Could not save map '$worldName' to the library. The world stays in the server root " +
                    "and is saved into the library on the next start; the previous version in the library is untouched.", ex)
            } finally {
                store.endPendingSave(worldName)
                setups.remove(key(worldName), state)
            }
            if (saved) {
                if (finalUnloaded) {
                    runtime.deleteNow(worldName, allowSetup = true)
                } else {
                    // still loaded: leave the folder, but demote it to an ordinary disposable copy
                    log.warning("World '$worldName' could not be unloaded; its folder is removed on the next start.")
                    try {
                        File(File(serverRoot, worldName), FileOps.SETUP_MARKER).delete()
                        File(File(serverRoot, worldName), FileOps.MARKER).writeText(System.currentTimeMillis().toString())
                    } catch (ex: Exception) {
                        log.warning("Could not update the marker of '$worldName': ${ex.message}")
                    }
                }
            }
        }
        if (bw.isShuttingDown()) saveJob() else io.submit(saveJob)
    }

    /** Periodic safety net: flush every world that is being edited to disk. Main thread. */
    fun checkpointSetupWorlds() {
        for (state in setups.values) {
            if (!state.active || state.closing) continue
            try {
                Bukkit.getWorld(state.worldName)?.save()
            } catch (t: Throwable) {
                log.log(Level.WARNING, "Could not save setup world '${state.worldName}'", t)
            }
        }
    }

    /** Server shutdown: save every unfinished setup session into the library. Main thread, blocking. */
    fun finishSetupSessions() {
        for (state in setups.values.toList()) {
            if (!state.active || state.closing) continue
            state.closing = true
            store.beginPendingSave(state.worldName)
            closeSetupWorld(state, 10)
        }
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

    override fun getDisplayName(): String = "BedWars1058-MapLoader (just-in-time map loading)"
}
