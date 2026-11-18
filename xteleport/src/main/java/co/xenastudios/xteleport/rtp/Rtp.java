package co.xenastudios.xteleport.rtp;

import co.xenastudios.xteleport.config.Settings.RtpWorld;
import co.xenastudios.xteleport.config.XTConfig;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;

/** {@code /rtp}'s settings as the search wants them, read once per config, and the search itself. */
public final class Rtp {

    private final Search search;
    private volatile XTConfig.Rtp settings;
    private volatile Set<Biome> blockedBiomes = Set.of();

    public Rtp(co.xenastudios.xteleport.XTeleport plugin) {
        this.search = new Search(plugin, plugin::loadChunk);
    }

    /** Takes the config's settings, on startup and each reload. Warns about biomes it doesn't know. */
    public void configure(XTConfig.Rtp settings, Logger logger) {
        Registry<Biome> biomes = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
        Set<Biome> blocked = new HashSet<>();
        for (String name : settings.blockedBiomes()) {
            NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT).strip());
            Biome biome = key == null ? null : biomes.get(key);
            if (biome == null) {
                logger.warning("config.yml: rtp.blocked-biomes: \"" + name + "\" isn't a biome, so it's skipped.");
            } else {
                blocked.add(biome);
            }
        }
        this.blockedBiomes = Set.copyOf(blocked);
        this.settings = settings;
    }

    public XTConfig.Rtp settings() {
        return settings;
    }

    /**
     * Where /rtp may send this player in this world, or null if it may not: the world isn't
     * listed, is off, or needs a permission they don't have.
     */
    public RtpWorld allowed(Player player, World world) {
        RtpWorld rules = rules(world);
        if (rules == null || !rules.permission().isBlank() && !player.hasPermission(rules.permission())) {
            return null;
        }
        return rules;
    }

    /** The world's settings, or null if /rtp can't send anyone there. */
    public RtpWorld rules(World world) {
        Map<String, RtpWorld> worlds = settings.worlds();
        RtpWorld rules = worlds.get(world.getName());
        return rules != null && rules.enabled() ? rules : null;
    }

    public Search.Start begin(UUID player) {
        return search.begin(player, settings.maxSearches());
    }

    public void end(UUID player) {
        search.end(player);
    }

    /** Whether the player has a search running. Any thread. */
    public boolean searching(UUID player) {
        return search.searching(player);
    }

    /**
     * Searches a world by its settings. Call {@link #begin} first and {@link #end} once the
     * future completes; it always completes, with what went wrong if anything did. Any
     * thread.
     *
     * <p>The world's spawn point (and, in the search, its border) may be read from another
     * world's region on Folia, as for {@code /rtp world_nether} typed in the overworld.
     * Folia doesn't tie either to a region: both are read as a few plain values, with no
     * thread check on 1.21.11 or 26.3, so this is safe.
     */
    public CompletableFuture<Search.Found> find(World world, RtpWorld rules) {
        XTConfig.Rtp current = settings;
        int centerX = rules.centerX();
        int centerZ = rules.centerZ();
        if (rules.centerOnSpawn()) {
            Location spawn;
            try {
                spawn = world.getSpawnLocation();
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            centerX = spawn.getBlockX();
            centerZ = spawn.getBlockZ();
        }
        return search.find(world, new Search.Area(centerX, centerZ, rules.minRadius(), rules.maxRadius(), current.attempts(),
            current.onlyGenerated(), rules.allowMainIsland(), blockedBiomes));
    }

    /** A search for the API, by the caller's settings, with the config's blocked biomes. */
    public CompletableFuture<Search.Found> find(World world, co.xenastudios.xteleport.api.RtpSettings asked) {
        return search.find(world, new Search.Area(asked.centerX(), asked.centerZ(), asked.minRadius(), asked.maxRadius(),
            asked.attempts(), asked.onlyGenerated(), false, blockedBiomes));
    }
}
