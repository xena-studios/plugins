package co.xenastudios.xplots.menu;

import co.xenastudios.core.text.Message;
import co.xenastudios.xplots.config.XPConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** A chest menu whose items are buttons. Nothing in it can be taken. */
public final class Menu implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    Menu(int rows, String title, TagResolver... placeholders) {
        Component shown = Message.of(title).render(placeholders);
        this.inventory = Bukkit.createInventory(this, rows * 9, shown);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Puts a button in a slot; clicking it runs the action, on the next tick. */
    void set(int slot, ItemStack item, Consumer<Player> action) {
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    /** Fills the empty slots, so the menu reads as a panel. */
    void fill(Material filler) {
        if (filler == Material.AIR || !filler.isItem()) {
            return;
        }
        ItemStack pane = new ItemStack(filler);
        ItemMeta meta = pane.getItemMeta();
        meta.setHideTooltip(true);
        pane.setItemMeta(meta);
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, pane);
            }
        }
    }

    Consumer<Player> action(int slot) {
        return actions.get(slot);
    }

    /** A button from the config, with placeholders filled in. */
    static ItemStack item(XPConfig.Button button, TagResolver... placeholders) {
        ItemStack item = new ItemStack(button.material().isItem() ? button.material() : Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(Message.of(button.name()).render(placeholders)));
        List<Component> lore = new ArrayList<>(button.lore().size());
        for (String line : button.lore()) {
            lore.add(plain(Message.of(line).render(placeholders)));
        }
        meta.lore(lore);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    // Item names are italic unless told otherwise, which menus never want.
    private static Component plain(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
