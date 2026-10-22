package co.xenastudios.xlimbo.world;

import java.util.List;
import java.util.Random;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

/**
 * A void with one floor layer and nothing else: no terrain, caves, structures or mobs,
 * so every chunk is the same few hundred blocks and costs the same however far out.
 */
public final class VoidGenerator extends ChunkGenerator {

    private final int floorY;
    private final Material floor;

    public VoidGenerator(int floorY, Material floor) {
        this.floorY = floorY;
        this.floor = floor;
    }

    public int floorY() {
        return floorY;
    }

    public Material floor() {
        return floor;
    }

    @Override
    public void generateNoise(WorldInfo world, Random random, int chunkX, int chunkZ, ChunkData chunk) {
        if (floorY >= chunk.getMinHeight() && floorY < chunk.getMaxHeight()) {
            chunk.setRegion(0, floorY, 0, 16, floorY + 1, 16, floor);
        }
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo world) {
        return new BiomeProvider() {
            @Override
            public Biome getBiome(WorldInfo info, int x, int y, int z) {
                return Biome.THE_VOID;
            }

            @Override
            public List<Biome> getBiomes(WorldInfo info) {
                return List.of(Biome.THE_VOID);
            }
        };
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, floorY + 1, 0.5);
    }

    // Nothing of vanilla's: noise, surface, caves, decorations, mobs, structures.
    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }
}
