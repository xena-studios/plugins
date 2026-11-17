package co.xenastudios.xrewind.listen;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.log.Actor;
import co.xenastudios.xrewind.log.Entry;
import co.xenastudios.xrewind.log.LogQueue;
import co.xenastudios.xrewind.storage.Clock;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;

/** Blocks players break. Last to see the event, and only if it went through. */
public final class BlockBreaks extends ListenerModule {

    private final LogQueue queue;
    private final BlockData air = Material.AIR.createBlockData();

    public BlockBreaks(Plugin plugin, LogQueue queue) {
        super(plugin);
        this.queue = queue;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        queue.add(new Entry.BlockChange(Clock.now(), Actor.of(event.getPlayer()), block.getWorld().getName(),
            block.getX(), block.getY(), block.getZ(), Actions.BREAK, block.getBlockData(), air));
    }
}
