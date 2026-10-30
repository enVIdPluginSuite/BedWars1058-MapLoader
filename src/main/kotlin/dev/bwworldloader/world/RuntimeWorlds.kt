package dev.bwworldloader.world

import dev.bwworldloader.BwContext
import dev.bwworldloader.storage.FileOps
import org.bukkit.Bukkit
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Bookkeeping for the temporary world copies this plugin puts in the server root.
 *
 * A runtime world is always a disposable copy of a stored map. Only worlds we created (tracked in [owned] and
 * stamped with a marker file) are ever deleted, so a map the admin has not moved yet can never be destroyed.
 */
class RuntimeWorlds(
    private val bw: BwContext,
    private val io: IoExecutor,
    private val serverRoot: File,
    private val log: Logger,
) {
    private val owned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun isOwned(worldName: String): Boolean = owned.contains(worldName.lowercase(Locale.ROOT))

    fun markOwned(worldName: String) {
        owned.add(worldName.lowercase(Locale.ROOT))
    }

    /** Stamps a world folder that was created by someone else (BedWars1058's own adapter) as ours. */
    fun adopt(worldName: String) {
        markOwned(worldName)
        io.submit {
            val dir = File(serverRoot, worldName)
            if (dir.isDirectory) File(dir, FileOps.MARKER).writeText(System.currentTimeMillis().toString())
        }
    }

    fun ownedCount(): Int = owned.size

    /**
     * Worlds that must never be used as an arena runtime copy: everything currently loaded that is not ours
     * (lobby, nether, ...) and always the main world. Call on the main thread.
     */
    fun protectedNames(): Set<String> {
        val set = HashSet<String>()
        val worlds = Bukkit.getWorlds()
        for (w in worlds) if (!isOwned(w.name)) set += w.name.lowercase(Locale.ROOT)
        worlds.firstOrNull()?.let { set += it.name.lowercase(Locale.ROOT) }
        return set
    }

    /**
     * Unloads [worldName] without saving and deletes its runtime copy. Main thread only.
     * Unloading can fail for a moment (players still being moved out), so it is retried for a few seconds.
     */
    fun unloadAndDelete(worldName: String, attempt: Int = 0) {
        val main = Bukkit.getWorlds().firstOrNull()?.name
        if (main != null && main.equals(worldName, ignoreCase = true)) return

        val world = Bukkit.getWorld(worldName)
        if (world != null) {
            val unloaded = try {
                Bukkit.unloadWorld(world, false)
            } catch (t: Throwable) {
                log.log(Level.WARNING, "Error while unloading world $worldName", t)
                false
            }
            if (!unloaded) {
                if (bw.isShuttingDown() || attempt >= 10) {
                    log.warning("Could not unload world '$worldName'. It will be removed on the next start.")
                    return
                }
                bw.syncLater(20L) { unloadAndDelete(worldName, attempt + 1) }
                return
            }
        }
        deleteRuntimeCopy(worldName)
    }

    /**
     * Removes a half-copied runtime folder after a failed [dev.bwworldloader.storage.WorldStore.materialize].
     * Only folders that carry our marker are touched, so a real map or the main world is never at risk.
     */
    fun deleteMarkedCopy(worldName: String) {
        val dir = File(serverRoot, worldName)
        if (!File(dir, FileOps.MARKER).exists()) return
        io.submit {
            try {
                FileOps.deleteRecursively(dir.toPath())
            } catch (ex: Exception) {
                log.warning("Could not delete partial runtime world '$worldName': ${ex.message}")
            }
        }
    }

    private fun deleteRuntimeCopy(worldName: String) {
        if (!isOwned(worldName)) return
        if (bw.isShuttingDown()) deleteNow(worldName) else io.submit { deleteNow(worldName) }
    }

    /**
     * Blocking deletion of an owned runtime copy; for the IO thread or the shutdown path.
     * Returns true when the folder is gone. Worlds we do not own are never touched.
     */
    fun deleteNow(worldName: String): Boolean {
        if (!isOwned(worldName)) return false
        return try {
            FileOps.deleteRecursively(File(serverRoot, worldName).toPath())
            owned.remove(worldName.lowercase(Locale.ROOT))
            true
        } catch (ex: Exception) {
            log.warning("Could not delete runtime world '$worldName': ${ex.message}. It will be removed on the next start.")
            false
        }
    }

    /**
     * Removes runtime copies left behind by a crash (folders carrying our marker, plus BedWars1058's own
     * `bw_temp_*` auto-scale worlds). Runs synchronously at startup, before any arena is activated, so it cannot
     * race with a fresh copy. [keep] are world names that are about to be (or being) loaded.
     */
    fun sweepOrphans(keep: Set<String>): Int {
        val loaded = Bukkit.getWorlds().map { it.name.lowercase(Locale.ROOT) }.toSet()
        val kids = serverRoot.listFiles() ?: return 0
        var removed = 0
        for (d in kids) {
            if (!d.isDirectory) continue
            val key = d.name.lowercase(Locale.ROOT)
            if (key in loaded || key in keep) continue
            if (File(d, FileOps.MARKER).exists() || d.name.startsWith("bw_temp_")) {
                try {
                    FileOps.deleteRecursively(d.toPath())
                    removed++
                } catch (ex: Exception) {
                    log.warning("Could not remove leftover runtime world '${d.name}': ${ex.message}")
                }
            }
        }
        if (removed > 0) log.info("Removed $removed leftover runtime world(s) from a previous run.")
        return removed
    }
}
