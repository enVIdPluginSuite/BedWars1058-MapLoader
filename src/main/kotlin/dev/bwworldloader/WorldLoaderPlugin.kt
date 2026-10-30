package dev.bwworldloader

import com.andrei1058.bedwars.api.BedWars
import dev.bwworldloader.adapter.LoaderRestoreAdapter
import dev.bwworldloader.command.LoaderCommand
import dev.bwworldloader.config.ConfigService
import dev.bwworldloader.config.LoadMode
import dev.bwworldloader.listener.ArenaListener
import dev.bwworldloader.pool.ArenaPool
import dev.bwworldloader.storage.FileOps
import dev.bwworldloader.storage.WorldStore
import dev.bwworldloader.world.IoExecutor
import dev.bwworldloader.world.RuntimeWorlds
import dev.bwworldloader.world.WorldActivator
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale
import java.util.logging.Filter
import java.util.logging.Logger

/** Everything that exists while the plugin is active; `null` in [WorldLoaderPlugin.services] otherwise. */
class Services(
    val bw: BwContext,
    val settingsService: ConfigService,
    val io: IoExecutor,
    val store: WorldStore,
    val runtime: RuntimeWorlds,
    val pool: ArenaPool,
    val activator: WorldActivator,
    val adapter: LoaderRestoreAdapter,
    val takeover: Takeover,
)

/** Calls [onEnabled] once BedWars1058 has finished enabling (its arenas exist by then). */
class BedWarsEnableListener(private val bedwars: Plugin, private val onEnabled: () -> Unit) : Listener {
    @EventHandler
    fun onPluginEnable(e: PluginEnableEvent) {
        if (e.plugin === bedwars) {
            HandlerList.unregisterAll(this)
            onEnabled()
        }
    }
}

class WorldLoaderPlugin : JavaPlugin() {

    @Volatile
    var services: Services? = null
        private set

    private var settingsService: ConfigService? = null
    private var earlyStore: WorldStore? = null
    private var filteredLogger: Logger? = null
    private var previousFilter: Filter? = null
    private var hooked = false

    data class ReloadOutcome(val ok: Boolean, val message: String, val warnings: List<String> = emptyList())

    data class MigrationPlan(val movable: List<String>, val alreadyStored: List<String>, val inUse: List<String>)

    /**
     * This plugin is enabled BEFORE BedWars1058 (`loadbefore` in plugin.yml) so it can silence BedWars' startup
     * warnings about maps that live in the library. The real work starts in [hook] when BedWars is done enabling.
     */
    override fun onEnable() {
        val cfg = ConfigService(this)
        cfg.load().forEach { logger.warning(it) }
        settingsService = cfg
        val store = WorldStore(server.worldContainer, logger, cfg.settings.storage)
        store.rebind(cfg.settings.storage)
        earlyStore = store

        val bwPlugin = server.pluginManager.getPlugin("BedWars1058")
        if (bwPlugin == null) {
            logger.severe("BedWars1058 is not installed. Disabling.")
            server.pluginManager.disablePlugin(this)
            return
        }
        installWarningFilter(bwPlugin, store)

        if (bwPlugin.isEnabled) {
            hook(bwPlugin) // this plugin was loaded late (plugin manager / reload): BedWars is already running
        } else {
            server.pluginManager.registerEvents(BedWarsEnableListener(bwPlugin) { hook(bwPlugin) }, this)
            server.scheduler.runTask(this, Runnable {
                if (!hooked && !bwPlugin.isEnabled) {
                    logger.severe("BedWars1058 did not enable, so BedWars1058-MapLoader stays inactive.")
                }
            })
        }
    }

    private fun installWarningFilter(bwPlugin: Plugin, store: WorldStore) {
        val target = bwPlugin.logger
        previousFilter = target.filter
        filteredLogger = target
        target.filter = MapWarningFilter({ name -> store.exists(name) }, previousFilter)
    }

    private fun removeWarningFilter() {
        val target = filteredLogger ?: return
        if (target.filter is MapWarningFilter) target.filter = previousFilter
        filteredLogger = null
    }

    /** BedWars1058 finished enabling: take over its world loading. */
    private fun hook(bwPlugin: Plugin) {
        if (hooked) return
        val settingsService = settingsService ?: return
        val store = earlyStore ?: return
        val api = server.servicesManager.getRegistration(BedWars::class.java)?.provider
        if (api == null) {
            logger.severe("BedWars1058 did not register its API. Disabling.")
            server.pluginManager.disablePlugin(this)
            return
        }
        val settings = { settingsService.settings }

        val built = try {
            build(BwContext(this, api, bwPlugin), settingsService, store, settings)
        } catch (ex: LinkageError) {
            logger.severe("This BedWars1058 version is not compatible with this plugin (${ex.javaClass.simpleName}: ${ex.message}).")
            server.pluginManager.disablePlugin(this)
            return
        }

        if (!built.takeover.run()) {
            built.io.shutdown(2)
            server.pluginManager.disablePlugin(this)
            return
        }
        hooked = true
        services = built

        server.pluginManager.registerEvents(ArenaListener(built.bw, built.pool, built.takeover, settings), this)
        val command = LoaderCommand(this)
        getCommand("bwloader")?.let {
            it.setExecutor(command)
            it.tabCompleter = command
        }
        built.pool.onStall = { head -> built.adapter.skipStuck(head) }
        server.scheduler.runTaskTimer(this, Runnable { built.pool.tick() }, 40L, 20L)
        // flush worlds that are being edited with /bw setupArena every 2 minutes
        server.scheduler.runTaskTimer(this, Runnable { built.adapter.checkpointSetupWorlds() }, 2400L, 2400L)

        built.takeover.registerMissingArenas(this) { built.pool.requestRefill() }

        val s = settingsService.settings
        logger.info("Map library: ${built.store.storageDir} (${built.store.templates().size} maps), mode ${s.loading.mode}, " +
            "${s.loading.warmPerGroup} warm arena(s) per group.")
    }

    private fun build(bw: BwContext, cfg: ConfigService, store: WorldStore, settings: () -> dev.bwworldloader.config.LoaderSettings): Services {
        val initial = cfg.settings
        val io = IoExecutor(logger, initial.loading.ioThreads)
        val runtime = RuntimeWorlds(bw, io, server.worldContainer, logger)
        runtime.setupRecovery = { worldName ->
            val dir = java.io.File(server.worldContainer, worldName)
            if (!FileOps.isWorldFolder(dir)) {
                true // an empty leftover: nothing to save
            } else {
                try {
                    store.saveFromRuntime(worldName, worldName, forceBackup = true)
                    logger.warning("Recovered unsaved /bw setupArena work for '$worldName' after an unclean shutdown: " +
                        "it was saved into the library (the previous version is in ${store.storageDir.resolve(".backups").resolve(worldName)}).")
                    true
                } catch (ex: Exception) {
                    logger.log(java.util.logging.Level.SEVERE, "Could not recover setup work for '$worldName'", ex)
                    false
                }
            }
        }
        val pool = ArenaPool(bw, settings, logger)
        val activator = WorldActivator(bw, store, runtime, pool, io, settings, logger)
        val adapter = LoaderRestoreAdapter(this, bw, store, runtime, pool, activator, io, settings, server.worldContainer, logger)
        activator.isSetupWorld = { name -> adapter.isSetupWorld(name) }
        val takeover = Takeover(bw, store, runtime, pool, adapter, settings, logger)
        return Services(bw, cfg, io, store, runtime, pool, activator, adapter, takeover)
    }

    override fun onDisable() {
        removeWarningFilter()
        val rt = services ?: return
        server.scheduler.cancelTasks(this)
        rt.takeover.cleanupSeeds()
        // Anyone still running /bw setupArena: save their work into the library before the server stops.
        rt.adapter.finishSetupSessions()
        rt.pool.hideAllListed()
        rt.io.shutdown(15)
        // Intentionally keep the services reference: BedWars1058 disables its arenas AFTER this plugin
        // and still calls the restore adapter, which then runs synchronously (see BwContext.isShuttingDown).
    }

    // ---------------------------------------------------------------------------------------------
    // Reload / live changes
    // ---------------------------------------------------------------------------------------------

    /** `/bwloader reload`: re-read config.yml and apply everything that can change at runtime. */
    fun reloadSettings(): ReloadOutcome {
        val rt = services ?: return ReloadOutcome(false, "Plugin is not active.")
        return when (val r = rt.settingsService.reload()) {
            is ConfigService.ReloadResult.Failed -> ReloadOutcome(false, r.message + " The previous settings stay active.")
            is ConfigService.ReloadResult.Applied -> {
                applyAll(rt)
                ReloadOutcome(
                    true,
                    "Reloaded. Library: ${rt.store.storageDir} (${rt.store.templates().size} maps), mode ${r.settings.loading.mode}.",
                    r.warnings,
                )
            }
        }
    }

    /** Applies one changed key (from `/bwloader config set`); returns a short note for the admin. */
    fun applySettingsChange(key: String): String? {
        val rt = services ?: return null
        applyAll(rt)
        return when {
            key.startsWith("storage.") -> "Library: ${rt.store.storageDir} (${rt.store.templates().size} maps). " +
                "Maps are not moved automatically - use /bwloader migrate to move existing ones."
            key == "loading.mode" -> if (rt.settingsService.settings.loading.mode == LoadMode.EAGER)
                "Parked arenas are being loaded now." else "Arenas will be parked as they restart."
            else -> null
        }
    }

    private fun applyAll(rt: Services) {
        val s = rt.settingsService.settings
        rt.store.rebind(s.storage)
        rt.io.resize(s.loading.ioThreads)
        if (s.loading.mode == LoadMode.EAGER && rt.pool.coldCount() > 0) {
            val n = rt.pool.activateAll()
            logger.info("Mode is EAGER: loading $n parked arena(s).")
        }
        rt.pool.syncListing()
        rt.pool.requestRefill()
    }

    // ---------------------------------------------------------------------------------------------
    // Migration of maps from the server root into the library
    // ---------------------------------------------------------------------------------------------

    fun planMigration(): MigrationPlan {
        val rt = services ?: return MigrationPlan(emptyList(), emptyList(), emptyList())
        val protectedNames = rt.runtime.protectedNames()
        val movable = ArrayList<String>()
        val both = ArrayList<String>()
        val inUse = ArrayList<String>()
        for (name in rt.takeover.configuredArenaNames()) {
            if (rt.store.legacyRootWorld(name) == null) continue
            val key = name.lowercase(Locale.ROOT)
            when {
                key in protectedNames || rt.runtime.isOwned(name) || Bukkit.getWorld(name) != null -> inUse += name
                rt.store.find(name) != null -> both += name
                else -> movable += name
            }
        }
        return MigrationPlan(movable, both, inUse)
    }

    fun runMigration(plan: MigrationPlan, sender: CommandSender) {
        val rt = services ?: return
        sender.sendMessage(ChatColor.GRAY.toString() + "Moving ${plan.movable.size} map(s) in the background...")
        rt.io.submit {
            var moved = 0
            val failed = ArrayList<String>()
            for (name in plan.movable) {
                try {
                    val dir = rt.store.legacyRootWorld(name) ?: continue
                    rt.store.importLegacy(name, dir)
                    moved++
                } catch (ex: Exception) {
                    failed += name
                    logger.warning("Could not move map '$name': ${ex.message}")
                }
            }
            val movedCount = moved
            rt.bw.sync {
                sender.sendMessage(ChatColor.GREEN.toString() + "Moved $movedCount map(s) into ${rt.store.storageDir}.")
                if (failed.isNotEmpty()) {
                    sender.sendMessage(ChatColor.RED.toString() + "Failed: ${failed.joinToString(", ")} (see console).")
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Restore: library -> server root (the reverse of migrate)
    // ---------------------------------------------------------------------------------------------

    data class RestorePlan(val restorable: List<String>, val conflicts: List<String>, val inUse: List<String>, val notFound: List<String>)

    /** [target] is a map name or "all". */
    fun planRestore(target: String): RestorePlan {
        val rt = services ?: return RestorePlan(emptyList(), emptyList(), emptyList(), emptyList())
        val protectedNames = rt.runtime.protectedNames()
        val names = if (target.equals("all", ignoreCase = true)) rt.store.names()
        else listOf(rt.store.find(target)?.name ?: target)
        val ok = ArrayList<String>()
        val conflicts = ArrayList<String>()
        val inUse = ArrayList<String>()
        val missing = ArrayList<String>()
        for (name in names) {
            val key = name.lowercase(Locale.ROOT)
            when {
                rt.store.find(name) == null -> missing += name
                key in protectedNames || rt.runtime.isOwned(name) || Bukkit.getWorld(name) != null -> inUse += name
                java.io.File(server.worldContainer, name).exists() -> conflicts += name
                else -> ok += name
            }
        }
        return RestorePlan(ok, conflicts, inUse, missing)
    }

    fun runRestore(plan: RestorePlan, move: Boolean, sender: CommandSender) {
        val rt = services ?: return
        sender.sendMessage(ChatColor.GRAY.toString() + "Restoring ${plan.restorable.size} map(s) into the server root in the background...")
        rt.io.submit {
            var done = 0
            val failed = ArrayList<String>()
            for (name in plan.restorable) {
                try {
                    rt.store.restoreToRoot(name, move)
                    done++
                } catch (ex: Exception) {
                    failed += name
                    logger.warning("Could not restore map '$name': ${ex.message}")
                }
            }
            val count = done
            rt.bw.sync {
                sender.sendMessage(ChatColor.GREEN.toString() + "Restored $count map(s) into ${server.worldContainer.path}" +
                    if (move) " (removed from the library)." else " (copies stay in the library).")
                if (failed.isNotEmpty()) sender.sendMessage(ChatColor.RED.toString() + "Failed: ${failed.joinToString(", ")} (see console).")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Disable / enable arenas (so their maps can be moved while the server runs)
    // ---------------------------------------------------------------------------------------------

    data class DisablePlan(val loaded: List<String>, val parked: List<String>, val busy: List<String>, val notFound: List<String>, val players: Int)

    /** Arenas that were disabled with /bwloader disable and not enabled again yet (until the next restart). */
    private val disabledArenas: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun disabledArenaNames(): List<String> = disabledArenas.sorted()

    /** Names of every arena BedWars knows right now (loaded, parked, loading). */
    fun knownArenaNames(): List<String> {
        val rt = services ?: return emptyList()
        val names = LinkedHashSet<String>()
        rt.bw.arenas.forEach { names += it.arenaName }
        rt.pool.coldNames().forEach { names += it }
        rt.bw.queue.forEach { names += it.arenaName }
        return names.sortedBy { it.lowercase(Locale.ROOT) }
    }

    /** [target] is an arena name or "all". */
    fun planDisable(target: String): DisablePlan {
        val rt = services ?: return DisablePlan(emptyList(), emptyList(), emptyList(), emptyList(), 0)
        val names = if (target.equals("all", ignoreCase = true)) knownArenaNames()
        else listOf(knownArenaNames().firstOrNull { it.equals(target, ignoreCase = true) } ?: target)
        val loaded = ArrayList<String>()
        val parked = ArrayList<String>()
        val busy = ArrayList<String>()
        val missing = ArrayList<String>()
        var players = 0
        for (name in names) {
            val real = rt.bw.arenas.firstOrNull { it.arenaName.equals(name, ignoreCase = true) && !rt.pool.isListed(it) }
            when {
                rt.pool.listedByName(name) != null || (rt.pool.coldNames().any { it.equals(name, true) } && real == null) -> parked += name
                real != null -> {
                    loaded += name
                    players += real.players.size + real.spectators.size
                }
                rt.bw.queue.any { it.arenaName.equals(name, ignoreCase = true) } -> busy += name
                else -> missing += name
            }
        }
        return DisablePlan(loaded, parked, busy, missing, players)
    }

    fun runDisable(plan: DisablePlan): Int {
        val rt = services ?: return 0
        var done = 0
        for (name in plan.parked) {
            if (rt.pool.disableListed(name)) {
                disabledArenas += name
                done++
            }
        }
        for (name in plan.loaded) {
            val a = rt.bw.arenas.firstOrNull { it.arenaName.equals(name, ignoreCase = true) && !rt.pool.isListed(it) } ?: continue
            try {
                a.disable() // BedWars kicks the players, calls our adapter (world unloaded + copy deleted) and forgets the arena
                disabledArenas += name
                done++
            } catch (ex: Exception) {
                logger.warning("Could not disable arena '$name': ${ex.message}")
            }
        }
        return done
    }

    /** Registers disabled arenas with BedWars again. [target] is an arena name or "all". Returns how many were enabled. */
    fun enableArenas(target: String): List<String> {
        val rt = services ?: return emptyList()
        val configured = rt.takeover.configuredArenaNames()
        val names = if (target.equals("all", ignoreCase = true)) (disabledArenas.toList() + configured).distinct()
        else listOf(configured.firstOrNull { it.equals(target, ignoreCase = true) } ?: return emptyList())
        val known = knownArenaNames().map { it.lowercase(Locale.ROOT) }.toSet()
        val enabled = ArrayList<String>()
        for (name in names) {
            if (name.lowercase(Locale.ROOT) in known) continue
            try {
                rt.pool.expectParking(name)
                rt.bw.arenaUtil.loadArena(name, null)
                rt.pool.settleExpectation(name)
                disabledArenas.removeIf { it.equals(name, ignoreCase = true) }
                enabled += name
            } catch (ex: Exception) {
                logger.warning("Could not enable arena '$name': ${ex.message}")
            }
        }
        rt.pool.requestRefill()
        return enabled
    }

    // ---------------------------------------------------------------------------------------------
    // Sleep: put loaded arenas back into the library right now (they stay listed and wake up on demand)
    // ---------------------------------------------------------------------------------------------

    data class SleepPlan(val idle: List<com.andrei1058.bedwars.api.arena.IArena>, val busy: List<com.andrei1058.bedwars.api.arena.IArena>)

    /** [target] is "idle"/"all" or an arena name. */
    fun planSleep(target: String): SleepPlan {
        val rt = services ?: return SleepPlan(emptyList(), emptyList())
        val real = rt.bw.arenas.filter { !rt.pool.isListed(it) }
        val chosen = if (target.equals("idle", true) || target.equals("all", true)) real
        else real.filter { it.arenaName.equals(target, ignoreCase = true) }
        val idle = ArrayList<com.andrei1058.bedwars.api.arena.IArena>()
        val busy = ArrayList<com.andrei1058.bedwars.api.arena.IArena>()
        for (a in chosen) {
            val isIdle = a.status == com.andrei1058.bedwars.api.arena.GameState.waiting && a.players.isEmpty() && a.spectators.isEmpty()
            if (isIdle) idle += a else busy += a
        }
        return SleepPlan(idle, busy)
    }

    /** Sends the arenas to sleep: world unloaded, runtime copy deleted, arena parked and listed again. */
    fun runSleep(plan: SleepPlan, includeBusy: Boolean): Int {
        val rt = services ?: return 0
        var n = 0
        val all = if (includeBusy) plan.idle + plan.busy else plan.idle
        for (a in all) {
            try {
                for (p in a.players.toList()) a.removePlayer(p, false)
                for (p in a.spectators.toList()) a.removeSpectator(p, false)
                a.restart() // -> our adapter deletes the copy and re-registers the arena, which is then parked
                n++
            } catch (ex: Exception) {
                logger.warning("Could not put arena '${a.arenaName}' to sleep: ${ex.message}")
            }
        }
        rt.pool.touch()
        return n
    }
}
