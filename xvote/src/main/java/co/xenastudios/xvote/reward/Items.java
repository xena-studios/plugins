package co.xenastudios.xvote.reward;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/** Reward items as the config writes them: like /give, then an optional amount. */
public final class Items {

    private Items() {}

    /**
     * Reads {@code diamond}, {@code diamond 3} or {@code diamond_sword[enchantments={sharpness:3}] 1}.
     *
     * @throws IllegalArgumentException with a reason, if it isn't an item
     */
    public static ItemStack parse(String written) {
        String text = written.strip();
        int amount = 1;
        int space = text.lastIndexOf(' ');
        if (space > 0 && text.substring(space + 1).matches("\\d{1,4}")) {
            amount = Integer.parseInt(text.substring(space + 1));
            text = text.substring(0, space).strip();
        }
        if (text.isEmpty() || amount < 1) {
            throw new IllegalArgumentException("isn't an item, such as diamond 3");
        }
        ItemStack item;
        try {
            item = Bukkit.getItemFactory().createItemStack(text);
        } catch (RuntimeException e) {
            // Paper says why in its own words, which owners know from /give.
            throw new IllegalArgumentException("isn't an item, such as diamond 3 (" + e.getMessage() + ")");
        }
        if (item.getType().isAir()) {
            throw new IllegalArgumentException("is air");
        }
        item.setAmount(Math.min(amount, 6400));
        return item;
    }
}
