package dev.etran

import com.andrei1058.bedwars.api.arena.IArena
import com.andrei1058.bedwars.api.server.ServerType
import dev.etran.adapter.LoaderRestoreAdapter
import dev.etran.config.LoaderSettings
import dev.etran.pool.ArenaPool
import dev.etran.storage.FileOps
import dev.etran.storage.WorldStore
import dev.etran.world.RuntimeWorlds
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.logging.Logger

/**
 * Hooks this plugin into a BedWars1058 that has already started.
 *
 * Why this is needed: BedWars1058 creates every arena *inside its own onEnable* with its built-in restore
 * adapter, before any add-on plugin can run. At that point
 *  - maps that were moved out of the server root look "missing", so BedWars skipped those arenas, and
 *  - the first arena of the enable queue is already being loaded by the built-in adapter.
 * [run] swaps the adapter, hands that first arena a correct map, and [registerMissingArenas] re-creates every
 * arena BedWars skipped (spread over several ticks so startup does not stall).
 */
class Takeover(
    private val bw: BwContext,
    private val store: WorldStore,
    private val runtime: RuntimeWorlds,
    private val pool: ArenaPool,
    private val adapter: LoaderRestoreAdapter,
    private val settings: () -> LoaderSettings,
    private val log: Logger,
) {
    private class Seed(val arena: IArena, val zip: File, val backup: File?)

    private val seeds = ArrayList<Seed>()

    /** Installs the adapter. Returns false (after logging why) when that is not possible. */
    fun run(): Boolean {
        val queued = bw.queue.toList()
        runtime.sweepOrphans(queued.map { it.worldName.lowercase(Locale.ROOT) }.toSet())

        // The head of the queue is already being loaded by BedWars' built-in adapter. Let it finish, but make
        // sure it reads the right map: give it the stored map as the "pristine cache" zip it looks for.
        val head = queued.firstOrNull()
        if (head != null) {
            pool.markLoading(head)
            seedStockCache(head)
            queued.drop(1).forEach { pool.expectParking(it.arenaName) }
        }

        try {
            bw.api.setRestoreAdapter(adapter)
        } catch (ex: IllegalAccessError) {
            log.severe("BedWars1058 refused the new restore adapter: ${ex.message}")
            log.severe("Arenas were already running when this plugin started. Restart the server instead of reloading plugins.")
            cleanupSeeds()
            return false
        }
        log.info("Hooked into BedWars1058 as restore adapter (${adapter.displayName}).")
        return true
    }

    private fun seedStockCache(head: IArena) {
        if (store.find(head.arenaName) == null) return // nothing stored: the built-in adapter uses the map in the root
        try {
            val zip = File(bw.stockCacheDir, head.arenaName + ".zip")
            val backup = File(bw.stockCacheDir, head.arenaName + ".zip.maploader-bak")
            bw.stockCacheDir.mkdirs()
            var kept: File? = null
            if (zip.exists()) {
                FileOps.moveReplacing(zip.toPath(), backup.toPath())
                kept = backup
            }
            store.exportAsZip(head.arenaName, zip.toPath())
            seeds += Seed(head, zip, kept)
        } catch (ex: Exception) {
            log.warning("Could not prepare the first arena '${head.arenaName}': ${ex.message}")
        }
    }

    /** BedWars finished initialising [a]; if it was the first, hand its temporary cache zip back. */
    fun onArenaEnabled(a: IArena) {
        val seed = seeds.firstOrNull { it.arena === a } ?: return
        seeds.remove(seed)
        restore(seed)
        // This world was copied from our library by the built-in adapter, so it is a disposable runtime copy.
        runtime.adopt(a.worldName)
    }

    fun cleanupSeeds() {
        seeds.toList().forEach { restore(it) }
        seeds.clear()
    }

    private fun restore(seed: Seed) {
        try {
            seed.zip.delete()
            if (seed.backup != null && seed.backup.exists()) {
                FileOps.moveReplacing(seed.backup.toPath(), seed.zip.toPath())
            }
        } catch (ex: Exception) {
            log.warning("Could not restore BedWars1058's cache file ${seed.zip.name}: ${ex.message}")
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Creates the arenas BedWars1058 skipped at startup because their map is no longer in the server root.
     * [registerPerTick] arenas are created per tick.
     */
    fun registerMissingArenas(plugin: WorldLoaderPlugin, onDone: () -> Unit = {}) {
        val files = bw.arenasDir.listFiles { f -> f.isFile && f.name.endsWith(".yml") }
        if (files == null || files.isEmpty()) {
            onDone()
            return
        }
        val known = HashSet<String>()
        bw.queue.forEach { known += it.arenaName.lowercase(Locale.ROOT) }
        bw.arenaUtil.arenas.forEach { known += it.arenaName.lowercase(Locale.ROOT) }
        pool.coldNames().forEach { known += it.lowercase(Locale.ROOT) }

        var todo = files.map { it.name.removeSuffix(".yml") }
            .filter { it.lowercase(Locale.ROOT) !in known }
            .sortedBy { it.lowercase(Locale.ROOT) }

        // On a BungeeCord server without auto-scale BedWars1058 only ever loads ONE random arena.
        if (bw.serverType == ServerType.BUNGEE && !bw.autoscale) {
            if (known.isNotEmpty() || todo.isEmpty()) todo = emptyList() else todo = listOf(todo.random())
        }

        if (todo.isEmpty()) {
            onDone()
            return
        }
        log.info("Registering ${todo.size} arena(s) whose maps are in the library...")
        val queue = ArrayDeque(todo)
        object : BukkitRunnable() {
            override fun run() {
                if (bw.isShuttingDown()) {
                    cancel()
                    return
                }
                var n = 0
                val perTick = settings().loading.registerPerTick
                while (n < perTick && queue.isNotEmpty()) {
                    register(queue.poll())
                    n++
                }
                if (queue.isEmpty()) {
                    cancel()
                    onDone()
                }
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    private fun register(name: String) {
        try {
            pool.expectParking(name)
            bw.arenaUtil.loadArena(name, null)
            pool.settleExpectation(name)
        } catch (ex: Exception) {
            log.warning("Could not register arena '$name': ${ex.message}")
        }
    }

    /** Names of arenas BedWars1058 has configuration for (used by the migrate command). */
    fun configuredArenaNames(): List<String> =
        bw.arenasDir.listFiles { f -> f.isFile && f.name.endsWith(".yml") }
            ?.map { it.name.removeSuffix(".yml") }?.sorted() ?: emptyList()
}
