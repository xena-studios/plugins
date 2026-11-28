package co.xenastudios.xlimbo.bukkit

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Biome
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.generator.BiomeProvider
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.io.File
import java.util.Random

/**
 * A void with one floor layer and nothing else: no terrain, caves, structures or mobs, so every
 * chunk is the same few hundred blocks and costs the same however far out.
 */
public class VoidGenerator(public val floorY: Int, public val floor: Material) : ChunkGenerator() {
    // Kept by name, for a generator a reloaded-in-place xLimbo left behind (LimboWorld.generatorFloor).
    public fun floorY(): Int = floorY

    override fun generateNoise(world: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunk: ChunkData) {
        if (floorY >= chunk.minHeight && floorY < chunk.maxHeight) chunk.setRegion(0, floorY, 0, 16, floorY + 1, 16, floor)
    }

    override fun getDefaultBiomeProvider(world: WorldInfo): BiomeProvider = VoidBiome

    override fun getFixedSpawnLocation(world: World, random: Random): Location = Location(world, 0.5, floorY + 1.0, 0.5)

    // Nothing of vanilla's: noise, surface, caves, decorations, mobs, structures.
    override fun shouldGenerateNoise(): Boolean = false

    override fun shouldGenerateSurface(): Boolean = false

    override fun shouldGenerateCaves(): Boolean = false

    override fun shouldGenerateDecorations(): Boolean = false

    override fun shouldGenerateMobs(): Boolean = false

    override fun shouldGenerateStructures(): Boolean = false

    private object VoidBiome : BiomeProvider() {
        override fun getBiome(info: WorldInfo, x: Int, y: Int, z: Int): Biome = Biome.THE_VOID

        override fun getBiomes(info: WorldInfo): List<Biome> = listOf(Biome.THE_VOID)
    }
}

/** The generator for a world the server loads itself, asked through the bootstrap (bukkit.yml's `generator: xLimbo`). */
public object Generators {
    /**
     * xLimbo's generator, with the floor its config file names, read only: carrying the file forward
     * and saving it is the real load's job, which also needs to see it was carried. The defaults
     * when the file isn't there or can't be read.
     */
    @JvmStatic
    public fun forWorld(folder: File, @Suppress("UNUSED_PARAMETER") world: String): ChunkGenerator {
        var y = DEFAULT_Y
        var block = DEFAULT_BLOCK
        try {
            val file = File(folder, "config.yml")
            if (file.isFile) {
                val yaml = YamlConfiguration.loadConfiguration(file)
                y = yaml.getInt("world.floor-y", DEFAULT_Y).coerceIn(-63, 318)
                block = floor(yaml.getString("world.floor-block")) ?: DEFAULT_BLOCK
            }
        } catch (e: Exception) {
            // The defaults, as for a missing file.
        }
        return VoidGenerator(y, block)
    }

    /** The block a floor-block names, if it's one a floor can be: solid, and not one that falls. */
    public fun floor(name: String?): Material? {
        val material = name?.let { Material.matchMaterial(it.trim()) } ?: return null
        return material.takeIf { it.isBlock && it.isSolid && !it.hasGravity() }
    }

    public const val DEFAULT_Y: Int = 64
    public val DEFAULT_BLOCK: Material = Material.PURPLE_STAINED_GLASS
}
