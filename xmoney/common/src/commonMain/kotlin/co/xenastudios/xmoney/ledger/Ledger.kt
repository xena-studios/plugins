package co.xenastudios.xmoney.ledger

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome as DbOutcome
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.Statement
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.later
import co.xenastudios.xmoney.api.TransactionKind
import co.xenastudios.xmoney.api.TransactionResult.Status
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the ledger asks around a change: [refuse] before it (the pre-event and add-ons' hooks,
 * on the caller's thread, before any lock), [saved] once it's in the database (for the
 * post-events, which mustn't hold up the writer).
 */
public interface LedgerHooks {
    /** `null` to let it through; otherwise why not ("" when there's nothing to say). */
    public fun refuse(account: PlayerRef, change: Long, cause: Cause, other: PlayerRef?): String?

    /** These changes are saved, in this order. On the database's thread: hand them on, never wait. */
    public fun saved(entries: List<SavedChange>)

    public companion object {
        public val NONE: LedgerHooks = object : LedgerHooks {
            override fun refuse(account: PlayerRef, change: Long, cause: Cause, other: PlayerRef?): String? = null
            override fun saved(entries: List<SavedChange>) = Unit
        }
    }
}

/** One saved change, for the post-event: units. */
public class SavedChange(
    public val account: PlayerRef,
    public val change: Long,
    public val balance: Long,
    public val cause: Cause,
    public val other: PlayerRef?,
)

/**
 * Every change to a balance goes through here, from Vault, the Common Economy API, xMoney's API
 * and the commands alike.
 *
 * **On a single server memory is the truth.** A change is checked and applied under the
 * account's own lock (a transfer takes both, in uuid order, so two opposite transfers can't
 * deadlock) and queued for the database's one writer before the lock is let go, so the ledger's
 * order is the order changes happened. Each balance is saved as a `+ delta` with its ledger row
 * in the same write, so they always agree. Nothing waits for the database: the result is
 * complete when it's returned.
 *
 * **On a network** sharing one database, another server may have spent the money, so what takes
 * money (a withdrawal, a transfer, setting a balance) is decided by the database, in a
 * transaction of its own (`UPDATE ... WHERE balance >= ?`), and its result completes once that's
 * committed. Each is queued, then either started by the writer or abandoned by its caller, by a
 * compare-and-set, never both: an abandoned one never runs, so refusing it is true. While the
 * database is away it's refused at once; once started it has a second to reach its commit, or
 * it's rolled back and refused. A commit whose answer is lost is looked up by the core's writer,
 * never run twice. Deposits can't overspend, so they're applied in memory at once and kept as
 * pending until saved (in batches); every saved change sets memory to the database's balance plus
 * this server's pending changes, and tells the other servers on the core's change feed.
 */
public class Ledger(
    public val accounts: Accounts,
    internal val db: Database,
    /** The change feed, on a network; `null` on a single server, which never loads it. */
    internal val feed: Feed?,
    /** Whether the database is shared with other servers (network mode, on MySQL). */
    public val shared: Boolean,
    internal val platform: Platform,
    /** The highest balance allowed, in units: read on each change, so a reload applies. */
    internal val maxBalance: () -> Long,
) {
    @Volatile
    public var hooks: LedgerHooks? = null

    // Made with the first change (LedgerWork): reading the balances in at startup doesn't need them.
    @Volatile
    private var madeStatements: Statements? = null

    internal val statements: Statements get() = madeStatements ?: Statements(db.sql, CURRENCY).also { madeStatements = it }
    internal val log = platform.log
    internal val clock = platform.clock
    internal val counter = Any()
    internal var inFlight = 0L

    /** The last thing that went wrong saving, for `/xmoney status`; `null` while all is well. */
    @Volatile
    public var problem: String? = null
        internal set

    /** How long a change that waits for the database waits to be started, on a network. Shorter in tests. */
    public var waitMillis: Long = WAIT_MS

    /** How long a started change has to reach its commit, on a network. Shorter in tests. */
    public var budgetMillis: Long = CHECKED_BUDGET_MS

    /** Changes waiting for the database. */
    public val waiting: Long get() = locked(counter) { inFlight }

    /** Whether the database's writer can reach it now. */
    public val available: Boolean get() = db.available

    /** Where the data is, for the console. */
    public val description: String get() = db.description

    internal fun now(): Long = clock.now().toEpochMilliseconds()

    internal fun full(): Boolean = locked(counter) { inFlight >= MAX_WAITING }

    // Everything that changes or reads the database after startup is in [LedgerWork], made the
    // first time it's needed: a server that's just started has loaded only what reads the balances
    // in (decisions/2026-11-25-a-lighter-core.md).
    @Volatile
    private var made: LedgerWork? = null

    private fun work(): LedgerWork = made ?: locked(counter) { made ?: LedgerWork(this).also { made = it } }

    // ---- accounts ----

    /**
     * A player's account, made with the starting balance if they have none: a player joining, or
     * staff naming one who has played. Keeps the name up to date. On a network a new account's
     * starting balance is only given by the first server to make it, so it may arrive a moment later.
     */
    public fun open(id: PlayerRef, name: String, start: Long): Account = work().open(id, name, start)

    /**
     * The account for a uuid a plugin asks about, made empty if there's none: a plugin paying a
     * player who never joined, an NPC, or an import. No starting balance: that's for players
     * joining. A name another account is known by isn't given to it, so names keep finding the
     * right account after a restart.
     *
     * @param player false for an account that isn't a player's, which the top list leaves out
     */
    public fun account(id: PlayerRef, name: String?, player: Boolean): Account = accounts[id] ?: work().account(id, name, player)

    /**
     * The account a plugin means by a name, through Vault's old name-based calls: the player or
     * account known by it, or otherwise one that isn't a player's (a town's bank), made empty,
     * with a uuid worked out from the name so it's the same every time (and the same as 1.x's).
     *
     * @return `null` if more than one account has had the name, so it can't say which is meant
     */
    public fun named(name: String): Account? = accounts.named(name) ?: work().named(name)

    /** A player's own name, as they join: it finds their account from now on, even if another account had it. */
    public fun claim(account: Account, name: String): Unit = work().claim(account, name)

    /** A new name for an account, as a plugin asks, unless another account is known by it. */
    public fun rename(account: Account, name: String): Boolean = work().rename(account, name)

    // ---- changes ----

    /** Adds [amount] units to a balance. */
    public fun deposit(account: Account, amount: Long, cause: Cause, other: PlayerRef? = null): Pending<Outcome> = work().deposit(account, amount, cause, other)

    /** Takes [amount] units from a balance, if it covers them. */
    public fun withdraw(account: Account, amount: Long, cause: Cause): Pending<Outcome> = work().withdraw(account, amount, cause)

    /** Moves [amount] units from one account to another: both change, or neither. The outcome's balance is the payer's. */
    public fun transfer(from: Account, to: Account, amount: Long, cause: Cause): Pending<Outcome> = work().transfer(from, to, amount, cause)

    /** Sets a balance, whatever it was. The outcome's amount is how much it changed by, either way. */
    public fun set(account: Account, balance: Long, cause: Cause): Pending<Outcome> = work().set(account, balance, cause)

    /**
     * Gives up on the change behind [result], for a caller that waits with a timeout of its own (a
     * Vault call on a server thread): it's refused as [refused] and never runs, unless the writer
     * has started it, when its real outcome is coming. `true` if it was given up.
     */
    public fun abandon(result: Pending<Outcome>, refused: Outcome): Boolean = made?.abandon(result, refused) ?: false

    /**
     * Sets many balances at once, for an import: each to its target, as [cause]. On a network the
     * database sets them, [IMPORT_CHUNK] to a transaction (or [IMPORT_BUDGET_MS] of work) with
     * payments between, waiting out a database that's away, and the other servers read every
     * balance again once it's done. On one server it's in memory at once.
     *
     * @return how many were set
     */
    public fun setAll(targets: List<Pair<Account, Long>>, cause: Cause): Pending<Int> = work().setAll(targets, cause)

    /**
     * Keeps this server's memory in step with the others': accounts another server changed are
     * read again, and everything is read again when the feed says changes may have been missed,
     * or after another server's import. Before the feed starts.
     */
    public fun listen(): Unit = work().listen()

    /** Reads an account another server changed again, after every write queued before it. */
    public fun refresh(id: PlayerRef): Unit = work().refresh(id)

    /** Reads every account and balance again: network mode, when this server may have missed changes. */
    public fun resync(): Unit = work().resync()

    /** One account as stored. */
    public class Stored(public val id: PlayerRef, public val name: String, public val player: Boolean, public val balance: Long)

    /** Every account and its balance, on [session]: at startup, and on a resync. */
    public fun readAll(session: SqlSession): DbOutcome<List<Stored>, DbError> {
        val balances = HashMap<PlayerRef, Long>()
        when (val rows = session.query(db.sql.raw("SELECT uuid, balance FROM {p}balances WHERE currency = ?", SqlValue.Text(CURRENCY)))) {
            is DbOutcome.Err -> return rows
            is DbOutcome.Ok -> for (row in rows.value) PlayerRef.parseOrNull(text(row, 0))?.let { balances[it] = row.long(1) ?: 0 }
        }
        val out = ArrayList<Stored>(balances.size)
        when (val rows = session.query(db.sql.raw("SELECT uuid, name, player FROM {p}accounts"))) {
            is DbOutcome.Err -> return rows
            is DbOutcome.Ok -> for (row in rows.value) {
                val id = PlayerRef.parseOrNull(text(row, 0)) ?: continue
                out += Stored(id, text(row, 1), (row.long(2) ?: 1) != 0L, balances.remove(id) ?: 0)
            }
        }
        // A balance without an account row, which xMoney never leaves; kept rather than lost.
        for ((id, balance) in balances) out += Stored(id, id.toString().substring(0, 8), true, balance)
        return DbOutcome.Ok(out)
    }

    /** Loads [stored] into memory: at startup, before anyone can ask. */
    public fun load(stored: List<Stored>) {
        for (s in stored) accounts.load(s.id, s.name, s.player, s.balance)
    }

    // ---- reading ----

    /** One row of a player's history. Units. */
    public class HistoryRow(
        public val id: Long, public val time: Long, public val amount: Long, public val balance: Long,
        public val kind: TransactionKind, public val source: String, public val reason: String, public val other: PlayerRef?,
    )

    /** How many ledger rows [id] has, and a page of them, newest first: after everything queued so far is saved. */
    public fun history(id: PlayerRef, offset: Long, limit: Int): Pending<DbOutcome<Pair<Long, List<HistoryRow>>, DbError>> = work().history(id, offset, limit)

    /** Accounts whose balance changed in xMoney other than by a starting balance or an import: what an import would set back. */
    public fun changedHere(): Pending<DbOutcome<Set<PlayerRef>, DbError>> = work().changedHere()

    /** Deletes everything kept about [id]: its account, balance and ledger rows, here and (on a network) on the other servers. */
    public fun forget(id: PlayerRef): Pending<DbOutcome<Long, DbError>> = work().forget(id)

    /**
     * Deletes ledger rows older than [before] (epoch ms), [PRUNE_SLICE] ids at a time from the
     * oldest row, each slice its own transaction so payments go between, stopping at the first
     * slice with a newer row (rows are written in time order). A slice costs the same however big
     * the ledger is. Completes with how many rows went.
     */
    public fun prune(before: Long): Pending<Long> = work().prune(before)

    /** Completes once everything queued so far is saved (or failed). */
    public fun flush(): Pending<Unit> = work().flush()

    internal fun text(row: Row, index: Int): String = when (val value = row.values[index]) {
        is SqlValue.Text -> value.value
        SqlValue.Null -> ""
        else -> value.toString()
    }

    public companion object {
        /** The currency's id in the database. */
        public const val CURRENCY: String = "default"

        /** How long a change that waits for the database waits to be started, on a network. */
        public const val WAIT_MS: Long = 5_000

        /** How long a started change has to reach its commit, on a network, before it's rolled back and refused. */
        public const val CHECKED_BUDGET_MS: Long = 1_000

        /** Changes waiting for the database past which new ones are refused rather than run the server out of memory. */
        public const val MAX_WAITING: Long = 100_000

        public const val PRUNE_SLICE: Long = 5_000
        public const val IMPORT_CHUNK: Int = 100
        public const val IMPORT_BUDGET_MS: Long = 20

        /** The change feed's topic for one account, and for every account (after an import). */
        public const val ACCOUNT_TOPIC: String = "account"
        public const val RESYNC_TOPIC: String = "accounts"
    }
}
