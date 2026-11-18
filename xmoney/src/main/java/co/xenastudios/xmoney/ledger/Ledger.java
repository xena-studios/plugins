package co.xenastudios.xmoney.ledger;

import co.xenastudios.xmoney.api.TransactionKind;
import co.xenastudios.xmoney.api.TransactionResult.Status;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Every change to a balance goes through here, from Vault, the API and the commands alike.
 *
 * <p><b>On a single server</b> memory is the truth. A change is checked and applied under
 * the account's own lock (a transfer takes both accounts' locks, in uuid order, so two
 * transfers between the same players can't deadlock), and queued for the {@link Writer}
 * before the lock is let go, so the ledger's order is the order changes happened. Nothing
 * waits for the database: the future is complete when it's returned.
 *
 * <p><b>On a network</b> sharing one database, another server may have spent the money, so
 * anything that takes money (a withdrawal, a transfer, setting a balance) is decided by the
 * database ({@link Op.Checked}: {@code UPDATE ... WHERE balance >= ?}) and its future
 * completes once the writer has done it. Deposits can't overspend, so they're made in memory
 * at once and saved after, as on one server.
 */
public final class Ledger {

    /** How long a change that waits for the database waits to be started, on a network. */
    static final long WAIT_MS = 5_000;

    private final Accounts accounts;
    private final Writer writer;
    private final boolean shared;
    private final LongSupplier maxBalance;
    private volatile Events events = Events.NONE;

    /**
     * @param shared whether the database is shared with other servers
     * @param maxBalance the highest balance allowed, in units (read on each change, so a reload applies)
     */
    public Ledger(Accounts accounts, Writer writer, boolean shared, LongSupplier maxBalance) {
        this.accounts = accounts;
        this.writer = writer;
        this.shared = shared;
        this.maxBalance = maxBalance;
    }

    public void events(Events events) {
        this.events = events;
        writer.events(events);
    }

    public Accounts accounts() {
        return accounts;
    }

    public Writer writer() {
        return writer;
    }

    /** Whether the database is shared with other servers. */
    public boolean shared() {
        return shared;
    }

    // ---- accounts ----

    /**
     * A player's account, made with the starting balance if they have none: a player joining,
     * or staff naming one who has played. Keeps the name up to date. On a network, a new
     * account's starting balance is only given by the first server to make it, so the
     * balance may arrive a moment later.
     */
    public Account open(UUID id, String name, long start) {
        Account existing = accounts.get(id);
        if (existing != null) {
            rename(existing, name);
            return existing;
        }
        return accounts.getOrCreate(id, key -> {
            Account account = new Account(key, name, true, shared ? 0 : Math.max(0, start));
            writer.add(new Op.Name(account));
            Cause cause = new Cause(TransactionKind.START, "xMoney", "starting balance");
            if (shared) {
                writer.add(new Op.Create(account, Math.max(0, start), cause));
            } else if (start > 0) {
                writer.add(new Op.Write(List.of(new Entry(account, start, start, cause, null, System.currentTimeMillis())), false));
            }
            return account;
        });
    }

    /**
     * The account for a uuid a plugin asks about, made empty if there's none: a plugin paying a
     * player who never joined, or an NPC. No starting balance: that's for players joining.
     *
     * @param player false for an account that isn't a player's, which the top list leaves out
     */
    public Account account(UUID id, String name, boolean player) {
        Account existing = accounts.get(id);
        if (existing != null) {
            return existing;
        }
        String shown = name == null || name.isBlank() ? id.toString().substring(0, 8) : name;
        return accounts.getOrCreate(id, key -> {
            Account account = new Account(key, shown, player, 0);
            writer.add(new Op.Name(account));
            return account;
        });
    }

    /**
     * The account a plugin means by a name, through Vault's old name-based calls: the player
     * or account known by that name, or otherwise an account that isn't a player's (a town's
     * bank, say), made empty, with a uuid worked out from the name so it's the same every time.
     */
    public Account named(String name) {
        Account known = accounts.named(name);
        if (known != null) {
            return known;
        }
        UUID id = UUID.nameUUIDFromBytes(("xMoney account:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
        return accounts.getOrCreate(id, key -> {
            Account account = new Account(key, name, false, 0);
            writer.add(new Op.Name(account));
            return account;
        });
    }

    /** Keeps an account's name, as when a player joins after changing theirs. */
    public void rename(Account account, String name) {
        if (name != null && !name.isBlank() && !name.equals(account.name())) {
            synchronized (account) {
                accounts.rename(account, name);
                writer.add(new Op.Name(account));
            }
        }
    }

    // ---- changes ----

    /** Adds to a balance. Amounts in units. */
    public CompletableFuture<Outcome> deposit(Account account, long amount, Cause cause) {
        return deposit(account, amount, cause, null);
    }

    private CompletableFuture<Outcome> deposit(Account account, long amount, Cause cause, UUID other) {
        if (amount <= 0) {
            return done(Outcome.failed(Status.INVALID_AMOUNT, account.balance()));
        }
        if (!events.allow(account.id(), amount, cause, other)) {
            return done(Outcome.failed(Status.CANCELLED, account.balance()));
        }
        synchronized (account) {
            long balance = account.balance;
            if (balance + amount > maxBalance.getAsLong()) {
                return done(Outcome.failed(Status.BALANCE_LIMIT, balance));
            }
            long after = balance + amount;
            accounts.set(account, after);
            if (shared) {
                account.pending += amount;
            }
            writer.add(new Op.Write(List.of(new Entry(account, amount, after, cause, other, System.currentTimeMillis())), shared));
            return done(new Outcome(Status.SUCCESS, amount, after));
        }
    }

    /** Takes from a balance, if it covers the amount. */
    public CompletableFuture<Outcome> withdraw(Account account, long amount, Cause cause) {
        if (amount <= 0) {
            return done(Outcome.failed(Status.INVALID_AMOUNT, account.balance()));
        }
        if (!events.allow(account.id(), -amount, cause, null)) {
            return done(Outcome.failed(Status.CANCELLED, account.balance()));
        }
        if (shared) {
            return checked(List.of(new Op.Change(account, -amount, -1, cause, null)));
        }
        synchronized (account) {
            long balance = account.balance;
            if (balance < amount) {
                return done(Outcome.failed(Status.INSUFFICIENT_FUNDS, balance));
            }
            long after = balance - amount;
            accounts.set(account, after);
            writer.add(new Op.Write(List.of(new Entry(account, -amount, after, cause, null, System.currentTimeMillis())), false));
            return done(new Outcome(Status.SUCCESS, amount, after));
        }
    }

    /** Moves money from one account to another: both change, or neither. The outcome's balance is the payer's. */
    public CompletableFuture<Outcome> transfer(Account from, Account to, long amount, Cause cause) {
        if (from == to || from.id().equals(to.id())) {
            return done(Outcome.failed(Status.SAME_ACCOUNT, from.balance()));
        }
        if (amount <= 0) {
            return done(Outcome.failed(Status.INVALID_AMOUNT, from.balance()));
        }
        if (!events.allow(from.id(), -amount, cause, to.id())) {
            return done(Outcome.failed(Status.CANCELLED, from.balance()));
        }
        if (shared) {
            if (to.balance() + amount > maxBalance.getAsLong()) {
                return done(Outcome.failed(Status.BALANCE_LIMIT, from.balance()));
            }
            return checked(List.of(new Op.Change(from, -amount, -1, cause, to.id()), new Op.Change(to, amount, -1, cause, from.id())));
        }
        // Always the lower uuid first, so two opposite transfers can't hold each other's lock.
        Account first = from.id().compareTo(to.id()) < 0 ? from : to;
        Account second = first == from ? to : from;
        synchronized (first) {
            synchronized (second) {
                long payer = from.balance;
                if (payer < amount) {
                    return done(Outcome.failed(Status.INSUFFICIENT_FUNDS, payer));
                }
                if (to.balance + amount > maxBalance.getAsLong()) {
                    return done(Outcome.failed(Status.BALANCE_LIMIT, payer));
                }
                long now = System.currentTimeMillis();
                long payerAfter = payer - amount;
                long payeeAfter = to.balance + amount;
                accounts.set(from, payerAfter);
                accounts.set(to, payeeAfter);
                writer.add(new Op.Write(List.of(
                    new Entry(from, -amount, payerAfter, cause, to.id(), now),
                    new Entry(to, amount, payeeAfter, cause, from.id(), now)), false));
                return done(new Outcome(Status.SUCCESS, amount, payerAfter));
            }
        }
    }

    /** Sets a balance, whatever it was. The outcome's amount is how much it changed by, either way. */
    public CompletableFuture<Outcome> set(Account account, long balance, Cause cause) {
        if (balance < 0 || balance > maxBalance.getAsLong()) {
            return done(Outcome.failed(balance < 0 ? Status.INVALID_AMOUNT : Status.BALANCE_LIMIT, account.balance()));
        }
        if (cause.kind() != TransactionKind.IMPORT && !events.allow(account.id(), balance - account.balance(), cause, null)) {
            return done(Outcome.failed(Status.CANCELLED, account.balance()));
        }
        if (shared) {
            return checked(List.of(new Op.Change(account, 0, balance, cause, null)));
        }
        synchronized (account) {
            long before = account.balance;
            long delta = balance - before;
            if (delta != 0) {
                accounts.set(account, balance);
                writer.add(new Op.Write(List.of(new Entry(account, delta, balance, cause, null, System.currentTimeMillis())), false));
            }
            return done(new Outcome(Status.SUCCESS, Math.abs(delta), balance));
        }
    }

    private CompletableFuture<Outcome> checked(List<Op.Change> changes) {
        if (writer.isWriter()) {
            // Called from a handler on the writer's own thread, which would wait for itself.
            return done(Outcome.failed(Status.UNAVAILABLE, changes.getFirst().account().balance()));
        }
        CompletableFuture<Outcome> future = new CompletableFuture<>();
        writer.add(new Op.Checked(changes, future, System.currentTimeMillis() + WAIT_MS));
        return future;
    }

    /** Re-reads an account another server changed. Network mode only. */
    public void refresh(UUID id) {
        writer.add(new Op.Refresh(id));
    }

    /** Deletes ledger rows older than {@code before} (epoch ms), a slice at a time between other writes. */
    public void prune(long before) {
        writer.add(new Op.Prune(before));
    }

    /** A future completed once everything queued so far is saved. */
    public CompletableFuture<Void> flush() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        writer.add(new Op.Flush(done));
        return done;
    }

    private static CompletableFuture<Outcome> done(Outcome outcome) {
        return CompletableFuture.completedFuture(outcome);
    }
}
