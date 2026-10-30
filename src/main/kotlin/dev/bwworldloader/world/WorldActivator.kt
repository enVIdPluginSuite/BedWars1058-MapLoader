package dev.bwworldloader.world

import com.andrei1058.bedwars.api.arena.IArena
import dev.bwworldloader.BwContext
import dev.bwworldloader.config.LoaderSettings
import dev.bwworldloader.pool.ArenaPool
import dev.bwworldloader.storage.WorldStore
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.generator.ChunkGenerator
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Brings one arena world online: copy the map out of the library on a background thread, then create the world
 * on the main thread - but never sooner than `stagger-ticks` after the previous world, so the server keeps
 * ticking smoothly instead of stalling while many maps load back to back.
 */
class WorldActivator(
    private val bw: BwContext,
    private val store: WorldStore,
    private val runtime: RuntimeWorlds,
    private val pool: ArenaPool,
    private val io: IoExecutor,
    private val settings: () -> LoaderSettings,
    private val log: Logger,
) {
    private var lastCreateNanos = 0L
    private val voidGenerator: ChunkGenerator? by lazy { VoidGenerators.create(log) }

    /** Starts loading [a]. BedWars1058 initialises the arena itself when the world's `WorldLoadEvent` fires. */
    fun load(a: IArena) {
        val arenaName = a.arenaName
        val worldName = a.worldName
        pool.markLoading(a)

        // Already loaded (for example by BedWars' own adapter): just hand it over, like the stock adapter does.
        val existing = Bukkit.getWorld(worldName)
        if (existing != null) {
            bw.sync { a.init(existing) }
            return
        }

        val protectedNames = runtime.protectedNames()
        val deleteLevelDat = settings().world.deleteLevelDat
        io.submit {
            val result = store.materialize(arenaName, worldName, protectedNames, deleteLevelDat)
            bw.sync { afterMaterialize(a, result) }
        }
    }

    private fun afterMaterialize(a: IArena, result: WorldStore.Materialized) {
        when (result) {
            is WorldStore.Materialized.Missing ->
                fail(a, "no map named '${a.arenaName}' was found in ${result.searched}", unload = false)
            is WorldStore.Materialized.Failed -> {
                log.log(Level.SEVERE, "Failed to prepare the world for arena '${a.arenaName}'", result.error)
                runtime.deleteMarkedCopy(a.worldName)
                fail(a, result.error.message ?: result.error.javaClass.simpleName, unload = false)
            }
            is WorldStore.Materialized.Ready -> {
                runtime.markOwned(a.worldName)
                if (result.importedFromRoot) pool.touch()
                createWhenDue(a)
            }
        }
    }

    private fun createWhenDue(a: IArena) {
        val staggerTicks = settings().loading.staggerTicks
        val elapsedTicks = (System.nanoTime() - lastCreateNanos) / 50_000_000L
        val wait = staggerTicks - elapsedTicks
        if (lastCreateNanos == 0L || wait <= 0L) createNow(a) else bw.syncLater(wait) { createNow(a) }
    }

    private fun createNow(a: IArena) {
        lastCreateNanos = System.nanoTime()
        pool.touch()
        val world = try {
            createWorld(a.worldName)
        } catch (t: Throwable) {
            log.log(Level.SEVERE, "Bukkit failed to create the world for arena '${a.arenaName}'", t)
            null
        }
        if (world == null) {
            fail(a, "Bukkit could not create the world", unload = true)
            return
        }
        // WorldLoadEvent -> BedWars1058 -> Arena#init has normally already run. If the event was not matched
        // (for example a case difference in the world name) the arena is still queued: initialise it ourselves.
        if (bw.queue.contains(a)) {
            log.warning("BedWars1058 did not pick up world '${world.name}' by itself; initialising arena '${a.arenaName}' manually.")
            a.init(world)
        }
    }

    /** Creates the Bukkit world for an already prepared folder in the server root. */
    fun createWorld(worldName: String, autoSave: Boolean = false): World? {
        val s = settings().world
        val creator = WorldCreator(worldName)
        creator.generateStructures(false)
        if (s.voidGenerator) voidGenerator?.let { creator.generator(it) }
        val world = Bukkit.createWorld(creator) ?: return null
        if (s.keepSpawnInMemory) {
            try {
                @Suppress("DEPRECATION")
                world.keepSpawnInMemory = true
            } catch (t: Throwable) {
                // removed / no-op on newer servers: spawn chunks are no longer kept loaded there anyway
            }
        }
        world.isAutoSave = autoSave
        return world
    }

    /** The load of [a] cannot continue: report, clean up and let BedWars move on to the next arena. */
    fun fail(a: IArena, reason: String, unload: Boolean) {
        log.severe("Cannot load arena '${a.arenaName}': $reason")
        pool.loadingFinished(a)
        if (unload) runtime.unloadAndDelete(a.worldName)
        // Next tick: BedWars' removeFromEnableQueue immediately starts the next arena, and we must not recurse.
        bw.sync { bw.dequeueAndAdvance(a) }
    }
}
