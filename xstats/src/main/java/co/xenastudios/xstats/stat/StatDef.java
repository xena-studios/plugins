package co.xenastudios.xstats.stat;

import co.xenastudios.xstats.api.StatInfo;
import co.xenastudios.xstats.api.StatUnit;
import java.util.List;

/**
 * One stat as it runs: from the config, or registered by another plugin. Its
 * {@code index} is its place in every player's arrays, fixed for as long as the server
 * runs, so a reload that adds or drops a stat never moves another's numbers.
 */
public record StatDef(
    int index,
    String id,
    String name,
    StatUnit unit,
    int decimals,
    boolean periods,
    boolean inGame,
    boolean website,
    String owner,
    Source source) {

    /** Where a stat's numbers come from. */
    public sealed interface Source permits Vanillas, Tracked, Placeholder, Custom {}

    /** Vanilla stats, added up: read from players live, or from stats files when importing. */
    public record Vanillas(List<Vanilla.Term> terms, List<Vanilla.Reader> readers) implements Source {}

    /** What xStats counts itself. */
    public record Tracked(Kind kind) implements Source {
        public enum Kind {
            /** Seconds online, not counting time standing still with the camera unmoved. */
            PLAYTIME,
            /** Times joined. */
            SESSIONS
        }
    }

    /** A PlaceholderAPI value, read with the rest and on quit. */
    public record Placeholder(String placeholder) implements Source {}

    /** Set by another plugin through the API. */
    public record Custom(String plugin) implements Source {}

    public StatInfo info() {
        return new StatInfo(id, name, unit, decimals, periods, inGame, owner);
    }

    /** Whether a value only ever grows, so a fall means its counter was reset. Placeholder values go up and down. */
    public boolean counter() {
        return !(source instanceof Placeholder);
    }
}
