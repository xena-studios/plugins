package co.xenastudios.xmoney.vault;

import co.xenastudios.xmoney.XMoney;
import co.xenastudios.xmoney.ledger.Account;
import co.xenastudios.xmoney.ledger.Outcome;
import co.xenastudios.xmoney.money.Money;
import java.util.List;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

/**
 * Vault 1's {@code Economy}, the API most plugins use. Every call answers from memory and is
 * safe from any thread. Amounts that aren't numbers, are negative, or are too large to hold
 * are refused; nothing is ever made from them. Worlds are ignored: one balance everywhere.
 * Banks aren't supported in 1.0, and say so.
 */
@SuppressWarnings("deprecation") // Vault 1 is deprecated in favour of VaultUnlocked 2; most plugins still use it.
final class LegacyEconomy implements Economy {

    private static final String NO_BANKS = XMoney.NAME + " doesn't have banks.";

    private final VaultBridge bridge;

    LegacyEconomy(VaultBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public boolean isEnabled() {
        return bridge.plugin.isEnabled();
    }

    @Override
    public String getName() {
        return XMoney.NAME;
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return bridge.bank.decimals();
    }

    @Override
    public String format(double amount) {
        if (!Double.isFinite(amount)) {
            return bridge.format(0);
        }
        long units = bridge.units(Math.abs(amount));
        if (units < 0) {
            units = Money.MAX_UNITS;
        }
        return bridge.format(amount < 0 ? -units : units);
    }

    @Override
    public String currencyNamePlural() {
        return bridge.bank.format().settings().plural();
    }

    @Override
    public String currencyNameSingular() {
        return bridge.bank.format().settings().singular();
    }

    // ---- accounts ----

    @Override
    public boolean hasAccount(String playerName) {
        return bridge.existing(playerName) != null;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return bridge.existing(player) != null;
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        if (playerName == null || playerName.isBlank() || bridge.existing(playerName) != null) {
            return false;
        }
        return bridge.payee(playerName) != null;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        if (player == null || bridge.existing(player) != null) {
            return false;
        }
        bridge.payee(player);
        return true;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // ---- balances ----

    @Override
    public double getBalance(String playerName) {
        return Money.toDouble(bridge.balance(bridge.existing(playerName)));
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return Money.toDouble(bridge.balance(bridge.existing(player)));
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return bridge.has(bridge.existing(playerName), amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return bridge.has(bridge.existing(player), amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    // ---- changes ----

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdraw(bridge.existing(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return withdraw(bridge.existing(player), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        long units = bridge.units(amount);
        if (units < 0) {
            return invalid(bridge.existing(playerName));
        }
        return deposit(bridge.payee(playerName), units);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        long units = bridge.units(amount);
        if (units < 0 || player == null) {
            return invalid(bridge.existing(player));
        }
        return deposit(bridge.payee(player), units);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    private EconomyResponse withdraw(Account account, double amount) {
        long units = bridge.units(amount);
        if (units < 0) {
            return invalid(account);
        }
        if (units == 0) {
            return new EconomyResponse(0, Money.toDouble(bridge.balance(account)), EconomyResponse.ResponseType.SUCCESS, "");
        }
        if (account == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
        }
        return response(bridge.withdraw(account, units, bridge.cause(null)));
    }

    private EconomyResponse deposit(Account account, long units) {
        if (account == null) {
            return invalid(null);
        }
        if (units == 0) {
            return new EconomyResponse(0, Money.toDouble(account.balance()), EconomyResponse.ResponseType.SUCCESS, "");
        }
        return response(bridge.deposit(account, units, bridge.cause(null)));
    }

    private EconomyResponse invalid(Account account) {
        return new EconomyResponse(0, Money.toDouble(bridge.balance(account)), EconomyResponse.ResponseType.FAILURE, "Not a valid amount");
    }

    private static EconomyResponse response(Outcome outcome) {
        return new EconomyResponse(Money.toDouble(outcome.amount()), Money.toDouble(outcome.balance()),
            outcome.success() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE, VaultBridge.reason(outcome));
    }

    // ---- banks: not in 1.0 ----

    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, NO_BANKS);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return noBanks();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}
