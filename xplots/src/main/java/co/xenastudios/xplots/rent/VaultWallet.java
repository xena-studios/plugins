package co.xenastudios.xplots.rent;

import co.xenastudios.xplots.model.Settings;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Rent through Vault's economy. The economy is looked up when it's used, since economy
 * plugins often register after xPlots starts. Only loaded when Vault is installed.
 */
final class VaultWallet implements Wallet {

    private Economy economy() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : provider.getProvider();
    }

    @Override
    public boolean ready() {
        return economy() != null;
    }

    @Override
    public boolean has(Player player, double amount) {
        Economy economy = economy();
        return economy != null && (amount <= 0 || economy.has(player, amount));
    }

    @Override
    public boolean take(Player player, double amount) {
        Economy economy = economy();
        if (economy == null) {
            return false;
        }
        if (amount <= 0) {
            return true;
        }
        return economy.has(player, amount) && economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    @Override
    public void give(Player player, double amount) {
        Economy economy = economy();
        if (economy != null && amount > 0) {
            economy.depositPlayer(player, amount);
        }
    }

    @Override
    public String format(double amount) {
        Economy economy = economy();
        return economy != null ? economy.format(amount) : Settings.strip(amount);
    }
}
