package co.xenastudios.xplots.setup;

import co.xenastudios.xplots.model.Box;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** The wand, and each sender's selection: two corners in a world. Main thread. */
public final class Selections {

    /** One sender's corners so far. */
    public static final class Selection {
        String world;
        int[] first;
        int[] second;

        /** The box between the corners, or null until both are set in one world. */
        public Box box() {
            return first == null || second == null ? null
                : Box.of(first[0], first[1], first[2], second[0], second[1], second[2]);
        }

        public String world() {
            return world;
        }
    }

    private final NamespacedKey wandKey;
    // Keyed by sender name: a player's, or "CONSOLE".
    private final Map<String, Selection> selections = new HashMap<>();

    public Selections(Plugin plugin) {
        this.wandKey = new NamespacedKey(plugin, "wand");
    }

    public ItemStack wand() {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(Component.text("xPlots wand", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            line("Left click: first corner"),
            line("Right click: second corner"),
            line("Sneak + right click: move the nearest sign here"),
            line("Sneak + left click: a plot's staff menu")));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        meta.setEnchantmentGlintOverride(true);
        wand.setItemMeta(meta);
        return wand;
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    public boolean isWand(ItemStack item) {
        if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    /**
     * Sets a corner, 1 or 2. A corner in another world starts the selection over.
     *
     * @return the selection
     */
    public Selection corner(String sender, int which, String world, int x, int y, int z) {
        Selection selection = selections.computeIfAbsent(sender, key -> new Selection());
        if (!world.equals(selection.world)) {
            selection.first = null;
            selection.second = null;
            selection.world = world;
        }
        int[] corner = {x, y, z};
        if (which == 1) {
            selection.first = corner;
        } else {
            selection.second = corner;
        }
        return selection;
    }

    /** The sender's selection, or null. */
    public Selection get(String sender) {
        return selections.get(sender);
    }

    public void forget(String sender) {
        selections.remove(sender);
    }
}
