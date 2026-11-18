package co.xenastudios.xstats.hook;

import co.xenastudios.xstats.XStats;
import co.xenastudios.xstats.api.Period;
import co.xenastudios.xstats.data.Board;
import co.xenastudios.xstats.data.Engine;
import co.xenastudios.xstats.data.PlayerRecord;
import co.xenastudios.xstats.stat.Catalog;
import co.xenastudios.xstats.stat.Format;
import java.util.Optional;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * xStats' placeholders, answered from memory: no database, no lookups beyond a map.
 * Only loaded when PlaceholderAPI is installed.
 *
 * <ul>
 *   <li>{@code %xstats_<stat>%}, {@code %xstats_<stat>_<period>%}: the player's value, written
 *       as players read it; add {@code _raw} for the bare number.</li>
 *   <li>{@code %xstats_rank_<stat>[_<period>]%}: their place, or blank.</li>
 *   <li>{@code %xstats_top_<stat>[_<period>]_<n>_name%}, {@code _value} and {@code _raw}: the
 *       nth place, or blank.</li>
 *   <li>{@code %xstats_kd%}: kills per death, from the {@code kills} and {@code deaths} stats.</li>
 * </ul>
 */
public final class StatPlaceholders extends PlaceholderExpansion {

    private final XStats xstats;

    public StatPlaceholders(XStats xstats) {
        this.xstats = xstats;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "xstats";
    }

    @Override
    public @NotNull String getVersion() {
        return xstats.getPluginMeta().getVersion();
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", xstats.getPluginMeta().getAuthors());
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        Engine engine = xstats.engine();
        if (engine == null || !engine.ready()) {
            return "";
        }
        Catalog.Snapshot stats = xstats.catalog().snapshot();
        if (params.startsWith("top_")) {
            return top(engine, stats, params.substring("top_".length()));
        }
        PlayerRecord record = player == null ? null : engine.player(player.getUniqueId());
        if (params.startsWith("rank_")) {
            Catalog.StatPeriod which = stats.byParam().get(params.substring("rank_".length()));
            if (which == null) {
                return null;
            }
            int rank = record == null ? 0 : engine.rank(record, which.stat(), which.period());
            return rank == 0 ? "" : Integer.toString(rank);
        }
        if (params.equals("kd")) {
            Optional<Long> kills = value(engine, stats, record, "kills");
            Optional<Long> deaths = value(engine, stats, record, "deaths");
            return kills.isEmpty() || deaths.isEmpty() ? null : Format.ratio(kills.get(), deaths.get());
        }
        boolean raw = params.endsWith("_raw");
        Catalog.StatPeriod which = stats.byParam().get(raw ? params.substring(0, params.length() - "_raw".length()) : params);
        if (which == null) {
            return null;
        }
        long value = record == null ? 0 : engine.value(record, which.stat(), which.period());
        return raw ? Long.toString(value) : Format.value(which.stat(), value);
    }

    private static Optional<Long> value(Engine engine, Catalog.Snapshot stats, PlayerRecord record, String stat) {
        return stats.get(stat).map(def -> record == null ? 0L : engine.value(record, def, Period.ALL));
    }

    /** {@code <stat>[_<period>]_<n>_name}, {@code _value} or {@code _raw}. */
    private static String top(Engine engine, Catalog.Snapshot stats, String rest) {
        int field = rest.lastIndexOf('_');
        int place = field < 0 ? -1 : rest.lastIndexOf('_', field - 1);
        if (place < 0) {
            return null;
        }
        String what = rest.substring(field + 1);
        Catalog.StatPeriod which = stats.byParam().get(rest.substring(0, place));
        int n;
        try {
            n = Integer.parseInt(rest.substring(place + 1, field));
        } catch (NumberFormatException e) {
            return null;
        }
        if (which == null || n < 1 || !(what.equals("name") || what.equals("value") || what.equals("raw"))) {
            return null;
        }
        Board board = engine.leaderboards().get(which.stat().id(), which.period());
        if (board == null || n > board.size()) {
            return "";
        }
        return switch (what) {
            case "name" -> board.order()[n - 1].name();
            case "value" -> Format.value(which.stat(), board.values()[n - 1]);
            default -> Long.toString(board.values()[n - 1]);
        };
    }
}
