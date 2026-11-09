package dev.etran.storage

import org.bukkit.Bukkit
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * World storage differs between server versions.
 *  - Up to 1.21.x a world is a folder in the server root: `<root>/<name>/region`.
 *  - From 26.1 Paper stores every world, including the ones plugins create, inside the main level folder:
 *    `<level>/dimensions/minecraft/<name lower case>/region`. A map copied into the server root is ignored and the
 *    world is generated from scratch (an empty void / vanilla world).
 * Maps are kept as plain folders or zips (old layout is fine, the new one is understood too) and are copied to
 * [worldDir] to be played.
 */
object WorldLayout {
    private val DATA = listOf("region", "entities", "poi")
    private val JUNK = listOf(
        "dimensions", "DIM-1", "DIM1", "playerdata", "players", "stats", "advancements",
        "level.dat", "level.dat_old", "level.dat_mcr", "uid.dat", "session.lock",
    )

    /** True on Minecraft 26.1+ (versions are "26.3-R0.1-SNAPSHOT" there, "1.21.8-R0.1-SNAPSHOT" before). */
    val modernServer: Boolean by lazy {
        val major = try {
            Bukkit.getBukkitVersion().takeWhile { it.isDigit() }.toIntOrNull() ?: 1
        } catch (t: Throwable) {
            1 // no server (unit tests)
        }
        major >= 26
    }

    /** The folder of the main level ("world"). */
    fun levelDirectory(): File =
        try {
            val server = Bukkit.getServer()
            val p = server.javaClass.getMethod("getLevelDirectory").invoke(server) as Path
            val f = p.toFile()
            if (f.isAbsolute) f else File(Bukkit.getWorldContainer(), f.path)
        } catch (t: Throwable) {
            File(Bukkit.getWorldContainer(), Bukkit.getWorlds().first().name)
        }

    /** Where the files of the world called [name] live while it is loaded. */
    fun worldDir(name: String, root: File = Bukkit.getWorldContainer(), modern: Boolean = modernServer): File =
        if (!modern) File(root, name)
        else File(levelDirectory(), "dimensions/minecraft/" + name.lowercase(Locale.ENGLISH).replace(' ', '_'))

    /** Folders that can hold runtime copies: the server root, plus the dimensions folder on 26.1+. */
    fun runtimeContainers(root: File = Bukkit.getWorldContainer()): List<File> =
        if (modernServer) listOf(root, File(levelDirectory(), "dimensions/minecraft")) else listOf(root)

    /**
     * Makes a freshly copied map usable. On 26.1+ [dir] is the final dimension folder: data is moved up from
     * `dimensions/minecraft/overworld` if the map was saved in the new layout and files that belong to a whole level
     * are dropped. On older servers a map in the new layout is converted back to the old one.
     */
    fun normalize(dir: File, modern: Boolean = modernServer): Boolean {
        var moved = false
        val overworld = File(dir, "dimensions/minecraft/overworld")
        if (modern) {
            if (!File(dir, "region").isDirectory && File(overworld, "region").isDirectory) {
                DATA.forEach { moved = move(File(overworld, it), File(dir, it)) || moved }
            }
            JUNK.forEach { n ->
                val f = File(dir, n)
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
        } else if (!File(dir, "region").isDirectory && File(overworld, "region").isDirectory) {
            DATA.forEach { moved = move(File(overworld, it), File(dir, it)) || moved }
            val nether = File(dir, "dimensions/minecraft/the_nether")
            if (nether.isDirectory && !File(dir, "DIM-1").exists()) moved = move(nether, File(dir, "DIM-1")) || moved
            val end = File(dir, "dimensions/minecraft/the_end")
            if (end.isDirectory && !File(dir, "DIM1").exists()) moved = move(end, File(dir, "DIM1")) || moved
        }
        return moved
    }

    private fun move(from: File, to: File): Boolean {
        if (!from.exists() || to.exists()) return false
        Files.move(from.toPath(), to.toPath())
        return true
    }
}
