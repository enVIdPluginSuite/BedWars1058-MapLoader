package dev.etran.storage

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Blocking file helpers. Everything here is meant to run on the IO executor, never the main thread. */
object FileOps {

    /** Dropped into every runtime world copy we create, so leftovers of a crash can be recognised and removed. */
    const val MARKER = ".maploader-runtime"

    /**
     * Dropped into the world a builder is editing during `/bw setupArena`. Unlike [MARKER] copies, which are
     * disposable, a setup world holds unsaved work: after a crash it is recovered into the map library, never deleted.
     */
    const val SETUP_MARKER = ".maploader-setup"

    fun isMarker(rel: String): Boolean = rel == MARKER || rel == SETUP_MARKER

    /** True when [dir] is a world folder this plugin created (disposable copy or setup world). */
    fun hasAnyMarker(dir: File): Boolean = File(dir, MARKER).exists() || File(dir, SETUP_MARKER).exists()

    private val WORLD_ROOT_TRASH = setOf("level.dat", "level.dat_old", "level.dat_mcr", "session.lock", "uid.dat")

    /**
     * True when [dir] looks like a Minecraft world: it has a `level.dat`, a `region` folder (all versions up to
     * 1.21) or a `dimensions` folder (newer save layout). Being liberal here keeps us future proof.
     */
    fun isWorldFolder(dir: File): Boolean =
        dir.isDirectory && (File(dir, "region").isDirectory || File(dir, "level.dat").isFile || File(dir, "dimensions").isDirectory)

    /** Skip rule used when materializing a world for play. */
    fun runtimeSkip(deleteLevelDat: Boolean): (String) -> Boolean = { rel ->
        isMarker(rel) || rel.endsWith("/session.lock") || rel == "session.lock" || rel == "uid.dat" ||
            (deleteLevelDat && (rel == "level.dat" || rel == "level.dat_old" || rel == "level.dat_mcr"))
    }

    /** Skip rule used when storing a world as a template. */
    val storeSkip: (String) -> Boolean = { rel ->
        isMarker(rel) || rel == "session.lock" || rel.endsWith("/session.lock") || rel == "uid.dat"
    }

    // ---------------------------------------------------------------------------------------------
    // Copy
    // ---------------------------------------------------------------------------------------------

    fun copyDirectory(src: Path, dst: Path, skip: (String) -> Boolean = { false }) {
        Files.createDirectories(dst)
        Files.walkFileTree(src, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = rel(src, dir)
                if (rel.isNotEmpty() && skip(rel)) return FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(dst.resolve(rel))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = rel(src, file)
                if (!skip(rel)) {
                    Files.copy(file, dst.resolve(rel), StandardCopyOption.REPLACE_EXISTING)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun rel(base: Path, p: Path): String = base.relativize(p).toString().replace('\\', '/')

    // ---------------------------------------------------------------------------------------------
    // Zip
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns the path prefix to strip when extracting [zip] ("" or e.g. "mapname/").
     * Admins often zip the world *folder* itself, which wraps everything in one extra directory.
     */
    fun detectZipPrefix(zip: Path): String {
        ZipFile(zip.toFile()).use { zf ->
            var firstSegment: String? = null
            var rootHasWorldMarker = false
            var allSameSegment = true
            val markers = listOf("level.dat", "region/", "dimensions/")
            var wrappedMarker = false
            for (entry in zf.entries()) {
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) continue
                if (markers.any { name == it || name.startsWith(it) }) rootHasWorldMarker = true
                val slash = name.indexOf('/')
                if (slash <= 0) {
                    allSameSegment = false
                    continue
                }
                val seg = name.substring(0, slash + 1)
                if (firstSegment == null) firstSegment = seg else if (firstSegment != seg) allSameSegment = false
                val inner = name.substring(slash + 1)
                if (markers.any { inner == it || inner.startsWith(it) }) wrappedMarker = true
            }
            return if (!rootHasWorldMarker && allSameSegment && wrappedMarker && firstSegment != null) firstSegment else ""
        }
    }

    fun unzip(zip: Path, dst: Path, skip: (String) -> Boolean = { false }) {
        val prefix = detectZipPrefix(zip)
        val root = dst.toAbsolutePath().normalize()
        Files.createDirectories(root)
        ZipFile(zip.toFile()).use { zf ->
            for (entry in zf.entries()) {
                var name = entry.name.replace('\\', '/')
                if (prefix.isNotEmpty()) {
                    if (!name.startsWith(prefix)) continue
                    name = name.substring(prefix.length)
                }
                if (name.isEmpty() || name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) continue
                val out = root.resolve(name).normalize()
                // zip-slip protection: an entry must never escape the target directory
                if (!out.startsWith(root)) throw IOException("Zip entry '${entry.name}' escapes the target directory")
                if (skip(name.trimEnd('/'))) continue
                if (entry.isDirectory) {
                    Files.createDirectories(out)
                } else {
                    Files.createDirectories(out.parent)
                    zf.getInputStream(entry).use { Files.copy(it, out, StandardCopyOption.REPLACE_EXISTING) }
                }
            }
        }
    }

    /** Zips the *contents* of [src] (entries are relative to the world root). Written atomically. */
    fun zipDirectory(src: Path, zipFile: Path, skip: (String) -> Boolean = { false }) {
        Files.createDirectories(zipFile.toAbsolutePath().parent)
        val tmp = zipFile.resolveSibling(zipFile.fileName.toString() + ".tmp")
        try {
            ZipOutputStream(Files.newOutputStream(tmp)).use { zos ->
                Files.walkFileTree(src, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val rel = rel(src, dir)
                        if (rel.isEmpty()) return FileVisitResult.CONTINUE
                        if (skip(rel)) return FileVisitResult.SKIP_SUBTREE
                        zos.putNextEntry(ZipEntry("$rel/"))
                        zos.closeEntry()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val rel = rel(src, file)
                        if (skip(rel)) return FileVisitResult.CONTINUE
                        zos.putNextEntry(ZipEntry(rel))
                        Files.copy(file, zos)
                        zos.closeEntry()
                        return FileVisitResult.CONTINUE
                    }
                })
            }
            moveReplacing(tmp, zipFile)
        } catch (ex: Exception) {
            Files.deleteIfExists(tmp)
            throw ex
        }
    }

    /**
     * Copies a zip template to [dst] normalised so that entries are relative to the world root
     * (strips a wrapping folder when there is one).
     */
    fun copyZipNormalized(src: Path, dst: Path) {
        val prefix = detectZipPrefix(src)
        Files.createDirectories(dst.toAbsolutePath().parent)
        if (prefix.isEmpty()) {
            val tmp = dst.resolveSibling(dst.fileName.toString() + ".tmp")
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING)
            moveReplacing(tmp, dst)
            return
        }
        val tmp = dst.resolveSibling(dst.fileName.toString() + ".tmp")
        try {
            ZipFile(src.toFile()).use { zf ->
                ZipOutputStream(Files.newOutputStream(tmp)).use { zos ->
                    for (entry in zf.entries()) {
                        val name = entry.name.replace('\\', '/')
                        if (!name.startsWith(prefix)) continue
                        val inner = name.substring(prefix.length)
                        if (inner.isEmpty() || inner.startsWith("__MACOSX/") || inner.endsWith(".DS_Store")) continue
                        zos.putNextEntry(ZipEntry(inner))
                        if (!entry.isDirectory) zf.getInputStream(entry).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            moveReplacing(tmp, dst)
        } catch (ex: Exception) {
            Files.deleteIfExists(tmp)
            throw ex
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Move / delete
    // ---------------------------------------------------------------------------------------------

    fun moveReplacing(src: Path, dst: Path) {
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (ex: Exception) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Renames [src] to [dst]; falls back to copy + delete across file systems. */
    fun moveDirectory(src: Path, dst: Path) {
        Files.createDirectories(dst.toAbsolutePath().parent)
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE)
        } catch (ex: Exception) {
            copyDirectory(src, dst)
            deleteRecursively(src)
        }
    }

    /** Deletes [path] and everything below it. Retries a few times because Windows can hold files briefly. */
    fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        var lastError: IOException? = null
        for (attempt in 1..5) {
            try {
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
                return
            } catch (ex: IOException) {
                lastError = ex
                try {
                    Thread.sleep(100L * attempt)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw lastError ?: IOException("Could not delete $path")
    }
}
