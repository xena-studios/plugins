package co.xenastudios.xrewind.listen;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.log.Actor;
import co.xenastudios.xrewind.log.Entry;
import co.xenastudios.xrewind.log.LogQueue;
import co.xenastudios.xrewind.storage.Clock;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;

/**
 * Blocks players place, with what was there before (water, tall grass, snow). Beds,
 * doors and tall plants are placed whole, so each of their blocks is logged.
 */
public final class BlockPlaces extends ListenerModule {

    private final LogQueue queue;

    public BlockPlaces(Plugin plugin, LogQueue queue) {
        super(plugin);
        this.queue = queue;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onPlace(BlockPlaceEvent event) {
        Actor actor = Actor.of(event.getPlayer());
        int time = Clock.now();
        if (event instanceof BlockMultiPlaceEvent multi) {
            for (BlockState replaced : multi.getReplacedBlockStates()) {
                log(time, actor, replaced);
            }
        } else {
            log(time, actor, event.getBlockReplacedState());
        }
    }

    private void log(int time, Actor actor, BlockState replaced) {
        // The block as it is now, after the place; the state is from before it.
        queue.add(new Entry.BlockChange(time, actor, replaced.getWorld().getName(), replaced.getX(), replaced.getY(),
            replaced.getZ(), Actions.PLACE, replaced.getBlockData(), replaced.getBlock().getBlockData()));
    }
}
