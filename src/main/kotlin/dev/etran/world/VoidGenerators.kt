package dev.etran.world

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.generator.ChunkGenerator
import java.util.Random
import java.util.logging.Logger

/**
 * Void chunk generator for parts of a map that were never built (exactly like BedWars1058's own adapter).
 *
 * The chunk generator API changed in 1.13 and this plugin compiles against 1.8, so there are two implementations:
 * [LegacyVoidGenerator] (1.8 - 1.12, here) and `ModernVoidGenerator` (1.13+, separate source set). The right one is
 * loaded by name, so the JVM never has to resolve the other.
 */
object VoidGenerators {

    fun create(log: Logger): ChunkGenerator? {
        val modernApi = try {
            Class.forName("org.bukkit.generator.ChunkGenerator\$ChunkData")
            true
        } catch (ex: ClassNotFoundException) {
            false
        }
        val pkg = VoidGenerators::class.java.name.substringBeforeLast('.')
        val className = if (modernApi) "$pkg.ModernVoidGenerator" else "$pkg.LegacyVoidGenerator"
        return try {
            Class.forName(className).getDeclaredConstructor().newInstance() as ChunkGenerator
        } catch (t: Throwable) {
            log.warning("Could not create the void chunk generator ($className): ${t.javaClass.simpleName}: ${t.message}. " +
                "Missing chunks will be generated normally.")
            null
        }
    }
}

/** Void generator for Bukkit 1.8 - 1.12. */
class LegacyVoidGenerator : ChunkGenerator() {
    @Deprecated("Legacy chunk generation API")
    override fun generateBlockSections(world: World, random: Random, x: Int, z: Int, biomes: BiomeGrid): Array<ByteArray?> =
        arrayOfNulls(world.maxHeight / 16)

    override fun getFixedSpawnLocation(world: World, random: Random): Location = Location(world, 0.0, 64.0, 0.0)
}
