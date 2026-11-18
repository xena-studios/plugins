package co.xenastudios.xrewind.api;

import java.util.List;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * What a rollback, restore or undo would change, worked out from the log and not yet
 * applied: a preview. Each block that changed many times is set once. Get one from
 * {@link XRewindApi#planRollback} or {@link XRewindApi#planRestore}, or from a
 * {@link RollbackResult#undo()}, and apply it with {@link XRewindApi#apply(Plan)}.
 *
 * <p>Made by xRewind only: other plugins don't implement it.
 *
 * @since 1.1.0
 */
public interface Plan {

    /**
     * What kind of plan it is.
     *
     * @since 1.1.0
     */
    enum Kind {
        /** Puts things back as they were before the matching changes. */
        ROLLBACK,
        /** Makes the matching changes again, after a rollback. */
        RESTORE,
        /** Puts back what applying another plan changed. */
        UNDO
    }

    /**
     * A block to set.
     *
     * @param world the world's name
     * @param x the block's x
     * @param y the block's y
     * @param z the block's z
     * @param target what the block becomes
     * @since 1.1.0
     */
    record BlockChange(@NotNull String world, int x, int y, int z, @NotNull BlockData target) {}

    /**
     * Items to put into or take out of a container.
     *
     * @param world the world's name
     * @param x the container's x
     * @param y the container's y
     * @param z the container's z
     * @param item the item, as a stack of one
     * @param amount how many to put in; negative to take out
     * @since 1.1.0
     */
    record ItemChange(@NotNull String world, int x, int y, int z, @NotNull ItemStack item, int amount) {}

    /**
     * What kind of plan it is.
     *
     * @return the kind
     * @since 1.1.0
     */
    @NotNull Kind kind();

    /**
     * The blocks it would set, as copies.
     *
     * @return the block changes, in the order they'd be made
     * @since 1.1.0
     */
    @NotNull List<BlockChange> blocks();

    /**
     * The items it would put into or take out of containers, as copies.
     *
     * @return the item changes
     * @since 1.1.0
     */
    @NotNull List<ItemChange> items();

    /**
     * Logged states this server can't read (a block from an older Minecraft that no longer
     * exists), which the plan leaves out.
     *
     * @return how many were left out
     * @since 1.1.0
     */
    int unreadable();
}
