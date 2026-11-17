package co.xenastudios.xrewind.listen;

import co.xenastudios.xrewind.log.Actor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.block.Block;

/**
 * Blocks broken in the last second, and by whom, so a torch or sign that falls off a
 * broken block (or a chain of sugar cane) is put down to the same player or explosion.
 * Safe from any thread.
 */
public final class RecentBreaks {

    private static final long KEEP_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final int TIDY_AT = 4_096;

    private record Spot(String world, int x, int y, int z) {}

    private record Break(Actor actor, long at) {}

    private final Map<Spot, Break> recent = new ConcurrentHashMap<>();

    public void broke(Block block, Actor actor) {
        long now = System.nanoTime();
        if (recent.size() >= TIDY_AT) {
            recent.values().removeIf(old -> now - old.at() > KEEP_NANOS);
        }
        recent.put(new Spot(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()), new Break(actor, now));
    }

    /** Who broke a block next to this one in the last second, or null. */
    public Actor besides(Block block) {
        if (recent.isEmpty()) {
            return null;
        }
        long now = System.nanoTime();
        String world = block.getWorld().getName();
        int[][] sides = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] side : sides) {
            Break found = recent.get(new Spot(world, block.getX() + side[0], block.getY() + side[1], block.getZ() + side[2]));
            if (found != null && now - found.at() <= KEEP_NANOS) {
                return found.actor();
            }
        }
        return null;
    }
}
