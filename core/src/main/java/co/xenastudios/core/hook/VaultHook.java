package co.xenastudios.core.hook;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** What {@link VaultEconomy} does once Vault is installed. Only loaded then. */
final class VaultHook {

    private static Economy economy() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : provider.getProvider();
    }

    boolean ready() {
        return economy() != null;
    }

    boolean has(OfflinePlayer player, double amount) {
        Economy economy = economy();
        return economy != null && (amount <= 0 || economy.has(player, amount));
    }

    boolean take(OfflinePlayer player, double amount) {
        Economy economy = economy();
        if (economy == null) {
            return false;
        }
        if (amount <= 0) {
            return true;
        }
        return economy.has(player, amount) && economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    boolean give(OfflinePlayer player, double amount) {
        Economy economy = economy();
        if (economy == null) {
            return false;
        }
        return amount <= 0 || economy.depositPlayer(player, amount).transactionSuccess();
    }

    double balance(OfflinePlayer player) {
        Economy economy = economy();
        return economy == null ? 0 : economy.getBalance(player);
    }

    String format(double amount) {
        Economy economy = economy();
        return economy == null ? null : economy.format(amount);
    }
}
