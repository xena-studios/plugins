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
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.plugin.Plugin;

/** Blocks burnt away ({@code #fire}), and fires players light. */
public final class Fire extends ListenerModule {

    private final LogQueue queue;
    private final BlockData air = Material.AIR.createBlockData();
    private final BlockData fire = Material.FIRE.createBlockData();
    private final Actor source = Actor.source("fire");

    public Fire(Plugin plugin, LogQueue queue) {
        super(plugin);
        this.queue = queue;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBurn(BlockBurnEvent event) {
        BlockBreaks.log(event.getBlock(), source, Clock.now(), queue, air);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() == null) {
            return;
        }
        Block block = event.getBlock();
        queue.add(new Entry.BlockChange(Clock.now(), Actor.of(event.getPlayer()), block.getWorld().getName(),
            block.getX(), block.getY(), block.getZ(), Actions.PLACE, block.getBlockData(), fire));
    }
}
