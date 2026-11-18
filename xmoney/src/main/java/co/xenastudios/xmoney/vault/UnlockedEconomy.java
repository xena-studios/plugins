package co.xenastudios.xmoney.vault;

import co.xenastudios.xmoney.XMoney;
import co.xenastudios.xmoney.ledger.Account;
import co.xenastudios.xmoney.ledger.Outcome;
import co.xenastudios.xmoney.money.Format;
import co.xenastudios.xmoney.money.Money;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import net.milkbowl.vault2.economy.EconomyResponse.ResponseType;
import net.milkbowl.vault2.economy.MultiEconomyResponse;

/**
 * VaultUnlocked 2's {@code Economy}: by uuid, in {@code BigDecimal}, with the calling
 * plugin's name, which the ledger records. One currency in 1.0, {@code default}; asking for
 * another finds nothing and changes nothing. Worlds are ignored. Shared accounts aren't
 * supported in 1.0, and say so. Every call answers from memory and is safe from any thread.
 * Amounts and balances keep four decimal places (12.50, 0.995): deposits and sets are rounded
 * down past them, withdrawals, transfers and {@code has} up.
 */
@SuppressWarnings("deprecation") // getBalance, which VaultUnlocked deprecated for balance() but still calls.
final class UnlockedEconomy implements Economy {

    private final VaultBridge bridge;

    UnlockedEconomy(VaultBridge bridge) {
        this.bridge = bridge;
    }

    private boolean ours(String currency) {
        if (currency == null || currency.isBlank()) {
            return true;
        }
        String asked = currency.toLowerCase(Locale.ROOT);
        var settings = bridge.bank.format().settings();
        return asked.equals(Format.currencyId()) || asked.equalsIgnoreCase(settings.singular()) || asked.equalsIgnoreCase(settings.plural());
    }

    private BigDecimal decimal(long units) {
        return bridge.bank.exact(units);
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
    public boolean hasSharedAccountSupport() {
        return false;
    }

    @Override
    public boolean hasMultiCurrencySupport() {
        return false;
    }

    @Override
    public int fractionalDigits(String pluginName) {
        return bridge.bank.decimals();
    }

    @Override
    public String format(BigDecimal amount) {
        if (amount == null) {
            return bridge.format(0);
        }
        long units = bridge.shown(amount.abs());
        if (units < 0) {
            units = Money.MAX_UNITS;
        }
        return bridge.format(amount.signum() < 0 ? -units : units);
    }

    @Override
    public String format(String pluginName, BigDecimal amount) {
        return format(amount);
    }

    @Override
    public String format(BigDecimal amount, String currency) {
        return format(amount);
    }

    @Override
    public String format(String pluginName, BigDecimal amount, String currency) {
        return format(amount);
    }

    @Override
    public boolean hasCurrency(String currency) {
        return ours(currency);
    }

    @Override
    public String getDefaultCurrency(String pluginName) {
        return Format.currencyId();
    }

    @Override
    public String defaultCurrencyNamePlural(String pluginName) {
        return bridge.bank.format().settings().plural();
    }

    @Override
    public String defaultCurrencyNameSingular(String pluginName) {
        return bridge.bank.format().settings().singular();
    }

    @Override
    public Collection<String> currencies() {
        return List.of(Format.currencyId());
    }

    // ---- accounts ----

    @Override
    public boolean createAccount(UUID id, String name) {
        return createAccount(id, name, true);
    }

    @Override
    public boolean createAccount(UUID id, String name, boolean player) {
        if (id == null || bridge.existing(id) != null) {
            return false;
        }
        bridge.bank.ledger().account(id, name, player);
        return true;
    }

    @Override
    public boolean createAccount(UUID id, String name, String world) {
        return createAccount(id, name, true);
    }

    @Override
    public boolean createAccount(UUID id, String name, String world, boolean player) {
        return createAccount(id, name, player);
    }

    @Override
    public Map<UUID, String> getUUIDNameMap() {
        Map<UUID, String> names = new HashMap<>();
        for (Account account : bridge.bank.accounts().all()) {
            names.put(account.id(), account.name());
        }
        return names;
    }

    @Override
    public Optional<String> getAccountName(UUID id) {
        Account account = bridge.existing(id);
        return account == null ? Optional.empty() : Optional.ofNullable(account.name());
    }

    @Override
    public boolean hasAccount(UUID id) {
        return bridge.existing(id) != null;
    }

    @Override
    public boolean hasAccount(UUID id, String world) {
        return hasAccount(id);
    }

    @Override
    public boolean renameAccount(UUID id, String name) {
        Account account = bridge.existing(id);
        // Not to a name another account is known by: name-based calls would find the wrong one.
        return account != null && bridge.bank.ledger().rename(account, name);
    }

    @Override
    public boolean renameAccount(String pluginName, UUID id, String name) {
        return renameAccount(id, name);
    }

    /** Accounts are never deleted: their money and history would go with them. */
    @Override
    public boolean deleteAccount(String pluginName, UUID id) {
        return false;
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID id, String currency) {
        return ours(currency);
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID id, String currency, String world) {
        return ours(currency);
    }

    // ---- balances ----

    @Override
    public BigDecimal getBalance(String pluginName, UUID id) {
        return decimal(bridge.balance(bridge.existing(id)));
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID id, String world) {
        return getBalance(pluginName, id);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID id, String world, String currency) {
        return ours(currency) ? getBalance(pluginName, id) : BigDecimal.ZERO;
    }

    @Override
    public boolean has(String pluginName, UUID id, BigDecimal amount) {
        return bridge.has(bridge.existing(id), amount);
    }

    @Override
    public boolean has(String pluginName, UUID id, String world, BigDecimal amount) {
        return has(pluginName, id, amount);
    }

    @Override
    public boolean has(String pluginName, UUID id, String world, String currency, BigDecimal amount) {
        return ours(currency) ? has(pluginName, id, amount) : amount != null && amount.signum() <= 0;
    }

    // ---- changes ----

    @Override
    public EconomyResponse withdraw(String pluginName, UUID id, BigDecimal amount) {
        Account account = bridge.existing(id);
        long units = bridge.outgoing(amount);
        if (units < 0) {
            return failure(account, "Not a valid amount");
        }
        if (units == 0) {
            return new EconomyResponse(BigDecimal.ZERO, decimal(bridge.balance(account)), ResponseType.SUCCESS, "");
        }
        if (account == null) {
            return failure(null, "Insufficient funds");
        }
        return response(bridge.withdraw(account, units, bridge.cause(pluginName)));
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID id, String world, BigDecimal amount) {
        return withdraw(pluginName, id, amount);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID id, String world, String currency, BigDecimal amount) {
        return ours(currency) ? withdraw(pluginName, id, amount) : unknownCurrency(id);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID id, BigDecimal amount) {
        long units = bridge.incoming(amount);
        if (units < 0 || id == null) {
            return failure(bridge.existing(id), "Not a valid amount");
        }
        Account account = bridge.payee(id);
        if (units == 0) {
            return new EconomyResponse(BigDecimal.ZERO, decimal(account.balance()), ResponseType.SUCCESS, "");
        }
        return response(bridge.deposit(account, units, bridge.cause(pluginName)));
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID id, String world, BigDecimal amount) {
        return deposit(pluginName, id, amount);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID id, String world, String currency, BigDecimal amount) {
        return ours(currency) ? deposit(pluginName, id, amount) : unknownCurrency(id);
    }

    @Override
    public EconomyResponse set(String pluginName, UUID id, BigDecimal amount) {
        long units = bridge.incoming(amount);
        if (units < 0 || id == null) {
            return failure(bridge.existing(id), "Not a valid amount");
        }
        Account account = bridge.payee(id);
        return response(bridge.bank.await(bridge.bank.ledger().set(account, units, bridge.cause(pluginName)), account));
    }

    @Override
    public EconomyResponse set(String pluginName, UUID id, String world, BigDecimal amount) {
        return set(pluginName, id, amount);
    }

    @Override
    public EconomyResponse set(String pluginName, UUID id, String world, String currency, BigDecimal amount) {
        return ours(currency) ? set(pluginName, id, amount) : unknownCurrency(id);
    }

    /** One step: both balances change, or neither, unlike Vault's default of a withdrawal then a deposit. */
    @Override
    public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, BigDecimal amount) {
        long units = bridge.outgoing(amount);
        Account payer = bridge.existing(from);
        if (units <= 0 || to == null) {
            return new MultiEconomyResponse(BigDecimal.ZERO, ResponseType.FAILURE, "Not a valid amount");
        }
        if (payer == null) {
            return new MultiEconomyResponse(BigDecimal.ZERO, ResponseType.FAILURE, "Insufficient funds");
        }
        Account payee = bridge.payee(to);
        Outcome outcome = bridge.bank.await(bridge.bank.ledger().transfer(payer, payee, units, bridge.cause(pluginName)), payer);
        MultiEconomyResponse response = new MultiEconomyResponse(decimal(outcome.amount()),
            outcome.success() ? ResponseType.SUCCESS : ResponseType.FAILURE, VaultBridge.reason(outcome));
        response.addBalance(from, decimal(payer.balance()));
        response.addBalance(to, decimal(payee.balance()));
        return response;
    }

    @Override
    public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, String world, BigDecimal amount) {
        return transfer(pluginName, from, to, amount);
    }

    @Override
    public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, String world, String currency, BigDecimal amount) {
        return ours(currency) ? transfer(pluginName, from, to, amount)
            : new MultiEconomyResponse(BigDecimal.ZERO, ResponseType.FAILURE, "Unknown currency");
    }

    @Override
    public EconomyResponse canWithdraw(String pluginName, UUID id, BigDecimal amount) {
        Account account = bridge.existing(id);
        long units = bridge.outgoing(amount);
        if (units < 0) {
            return failure(account, "Not a valid amount");
        }
        boolean enough = bridge.balance(account) >= units;
        return new EconomyResponse(enough ? decimal(units) : BigDecimal.ZERO, decimal(bridge.balance(account)),
            enough ? ResponseType.SUCCESS : ResponseType.FAILURE, enough ? "" : "Insufficient funds");
    }

    @Override
    public EconomyResponse canWithdraw(String pluginName, UUID id, String world, BigDecimal amount) {
        return canWithdraw(pluginName, id, amount);
    }

    @Override
    public EconomyResponse canWithdraw(String pluginName, UUID id, String world, String currency, BigDecimal amount) {
        return ours(currency) ? canWithdraw(pluginName, id, amount) : unknownCurrency(id);
    }

    @Override
    public EconomyResponse canDeposit(String pluginName, UUID id, BigDecimal amount) {
        Account account = bridge.existing(id);
        long units = bridge.incoming(amount);
        if (units < 0) {
            return failure(account, "Not a valid amount");
        }
        boolean fits = bridge.balance(account) + units <= bridge.bank.max();
        return new EconomyResponse(fits ? decimal(units) : BigDecimal.ZERO, decimal(bridge.balance(account)),
            fits ? ResponseType.SUCCESS : ResponseType.FAILURE, fits ? "" : "That would take the balance over the most it can hold");
    }

    @Override
    public EconomyResponse canDeposit(String pluginName, UUID id, String world, BigDecimal amount) {
        return canDeposit(pluginName, id, amount);
    }

    @Override
    public EconomyResponse canDeposit(String pluginName, UUID id, String world, String currency, BigDecimal amount) {
        return ours(currency) ? canDeposit(pluginName, id, amount) : unknownCurrency(id);
    }

    private EconomyResponse failure(Account account, String why) {
        return new EconomyResponse(BigDecimal.ZERO, decimal(bridge.balance(account)), ResponseType.FAILURE, why);
    }

    private EconomyResponse unknownCurrency(UUID id) {
        return failure(bridge.existing(id), "Unknown currency");
    }

    private EconomyResponse response(Outcome outcome) {
        return new EconomyResponse(decimal(outcome.amount()), decimal(outcome.balance()),
            outcome.success() ? ResponseType.SUCCESS : ResponseType.FAILURE, VaultBridge.reason(outcome));
    }

    // ---- shared accounts: not in 1.0 ----

    @Override
    public boolean createSharedAccount(String pluginName, UUID id, String name, UUID owner) {
        return false;
    }

    @Override
    public boolean isAccountOwner(String pluginName, UUID id, UUID player) {
        return false;
    }

    @Override
    public boolean setOwner(String pluginName, UUID id, UUID player) {
        return false;
    }

    @Override
    public boolean isAccountMember(String pluginName, UUID id, UUID player) {
        return false;
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID id, UUID player) {
        return false;
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID id, UUID player, AccountPermission... permissions) {
        return false;
    }

    @Override
    public boolean removeAccountMember(String pluginName, UUID id, UUID player) {
        return false;
    }

    @Override
    public boolean hasAccountPermission(String pluginName, UUID id, UUID player, AccountPermission permission) {
        return false;
    }

    @Override
    public boolean updateAccountPermission(String pluginName, UUID id, UUID player, AccountPermission permission, boolean value) {
        return false;
    }
}
