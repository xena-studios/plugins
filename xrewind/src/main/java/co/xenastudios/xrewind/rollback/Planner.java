package co.xenastudios.xrewind.rollback;

import co.xenastudios.xrewind.query.Formats;
import co.xenastudios.xrewind.query.Row;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * Turns matching rows into a {@link Plan}, collapsing each block's changes into one: a
 * block changed 200 times is set once. Runs off the server's threads.
 */
public final class Planner {

    private record Spot(String world, int x, int y, int z) {}

    private Planner() {}

    /**
     * @param rows oldest first
     * @param restore false for a rollback: each block goes back to how it was before its
     *     earliest matching change. True for a restore: to how it was after its latest.
     */
    public static Plan plan(List<Row> rows, boolean restore) {
        Map<Spot, String> targets = new LinkedHashMap<>();
        long[] ids = new long[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ids[i] = row.id();
            Spot spot = new Spot(row.world(), row.x(), row.y(), row.z());
            if (restore) {
                targets.put(spot, row.after());
            } else {
                targets.putIfAbsent(spot, row.before());
            }
        }
        Map<String, BlockData> parsed = new HashMap<>();
        List<Plan.Change> changes = new ArrayList<>(targets.size());
        int skipped = 0;
        for (Map.Entry<Spot, String> target : targets.entrySet()) {
            BlockData data = parsed.computeIfAbsent(target.getValue(), Planner::read);
            if (data == null) {
                skipped++;
                continue;
            }
            Spot spot = target.getKey();
            changes.add(new Plan.Change(spot.world(), spot.x(), spot.y(), spot.z(), data));
        }
        return new Plan(restore ? Plan.Kind.RESTORE : Plan.Kind.ROLLBACK, changes, ids, !restore, skipped);
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
