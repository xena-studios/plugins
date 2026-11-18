package co.xenastudios.xstats.data;

import java.util.UUID;

/** Something that happened to a player, applied in order at the next save. */
public sealed interface Event permits Event.Joined, Event.Sample, Event.Increment, Event.Set {

    UUID player();

    /**
     * A player joined.
     *
     * @param firstPlayed when the server says they first played, in epoch milliseconds
     */
    record Joined(UUID player, String name, long at, long firstPlayed, float yaw, float pitch) implements Event {}

    /**
     * A reading of a player's stats, made on their own thread.
     *
     * @param raw readings by stat index, in the unit xStats stores; {@link #NOT_READ} for stats not read this way
     * @param quit whether it's the reading made as they left
     */
    record Sample(UUID player, String name, long at, long[] raw, float yaw, float pitch, boolean excluded, boolean quit)
        implements Event {

        public static final long NOT_READ = Long.MIN_VALUE;
    }

    /** Another plugin added to a stat. */
    record Increment(UUID player, String stat, long amount) implements Event {}

    /** Another plugin set a stat. */
    record Set(UUID player, String stat, long value) implements Event {}
}
