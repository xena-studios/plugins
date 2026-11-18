package co.xenastudios.core.hook;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * The server's economy through Vault, for plugins that charge or pay players. Vault is
 * optional: this class never touches Vault's own classes, and the part that does is only
 * loaded once Vault is enabled. Without Vault, or without an economy plugin behind it,
 * nothing can be taken and {@link #ready} says so.
 *
 * <p>The economy is looked up each time it's used, since economy plugins often register
 * after the plugins that use them start, and can be swapped while the server runs.
 *
 * <p>A plugin using it declares Vault as an optional server dependency that loads before
 * it, with {@code joinClasspath = true}, and adds Vault's API {@code compileOnly}. Call it
 * where the economy plugin expects (the main thread for most), as Vault itself says.
 */
public final class VaultEconomy {

    private VaultHook hook;

    /** Whether Vault is installed and enabled. */
    public boolean installed() {
        return Bukkit.getPluginManager().isPluginEnabled("Vault");
    }

    // Created once Vault is enabled: the first point where Vault's classes are loaded.
    private VaultHook hook() {
        if (hook == null && installed()) {
            hook = new VaultHook();
        }
        return hook;
    }

    /** Whether payments can be taken now: Vault is installed, and an economy plugin is registered with it. */
    public boolean ready() {
        VaultHook vault = hook();
        return vault != null && vault.ready();
    }

    /** Whether the player has the amount. False when the economy isn't {@link #ready}. */
    public boolean has(OfflinePlayer player, double amount) {
        VaultHook vault = hook();
        return vault != null && vault.has(player, amount);
    }

    /** Takes the amount, or nothing if the player hasn't got it or the economy isn't ready. Nothing to take always works. */
    public boolean take(OfflinePlayer player, double amount) {
        VaultHook vault = hook();
        return vault != null && vault.take(player, amount);
    }

    /** Gives the player the amount; false if the economy isn't ready or refused it. */
    public boolean give(OfflinePlayer player, double amount) {
        VaultHook vault = hook();
        return vault != null && vault.give(player, amount);
    }

    /** The player's balance, or 0 when the economy isn't ready. */
    public double balance(OfflinePlayer player) {
        VaultHook vault = hook();
        return vault == null ? 0 : vault.balance(player);
    }

    /** The amount as the economy shows it ("$128.00"), or null when the economy isn't ready. */
    public String format(double amount) {
        VaultHook vault = hook();
        return vault == null ? null : vault.format(amount);
    }
}
