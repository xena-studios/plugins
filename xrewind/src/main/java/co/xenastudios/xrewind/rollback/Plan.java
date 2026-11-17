package co.xenastudios.xrewind.rollback;

import co.xenastudios.xrewind.log.Contents;
import java.util.List;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

/**
 * What a rollback, restore or undo will do: one target per block, item changes per
 * container, the rows it covers, and how many stored states this server couldn't read.
 *
 * @param markRolledBack what the rows' rolled_back flag becomes once it's applied
 */
public record Plan(Kind kind, List<Change> changes, long[] blockIds, long[] containerIds, boolean markRolledBack, int skipped) {

    public enum Kind { ROLLBACK, RESTORE, UNDO }

    /**
     * One thing to do at a block: set it to {@code target} (and put {@code contents} in
     * it), or, for a container change, add {@code delta} of {@code item} (taking away when
     * negative).
     */
    public record Change(String world, int x, int y, int z, BlockData target, Contents contents, ItemStack item, int delta) {

        public static Change block(String world, int x, int y, int z, BlockData target, Contents contents) {
            return new Change(world, x, y, z, target, contents, null, 0);
        }

        public static Change items(String world, int x, int y, int z, ItemStack item, int delta) {
            return new Change(world, x, y, z, null, null, item, delta);
        }

        public boolean isItems() {
            return item != null;
        }
    }
}
