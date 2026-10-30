package dev.etran.world

import dev.etran.BwContext
import dev.etran.storage.FileOps
import dev.etran.storage.NameLocks
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

    /**
     * Saves a world left behind by an interrupted `/bw setupArena` session into the map library; true when the
     * folder may be removed afterwards. Installed by the plugin. While it is missing, setup worlds are never deleted.
     */
    @Volatile
    var setupRecovery: ((String) -> Boolean)? = null

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
        deleteRuntimeCopy(worldName, System.currentTimeMillis())
    }

    /**
     * Removes a half-copied runtime folder after a failed [dev.etran.storage.WorldStore.materialize].
     * Only folders that carry our marker are touched (setup worlds only with [includeSetup]), so a real map or the main world is never at risk.
     */
    fun deleteMarkedCopy(worldName: String, includeSetup: Boolean = false) {
        val dir = File(serverRoot, worldName)
        val marked = File(dir, FileOps.MARKER).exists() || (includeSetup && File(dir, FileOps.SETUP_MARKER).exists())
        if (!marked) return
        io.submit {
            try {
                NameLocks.with(NameLocks.rt(worldName)) { FileOps.deleteRecursively(dir.toPath()) }
            } catch (ex: Exception) {
                log.warning("Could not delete partial runtime world '$worldName': ${ex.message}")
            }
        }
    }

    private fun deleteRuntimeCopy(worldName: String, unloadedAt: Long) {
        if (!isOwned(worldName)) return
        if (bw.isShuttingDown()) deleteNow(worldName, unloadedAt) else io.submit { deleteNow(worldName, unloadedAt) }
    }

    /**
     * Blocking deletion of an owned runtime copy; for the IO thread or the shutdown path.
     * Returns true when the folder is gone. Worlds we do not own are never touched, and neither is
     *  - a world holding unsaved setup work, unless [allowSetup] (the caller has just saved it), and
     *  - a copy created at or after [unloadedAt]: a deletion queued for an arena that was unloaded must not hit
     *    the fresh copy that a new arena or setup session of the same name has created in the meantime.
     */
    fun deleteNow(worldName: String, unloadedAt: Long? = null, allowSetup: Boolean = false): Boolean {
        if (!isOwned(worldName)) return false
        return NameLocks.with(NameLocks.rt(worldName)) {
            try {
                val dir = File(serverRoot, worldName)
                val setupMarker = File(dir, FileOps.SETUP_MARKER)
                if (setupMarker.exists() && !allowSetup) return@with false
                if (unloadedAt != null) {
                    val created = File(dir, FileOps.MARKER).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
                    if (created != null && created >= unloadedAt) return@with false
                }
                FileOps.deleteRecursively(dir.toPath())
                owned.remove(worldName.lowercase(Locale.ROOT))
                true
            } catch (ex: Exception) {
                log.warning("Could not delete runtime world '$worldName': ${ex.message}. It will be removed on the next start.")
                false
            }
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
            if (key in loaded) continue
            val setupWorld = File(d, FileOps.SETUP_MARKER).exists()
            // arenas about to load are skipped, but a setup world with the same name must still be rescued first
            if (key in keep && !setupWorld) continue
            if (setupWorld) {
                // Unsaved /bw setupArena work from a crash: save it into the library, never just delete it.
                val recover = setupRecovery
                if (recover == null) {
                    log.warning("World '${d.name}' holds unsaved setup work and was left in place.")
                } else if (recover(d.name)) {
                    try {
                        FileOps.deleteRecursively(d.toPath())
                        removed++
                    } catch (ex: Exception) {
                        log.warning("Could not remove recovered setup world '${d.name}': ${ex.message}")
                    }
                } else {
                    log.warning("Could not save the unfinished setup of '${d.name}' into the library; the world stays in the server root.")
                }
            } else if (File(d, FileOps.MARKER).exists() || d.name.startsWith("bw_temp_")) {
                try {
                    FileOps.deleteRecursively(d.toPath())
                    removed++
                } catch (ex: Exception) {
                    log.warning("Could not remove leftover runtime world '${d.name}': ${ex.message}")
                }
            }
        }
        if (removed > 0) log.info("Cleaned up $removed leftover world copy/copies from a previous run.")
        return removed
    }
}
