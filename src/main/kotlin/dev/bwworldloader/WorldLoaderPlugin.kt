package dev.bwworldloader

import com.andrei1058.bedwars.api.BedWars
import dev.bwworldloader.adapter.LoaderRestoreAdapter
import dev.bwworldloader.command.LoaderCommand
import dev.bwworldloader.config.ConfigService
import dev.bwworldloader.config.LoadMode
import dev.bwworldloader.listener.ArenaListener
import dev.bwworldloader.pool.ArenaPool
import dev.bwworldloader.storage.WorldStore
import dev.bwworldloader.world.IoExecutor
import dev.bwworldloader.world.RuntimeWorlds
import dev.bwworldloader.world.WorldActivator
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale

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

class WorldLoaderPlugin : JavaPlugin() {

    @Volatile
    var services: Services? = null
        private set

    data class ReloadOutcome(val ok: Boolean, val message: String, val warnings: List<String> = emptyList())

    data class MigrationPlan(val movable: List<String>, val alreadyStored: List<String>, val inUse: List<String>)

    override fun onEnable() {
        val bwPlugin = server.pluginManager.getPlugin("BedWars1058")
        if (bwPlugin == null || !bwPlugin.isEnabled) {
            logger.severe("BedWars1058 is not installed or failed to enable. Disabling.")
            server.pluginManager.disablePlugin(this)
            return
        }
        val api = server.servicesManager.getRegistration(BedWars::class.java)?.provider
        if (api == null) {
            logger.severe("BedWars1058 did not register its API. Disabling.")
            server.pluginManager.disablePlugin(this)
            return
        }

        val settingsService = ConfigService(this)
        settingsService.load().forEach { logger.warning(it) }
        val settings = { settingsService.settings }

        val built = try {
            build(BwContext(this, api, bwPlugin), settingsService, settings)
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
        services = built

        server.pluginManager.registerEvents(ArenaListener(built.pool, built.takeover, settings), this)
        val command = LoaderCommand(this)
        getCommand("bwloader")?.let {
            it.setExecutor(command)
            it.tabCompleter = command
        }
        built.pool.onStall = { head -> built.adapter.skipStuck(head) }
        server.scheduler.runTaskTimer(this, Runnable { built.pool.tick() }, 40L, 20L)

        built.takeover.registerMissingArenas(this) { built.pool.requestRefill() }

        val s = settingsService.settings
        logger.info("Map library: ${built.store.storageDir} (${built.store.templates().size} maps), mode ${s.loading.mode}, " +
            "${s.loading.warmPerGroup} warm arena(s) per group.")
    }

    private fun build(bw: BwContext, cfg: ConfigService, settings: () -> dev.bwworldloader.config.LoaderSettings): Services {
        val initial = cfg.settings
        val io = IoExecutor(logger, initial.loading.ioThreads)
        val store = WorldStore(server.worldContainer, logger, initial.storage)
        store.rebind(initial.storage)
        val runtime = RuntimeWorlds(bw, io, server.worldContainer, logger)
        val pool = ArenaPool(bw, settings, logger)
        val activator = WorldActivator(bw, store, runtime, pool, io, settings, logger)
        val adapter = LoaderRestoreAdapter(this, bw, store, runtime, pool, activator, io, settings, server.worldContainer, logger)
        val takeover = Takeover(bw, store, runtime, pool, adapter, settings, logger)
        return Services(bw, cfg, io, store, runtime, pool, activator, adapter, takeover)
    }

    override fun onDisable() {
        val rt = services ?: return
        server.scheduler.cancelTasks(this)
        rt.takeover.cleanupSeeds()
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
}
