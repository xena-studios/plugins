package co.xenastudios.xrewind.rollback;

import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.log.Contents;
import co.xenastudios.xrewind.query.Formats;
import co.xenastudios.xrewind.query.Row;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

/**
 * Turns matching rows into a {@link Plan}, collapsing each block's changes into one: a
 * block changed 200 times is set once. Item changes to a container add up per item.
 * Runs off the server's threads.
 */
public final class Planner {

    private record Spot(String world, int x, int y, int z) {}

    private record Target(String state, byte[] contents) {}

    private record Stack(Spot spot, ByteBuffer item) {}

    private Planner() {}

    /**
     * @param blocks block rows, oldest first
     * @param containers container rows, oldest first
     * @param restore false for a rollback: each block goes back to how it was before its
     *     earliest matching change, with what it held then, and items taken are put back
     *     and items added taken out. True for a restore: each block to how it was after its
     *     latest change, and items moved again.
     */
    public static Plan plan(List<Row> blocks, List<Row> containers, boolean restore) {
        Map<Spot, Target> targets = new LinkedHashMap<>();
        long[] blockIds = new long[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            Row row = blocks.get(i);
            blockIds[i] = row.id();
            Spot spot = new Spot(row.world(), row.x(), row.y(), row.z());
            if (restore) {
                targets.put(spot, new Target(row.after(), null));
            } else {
                // What a broken block held goes back with it.
                targets.putIfAbsent(spot, new Target(row.before(), row.action() == Actions.BREAK ? row.extra() : null));
            }
        }
        Map<String, BlockData> parsed = new HashMap<>();
        List<Plan.Change> changes = new ArrayList<>(targets.size());
        int skipped = 0;
        for (Map.Entry<Spot, Target> entry : targets.entrySet()) {
            BlockData data = parsed.computeIfAbsent(entry.getValue().state(), Planner::read);
            if (data == null) {
                skipped++;
                continue;
            }
            Spot spot = entry.getKey();
            changes.add(Plan.Change.block(spot.world(), spot.x(), spot.y(), spot.z(), data, Contents.read(entry.getValue().contents())));
        }

        Map<Stack, Integer> deltas = new LinkedHashMap<>();
        long[] containerIds = new long[containers.size()];
        for (int i = 0; i < containers.size(); i++) {
            Row row = containers.get(i);
            containerIds[i] = row.id();
            int amount = row.action() == Actions.ADD ? row.amount() : -row.amount();
            deltas.merge(new Stack(new Spot(row.world(), row.x(), row.y(), row.z()), ByteBuffer.wrap(row.extra())),
                restore ? amount : -amount, Integer::sum);
        }
        for (Map.Entry<Stack, Integer> entry : deltas.entrySet()) {
            if (entry.getValue() == 0) {
                continue;
            }
            ItemStack item;
            try {
                item = ItemStack.deserializeBytes(entry.getKey().item().array());
            } catch (RuntimeException e) {
                skipped++;
                continue;
            }
            Spot spot = entry.getKey().spot();
            changes.add(Plan.Change.items(spot.world(), spot.x(), spot.y(), spot.z(), item, entry.getValue()));
        }
        return new Plan(restore ? Plan.Kind.RESTORE : Plan.Kind.ROLLBACK, changes, blockIds, containerIds, !restore, skipped);
    }

    /**
     * A stored state as this server reads it. One from an older Minecraft that's since
     * changed (a renamed property) falls back to its block with default properties; a
     * block that no longer exists can't be read.
     */
    static BlockData read(String state) {
        try {
            return Bukkit.createBlockData(state);
        } catch (IllegalArgumentException e) {
            Material material = Material.matchMaterial(Formats.name(state));
            return material != null && material.isBlock() ? material.createBlockData() : null;
        }
    }
}
