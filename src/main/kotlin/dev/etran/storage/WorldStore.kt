package dev.etran.storage

import dev.etran.config.SaveFormat
import dev.etran.config.StorageSettings
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * The "map library": every BedWars map lives in one configurable folder ([StorageSettings.path]).
 *
 * A map is either a world **folder** (`<storage>/<name>/region/...`) or a **zip** (`<storage>/<name>.zip`).
 * Maps are only copied out into the server root (where Bukkit can load them) when an arena needs them, and the
 * copy is deleted again afterwards, so the root stays clean and nothing is loaded until it is required.
 *
 * Lookups go through a cached directory index (a single `listFiles` per refresh) instead of touching the disk
 * for every one of the hundreds of arenas.
 */
/** A runtime copy could not be created because something else (unsaved work, a foreign world) sits at its place. */
class UnsafeTargetException(message: String) : IllegalStateException(message)

class WorldStore(
    private val serverRoot: File,
    private val log: Logger,
    initial: StorageSettings,
) {
    enum class Kind { FOLDER, ZIP }

    data class Template(val name: String, val path: Path, val kind: Kind)

    sealed class Materialized {
        data class Ready(val worldDir: File, val importedFromRoot: Boolean) : Materialized()
        data class Missing(val searched: String) : Materialized()
        data class Failed(val error: Throwable) : Materialized()
    }

    private class Index(val builtAt: Long, val byKey: Map<String, Template>)

    @Volatile
    private var settings: StorageSettings = initial

    @Volatile
    private var index: Index = Index(0L, emptyMap())

    @Volatile
    private var lastRescanAttempt = 0L

    /** Maps whose setup session just ended and whose save to the library has not finished yet (name -> since). */
    private val pendingSaves = ConcurrentHashMap<String, Long>()

    /** Absolute, normalised storage directory. */
    val storageDir: Path
        get() = resolve(settings.path)

    fun rebind(newSettings: StorageSettings) {
        settings = newSettings
        if (newSettings.createIfMissing) {
            try {
                Files.createDirectories(storageDir)
            } catch (ex: IOException) {
                log.warning("Could not create storage folder $storageDir: ${ex.message}")
            }
        }
        rescan()
    }

    private fun resolve(path: String): Path {
        val p = File(path).toPath()
        val abs = if (p.isAbsolute) p else serverRoot.toPath().resolve(p)
        return abs.toAbsolutePath().normalize()
    }

    // ---------------------------------------------------------------------------------------------
    // Index
    // ---------------------------------------------------------------------------------------------

    /** Forces a fresh directory scan. Cheap enough to call from the main thread for hundreds of maps. */
    fun rescan(): Int {
        val found = LinkedHashMap<String, Template>()
        val dir = storageDir
        val children = dir.toFile().listFiles()
        if (children != null) {
            for (child in children.sortedBy { it.name.lowercase(Locale.ROOT) }) {
                val n = child.name
                if (n.startsWith(".")) continue
                if (child.isDirectory) {
                    if (FileOps.isWorldFolder(child)) found.putIfAbsent(key(n), Template(n, child.toPath(), Kind.FOLDER))
                } else if (child.isFile && n.endsWith(".zip", ignoreCase = true) && n.length > 4) {
                    val base = n.substring(0, n.length - 4)
                    found.putIfAbsent(key(base), Template(base, child.toPath(), Kind.ZIP))
                }
            }
        }
        index = Index(System.currentTimeMillis(), found)
        lastRescanAttempt = index.builtAt
        return found.size
    }

    private fun currentIndex(): Index {
        val ttl = settings.indexRefreshSeconds * 1000L
        val snap = index
        val now = System.currentTimeMillis()
        if (snap.builtAt == 0L || now - snap.builtAt > ttl) {
            rescan()
            return index
        }
        return snap
    }

    /** Looks a map up by arena name (case-insensitive). A miss triggers at most one rescan every 2 seconds. */
    fun find(name: String): Template? {
        val k = key(name)
        currentIndex().byKey[k]?.let { return it }
        val now = System.currentTimeMillis()
        if (now - lastRescanAttempt > 2000L) {
            rescan()
            return index.byKey[k]
        }
        return null
    }

    fun templates(): List<Template> = currentIndex().byKey.values.toList()

    fun names(): List<String> = templates().map { it.name }

    // ---------------------------------------------------------------------------------------------
    // Legacy (maps still in the server root)
    // ---------------------------------------------------------------------------------------------

    fun legacyRootWorld(name: String): File? {
        val safe = safeName(name) ?: return null
        val dir = File(serverRoot, safe)
        return if (FileOps.isWorldFolder(dir)) dir else null
    }

    /** Names of world folders sitting in the server root (excluding runtime copies). */
    fun legacyRootNames(): List<String> {
        val kids = serverRoot.listFiles() ?: return emptyList()
        return kids.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.startsWith("bw_temp") && FileOps.isWorldFolder(it) }
            .filter { !FileOps.hasAnyMarker(it) }
            .map { it.name }
            .sorted()
    }

    /** True if a map called [name] can be loaded (in storage, or importable from the root). */
    fun exists(name: String): Boolean =
        find(name) != null || (settings.importFromRoot && legacyRootWorld(name) != null)

    // ---------------------------------------------------------------------------------------------
    // Operations (blocking; call from the IO executor)
    // ---------------------------------------------------------------------------------------------

    /**
     * Prepares the runtime world `<serverRoot>/<worldName>` from the template called [arenaName].
     *
     * @param protectedNames world names that must never be touched (loaded worlds / main world), lower-case.
     */
    fun materialize(
        arenaName: String,
        worldName: String,
        protectedNames: Set<String>,
        deleteLevelDat: Boolean,
        setup: Boolean = false,
    ): Materialized {
        val safeArena = safeName(arenaName) ?: return Materialized.Failed(IllegalArgumentException("Illegal arena name '$arenaName'"))
        val safeWorld = safeName(worldName) ?: return Materialized.Failed(IllegalArgumentException("Illegal world name '$worldName'"))
        if (safeWorld.lowercase(Locale.ROOT) in protectedNames) {
            return Materialized.Failed(IllegalStateException("'$safeWorld' is a loaded/main world and cannot be used as an arena runtime copy"))
        }

        return NameLocks.with(NameLocks.tpl(safeArena)) {
            NameLocks.with(NameLocks.rt(safeWorld)) {
                try {
                    var template = find(safeArena)
                    var imported = false
                    if (template == null && settings.importFromRoot) {
                        val legacy = legacyRootWorld(safeArena)
                        if (legacy != null && safeArena.lowercase(Locale.ROOT) !in protectedNames && !FileOps.hasAnyMarker(legacy)) {
                            template = importLegacy(safeArena, legacy)
                            imported = true
                        }
                    }
                    val rootTarget = File(serverRoot, safeWorld)
                    val foreign = setup && rootTarget.isDirectory && !FileOps.hasAnyMarker(rootTarget)
                    if (foreign && template == null && FileOps.isWorldFolder(rootTarget)) {
                        // /bw setupArena on a world that only exists as a plain folder in the root: just use it.
                        // It is saved into the library when the session ends.
                        File(rootTarget, FileOps.SETUP_MARKER).writeText(System.currentTimeMillis().toString())
                        return@with Materialized.Ready(rootTarget, false)
                    }
                    if (foreign && template != null) {
                        // The library copy is the source of truth; keep the stray folder as a backup, never delete it.
                        val aside = backupDir(safeWorld).resolve("root-folder-" + System.currentTimeMillis())
                        aside.parent.toFile().mkdirs()
                        FileOps.moveDirectory(rootTarget.toPath(), aside)
                    }
                    if (template == null) {
                        Materialized.Missing(storageDir.toString())
                    } else {
                        val target = File(serverRoot, safeWorld)
                        // Never delete anything we did not create: a world with unsaved setup work, or a real
                        // world of the admin's that happens to share the arena's name, stays untouched.
                        if (target.exists()) {
                            when {
                                File(target, FileOps.SETUP_MARKER).exists() -> return@with Materialized.Failed(
                                    UnsafeTargetException("'$safeWorld' in the server root holds unsaved /bw setupArena work; it is kept (it is saved into the library on the next start)"))
                                File(target, FileOps.MARKER).exists() -> FileOps.deleteRecursively(target.toPath())
                                else -> return@with Materialized.Failed(
                                    UnsafeTargetException("A folder called '$safeWorld' already exists in the server root and was not created by this plugin; " +
                                        "it was not touched. Run /bwloader migrate to move it into the library, or remove it."))
                            }
                        }
                        target.mkdirs()
                        // marker first: if the server dies mid-copy the half-written folder is still recognised as ours
                        File(target, if (setup) FileOps.SETUP_MARKER else FileOps.MARKER).writeText(System.currentTimeMillis().toString())

                        val skip = FileOps.runtimeSkip(deleteLevelDat)
                        when (template.kind) {
                            Kind.FOLDER -> FileOps.copyDirectory(template.path, target.toPath(), skip)
                            Kind.ZIP -> FileOps.unzip(template.path, target.toPath(), skip)
                        }
                        Materialized.Ready(target, imported)
                    }
                } catch (ex: Exception) {
                    Materialized.Failed(ex)
                }
            }
        }
    }

    /**
     * Stores the runtime world `<serverRoot>/<worldName>` as the template [name] (used when a setup session ends).
     * Honors [StorageSettings.formatOnSave].
     *
     * Safe against data loss: the new version is written completely next to the old one and verified first; the
     * old version is then moved to `<storage>/.backups/<name>/` (the newest [StorageSettings.backupsToKeep] are
     * kept) instead of being deleted. A failure at any point leaves the old version in place.
     * [forceBackup] keeps at least one backup even when `backups-to-keep` is 0.
     */
    fun saveFromRuntime(worldName: String, name: String, forceBackup: Boolean = false): Template {
        val safeWorld = safeName(worldName) ?: throw IllegalArgumentException("Illegal world name '$worldName'")
        val safeTemplate = safeName(name) ?: throw IllegalArgumentException("Illegal map name '$name'")
        return NameLocks.with(NameLocks.tpl(safeTemplate)) {
            NameLocks.with(NameLocks.rt(safeWorld)) {
                val src = File(serverRoot, safeWorld)
                require(FileOps.isWorldFolder(src)) { "World folder '$safeWorld' does not exist" }
                ensureStorageDir()
                val keep = if (forceBackup) maxOf(settings.backupsToKeep, 1) else settings.backupsToKeep
                val existing = find(safeTemplate)
                val result: Template
                when (settings.formatOnSave) {
                    SaveFormat.FOLDER -> {
                        val tmp = storageDir.resolve(".$safeTemplate.saving")
                        FileOps.deleteRecursively(tmp)
                        try {
                            FileOps.copyDirectory(src.toPath(), tmp, FileOps.storeSkip)
                            check(FileOps.isWorldFolder(tmp.toFile())) { "Copy of '$safeWorld' is incomplete" }
                        } catch (ex: Exception) {
                            FileOps.deleteRecursively(tmp)
                            throw ex
                        }
                        val dst = storageDir.resolve(safeTemplate)
                        if (existing != null && existing.kind == Kind.FOLDER) {
                            val old = storageDir.resolve(".$safeTemplate.old")
                            FileOps.deleteRecursively(old)
                            FileOps.moveDirectory(existing.path, old)
                            try {
                                FileOps.moveDirectory(tmp, dst)
                            } catch (ex: Exception) {
                                FileOps.moveDirectory(old, existing.path) // put the previous version back
                                FileOps.deleteRecursively(tmp)
                                throw ex
                            }
                            archive(Template(safeTemplate, old, Kind.FOLDER), keep)
                        } else {
                            FileOps.moveDirectory(tmp, dst)
                            if (existing != null) archive(existing, keep)
                        }
                        result = Template(safeTemplate, dst, Kind.FOLDER)
                    }
                    SaveFormat.ZIP -> {
                        val dst = storageDir.resolve("$safeTemplate.zip")
                        if (existing != null && existing.kind == Kind.ZIP && keep > 0) backupCopy(existing, keep)
                        FileOps.zipDirectory(src.toPath(), dst, FileOps.storeSkip) // written to a temp file, then swapped in
                        if (existing != null && existing.kind == Kind.FOLDER) archive(existing, keep)
                        result = Template(safeTemplate, dst, Kind.ZIP)
                    }
                }
                rescan()
                result
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Pending saves (a setup session ended, its world is still being written back to the library)
    // ---------------------------------------------------------------------------------------------

    fun beginPendingSave(name: String) {
        pendingSaves[key(name)] = System.currentTimeMillis()
    }

    fun endPendingSave(name: String) {
        pendingSaves.remove(key(name))
    }

    /** True while the library entry for [name] is about to be replaced. Entries older than 3 minutes are ignored. */
    fun hasPendingSave(name: String): Boolean {
        val since = pendingSaves[key(name)] ?: return false
        if (System.currentTimeMillis() - since > 180_000L) {
            pendingSaves.remove(key(name))
            return false
        }
        return true
    }

    /** Moves a map that lives in the server root into the storage folder. Returns the new template. */
    fun importLegacy(name: String, legacyDir: File): Template {
        ensureStorageDir()
        val safe = safeName(name) ?: throw IllegalArgumentException("Illegal map name '$name'")
        return NameLocks.with(NameLocks.tpl(safe)) { importLegacyLocked(safe, legacyDir) }
    }

    private fun importLegacyLocked(safe: String, legacyDir: File): Template {
        val result: Template
        when (settings.formatOnSave) {
            SaveFormat.FOLDER -> {
                val dst = storageDir.resolve(safe)
                FileOps.moveDirectory(legacyDir.toPath(), dst)
                File(dst.toFile(), FileOps.MARKER).delete()
                File(dst.toFile(), FileOps.SETUP_MARKER).delete()
                result = Template(safe, dst, Kind.FOLDER)
            }
            SaveFormat.ZIP -> {
                val dst = storageDir.resolve("$safe.zip")
                FileOps.zipDirectory(legacyDir.toPath(), dst, FileOps.storeSkip)
                FileOps.deleteRecursively(legacyDir.toPath())
                result = Template(safe, dst, Kind.ZIP)
            }
        }
        log.info("Moved map '$safe' from the server root into $storageDir")
        rescan()
        return result
    }

    /**
     * Puts the stored map [name] back into the server root as an ordinary world folder `<serverRoot>/<name>`
     * (exactly what stock BedWars1058 expects). Refuses to touch anything that already exists there.
     * With [move] the map is then removed from the library (archived as a backup when backups are enabled).
     */
    fun restoreToRoot(name: String, move: Boolean): File {
        val safe = safeName(name) ?: throw IllegalArgumentException("Illegal map name '$name'")
        return NameLocks.with(NameLocks.tpl(safe)) {
            NameLocks.with(NameLocks.rt(safe)) {
                val t = find(safe) ?: throw IOException("Map '$safe' is not in the library")
                val target = File(serverRoot, safe)
                if (target.exists()) throw IOException("'$safe' already exists in the server root")
                val tmp = File(serverRoot, ".$safe.restoring")
                FileOps.deleteRecursively(tmp.toPath())
                try {
                    when (t.kind) {
                        Kind.FOLDER -> FileOps.copyDirectory(t.path, tmp.toPath(), FileOps.storeSkip)
                        Kind.ZIP -> FileOps.unzip(t.path, tmp.toPath(), FileOps.storeSkip)
                    }
                    check(FileOps.isWorldFolder(tmp)) { "Restored copy of '$safe' is incomplete" }
                    FileOps.moveDirectory(tmp.toPath(), target.toPath())
                } catch (ex: Exception) {
                    FileOps.deleteRecursively(tmp.toPath())
                    throw ex
                }
                if (move) {
                    archive(t, settings.backupsToKeep)
                    rescan()
                }
                target
            }
        }
    }

    /** Deletes the stored map (and nothing else). Returns true if something was removed. */
    fun delete(name: String): Boolean = NameLocks.with(NameLocks.tpl(name)) {
        val t = find(name)
        if (t == null) {
            false
        } else {
            archive(t, settings.backupsToKeep)
            rescan()
            true
        }
    }

    /** Copies the map [from] to a new map called [to]. */
    fun clone(from: String, to: String) {
        val safeTo = safeName(to) ?: throw IllegalArgumentException("Illegal map name '$to'")
        ensureStorageDir()
        val source = find(from)
        if (source != null) {
            when (source.kind) {
                Kind.FOLDER -> FileOps.copyDirectory(source.path, storageDir.resolve(safeTo), FileOps.storeSkip)
                Kind.ZIP -> FileOps.copyZipNormalized(source.path, storageDir.resolve("$safeTo.zip"))
            }
        } else {
            val legacy = legacyRootWorld(from) ?: throw IOException("Map '$from' does not exist")
            when (settings.formatOnSave) {
                SaveFormat.FOLDER -> FileOps.copyDirectory(legacy.toPath(), storageDir.resolve(safeTo), FileOps.storeSkip)
                SaveFormat.ZIP -> FileOps.zipDirectory(legacy.toPath(), storageDir.resolve("$safeTo.zip"), FileOps.storeSkip)
            }
        }
        rescan()
    }

    /**
     * Writes the template [arenaName] as a normalised zip at [dest]. Used to hand the very first arena to
     * BedWars1058's own adapter, which is already in flight when this plugin starts.
     */
    fun exportAsZip(arenaName: String, dest: Path): Boolean {
        val t = find(arenaName) ?: return false
        when (t.kind) {
            Kind.FOLDER -> FileOps.zipDirectory(t.path, dest, FileOps.storeSkip)
            Kind.ZIP -> FileOps.copyZipNormalized(t.path, dest)
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------

    private val backupStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

    private fun backupDir(name: String): Path = storageDir.resolve(".backups").resolve(name)

    private fun backupTarget(t: Template): Path {
        val stamp = LocalDateTime.now().format(backupStamp)
        return backupDir(t.name).resolve(if (t.kind == Kind.ZIP) "$stamp.zip" else stamp)
    }

    /** Moves [t] out of the library into the backups (or deletes it when [keep] is 0). */
    private fun archive(t: Template, keep: Int) {
        if (keep <= 0) {
            removeTemplate(t)
            return
        }
        val dst = backupTarget(t)
        Files.createDirectories(dst.parent)
        when (t.kind) {
            Kind.FOLDER -> FileOps.moveDirectory(t.path, dst)
            Kind.ZIP -> FileOps.moveReplacing(t.path, dst)
        }
        pruneBackups(t.name, keep)
    }

    /** Copies a zip template into the backups and leaves the original in place. */
    private fun backupCopy(t: Template, keep: Int) {
        val dst = backupTarget(t)
        Files.createDirectories(dst.parent)
        Files.copy(t.path, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        pruneBackups(t.name, keep)
    }

    private fun pruneBackups(name: String, keep: Int) {
        val kids = backupDir(name).toFile().listFiles() ?: return
        kids.sortedByDescending { it.name }.drop(keep).forEach {
            try {
                FileOps.deleteRecursively(it.toPath())
            } catch (ex: IOException) {
                log.warning("Could not prune old backup ${it.path}: ${ex.message}")
            }
        }
    }

    /** Backups kept for [name], newest first (folder names / zip names). */
    fun backupsOf(name: String): List<String> =
        backupDir(name).toFile().listFiles()?.map { it.name }?.sortedDescending() ?: emptyList()

    private fun removeTemplate(t: Template?) {
        if (t == null) return
        when (t.kind) {
            Kind.FOLDER -> FileOps.deleteRecursively(t.path)
            Kind.ZIP -> Files.deleteIfExists(t.path)
        }
    }

    private fun ensureStorageDir() {
        Files.createDirectories(storageDir)
    }

    private fun key(name: String) = name.lowercase(Locale.ROOT)

    companion object {
        /** Rejects anything that could escape its parent folder. */
        fun safeName(name: String): String? {
            val n = name.trim()
            if (n.isEmpty() || n == "." || n == ".." || n.contains('/') || n.contains('\\') || n.contains('\u0000') || n.contains(':')) return null
            return n
        }
    }
}
