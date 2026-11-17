package co.xenastudios.xrewind.query;

import co.xenastudios.xrewind.XRewind;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * The inspector: while it's on for a player, clicking a block shows its history instead
 * of breaking or using it, and placing one shows what was at that spot. Listens only
 * while someone has it on.
 */
public final class Inspector implements Listener {

    private final XRewind plugin;
    private final Lookups lookups;
    private final Set<UUID> inspecting = ConcurrentHashMap.newKeySet();
    private boolean registered;

    public Inspector(XRewind plugin, Lookups lookups) {
        this.plugin = plugin;
        this.lookups = lookups;
    }

    /** Turns it on or off for the player. @return whether it's on now */
    public synchronized boolean toggle(Player player) {
        boolean on = inspecting.add(player.getUniqueId());
        if (!on) {
            inspecting.remove(player.getUniqueId());
        }
        sync();
        return on;
    }

    public boolean isInspecting(Player player) {
        return inspecting.contains(player.getUniqueId());
    }

    /** Off for everyone: on disable. */
    public synchronized void clear() {
        inspecting.clear();
        sync();
    }

    private void sync() {
        if (!inspecting.isEmpty() && !registered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            registered = true;
        } else if (inspecting.isEmpty() && registered) {
            HandlerList.unregisterAll(this);
            registered = false;
        }
    }

    // First, so nothing else acts on a click that's only a question, and it isn't logged.
    @EventHandler(priority = EventPriority.LOWEST)
    void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (clicked == null || event.getHand() != EquipmentSlot.HAND || !isInspecting(player)) {
            return;
        }
        Block target;
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            target = clicked;
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            // Right-clicking with a block places it: that's handled as a place.
            if (event.getItem() != null && event.getItem().getType().isBlock()) {
                return;
            }
            target = clicked;
        } else {
            return;
        }
        event.setCancelled(true);
        show(player, target);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    void onPlace(BlockPlaceEvent event) {
        if (isInspecting(event.getPlayer())) {
            event.setCancelled(true);
            show(event.getPlayer(), event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    void onBreak(BlockBreakEvent event) {
        if (isInspecting(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        if (inspecting.remove(event.getPlayer().getUniqueId())) {
            synchronized (this) {
                sync();
            }
        }
    }

    private void show(Player player, Block block) {
        lookups.run(player, Lookups.at(), new Lookup.Center(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()), 1, true);
    }
}
