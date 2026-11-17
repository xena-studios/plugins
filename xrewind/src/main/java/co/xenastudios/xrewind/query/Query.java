package co.xenastudios.xrewind.query;

import java.util.List;
import java.util.Set;

/**
 * What a lookup, rollback or restore is about, as parsed from CoreProtect-style
 * parameters by {@link Params}.
 *
 * @param users players or {@code #sources}, lowercase; empty for anyone
 * @param excludedUsers players or sources to leave out, lowercase
 * @param seconds how far back, or 0 for no limit
 * @param radius blocks around the centre, or {@link #WORLD} or {@link #GLOBAL}, or {@link #UNSET}
 * @param world the world asked for with {@code w:}, or null for the sender's
 * @param actions what kinds of change; empty for every kind
 * @param blocks block names to include, lowercase without {@code minecraft:}; empty for all
 * @param excludedBlocks block names to leave out
 */
public record Query(
    List<String> users,
    List<String> excludedUsers,
    long seconds,
    int radius,
    String world,
    Set<Action> actions,
    List<String> blocks,
    List<String> excludedBlocks,
    boolean preview,
    boolean count,
    boolean silent) {

    public static final int UNSET = -1;
    /** {@code r:#world}: the whole of one world. */
    public static final int WORLD = -2;
    /** {@code r:#global}: every world. */
    public static final int GLOBAL = -3;

    /** The kinds of change a query can ask for with {@code a:}. */
    public enum Action {
        BREAK, PLACE, JOIN, QUIT;

        public boolean isBlock() {
            return this == BREAK || this == PLACE;
        }
    }

    /** Whether it asks for any block changes (true when no {@code a:} narrows it). */
    public boolean wantsBlocks() {
        return actions.isEmpty() || actions.stream().anyMatch(Action::isBlock);
    }

    public boolean wantsSessions() {
        return actions.contains(Action.JOIN) || actions.contains(Action.QUIT);
    }
}
