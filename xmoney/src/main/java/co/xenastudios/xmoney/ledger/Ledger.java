package co.xenastudios.xmoney.ledger;

import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.xmoney.api.TransactionKind;
import co.xenastudios.xmoney.api.TransactionResult.Status;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
 *
 * <p>On a network, a change the database decides is refused at once while the writer is
 * failing (a write failed and none has worked since), and given up if it isn't started
 * within {@link #WAIT_MS}, whether or not its caller is waiting for it: the future is
 * completed then. Either way nothing was taken. Callers on a game thread give up sooner
 * (see {@code Bank#await}).
 */
public final class Ledger {

    /** How long a change that waits for the database waits to be started, on a network. */
    public static final long WAIT_MS = 5_000;

    private final Accounts accounts;
    private final Writer writer;
    private final boolean shared;
    private final LongSupplier maxBalance;
    private volatile Events events = Events.NONE;
    /** {@link #WAIT_MS}, shorter in tests. */
    volatile long waitMs = WAIT_MS;

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
            claim(existing, name);
            return existing;
        }
        Account account = accounts.getOrCreate(id, key -> {
            Account made = new Account(key, name, true, shared ? 0 : Math.max(0, start));
            writer.add(new Op.Name(made));
            Cause cause = new Cause(TransactionKind.START, "xMoney", "starting balance");
            if (shared) {
                writer.add(new Op.Create(made, Math.max(0, start), cause));
            } else if (start > 0) {
                writer.add(new Op.Write(List.of(new Entry(made, start, start, cause, null, System.currentTimeMillis())), false));
            }
            return made;
        });
        // The name may have been another account's: a player joining under it takes it.
        claim(account, name);
        return account;
    }

    /**
     * The account for a uuid a plugin asks about, made empty if there's none: a plugin paying a
     * player who never joined, an NPC, or an import. No starting balance: that's for players
     * joining. A name another account is known by isn't given to it (it gets none until the
     * player joins), so names keep finding the right account after a restart.
     *
     * @param player false for an account that isn't a player's, which the top list leaves out
     */
    public Account account(UUID id, String name, boolean player) {
        Account existing = accounts.get(id);
        if (existing != null) {
            return existing;
        }
        String given = name == null || name.isBlank() || accounts.named(name) != null || accounts.ambiguous(name) ? "" : name;
        return accounts.getOrCreate(id, key -> {
            Account account = new Account(key, given, player, 0);
            writer.add(new Op.Name(account));
            return account;
        });
    }

    /**
     * The account a plugin means by a name, through Vault's old name-based calls: the player
     * or account known by that name, or otherwise an account that isn't a player's (a town's
     * bank, say), made empty, with a uuid worked out from the name so it's the same every time.
     *
     * @return null if more than one account has had the name, so it can't say which is meant
     */
    public Account named(String name) {
        Account known = accounts.named(name);
        if (known != null) {
            return known;
        }
        if (accounts.ambiguous(name)) {
            return null; // More than one player has had it: refused rather than guessed.
        }
        UUID id = UUID.nameUUIDFromBytes(("xMoney account:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
        return accounts.getOrCreate(id, key -> {
            Account account = new Account(key, name, false, 0);
            writer.add(new Op.Name(account));
            return account;
        });
    }

    /**
     * A player's own name, as they join or as Mojang says it is now: it finds their account from
     * now on, even if another account had it.
     */
    public void claim(Account account, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        synchronized (account) {
            boolean changed = !name.equals(account.storedName());
            List<Account> released = accounts.claim(account, name);
            if (changed) {
                writer.add(new Op.Name(account));
            }
            // Saved without their name, so it finds this player after a restart too.
            for (Account other : released) {
                writer.add(new Op.Name(other));
            }
        }
    }

    /**
     * A new name for an account, as a plugin asks, unless another account is known by it.
     *
     * @return whether the account now has the name
     */
    public boolean rename(Account account, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        synchronized (account) {
            if (name.equals(account.storedName()) && account == accounts.named(name)) {
                return true;
            }
            if (!accounts.rename(account, name)) {
                return false;
            }
            writer.add(new Op.Name(account));
            return true;
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
        if (writer.full()) {
            return done(Outcome.failed(Status.UNAVAILABLE, account.balance()));
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
        if (writer.full()) {
            return done(Outcome.failed(Status.UNAVAILABLE, account.balance()));
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
        if (writer.full()) {
            return done(Outcome.failed(Status.UNAVAILABLE, from.balance()));
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
        if (writer.full()) {
            return done(Outcome.failed(Status.UNAVAILABLE, account.balance()));
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

    // Called from a handler on the writer's own thread, it would wait for itself; while the
    // writer is failing, it would only wait to be refused. Both are refused now.
    private CompletableFuture<Outcome> checked(List<Op.Change> changes) {
        if (writer.isWriter() || writer.failing()) {
            return done(Outcome.failed(Status.UNAVAILABLE, changes.getFirst().account().balance()));
        }
        long wait = waitMs;
        Pending future = new Pending(System.currentTimeMillis() + wait);
        writer.add(new Op.Checked(changes, future));
        // A caller that never waits with a timeout of its own (the API, the commands) still hears
        // within the wait: if the writer hasn't started it by then, it never will.
        CompletableFuture.delayedExecutor(wait, TimeUnit.MILLISECONDS)
            .execute(() -> future.abandon(Outcome.failed(Status.UNAVAILABLE, changes.getFirst().account().balance())));
        return future;
    }

    /**
     * Sets many balances at once, for an import: each to its target, as {@code cause}. On a
     * network the database sets them, {@link Writer#IMPORT_CHUNK} to a transaction with
     * payments between, and the other servers read everything again once it's done; nothing
     * is dropped while the database is away, it waits. On one server it's in memory at once.
     *
     * @return how many were set
     */
    public CompletableFuture<Integer> setAll(List<Target> targets, Cause cause) {
        if (!shared) {
            int set = 0;
            for (Target target : targets) {
                if (set(target.account(), target.balance(), cause).join().success()) {
                    set++;
                }
            }
            return CompletableFuture.completedFuture(set);
        }
        CompletableFuture<Integer> done = new CompletableFuture<>();
        if (targets.isEmpty()) {
            done.complete(0);
        } else {
            // In uuid order, as every other write changes rows, so it can't deadlock another server's.
            List<Target> ordered = new java.util.ArrayList<>(targets);
            ordered.sort(java.util.Comparator.comparing(target -> target.account().id()));
            writer.add(new Op.Import(List.copyOf(ordered), cause, new int[1], done));
        }
        return done;
    }

    /** One balance {@link #setAll} sets, in units. */
    public record Target(Account account, long balance) {}

    /**
     * Keeps this server's memory in step with the others': accounts another server changed
     * are read again, and everything is read again when the feed says changes may have been
     * missed, or after another server's import. Before the feed starts.
     */
    public void listen(ChangeFeed feed) {
        feed.subscribe("account", change -> {
            try {
                refresh(UUID.fromString(change.payload()));
            } catch (IllegalArgumentException ignored) {
                // Not an account; nothing of ours.
            }
        });
        feed.subscribe(Writer.RESYNC_TOPIC, change -> resync());
        feed.onResync(this::resync);
    }

    /** Re-reads an account another server changed. Network mode only. */
    public void refresh(UUID id) {
        writer.add(new Op.Refresh(id));
    }

    /** Re-reads every account and balance. Network mode only. */
    public void resync() {
        writer.add(new Op.Resync());
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

    /**
     * For reading the database: completes once everything queued so far is saved, so the read
     * sees changes made just before it, or after {@link #WAIT_MS} if the database is away
     * (the read then sees what's saved). Never completes exceptionally.
     */
    public CompletableFuture<Void> saved() {
        return flush().exceptionally(stopped -> null).completeOnTimeout(null, WAIT_MS, TimeUnit.MILLISECONDS);
    }

    private static CompletableFuture<Outcome> done(Outcome outcome) {
        return CompletableFuture.completedFuture(outcome);
    }
}
