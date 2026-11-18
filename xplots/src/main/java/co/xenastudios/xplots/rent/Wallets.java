package co.xenastudios.xplots.rent;

import co.xenastudios.xplots.config.XPConfig;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** The wallet the config asks for, swapped on reload. */
public final class Wallets implements Wallet {

    private final Supplier<XPConfig> config;
    private final ItemWallet item;
    private Wallet vault;

    public Wallets(Supplier<XPConfig> config) {
        this.config = config;
        this.item = new ItemWallet(() -> config.get().currency().item());
    }

    private Wallet current() {
        if (config.get().currency().type() == XPConfig.Currency.Type.ITEM) {
            return item;
        }
        if (vault == null) {
            if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
                return Missing.INSTANCE;
            }
            vault = new VaultWallet();
        }
        return vault;
    }

    /** Why payments can't be taken, for the console, or null if they can. */
    public String problem() {
        XPConfig.Currency currency = config.get().currency();
        if (currency.type() == XPConfig.Currency.Type.ITEM) {
            return null;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            return "currency.type is vault, but Vault isn't installed. Install Vault and an economy plugin, or set currency.type: item.";
        }
        return current().ready() ? null : "Vault is installed, but no economy plugin is (such as EssentialsX).";
    }

    @Override
    public boolean ready() {
        return current().ready();
    }

    @Override
    public boolean has(Player player, double amount) {
        return current().has(player, amount);
    }

    @Override
    public boolean take(Player player, double amount) {
        return current().take(player, amount);
    }

    @Override
    public void give(Player player, double amount) {
        current().give(player, amount);
    }

    @Override
    public String format(double amount) {
        return current().format(amount);
    }

    private enum Missing implements Wallet {
        INSTANCE;

        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public boolean has(Player player, double amount) {
            return false;
        }

        @Override
        public boolean take(Player player, double amount) {
            return false;
        }

        @Override
        public void give(Player player, double amount) {}

        @Override
        public String format(double amount) {
            return co.xenastudios.xplots.model.Settings.strip(amount);
        }
    }
}
