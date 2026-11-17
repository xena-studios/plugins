package co.xenastudios.xrewind.rollback;

import java.util.List;
import org.bukkit.block.data.BlockData;

/**
 * What a rollback, restore or undo will do: one target per block, the rows it covers,
 * and how many stored states this server couldn't read.
 *
 * @param markRolledBack what the rows' rolled_back flag becomes once it's applied
 */
public record Plan(Kind kind, List<Change> changes, long[] ids, boolean markRolledBack, int skipped) {

    public enum Kind { ROLLBACK, RESTORE, UNDO }

    /** One block to set. */
    public record Change(String world, int x, int y, int z, BlockData target) {}
}
