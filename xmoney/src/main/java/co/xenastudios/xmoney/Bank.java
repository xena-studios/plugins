package co.xenastudios.xmoney;

import co.xenastudios.xmoney.api.TransactionResult;
import co.xenastudios.xmoney.config.XMConfig;
import co.xenastudios.xmoney.ledger.Account;
import co.xenastudios.xmoney.ledger.Accounts;
import co.xenastudios.xmoney.ledger.Ledger;
import co.xenastudios.xmoney.ledger.Outcome;
import co.xenastudios.xmoney.money.Format;
import co.xenastudios.xmoney.money.Money;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * What Vault, the API and the commands share: the ledger, the currency's format from the
 * live config, and the limits in units. Any thread.
 */
public final class Bank {

    /** The longest a Vault call waits for the shared database, on a network. */
    static final long VAULT_WAIT_SECONDS = 15;

    private final Ledger ledger;
    private final Supplier<XMConfig> config;
    private final Logger logger;
    private volatile Cached cached;

    private record Cached(XMConfig config, Format format, long max, long start) {}

    public Bank(Ledger ledger, Supplier<XMConfig> config, Logger logger) {
        this.ledger = ledger;
        this.config = config;
        this.logger = logger;
    }

    private Cached cached() {
        XMConfig current = config.get();
        Cached known = cached;
        if (known == null || known.config() != current) {
            long max = Money.units(BigDecimal.valueOf(current.maxBalance()), 0);
            long start = Money.units(BigDecimal.valueOf(current.startingBalance()), current.currency().decimals());
            known = new Cached(current, new Format(current.currency()), max < 0 ? Money.MAX_UNITS : max, Math.max(0, start));
            cached = known;
        }
        return known;
    }

    public Ledger ledger() {
        return ledger;
    }

    public Accounts accounts() {
        return ledger.accounts();
    }

    public Format format() {
        return cached().format();
    }

    public int decimals() {
        return cached().format().decimals();
    }

    /** The highest balance allowed, in units. */
    public long max() {
        return cached().max();
    }

    /** A new player's balance, in units. */
    public long start() {
        return cached().start();
    }

    public String format(long units) {
        return format().format(units);
    }

    public BigDecimal decimal(long units) {
        return Money.decimal(units, decimals());
    }

    /** The balance of the account, or 0 for none. */
    public long balance(Account account) {
        return account == null ? 0 : account.balance();
    }

    /**
     * The outcome now, waiting for it only on a network, and then at most
     * {@link #VAULT_WAIT_SECONDS}: Vault's callers need an answer on the spot.
     */
    public Outcome await(CompletableFuture<Outcome> future, Account account) {
        if (future.isDone()) {
            return future.join();
        }
        try {
            return future.get(VAULT_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warning("A payment waited " + VAULT_WAIT_SECONDS + "s for the shared database and was reported as failed."
                + " If the database answers later it may still go through; /eco history shows what happened.");
            return new Outcome(TransactionResult.Status.UNAVAILABLE, 0, balance(account));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(TransactionResult.Status.UNAVAILABLE, 0, balance(account));
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /** The API's result for an outcome. */
    public TransactionResult result(Outcome outcome) {
        return new TransactionResult(outcome.status(), decimal(outcome.amount()), decimal(outcome.balance()));
    }
}
