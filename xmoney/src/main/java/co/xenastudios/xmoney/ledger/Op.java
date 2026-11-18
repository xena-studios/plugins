package co.xenastudios.xmoney.ledger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Work for the {@link Writer}, done in the order it was queued. */
sealed interface Op {

    /** Whether it can share a database transaction with the ops around it. */
    default boolean batches() {
        return false;
    }

    /**
     * Changes already made in memory, to save. On a network, {@code optimistic} ones (deposits)
     * were also added to their accounts' {@link Account#pending}, which the writer takes back
     * once they're saved.
     */
    record Write(List<Entry> entries, boolean optimistic) implements Op {
        @Override
        public boolean batches() {
            return true;
        }
    }

    /** An account's name and kind, as a row in {@code accounts}. */
    record Name(Account account) implements Op {
        @Override
        public boolean batches() {
            return true;
        }
    }

    /**
     * On a network: changes the database decides, since another server may have spent the
     * money. Withdrawals succeed only if the shared balance still covers them. The writer
     * starts it only if its caller hasn't given up and its deadline hasn't passed (see
     * {@link Pending}); otherwise nothing changes.
     */
    record Checked(List<Change> changes, Pending done) implements Op {}

    /** One account's part of a {@link Checked}: a change by {@code delta}, or to {@code setTo} when it's 0 or more. */
    record Change(Account account, long delta, long setTo, Cause cause, UUID other) {}

    /** On a network: a new player's account, with the starting balance only if no server made it first. */
    record Create(Account account, long start, Cause cause) implements Op {}

    /** On a network: reads an account again, after another server changed it. */
    record Refresh(UUID id) implements Op {}

    /**
     * On a network: reads every account and balance again, when this server may have missed
     * changes (the change feed's resync), or after another server's import.
     */
    record Resync() implements Op {}

    /**
     * On a network: an import's balances, set {@link Writer#IMPORT_CHUNK} to a transaction.
     * After each chunk it goes to the back of the queue, so payments go between chunks. The
     * other servers are told once, at the end, to read everything again. {@code next} is
     * the writer's place in {@code targets}; {@code done} completes with how many were set.
     */
    record Import(List<Ledger.Target> targets, Cause cause, int[] next, CompletableFuture<Integer> done) implements Op {}

    /** Deletes ledger rows older than {@code before} (epoch ms), a slice at a time. */
    record Prune(long before) implements Op {}

    /** Completes once everything queued before it is saved. */
    record Flush(CompletableFuture<Void> done) implements Op {}
}
