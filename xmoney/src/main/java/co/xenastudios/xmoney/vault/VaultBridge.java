package co.xenastudios.xmoney.vault;

import co.xenastudios.xmoney.Bank;
import co.xenastudios.xmoney.XMoney;
import co.xenastudios.xmoney.api.TransactionKind;
import co.xenastudios.xmoney.ledger.Account;
import co.xenastudios.xmoney.ledger.Cause;
import co.xenastudios.xmoney.ledger.Outcome;
import co.xenastudios.xmoney.money.Money;
import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * What both Vault economies share: finding accounts without blocking, turning callers'
 * amounts into units, and the ledger's cause. Every method answers from memory, from any
 * thread; on a network a withdrawal waits for the shared database.
 */
final class VaultBridge {

    final XMoney plugin;
    final Bank bank;
    private final ClassLoader ours;

    VaultBridge(XMoney plugin) {
        this.plugin = plugin;
        this.bank = plugin.bank();
        this.ours = VaultBridge.class.getClassLoader();
    }

    Cause cause(String pluginName) {
        String source = pluginName == null || pluginName.isBlank() ? Callers.plugin(ours, "Vault") : pluginName;
        return new Cause(TransactionKind.PLUGIN, source, "");
    }

    Account existing(OfflinePlayer player) {
        return player == null ? null : bank.accounts().get(player.getUniqueId());
    }

    Account existing(UUID id) {
        return id == null ? null : bank.accounts().get(id);
    }

    Account existing(String name) {
        return name == null ? null : bank.accounts().named(name);
    }

    /** The account to pay, made empty if there's none. Never asks the disk or the web for a name. */
    Account payee(UUID id) {
        Account account = bank.accounts().get(id);
        if (account != null) {
            return account;
        }
        Player online = Bukkit.getPlayer(id);
        String name = online != null ? online.getName() : plugin.players().cachedName(id);
        return bank.ledger().account(id, name, true);
    }

    Account payee(OfflinePlayer player) {
        return player == null ? null : payee(player.getUniqueId());
    }

    /** A name-based account: the player or account by that name, or a non-player account made for it. */
    Account payee(String name) {
        return name == null || name.isBlank() ? null : bank.ledger().named(name);
    }

    long balance(Account account) {
        return account == null ? 0 : account.balance();
    }

    /** Whether the account has the amount. NaN or negative amounts: only "has nothing" is true. */
    boolean has(Account account, double amount) {
        if (Double.isNaN(amount)) {
            return false;
        }
        if (amount <= 0) {
            return true;
        }
        long units = Money.units(amount, bank.decimals());
        return units >= 0 && balance(account) >= units;
    }

    boolean has(Account account, BigDecimal amount) {
        if (amount == null) {
            return false;
        }
        if (amount.signum() <= 0) {
            return true;
        }
        long units = Money.units(amount, bank.decimals());
        return units >= 0 && balance(account) >= units;
    }

    /**
     * An amount to move, in units: -1 if it can't be one (NaN, infinite, negative, or past the
     * most an account can hold), 0 if it rounds to nothing.
     */
    long units(double amount) {
        return Money.units(amount, bank.decimals());
    }

    long units(BigDecimal amount) {
        return Money.units(amount, bank.decimals());
    }

    Outcome withdraw(Account account, long units, Cause cause) {
        return bank.await(bank.ledger().withdraw(account, units, cause), account);
    }

    Outcome deposit(Account account, long units, Cause cause) {
        return bank.await(bank.ledger().deposit(account, units, cause), account);
    }

    String format(long units) {
        return bank.format(units);
    }

    /** Why it didn't happen, for Vault's error message. */
    static String reason(Outcome outcome) {
        return switch (outcome.status()) {
            case SUCCESS -> "";
            case INSUFFICIENT_FUNDS -> "Insufficient funds";
            case BALANCE_LIMIT -> "That would take the balance over the most it can hold";
            case INVALID_AMOUNT -> "Not a valid amount";
            case SAME_ACCOUNT -> "Can't transfer to the same account";
            case CANCELLED -> "Cancelled by another plugin";
            case UNAVAILABLE -> "The economy's database didn't answer in time";
        };
    }
}
