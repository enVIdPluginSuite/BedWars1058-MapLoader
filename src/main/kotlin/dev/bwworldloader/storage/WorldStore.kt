package dev.bwworldloader.storage

import dev.bwworldloader.config.SaveFormat
import dev.bwworldloader.config.StorageSettings
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
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
            .filter { !File(it, FileOps.MARKER).exists() }
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
    ): Materialized {
        val safeArena = safeName(arenaName) ?: return Materialized.Failed(IllegalArgumentException("Illegal arena name '$arenaName'"))
        val safeWorld = safeName(worldName) ?: return Materialized.Failed(IllegalArgumentException("Illegal world name '$worldName'"))
        if (safeWorld.lowercase(Locale.ROOT) in protectedNames) {
            return Materialized.Failed(IllegalStateException("'$safeWorld' is a loaded/main world and cannot be used as an arena runtime copy"))
        }

        return try {
            var template = find(safeArena)
            var imported = false
            if (template == null && settings.importFromRoot) {
                val legacy = legacyRootWorld(safeArena)
                if (legacy != null && safeArena.lowercase(Locale.ROOT) !in protectedNames) {
                    template = importLegacy(safeArena, legacy)
                    imported = true
                }
            }
            if (template == null) return Materialized.Missing(storageDir.toString())

            val target = File(serverRoot, safeWorld)
            if (target.exists()) FileOps.deleteRecursively(target.toPath())
            target.mkdirs()
            // marker first: if the server dies mid-copy the half-written folder is still recognised as ours
            File(target, FileOps.MARKER).writeText(System.currentTimeMillis().toString())

            val skip = FileOps.runtimeSkip(deleteLevelDat)
            when (template.kind) {
                Kind.FOLDER -> FileOps.copyDirectory(template.path, target.toPath(), skip)
                Kind.ZIP -> FileOps.unzip(template.path, target.toPath(), skip)
            }
            Materialized.Ready(target, imported)
        } catch (ex: Exception) {
            Materialized.Failed(ex)
        }
    }

    /**
     * Stores the runtime world `<serverRoot>/<worldName>` as the template [name] (used when a setup session ends).
     * Honors [StorageSettings.formatOnSave]. The old template of that name is replaced.
     */
    fun saveFromRuntime(worldName: String, name: String): Template {
        val safeWorld = safeName(worldName) ?: throw IllegalArgumentException("Illegal world name '$worldName'")
        val safeTemplate = safeName(name) ?: throw IllegalArgumentException("Illegal map name '$name'")
        val src = File(serverRoot, safeWorld)
        require(FileOps.isWorldFolder(src)) { "World folder '$safeWorld' does not exist" }
        ensureStorageDir()
        val existing = find(safeTemplate)
        val result: Template
        when (settings.formatOnSave) {
            SaveFormat.FOLDER -> {
                val tmp = storageDir.resolve(".$safeTemplate.saving")
                FileOps.deleteRecursively(tmp)
                FileOps.copyDirectory(src.toPath(), tmp, FileOps.storeSkip)
                removeTemplate(existing)
                val dst = storageDir.resolve(safeTemplate)
                FileOps.deleteRecursively(dst)
                FileOps.moveDirectory(tmp, dst)
                result = Template(safeTemplate, dst, Kind.FOLDER)
            }
            SaveFormat.ZIP -> {
                val dst = storageDir.resolve("$safeTemplate.zip")
                FileOps.zipDirectory(src.toPath(), dst, FileOps.storeSkip)
                if (existing != null && existing.kind == Kind.FOLDER) FileOps.deleteRecursively(existing.path)
                result = Template(safeTemplate, dst, Kind.ZIP)
            }
        }
        rescan()
        return result
    }

    /** Moves a map that lives in the server root into the storage folder. Returns the new template. */
    fun importLegacy(name: String, legacyDir: File): Template {
        ensureStorageDir()
        val safe = safeName(name) ?: throw IllegalArgumentException("Illegal map name '$name'")
        val result: Template
        when (settings.formatOnSave) {
            SaveFormat.FOLDER -> {
                val dst = storageDir.resolve(safe)
                FileOps.moveDirectory(legacyDir.toPath(), dst)
                File(dst.toFile(), FileOps.MARKER).delete()
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

    /** Deletes the stored map (and nothing else). Returns true if something was removed. */
    fun delete(name: String): Boolean {
        val t = find(name) ?: return false
        removeTemplate(t)
        rescan()
        return true
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
