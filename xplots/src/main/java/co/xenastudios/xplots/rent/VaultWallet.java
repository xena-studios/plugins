package co.xenastudios.xplots.rent;

import co.xenastudios.core.hook.VaultEconomy;
import co.xenastudios.xplots.model.Settings;
import org.bukkit.entity.Player;

/** Rent through Vault's economy, by the core's hook. Without Vault, or an economy plugin, nothing can be taken. */
final class VaultWallet implements Wallet {

    private final VaultEconomy economy = new VaultEconomy();

    boolean installed() {
        return economy.installed();
    }

    @Override
    public boolean ready() {
        return economy.ready();
    }

    @Override
    public boolean has(Player player, double amount) {
        return economy.has(player, amount);
    }

    @Override
    public boolean take(Player player, double amount) {
        return economy.take(player, amount);
    }

    @Override
    public void give(Player player, double amount) {
        economy.give(player, amount);
    }

    @Override
    public String format(double amount) {
        String shown = economy.format(amount);
        return shown != null ? shown : Settings.strip(amount);
    }
}
