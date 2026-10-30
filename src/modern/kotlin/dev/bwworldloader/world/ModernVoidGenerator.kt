package dev.bwworldloader.world

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.generator.ChunkGenerator
import java.util.Random

/**
 * Void generator for Bukkit 1.13 and newer (ChunkData based API).
 *
 * This class is in its own source set because it needs a newer Bukkit API than the rest of the plugin.
 * It is only ever referenced by name from [VoidGenerators], and only on servers that have `ChunkData`.
 */
class ModernVoidGenerator : ChunkGenerator() {
    override fun generateChunkData(world: World, random: Random, x: Int, z: Int, biome: BiomeGrid): ChunkData =
        createChunkData(world)

    override fun getFixedSpawnLocation(world: World, random: Random): Location = Location(world, 0.0, 64.0, 0.0)
}
