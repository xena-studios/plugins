package co.xenastudios.xmoney.hook;

import co.xenastudios.core.text.ChatPages;
import co.xenastudios.xmoney.Bank;
import co.xenastudios.xmoney.XMoney;
import co.xenastudios.xmoney.api.Currency;
import co.xenastudios.xmoney.api.HistoryEntry;
import co.xenastudios.xmoney.api.HistoryPage;
import co.xenastudios.xmoney.api.TopEntry;
import co.xenastudios.xmoney.api.TransactionKind;
import co.xenastudios.xmoney.api.TransactionResult;
import co.xenastudios.xmoney.api.TransactionResult.Status;
import co.xenastudios.xmoney.api.XMoneyApi;
import co.xenastudios.xmoney.config.XMConfig;
import co.xenastudios.xmoney.ledger.Account;
import co.xenastudios.xmoney.ledger.Accounts;
import co.xenastudios.xmoney.ledger.Cause;
import co.xenastudios.xmoney.ledger.Outcome;
import co.xenastudios.xmoney.ledger.Store;
import co.xenastudios.xmoney.money.Format;
import co.xenastudios.xmoney.money.Money;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.bukkit.plugin.Plugin;

/** {@link XMoneyApi}, over the same ledger as Vault and the commands. */
public final class MoneyService implements XMoneyApi {

    /** Rows on a page of {@link #history}. */
    public static final int HISTORY_PAGE = 10;

    private final XMoney plugin;

    public MoneyService(XMoney plugin) {
        this.plugin = plugin;
    }

    private Bank bank() {
        return plugin.bank();
    }

    @Override
    public BigDecimal balance(UUID player) {
        Account account = bank().accounts().get(player);
        return bank().exact(account == null ? 0 : account.balance());
    }

    @Override
    public boolean hasAccount(UUID player) {
        return bank().accounts().get(player) != null;
    }

    @Override
    public boolean has(UUID player, BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        // Rounded up, as a withdrawal of it would be.
        long units = Money.outgoing(amount.max(BigDecimal.ZERO));
        Account account = bank().accounts().get(player);
        return units >= 0 && (account == null ? 0 : account.balance()) >= units;
    }

    private Cause cause(Plugin source, String reason) {
        return new Cause(TransactionKind.PLUGIN, Objects.requireNonNull(source, "source").getName(), reason);
    }

    private CompletableFuture<TransactionResult> invalid(Account account) {
        return CompletableFuture.completedFuture(new TransactionResult(Status.INVALID_AMOUNT, BigDecimal.ZERO,
            bank().exact(account == null ? 0 : account.balance())));
    }

    private CompletableFuture<TransactionResult> result(CompletableFuture<Outcome> outcome) {
        return outcome.thenApply(bank()::result);
    }

    @Override
    public CompletableFuture<TransactionResult> deposit(Plugin source, UUID player, BigDecimal amount, String reason) {
        Cause cause = cause(source, reason);
        long units = Money.incoming(amount);
        if (units <= 0) {
            return invalid(bank().accounts().get(player));
        }
        return result(bank().ledger().deposit(payee(player), units, cause));
    }

    @Override
    public CompletableFuture<TransactionResult> withdraw(Plugin source, UUID player, BigDecimal amount, String reason) {
        Cause cause = cause(source, reason);
        Account account = bank().accounts().get(player);
        long units = Money.outgoing(amount);
        if (units <= 0) {
            return invalid(account);
        }
        if (account == null) {
            return CompletableFuture.completedFuture(new TransactionResult(Status.INSUFFICIENT_FUNDS, BigDecimal.ZERO, bank().exact(0)));
        }
        return result(bank().ledger().withdraw(account, units, cause));
    }

    @Override
    public CompletableFuture<TransactionResult> transfer(Plugin source, UUID from, UUID to, BigDecimal amount, String reason) {
        Cause cause = cause(source, reason);
        Account payer = bank().accounts().get(from);
        long units = Money.outgoing(amount);
        if (units <= 0) {
            return invalid(payer);
        }
        if (from.equals(to)) {
            return CompletableFuture.completedFuture(new TransactionResult(Status.SAME_ACCOUNT, BigDecimal.ZERO,
                bank().exact(payer == null ? 0 : payer.balance())));
        }
        if (payer == null) {
            return CompletableFuture.completedFuture(new TransactionResult(Status.INSUFFICIENT_FUNDS, BigDecimal.ZERO, bank().exact(0)));
        }
        return result(bank().ledger().transfer(payer, payee(to), units, cause));
    }

    private Account payee(UUID id) {
        Account account = bank().accounts().get(id);
        // Citizens' NPCs have version 2 uuids, and stay out of the top list, as through Vault.
        return account != null ? account : bank().ledger().account(id, plugin.players().cachedName(id), id.version() != 2);
    }

    @Override
    public List<TopEntry> top(int from, int count) {
        List<TopEntry> entries = new ArrayList<>();
        for (Accounts.Ranked ranked : bank().accounts().top(Math.max(1, from), Math.clamp(count, 0, 100))) {
            entries.add(new TopEntry(ranked.rank(), ranked.account().id(), ranked.account().name(), bank().exact(ranked.balance())));
        }
        return entries;
    }

    @Override
    public CompletableFuture<HistoryPage> history(UUID player, int page) {
        Store store = plugin.store();
        int decimals = bank().decimals();
        // Once what's been changed so far is saved, so the page has it.
        return plugin.ledger().saved().thenApplyAsync(ignored -> {
            try {
                long total = store.count(player);
                int pages = ChatPages.pages(total, HISTORY_PAGE);
                int number = Math.clamp(page, 1, pages);
                List<HistoryEntry> entries = new ArrayList<>();
                for (Store.Row row : store.history(player, ChatPages.offset(number, HISTORY_PAGE), HISTORY_PAGE)) {
                    entries.add(new HistoryEntry(row.id(), Instant.ofEpochMilli(row.time()), Money.exact(row.amount(), decimals),
                        Money.exact(row.balance(), decimals), row.kind(), row.source(), row.reason(), row.other()));
                }
                return new HistoryPage(entries, number, pages, total);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, plugin.tasks()::async);
    }

    @Override
    public String format(BigDecimal amount) {
        long units = Money.units(Objects.requireNonNull(amount, "amount").abs(), bank().decimals());
        if (units < 0) {
            units = Money.MAX_UNITS;
        }
        return bank().format(amount.signum() < 0 ? -units : units);
    }

    @Override
    public Currency currency() {
        XMConfig.CurrencySettings settings = bank().format().settings();
        return new Currency(Format.currencyId(), settings.singular(), settings.plural(), settings.symbol(), settings.decimals());
    }
}
