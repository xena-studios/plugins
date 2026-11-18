package co.xenastudios.xplots.menu;

import co.xenastudios.core.ListenerModule;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;

/** Clicks in xPlots' menus: nothing moves, and buttons do what they say. */
public final class MenuListener extends ListenerModule {

    public MenuListener(Plugin plugin) {
        super(plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Menu menu)) {
            return;
        }
        // Shift-clicks and number keys from the player's own inventory would move items in.
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Consumer<Player> action = menu.action(event.getSlot());
        if (action != null) {
            // Next tick, so a button that opens another menu doesn't do it mid-click.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    action.accept(player);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu) {
            int size = event.getView().getTopInventory().getSize();
            for (int slot : event.getRawSlots()) {
                if (slot < size) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }
}
