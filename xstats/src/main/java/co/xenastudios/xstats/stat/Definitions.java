package co.xenastudios.xstats.stat;

import co.xenastudios.xstats.api.StatUnit;
import co.xenastudios.xstats.config.XSConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Turns the config's {@code stats} section into the stats xStats runs, warning about any it can't use. */
public final class Definitions {

    /** A stat id: what placeholders, commands and the web API name it by. */
    public static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
    /** Words a stat id can't be, because placeholders use them: %xstats_rank_kills%, %xstats_top_kills_1_name%. */
    private static final List<String> RESERVED = List.of("rank", "top", "kd", "first_join", "last_seen", "private");
    /** Endings placeholders add to a stat's id: %xstats_kills_daily%, %xstats_kills_raw%. */
    private static final List<String> SUFFIXES = List.of("_raw", "_all", "_daily", "_weekly", "_monthly");
    /** Minecraft's stats that go back to 0 by themselves, so they can't be added up as counters. */
    private static final List<String> NOT_COUNTERS = List.of("time_since_death", "time_since_rest");

    private Definitions() {}

    /**
     * Why an id can't be used, or null if it can: one placeholders would read as another
     * stat's ({@code kills_daily} is kills today), or as rank_ and top_ placeholders.
     */
    public static String clash(String id) {
        if (RESERVED.contains(id)) {
            return "\"" + id + "\" is kept for placeholders; pick another id";
        }
        if (id.startsWith("top_") || id.startsWith("rank_")) {
            return "an id can't start with top_ or rank_, which placeholders use; pick another id";
        }
        for (String suffix : SUFFIXES) {
            if (id.endsWith(suffix)) {
                return "an id can't end with " + suffix + ", which placeholders add; pick another id";
            }
        }
        return null;
    }

    /** A stat registered through the API, kept across reloads. */
    /**
     * A stat another plugin registered.
     *
     * @param latest a value set outright (a balance): on a network, the latest any server set
     */
    public record Registered(String id, String name, StatUnit unit, String plugin, boolean latest) {}

    /**
     * The config's stats, then those other plugins registered. A stat with a problem is left
     * out, and {@code warn} says why.
     */
    public static List<StatDef> build(Map<String, XSConfig.StatSettings> settings, List<Registered> registered,
                                      Catalog catalog, Consumer<String> warn) {
        List<StatDef> stats = new ArrayList<>();
        for (Map.Entry<String, XSConfig.StatSettings> entry : settings.entrySet()) {
            String id = entry.getKey();
            try {
                stats.add(define(id, entry.getValue(), catalog));
            } catch (IllegalArgumentException e) {
                warn.accept("stats." + id + ": " + e.getMessage() + ". It's left out until that's fixed.");
            }
        }
        for (Registered stat : registered) {
            if (stats.stream().anyMatch(defined -> defined.id().equals(stat.id()))) {
                warn.accept(stat.plugin() + " registered the stat " + stat.id() + ", but the config already has one by that"
                    + " name; the config's is used.");
                continue;
            }
            stats.add(new StatDef(catalog.index(stat.id()), stat.id(), stat.name(), stat.unit(), 0, true, true, true,
                stat.plugin(), new StatDef.Custom(stat.plugin(), stat.latest())));
        }
        return stats;
    }

    static StatDef define(String id, XSConfig.StatSettings settings, Catalog catalog) {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("an id is 1 to 32 lowercase letters, digits and _");
        }
        String clash = clash(id);
        if (clash != null) {
            throw new IllegalArgumentException(clash);
        }
        String source = settings.source().strip();
        if (source.isEmpty()) {
            throw new IllegalArgumentException("it needs a source, such as player_kills");
        }
        String name = settings.name().isBlank() ? id : settings.name();
        StatDef.Source parsed;
        StatUnit unit;
        String owner;
        String lower = source.toLowerCase(Locale.ROOT);
        if (lower.startsWith("xstats:")) {
            StatDef.Tracked.Kind kind = switch (lower.substring("xstats:".length())) {
                case "playtime" -> StatDef.Tracked.Kind.PLAYTIME;
                case "sessions" -> StatDef.Tracked.Kind.SESSIONS;
                default -> throw new IllegalArgumentException("xStats counts xstats:playtime and xstats:sessions, not " + source);
            };
            parsed = new StatDef.Tracked(kind);
            unit = kind == StatDef.Tracked.Kind.PLAYTIME ? StatUnit.TIME : StatUnit.COUNT;
            owner = "xstats";
        } else if (lower.startsWith("papi:")) {
            String placeholder = source.substring("papi:".length()).strip();
            if (!placeholder.matches("%[^%\\s]+%")) {
                throw new IllegalArgumentException("write a placeholder as papi:%name%, such as papi:%vault_eco_balance%");
            }
            parsed = new StatDef.Placeholder(placeholder);
            unit = StatUnit.COUNT;
            owner = "placeholder";
        } else {
            List<Vanilla.Term> terms = new ArrayList<>();
            List<Vanilla.Reader> readers = new ArrayList<>();
            StatUnit first = null;
            for (String part : source.split("\\+")) {
                Vanilla.Term term = Vanilla.term(part);
                if (term.category() == Vanilla.Category.CUSTOM && NOT_COUNTERS.contains(term.name())) {
                    throw new IllegalArgumentException(term.name() + " goes back to 0 by itself (at each death or sleep), so it"
                        + " can't be counted up; use a placeholder for it instead");
                }
                StatUnit termUnit = Vanilla.unit(term);
                if (first != null && termUnit != first) {
                    throw new IllegalArgumentException("its parts count different things (" + terms.getFirst() + " and " + term
                        + "), so they can't be added up");
                }
                first = termUnit;
                terms.add(term);
                readers.add(Vanilla.reader(term));
            }
            parsed = new StatDef.Vanillas(List.copyOf(terms), List.copyOf(readers));
            unit = first;
            owner = "minecraft";
        }
        if (settings.unit() != XSConfig.Unit.AUTO) {
            unit = StatUnit.valueOf(settings.unit().name());
        }
        return new StatDef(catalog.index(id), id, name, unit, settings.decimals(), settings.periods(), settings.inGame(),
            settings.website(), owner, parsed);
    }
}
