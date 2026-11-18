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

    /** The account by this name, or failing that, the account of the player the server has cached by it. */
    Account existing(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Account known = bank.accounts().named(name);
        if (known != null) {
            return known;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : bank.accounts().get(cached.getUniqueId());
    }

    /** The account to pay, made empty if there's none. Never asks the disk or the web for a name. */
    Account payee(UUID id) {
        Account account = bank.accounts().get(id);
        if (account != null) {
            return account;
        }
        Player online = Bukkit.getPlayer(id);
        String name = online != null ? online.getName() : plugin.players().cachedName(id);
        // Players' uuids are version 4 (online mode) or 3 (offline); Citizens' NPCs use version
        // 2, and stay out of the top list.
        return bank.ledger().account(id, name, id.version() != 2);
    }

    Account payee(OfflinePlayer player) {
        return player == null ? null : payee(player.getUniqueId());
    }

    /**
     * A name-based account: the player or account by that name; a player the server knows by
     * it (from its cache, never the disk or the web), which also settles a name two stored
     * accounts share; or otherwise a non-player account made for it. Null if more than one
     * account has had the name and the server's cache doesn't say whose it is now.
     */
    Account payee(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Account known = bank.accounts().named(name);
        if (known != null) {
            return known;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            return payee(cached.getUniqueId());
        }
        return bank.accounts().ambiguous(name) ? null : bank.ledger().named(name);
    }

    long balance(Account account) {
        return account == null ? 0 : account.balance();
    }

    /**
     * Whether the account has the amount, rounded up as a withdrawal would be. NaN or negative
     * amounts: only "has nothing" is true.
     */
    boolean has(Account account, double amount) {
        if (Double.isNaN(amount)) {
            return false;
        }
        if (amount <= 0) {
            return true;
        }
        long units = Money.outgoing(amount);
        return units >= 0 && balance(account) >= units;
    }

    boolean has(Account account, BigDecimal amount) {
        if (amount == null) {
            return false;
        }
        if (amount.signum() <= 0) {
            return true;
        }
        long units = Money.outgoing(amount);
        return units >= 0 && balance(account) >= units;
    }

    /**
     * An amount to pay in or set, in units, rounded down past four places: -1 if it can't be
     * one (NaN, infinite, negative, or past the most an account can hold), 0 if it's less than
     * a ten-thousandth.
     */
    long incoming(double amount) {
        return Money.incoming(amount);
    }

    long incoming(BigDecimal amount) {
        return Money.incoming(amount);
    }

    /** An amount to take, in units, rounded up past four places, so a positive amount is never 0; -1 if it can't be one. */
    long outgoing(double amount) {
        return Money.outgoing(amount);
    }

    long outgoing(BigDecimal amount) {
        return Money.outgoing(amount);
    }

    /** An amount to show, rounded half up to the currency's decimals; -1 if it can't be one. */
    long shown(double amount) {
        return Money.units(amount, bank.decimals());
    }

    long shown(BigDecimal amount) {
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
