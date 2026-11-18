package co.xenastudios.xplots.rent;

import co.xenastudios.xplots.config.XPConfig;
import java.util.function.Supplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Rent paid in an item, such as a box server's Coin: matched by material, and by name and
 * custom model data when the config gives them. Amounts are whole items, rounded up.
 */
final class ItemWallet implements Wallet {

    private final Supplier<XPConfig.ItemCurrency> settings;

    ItemWallet(Supplier<XPConfig.ItemCurrency> settings) {
        this.settings = settings;
    }

    @Override
    public boolean ready() {
        return true;
    }

    @Override
    public boolean has(Player player, double amount) {
        return count(player.getInventory(), settings.get()) >= whole(amount);
    }

    @Override
    public boolean take(Player player, double amount) {
        XPConfig.ItemCurrency item = settings.get();
        int needed = whole(amount);
        PlayerInventory inventory = player.getInventory();
        if (count(inventory, item) < needed) {
            return false;
        }
        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length && needed > 0; i++) {
            ItemStack stack = contents[i];
            if (matches(stack, item)) {
                int used = Math.min(needed, stack.getAmount());
                needed -= used;
                stack.setAmount(stack.getAmount() - used);
                contents[i] = stack.getAmount() <= 0 ? null : stack;
            }
        }
        inventory.setStorageContents(contents);
        return true;
    }

    @Override
    @SuppressWarnings("deprecation") // Custom model data as a number, as matches() reads it.
    public void give(Player player, double amount) {
        int count = (int) Math.floor(amount);
        if (count <= 0) {
            return;
        }
        XPConfig.ItemCurrency item = settings.get();
        ItemStack stack = new ItemStack(item.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (!item.name().isBlank()) {
                meta.displayName(net.kyori.adventure.text.Component.text(item.name())
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            }
            if (item.customModelData() != 0) {
                meta.setCustomModelData(item.customModelData());
            }
            stack.setItemMeta(meta);
        }
        int max = stack.getMaxStackSize();
        while (count > 0) {
            ItemStack give = stack.clone();
            give.setAmount(Math.min(max, count));
            count -= give.getAmount();
            // What doesn't fit is dropped at their feet, never lost.
            player.getInventory().addItem(give).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
    }

    @Override
    public String format(double amount) {
        return XPConfig.amount(settings.get(), whole(amount));
    }

    private static int whole(double amount) {
        return amount <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.ceil(amount - 1e-9));
    }

    private static int count(PlayerInventory inventory, XPConfig.ItemCurrency item) {
        int total = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (matches(stack, item)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    @SuppressWarnings("deprecation") // Custom model data as a number: what resource packs for 1.21.11 use.
    static boolean matches(ItemStack stack, XPConfig.ItemCurrency item) {
        if (stack == null || stack.getType() != item.material()) {
            return false;
        }
        if (item.name().isBlank() && item.customModelData() == 0) {
            return true;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (!item.name().isBlank()) {
            if (!meta.hasDisplayName()) {
                return false;
            }
            String shown = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
            if (!shown.strip().equalsIgnoreCase(item.name().strip())) {
                return false;
            }
        }
        return item.customModelData() == 0 || (meta.hasCustomModelData() && meta.getCustomModelData() == item.customModelData());
    }
}
