package co.xenastudios.xrewind.rollback;

import co.xenastudios.xrewind.log.Contents;
import java.util.List;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

/**
 * What a rollback, restore or undo will do: one target per block, item changes per
 * container, and how many stored states this server couldn't read. Each change carries
 * the rows it covers, so only the rows of changes really applied are marked.
 *
 * @param markRolledBack what the rows' rolled_back flag becomes once it's applied
 */
public record Plan(Kind kind, List<Change> changes, boolean markRolledBack, int skipped) {

    public enum Kind { ROLLBACK, RESTORE, UNDO }

    /**
     * One thing to do at a block: set it to {@code target} (and put {@code contents} in
     * it), or, for a container change, add {@code delta} of {@code item} (taking away when
     * negative).
     */
    public record Change(String world, int x, int y, int z, BlockData target, Contents contents, ItemStack item, int delta,
                         long[] ids) {

        /** @param ids the block rows it covers */
        public static Change block(String world, int x, int y, int z, BlockData target, Contents contents, long[] ids) {
            return new Change(world, x, y, z, target, contents, null, 0, ids);
        }

        /** @param ids the container rows it covers */
        public static Change items(String world, int x, int y, int z, ItemStack item, int delta, long[] ids) {
            return new Change(world, x, y, z, null, null, item, delta, ids);
        }

        /** A container change (possibly of nothing, when what was added and taken evens out). */
        public boolean isItems() {
            return target == null;
        }
    }
}
