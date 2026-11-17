package co.xenastudios.xrewind.log;

import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

/**
 * One thing that happened, as a listener hands it over: references to what the event
 * already had, and nothing worked out. Turning names, worlds and block states into ids
 * is the writer's job, on its own thread, so a listener costs almost nothing.
 *
 * <p>{@code time} is {@link co.xenastudios.xrewind.storage.Clock} seconds.
 */
public sealed interface Entry {

    /** The world it happened in, or null for one that isn't a change. */
    String world();

    /**
     * A block changed from {@code before} to {@code after}: placed, broken, burnt, exploded...
     *
     * @param contents what the block held before, so a rollback can put it back: a
     *     container's items or a sign's text; null for most blocks
     */
    record BlockChange(int time, Actor actor, String world, int x, int y, int z, byte action,
                       BlockData before, BlockData after, Contents contents) implements Entry {

        public BlockChange(int time, Actor actor, String world, int x, int y, int z, byte action, BlockData before, BlockData after) {
            this(time, actor, world, x, y, z, action, before, after, null);
        }
    }

    /** A block change from another logger's history, its states already as text. */
    record ImportedBlock(int time, Actor actor, String world, int x, int y, int z, byte action,
                         String before, String after, boolean rolledBack) implements Entry {}

    /** A container change from another logger's history. {@code item} is a serialized stack of one. */
    record ImportedContainer(int time, Actor actor, String world, int x, int y, int z, byte action,
                             String material, byte[] item, int amount, boolean rolledBack) implements Entry {}

    /** Items put in ({@link Actions#ADD}) or taken out of a container. {@code item} is a copy. */
    record ContainerChange(int time, Actor actor, String world, int x, int y, int z, byte action,
                           ItemStack item, int amount) implements Entry {}

    /** An entity killed. {@code type} is its key, such as {@code minecraft:zombie}. */
    record Kill(int time, Actor actor, String world, int x, int y, int z, String type) implements Entry {}

    /** Text: a sign written, a chat message, a command. */
    record Text(int time, Actor actor, String world, int x, int y, int z, byte kind, String message) implements Entry {}

    /** A player joined or quit, at this spot. */
    record Session(int time, Actor actor, String world, int x, int y, int z, byte action) implements Entry {}

    /**
     * Block rows a rollback undid ({@code rolledBack} true) or a restore put back. Goes
     * through the queue, so the writer stays the only thing that writes.
     */
    record MarkRolledBack(String table, long[] ids, boolean rolledBack) implements Entry {
        @Override
        public String world() {
            return null;
        }
    }
}
