package co.xenastudios.xplots.rent;

import co.xenastudios.xplots.config.XPConfig;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

/** The wallet the config asks for, swapped on reload. */
public final class Wallets implements Wallet {

    private final Supplier<XPConfig> config;
    private final ItemWallet item;
    private final VaultWallet vault = new VaultWallet();

    public Wallets(Supplier<XPConfig> config) {
        this.config = config;
        this.item = new ItemWallet(() -> config.get().currency().item());
    }

    private Wallet current() {
        return config.get().currency().type() == XPConfig.Currency.Type.ITEM ? item : vault;
    }

    /** Why payments can't be taken, for the console, or null if they can. */
    public String problem() {
        XPConfig.Currency currency = config.get().currency();
        if (currency.type() == XPConfig.Currency.Type.ITEM) {
            return null;
        }
        if (!vault.installed()) {
            return "currency.type is vault, but Vault isn't installed. Install Vault and an economy plugin, or set currency.type: item.";
        }
        return vault.ready() ? null : "Vault is installed, but no economy plugin is (such as EssentialsX).";
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
}
