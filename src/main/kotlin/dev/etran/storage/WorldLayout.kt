package dev.etran.storage

import org.bukkit.Bukkit
import java.io.File
import java.nio.file.Files

/**
 * Minecraft 26.1 moved the overworld out of the world root into `dimensions/minecraft/overworld` (and DIM-1 / DIM1
 * into `dimensions/minecraft/the_nether` / `the_end`). A map saved in the other layout would load as a freshly
 * generated vanilla world, so a copied map is converted to the layout the running server expects.
 */
object WorldLayout {
    private val DATA = listOf("region", "entities", "poi")
    private val EXTRA = listOf("DIM-1" to "the_nether", "DIM1" to "the_end")

    /** True on Minecraft 26.1+ (versions are "26.3-R0.1-SNAPSHOT" there, "1.21.8-R0.1-SNAPSHOT" before). */
    val modernServer: Boolean by lazy {
        val major = try {
            Bukkit.getBukkitVersion().takeWhile { it.isDigit() }.toIntOrNull() ?: 1
        } catch (t: Throwable) {
            1 // no server (unit tests)
        }
        major >= 26
    }

    /** Converts [dir] to the layout of the running server. Returns true if something was moved. */
    fun normalize(dir: File, modern: Boolean = modernServer): Boolean {
        val overworld = File(dir, "dimensions/minecraft/overworld")
        var moved = false
        if (modern) {
            if (File(dir, "region").isDirectory && !File(overworld, "region").isDirectory) {
                overworld.mkdirs()
                DATA.forEach { moved = move(File(dir, it), File(overworld, it)) || moved }
            }
            for ((old, new) in EXTRA) {
                val target = File(dir, "dimensions/minecraft/$new")
                if (File(dir, old).isDirectory && !File(target, "region").isDirectory) {
                    target.parentFile.mkdirs()
                    moved = move(File(dir, old), target) || moved
                }
            }
        } else {
            if (File(overworld, "region").isDirectory && !File(dir, "region").isDirectory) {
                DATA.forEach { moved = move(File(overworld, it), File(dir, it)) || moved }
            }
            for ((old, new) in EXTRA) {
                val source = File(dir, "dimensions/minecraft/$new")
                if (source.isDirectory && !File(dir, old).exists()) moved = move(source, File(dir, old)) || moved
            }
        }
        return moved
    }

    private fun move(from: File, to: File): Boolean {
        if (!from.exists() || to.exists()) return false
        Files.move(from.toPath(), to.toPath())
        return true
    }
}
