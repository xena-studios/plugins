package co.xenastudios.xrewind.listen;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.log.Actor;
import co.xenastudios.xrewind.log.Contents;
import co.xenastudios.xrewind.log.Entry;
import co.xenastudios.xrewind.log.LogQueue;
import co.xenastudios.xrewind.storage.Clock;
import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;

/**
 * Blocks players break, with what they held (a chest's items, a sign's text), and the
 * blocks that fall off with them: torches, signs, rails, carpets, chains of sugar cane.
 * Last to see the event, and only if it went through.
 */
public final class BlockBreaks extends ListenerModule {

    private final LogQueue queue;
    private final RecentBreaks recent;
    private final BlockData air = Material.AIR.createBlockData();

    public BlockBreaks(Plugin plugin, LogQueue queue, RecentBreaks recent) {
        super(plugin);
        this.queue = queue;
        this.recent = recent;
        Contents.canHold(Material.CHEST); // Works out which blocks hold contents now, not on the first break.
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Actor actor = Actor.of(event.getPlayer());
        log(block, actor, Clock.now(), queue, air);
        recent.broke(block, actor);
    }

    // A block lost what held it up: put it down to whoever broke the one beside it.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onFallOff(BlockDestroyEvent event) {
        Block block = event.getBlock();
        Actor actor = recent.besides(block);
        if (actor != null) {
            queue.add(new Entry.BlockChange(Clock.now(), actor, block.getWorld().getName(), block.getX(), block.getY(),
                block.getZ(), Actions.BREAK, block.getBlockData(), event.getNewState(), null));
            recent.broke(block, actor);
        }
    }

    /** Logs a block about to be broken, with its contents if it holds any. */
    static void log(Block block, Actor actor, int time, LogQueue queue, BlockData after) {
        BlockData before = block.getBlockData();
        Contents contents = Contents.canHold(before.getMaterial()) ? Contents.of(block) : null;
        queue.add(new Entry.BlockChange(time, actor, block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
            Actions.BREAK, before, after, contents));
    }
}
