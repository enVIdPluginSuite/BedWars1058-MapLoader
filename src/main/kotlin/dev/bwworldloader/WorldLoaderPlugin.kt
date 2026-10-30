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

        server.pluginManager.registerEvents(ArenaListener(built.pool, built.takeover, settings), this)
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
}
