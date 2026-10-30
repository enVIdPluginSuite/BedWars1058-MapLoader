package dev.etran.world

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.generator.ChunkGenerator
import java.util.Random
import java.util.logging.Logger

/**
 * Chunk generator for pieces of a map that were never built: they come out as void instead of vanilla terrain,
 * exactly like BedWars1058's own void generator.
 *
 * The Bukkit chunk generator API changed in 1.13, and we compile against 1.8, so there are two implementations:
 *  - [LegacyVoidGenerator] for 1.8 - 1.12 (lives here, compiled against the 1.8 API)
 *  - `ModernVoidGenerator` for 1.13+ (separate source set, compiled against a newer API)
 * The right one is picked and loaded by name at runtime, so the JVM never tries to resolve the other.
 */
object VoidGenerators {

    fun create(log: Logger): ChunkGenerator? {
        val modernApi = try {
            Class.forName("org.bukkit.generator.ChunkGenerator\$ChunkData")
            true
        } catch (ex: ClassNotFoundException) {
            false
        }
        val className = if (modernApi) "dev.bwworldloader.world.ModernVoidGenerator" else "dev.bwworldloader.world.LegacyVoidGenerator"
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
