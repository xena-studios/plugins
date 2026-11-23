package co.xenastudios.xmoney;

import co.xenastudios.xmoney.api.TransactionResult;
import co.xenastudios.xmoney.config.XMConfig;
import co.xenastudios.xmoney.ledger.Account;
import co.xenastudios.xmoney.ledger.Accounts;
import co.xenastudios.xmoney.ledger.Ledger;
import co.xenastudios.xmoney.ledger.Outcome;
import co.xenastudios.xmoney.ledger.Pending;
import co.xenastudios.xmoney.money.Format;
import co.xenastudios.xmoney.money.Money;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * What Vault, the API and the commands share: the ledger, the currency's format from the
 * live config, and the limits in units. Any thread.
 */
public final class Bank {

    /** The most {@code network-wait-ms} may be: a server thread never waits longer to be refused. */
    public static final int MAX_SERVER_THREAD_WAIT_MS = 250;
    private static final long WARN_EVERY_MS = 60_000;

    private final Ledger ledger;
    private final Supplier<XMConfig> config;
    private final Logger logger;
    private final BooleanSupplier serverThread;
    private final AtomicLong lastWarned = new AtomicLong();
    private final AtomicLong refusedSince = new AtomicLong();
    private volatile Cached cached;

    private record Cached(XMConfig config, Format format, long max, long start) {}

    /** @param serverThread whether the caller is on one of the server's game threads (Bukkit's primary thread, or a Folia region's) */
    public Bank(Ledger ledger, Supplier<XMConfig> config, Logger logger, BooleanSupplier serverThread) {
        this.ledger = ledger;
        this.config = config;
        this.logger = logger;
        this.serverThread = serverThread;
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

    /** The units with the currency's decimals, rounded down: for showing. */
    public BigDecimal decimal(long units) {
        return Money.decimal(units, decimals());
    }

    /** The units with every place they have, and at least the currency's decimals: for other plugins. */
    public BigDecimal exact(long units) {
        return Money.exact(units, decimals());
    }

    /** The balance of the account, or 0 for none. */
    public long balance(Account account) {
        return account == null ? 0 : account.balance();
    }

    /**
     * The outcome now, for Vault's callers, which need an answer on the spot. Only a change
     * the shared database decides (on a network) isn't done already. Not yet started by the
     * writer within {@code network-wait-ms} on a server thread, or {@link Ledger#WAIT_MS}
     * elsewhere, it's abandoned and refused, and the writer never runs it, so nothing was
     * taken. Once started, its real outcome is waited for: up to about 4 seconds before its
     * commit (past {@code Writer#CHECKED_BUDGET_MS} it's refused, honestly, before the commit),
     * and up to 3 more for the commit. Only if the database goes away at the moment of the
     * commit does the caller wait until it answers again: whether it went through is never
     * guessed. That's the case per-account leases would remove (designed in #66, not planned), and
     * why network mode is experimental.
     */
    public Outcome await(CompletableFuture<Outcome> future, Account account) {
        if (future.isDone()) {
            return join(future);
        }
        long wait = serverThread.getAsBoolean()
            ? Math.clamp(cached().config().networkWaitMs(), 1, MAX_SERVER_THREAD_WAIT_MS)
            : Ledger.WAIT_MS;
        Outcome refused = new Outcome(TransactionResult.Status.UNAVAILABLE, 0, balance(account));
        try {
            return future.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (future instanceof Pending pending && pending.abandon(refused)) {
                warn(wait);
                return refused;
            }
            // Started: the database is deciding it. Never guessed at, so waited for.
            return join(future);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private static Outcome join(CompletableFuture<Outcome> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    // Once a minute at most, however many are refused: a shop clicked during an outage is many.
    private void warn(long waited) {
        long refused = refusedSince.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastWarned.get();
        if (now - last >= WARN_EVERY_MS && lastWarned.compareAndSet(last, now)) {
            refusedSince.set(0);
            logger.warning(refused + (refused == 1 ? " payment was" : " payments were") + " refused after waiting " + waited
                + "ms for the shared database. Nothing was taken from them. " + (ledger.writer().problem() == null
                ? "It's slow to answer." : "The problem: " + ledger.writer().problem()));
        }
    }

    /** The API's result for an outcome. */
    public TransactionResult result(Outcome outcome) {
        return new TransactionResult(outcome.status(), exact(outcome.amount()), exact(outcome.balance()));
    }
}
