package co.xenastudios.xrewind.log;

import org.bukkit.block.data.BlockData;

/**
 * One thing that happened, as a listener hands it over: references to what the event
 * already had, and nothing worked out. Turning names, worlds and block states into ids
 * is the writer's job, on its own thread, so a listener costs almost nothing.
 *
 * <p>{@code time} is {@link co.xenastudios.xrewind.storage.Clock} seconds.
 */
public sealed interface Entry {

    /** A block changed from {@code before} to {@code after}: placed, broken, burnt, exploded... */
    record BlockChange(int time, Actor actor, String world, int x, int y, int z, byte action,
                       BlockData before, BlockData after) implements Entry {}

    /** A player joined or quit, at this spot. */
    record Session(int time, Actor actor, String world, int x, int y, int z, byte action) implements Entry {}

    /**
     * Block rows a rollback undid ({@code rolledBack} true) or a restore put back. Goes
     * through the queue, so the writer stays the only thing that writes.
     */
    record MarkRolledBack(long[] ids, boolean rolledBack) implements Entry {}
}
