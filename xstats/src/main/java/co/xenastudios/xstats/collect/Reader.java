package co.xenastudios.xstats.collect;

import co.xenastudios.xstats.data.Event;
import co.xenastudios.xstats.stat.Catalog;
import co.xenastudios.xstats.stat.StatDef;
import co.xenastudios.xstats.stat.Vanilla;
import java.util.Arrays;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Reads a player's stats into an {@link Event.Sample}: Minecraft's own counters (a map
 * lookup each), placeholders, and where they're looking, for AFK-aware playtime. Runs on
 * the player's own thread. Builds nothing else and touches no database.
 */
public final class Reader {

    /** Leaderboards leave out players with it, such as staff. */
    public static final String EXCLUDE = "xstats.leaderboard.exclude";

    private final Catalog catalog;
    private final Logger logger;
    private volatile boolean placeholderFailed;

    public Reader(Catalog catalog, Logger logger) {
        this.catalog = catalog;
        this.logger = logger;
    }

    /**
     * Reads a player now. Call on their thread.
     *
     * @param quit whether they're leaving
     * @param placeholders whether to fill in placeholder stats (not while the server stops,
     *     when other plugins may already be gone)
     */
    public Event.Sample read(Player player, boolean quit, boolean placeholders) {
        Catalog.Snapshot stats = catalog.snapshot();
        long[] raw = new long[stats.width()];
        Arrays.fill(raw, Event.Sample.NOT_READ);
        boolean papi = placeholders && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        for (StatDef stat : stats.stats()) {
            switch (stat.source()) {
                case StatDef.Vanillas vanilla -> {
                    long sum = 0;
                    for (Vanilla.Reader reader : vanilla.readers()) {
                        sum += reader.read(player);
                    }
                    raw[stat.index()] = sum;
                }
                case StatDef.Placeholder placeholder -> {
                    if (papi) {
                        Long value = placeholder(player, placeholder.placeholder(), stat.decimals());
                        if (value != null) {
                            raw[stat.index()] = value;
                        }
                    }
                }
                case StatDef.Tracked tracked -> { }
                case StatDef.Custom custom -> { }
            }
        }
        Location look = player.getLocation();
        return new Event.Sample(player.getUniqueId(), player.getName(), System.currentTimeMillis(), raw,
            look.getYaw(), look.getPitch(), player.hasPermission(EXCLUDE), quit);
    }

    private Long placeholder(Player player, String placeholder, int decimals) {
        if (placeholderFailed) {
            return null;
        }
        try {
            String filled = PlaceholderHook.fill(player, placeholder);
            return filled.equals(placeholder) ? null : Numbers.parse(filled, decimals);
        } catch (RuntimeException | LinkageError e) {
            if (!placeholderFailed) {
                placeholderFailed = true;
                logger.warning("Couldn't fill in " + placeholder + " for a stat: " + e + ". Placeholder stats are skipped"
                    + " until the next reload.");
            }
            return null;
        }
    }

    /** After a reload: try placeholders again. */
    public void reset() {
        placeholderFailed = false;
    }
}
