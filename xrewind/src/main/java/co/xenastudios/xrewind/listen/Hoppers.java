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
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Items hoppers and droppers move between containers ({@code #hopper}). Busy on farms, so off by default. */
public final class Hoppers extends ListenerModule {

    private final LogQueue queue;
    private final Actor hopper = Actor.source("hopper");

    public Hoppers(Plugin plugin, LogQueue queue) {
        super(plugin);
        this.queue = queue;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onMove(InventoryMoveItemEvent event) {
        ItemStack item = event.getItem().clone();
        int time = Clock.now();
        BlockState from = Containers.block(event.getSource().getHolder(false));
        if (from != null) {
            queue.add(new Entry.ContainerChange(time, hopper, from.getWorld().getName(), from.getX(), from.getY(), from.getZ(),
                Actions.REMOVE, item, item.getAmount()));
        }
        BlockState to = Containers.block(event.getDestination().getHolder(false));
        if (to != null) {
            queue.add(new Entry.ContainerChange(time, hopper, to.getWorld().getName(), to.getX(), to.getY(), to.getZ(),
                Actions.ADD, item, item.getAmount()));
        }
    }
}
