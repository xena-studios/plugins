package co.xenastudios.xmoney.ledger

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome as DbOutcome
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.Statement
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.later
import co.xenastudios.xmoney.api.TransactionKind
import co.xenastudios.xmoney.api.TransactionResult.Status
import co.xenastudios.xmoney.ledger.Ledger.Companion.ACCOUNT_TOPIC
import co.xenastudios.xmoney.ledger.Ledger.Companion.IMPORT_BUDGET_MS
import co.xenastudios.xmoney.ledger.Ledger.Companion.IMPORT_CHUNK
import co.xenastudios.xmoney.ledger.Ledger.Companion.PRUNE_SLICE
import co.xenastudios.xmoney.ledger.Ledger.Companion.RESYNC_TOPIC
import co.xenastudios.xmoney.ledger.Ledger.HistoryRow
import kotlin.time.Duration.Companion.milliseconds

/**
 * What [Ledger] does once the server runs: every change, the network's checked transactions,
 * imports, history, forgetting and pruning. Made the first time one is asked for; see [Ledger]
 * for how changes are kept safe.
 */
internal class LedgerWork(private val ledger: Ledger) {
    private val accounts get() = ledger.accounts
    private val db get() = ledger.db
    private val feed get() = ledger.feed
    private val shared get() = ledger.shared
    private val platform get() = ledger.platform
    private val hooks get() = ledger.hooks
    private val statements get() = ledger.statements
    private val log get() = ledger.log
    private val clock get() = ledger.clock
    private val counter get() = ledger.counter
    private val waitMillis get() = ledger.waitMillis
    private val budgetMillis get() = ledger.budgetMillis

    private var inFlight: Long
        get() = ledger.inFlight
        set(value) {
            ledger.inFlight = value
        }

    private var problem: String?
        get() = ledger.problem
        set(value) {
            ledger.problem = value
        }

    private fun maxBalance(): Long = ledger.maxBalance()

    private fun now(): Long = ledger.now()

    private fun full(): Boolean = ledger.full()

    private fun text(row: co.xenastudios.core.common.db.Row, index: Int): String = ledger.text(row, index)

    private fun readAll(session: SqlSession) = ledger.readAll(session)

    // ---- accounts ----

    /**
     * A player's account, made with the starting balance if they have none: a player joining, or
     * staff naming one who has played. Keeps the name up to date. On a network a new account's
     * starting balance is only given by the first server to make it, so it may arrive a moment later.
     */
    fun open(id: PlayerRef, name: String, start: Long): Account {
        accounts[id]?.let {
            claim(it, name)
            return it
        }
        var created: Pending<DbOutcome<Applied?, DbError>>? = null
        val account = accounts.getOrCreate(id) {
            val made = Account(id, name, true, if (shared) 0 else maxOf(0, start))
            val cause = Cause(TransactionKind.START, "xMoney", "starting balance")
            if (shared) {
                created = create(made, maxOf(0, start), cause)
            } else {
                val time = now()
                val writes = ArrayList<Statement>(statements.account(made, time))
                val entries = if (start > 0) listOf(Entry(made, start, start, cause, null, time)) else emptyList()
                for (entry in entries) {
                    writes += statements.add(made.id, entry.delta)
                    writes += statements.ledger(entry)
                }
                save(writes, entries)
            }
            made
        }
        // Once getOrCreate has indexed it: the database may have answered already (#187), and
        // what it read is for this account, which `accounts` only knows from now on.
        created?.onSettled { result, _ -> created(account, result) }
        // The name may have been another account's: a player joining under it takes it.
        claim(account, name)
        return account
    }

    /**
     * The account for a uuid a plugin asks about, made empty if there's none: a plugin paying a
     * player who never joined, an NPC, or an import. No starting balance: that's for players
     * joining. A name another account is known by isn't given to it, so names keep finding the
     * right account after a restart.
     *
     * @param player false for an account that isn't a player's, which the top list leaves out
     */
    fun account(id: PlayerRef, name: String?, player: Boolean): Account {
        accounts[id]?.let { return it }
        val given = if (name.isNullOrBlank() || accounts.named(name) != null || accounts.ambiguous(name)) "" else name
        return accounts.getOrCreate(id) {
            Account(id, given, player, 0).also { saveName(it) }
        }
    }

    /**
     * The account a plugin means by a name, through Vault's old name-based calls: the player or
     * account known by it, or otherwise one that isn't a player's (a town's bank), made empty,
     * with a uuid worked out from the name so it's the same every time (and the same as 1.x's).
     *
     * @return `null` if more than one account has had the name, so it can't say which is meant
     */
    fun named(name: String): Account? {
        accounts.named(name)?.let { return it }
        if (accounts.ambiguous(name)) return null
        val id = NameUuid.of(name)
        return accounts.getOrCreate(id) { Account(id, name, false, 0).also { saveName(it) } }
    }

    /** A player's own name, as they join: it finds their account from now on, even if another account had it. */
    fun claim(account: Account, name: String) {
        if (name.isBlank()) return
        locked(account) {
            val changed = name != account.storedName
            val released = accounts.claim(account, name)
            if (changed) saveName(account)
            // Saved without their name, so it finds this player after a restart too.
            for (other in released) saveName(other)
        }
    }

    /** A new name for an account, as a plugin asks, unless another account is known by it. */
    fun rename(account: Account, name: String): Boolean {
        if (name.isBlank()) return false
        return locked(account) {
            if (name == account.storedName && accounts.named(name) === account) return@locked true
            if (!accounts.rename(account, name)) return@locked false
            saveName(account)
            true
        }
    }

    private fun saveName(account: Account) {
        save(statements.account(account, now()) + published(account.id), emptyList())
    }

    // ---- changes ----

    /** Adds [amount] units to a balance. */
    fun deposit(account: Account, amount: Long, cause: Cause, other: PlayerRef? = null): Pending<Outcome> {
        if (amount <= 0) return done(Outcome.failed(Status.INVALID_AMOUNT, account.balance))
        if (full()) return done(Outcome.failed(Status.UNAVAILABLE, account.balance))
        if (hooks?.refuse(account.id, amount, cause, other) != null) return done(Outcome.failed(Status.CANCELLED, account.balance))
        return locked(account) {
            if (account.gone) return@locked done(Outcome.failed(Status.INVALID_AMOUNT, 0))
            val balance = account.balance
            if (balance + amount > maxBalance()) return@locked done(Outcome.failed(Status.BALANCE_LIMIT, balance))
            val after = balance + amount
            accounts.set(account, after)
            val entry = Entry(account, amount, after, cause, other, now())
            if (shared) {
                account.pending += amount
                queueDeposit(entry)
            } else {
                save(statements.add(account.id, amount) + statements.ledger(entry), listOf(entry))
            }
            done(Outcome(Status.SUCCESS, amount, after))
        }
    }

    /** Takes [amount] units from a balance, if it covers them. */
    fun withdraw(account: Account, amount: Long, cause: Cause): Pending<Outcome> {
        if (amount <= 0) return done(Outcome.failed(Status.INVALID_AMOUNT, account.balance))
        if (full()) return done(Outcome.failed(Status.UNAVAILABLE, account.balance))
        if (hooks?.refuse(account.id, -amount, cause, null) != null) return done(Outcome.failed(Status.CANCELLED, account.balance))
        if (shared) return checked(listOf(Change(account, -amount, -1, cause, null)))
        return locked(account) {
            val balance = account.balance
            if (account.gone || balance < amount) return@locked done(Outcome.failed(Status.INSUFFICIENT_FUNDS, balance))
            val after = balance - amount
            accounts.set(account, after)
            val entry = Entry(account, -amount, after, cause, null, now())
            save(statements.add(account.id, -amount) + statements.ledger(entry), listOf(entry))
            done(Outcome(Status.SUCCESS, amount, after))
        }
    }

    /** Moves [amount] units from one account to another: both change, or neither. The outcome's balance is the payer's. */
    fun transfer(from: Account, to: Account, amount: Long, cause: Cause): Pending<Outcome> {
        if (from === to || from.id == to.id) return done(Outcome.failed(Status.SAME_ACCOUNT, from.balance))
        if (amount <= 0) return done(Outcome.failed(Status.INVALID_AMOUNT, from.balance))
        if (full()) return done(Outcome.failed(Status.UNAVAILABLE, from.balance))
        if (hooks?.refuse(from.id, -amount, cause, to.id) != null) return done(Outcome.failed(Status.CANCELLED, from.balance))
        if (shared) {
            if (to.balance + amount > maxBalance()) return done(Outcome.failed(Status.BALANCE_LIMIT, from.balance))
            return checked(listOf(Change(from, -amount, -1, cause, to.id), Change(to, amount, -1, cause, from.id)))
        }
        // Always the lower uuid first, so two opposite transfers can't hold each other's lock.
        val first = if (from.id.toString() < to.id.toString()) from else to
        val second = if (first === from) to else from
        return locked(first) {
            locked(second) {
                val payer = from.balance
                if (from.gone || to.gone || payer < amount) return@locked done(Outcome.failed(Status.INSUFFICIENT_FUNDS, payer))
                if (to.balance + amount > maxBalance()) return@locked done(Outcome.failed(Status.BALANCE_LIMIT, payer))
                val time = now()
                val payerAfter = payer - amount
                val payeeAfter = to.balance + amount
                accounts.set(from, payerAfter)
                accounts.set(to, payeeAfter)
                val entries = listOf(Entry(from, -amount, payerAfter, cause, to.id, time), Entry(to, amount, payeeAfter, cause, from.id, time))
                save(statements.add(from.id, -amount) + statements.add(to.id, amount) + entries.map(statements::ledger), entries)
                done(Outcome(Status.SUCCESS, amount, payerAfter))
            }
        }
    }

    /** Sets a balance, whatever it was. The outcome's amount is how much it changed by, either way. */
    fun set(account: Account, balance: Long, cause: Cause): Pending<Outcome> {
        if (balance < 0 || balance > maxBalance()) {
            return done(Outcome.failed(if (balance < 0) Status.INVALID_AMOUNT else Status.BALANCE_LIMIT, account.balance))
        }
        if (full()) return done(Outcome.failed(Status.UNAVAILABLE, account.balance))
        if (cause.kind != TransactionKind.IMPORT && hooks?.refuse(account.id, balance - account.balance, cause, null) != null) {
            return done(Outcome.failed(Status.CANCELLED, account.balance))
        }
        if (shared) return checked(listOf(Change(account, 0, balance, cause, null)))
        return locked(account) {
            if (account.gone) return@locked done(Outcome.failed(Status.INVALID_AMOUNT, 0))
            val delta = balance - account.balance
            if (delta != 0L) {
                accounts.set(account, balance)
                val entry = Entry(account, delta, balance, cause, null, now())
                save(statements.add(account.id, delta) + statements.ledger(entry), listOf(entry))
            }
            done(Outcome(Status.SUCCESS, if (delta < 0) -delta else delta, balance))
        }
    }

    // A single server's writes (and names everywhere): queued now, in order, and the post-events
    // once they're committed.
    private fun save(writes: List<Statement>, entries: List<Entry>) {
        locked(counter) { inFlight++ }
        db.write(writes).onSettled { result, _ ->
            locked(counter) { inFlight-- }
            when (result) {
                is DbOutcome.Err -> {
                    problem = result.error.toString()
                    log.error("Couldn't save ${if (entries.isEmpty()) "an account's name" else "a balance change"} to ${db.description}: ${result.error}. " +
                        "Balances in memory stay as they are; the database is behind them until it's fixed.")
                }
                else -> {
                    if (problem != null && db.available) problem = null
                    if (entries.isNotEmpty()) hooks?.saved(entries.map { SavedChange(it.account.id, it.delta, it.after, it.cause, it.other) })
                }
            }
        }
    }

    private fun published(id: PlayerRef): List<Statement> = feed?.publishing(ACCOUNT_TOPIC, id.toString()).orEmpty()

    // ---- network ----

    /** One account's part of a checked change: by [delta], or to [setTo] when it's 0 or more. */
    private class Change(val account: Account, val delta: Long, val setTo: Long, val cause: Cause, val other: PlayerRef?)

    /** A change the database decides: queued, then started by the writer or abandoned by its caller, never both. */
    private class Checked(val changes: List<Change>, val deadline: Long) {
        val result: Pending<Outcome> = newPending()
        var state = QUEUED
        var startedAt = 0L

        fun abandon(refused: Outcome): Boolean {
            val abandoned = locked(this) {
                if (state == QUEUED) {
                    state = ABANDONED
                    true
                } else false
            }
            if (abandoned) result.resolve(refused)
            return abandoned
        }

        /** The writer's claim on it, just before running it: `false` if it was abandoned, now or before. */
        fun start(now: Long, elapsed: Long, refused: Outcome): Boolean {
            val outcome = locked(this) {
                when {
                    state == STARTED -> true
                    state == ABANDONED -> false
                    now > deadline -> {
                        state = ABANDONED
                        null
                    }
                    else -> {
                        state = STARTED
                        startedAt = elapsed
                        true
                    }
                }
            }
            if (outcome == null) {
                result.resolve(refused)
                return false
            }
            return outcome
        }

        companion object {
            const val QUEUED = 0
            const val STARTED = 1
            const val ABANDONED = 2
        }
    }

    private val waitingChanges = HashMap<Pending<Outcome>, Checked>()

    /**
     * Gives up on the change behind [result], for a caller that waits with a timeout of its own (a
     * Vault call on a server thread): it's refused as [refused] and never runs, unless the writer
     * has started it, when its real outcome is coming. `true` if it was given up.
     */
    fun abandon(result: Pending<Outcome>, refused: Outcome): Boolean =
        locked(waitingChanges) { waitingChanges[result] }?.abandon(refused) ?: false

    private fun elapsedMillis(): Long = clock.elapsed().inWholeMilliseconds

    private fun checked(changes: List<Change>): Pending<Outcome> {
        val first = changes.first().account
        if (!db.available) return done(Outcome.failed(Status.UNAVAILABLE, first.balance))
        val checked = Checked(changes, now() + waitMillis)
        locked(waitingChanges) { waitingChanges[checked.result] = checked }
        checked.result.onSettled { _, _ -> locked(waitingChanges) { waitingChanges.remove(checked.result) } }
        val refused = Outcome.failed(Status.UNAVAILABLE, first.balance)
        locked(counter) { inFlight++ }
        db.transaction { session -> runChecked(session, checked, refused) }.onSettled { result, error ->
            locked(counter) { inFlight-- }
            settleChecked(checked, result, error)
        }
        // A caller that never waits with a timeout of its own (the API, the commands) still hears
        // within the wait: if the writer hasn't started it by then, it never will.
        platform.scheduler.later(Where.Async, waitMillis.milliseconds) { checked.abandon(refused) }
        return checked.result
    }

    /** What a checked change did: each account's balance after, from the database. */
    private class Applied(val entries: List<Entry>, val balances: Map<PlayerRef, Long>)

    private fun runChecked(session: SqlSession, checked: Checked, refused: Outcome): DbOutcome<Applied?, DbError> {
        if (!checked.start(now(), elapsedMillis(), refused)) return DbOutcome.Ok(null)
        val time = now()
        val entries = ArrayList<Entry>()
        // In uuid order, as every write changes rows, so two servers' transactions can't deadlock.
        for (change in checked.changes.sortedBy { it.account.id.toString() }) {
            if (elapsedMillis() - checked.startedAt > budgetMillis) return DbOutcome.Err(TOO_SLOW)
            val id = change.account.id
            run(session, statements.ensure(id))?.let { return DbOutcome.Err(it) }
            when {
                change.setTo >= 0 -> {
                    val before = when (val read = balance(session, id, lock = true)) {
                        is DbOutcome.Ok -> read.value
                        is DbOutcome.Err -> return read
                    }
                    if (before != change.setTo) {
                        when (val swapped = session.execute(statements.swap(id, before, change.setTo))) {
                            is DbOutcome.Err -> return swapped
                            is DbOutcome.Ok -> if (swapped.value == 0) return DbOutcome.Err(TOO_SLOW)
                        }
                        entries += Entry(change.account, change.setTo - before, 0, change.cause, change.other, time)
                    }
                }
                change.delta < 0 -> when (val took = session.execute(statements.take(id, -change.delta))) {
                    is DbOutcome.Err -> return took
                    is DbOutcome.Ok -> {
                        if (took.value == 0) return DbOutcome.Err(INSUFFICIENT)
                        entries += Entry(change.account, change.delta, 0, change.cause, change.other, time)
                    }
                }
                else -> {
                    for (statement in statements.add(id, change.delta)) run(session, statement)?.let { return DbOutcome.Err(it) }
                    entries += Entry(change.account, change.delta, 0, change.cause, change.other, time)
                }
            }
        }
        return finish(session, entries, checked)
    }

    // The balances after, read back, the ledger rows in the order the changes happened, and the
    // other servers told.
    private fun finish(session: SqlSession, entries: List<Entry>, checked: Checked?): DbOutcome<Applied?, DbError> {
        val balances = LinkedHashMap<PlayerRef, Long>()
        for (entry in entries) {
            if (entry.account.id in balances) continue
            when (val read = balance(session, entry.account.id, lock = false)) {
                is DbOutcome.Ok -> balances[entry.account.id] = read.value
                is DbOutcome.Err -> return read
            }
        }
        // Each row's balance after, worked back from the database's: the last change to an account
        // left its balance, each earlier one that minus the changes after it.
        val left = HashMap(balances)
        for (entry in entries.asReversed()) {
            val after = left.getValue(entry.account.id)
            entry.after = after
            left[entry.account.id] = after - entry.delta
        }
        for (entry in entries) run(session, statements.ledger(entry))?.let { return DbOutcome.Err(it) }
        for (id in balances.keys) for (statement in published(id)) run(session, statement)?.let { return DbOutcome.Err(it) }
        if (checked != null && elapsedMillis() - checked.startedAt > budgetMillis) return DbOutcome.Err(TOO_SLOW)
        return DbOutcome.Ok(Applied(entries, balances))
    }

    private fun run(session: SqlSession, statement: Statement): DbError? = (session.execute(statement) as? DbOutcome.Err)?.error

    private fun balance(session: SqlSession, id: PlayerRef, lock: Boolean): DbOutcome<Long, DbError> =
        when (val rows = session.query(statements.balance(id, lock))) {
            is DbOutcome.Ok -> DbOutcome.Ok(rows.value.firstOrNull()?.long(0) ?: 0L)
            is DbOutcome.Err -> rows
        }

    private fun settleChecked(checked: Checked, result: DbOutcome<Applied?, DbError>?, error: Throwable?) {
        val first = checked.changes.first().account
        when {
            result is DbOutcome.Ok -> {
                val applied = result.value ?: return
                apply(applied)
                val change = checked.changes.first()
                val moved = if (change.setTo >= 0) applied.entries.firstOrNull()?.delta ?: 0 else change.delta
                checked.result.resolve(Outcome(Status.SUCCESS, if (moved < 0) -moved else moved, first.balance))
            }
            result is DbOutcome.Err && result.error === INSUFFICIENT -> checked.result.resolve(Outcome.failed(Status.INSUFFICIENT_FUNDS, first.balance))
            else -> {
                val why = (result as? DbOutcome.Err)?.error ?: DbError(DbError.Kind.OTHER, error.toString())
                if (why !== TOO_SLOW && why.kind != DbError.Kind.CLOSED) problem = why.toString()
                if (why === TOO_SLOW) log.warn("${db.description} took over ${budgetMillis}ms to take a payment, so it was rolled back and refused. Nothing was taken.")
                checked.result.resolve(Outcome.failed(Status.UNAVAILABLE, first.balance))
            }
        }
    }

    // Memory follows the database: each account's balance there, plus this server's unsaved deposits.
    private fun apply(applied: Applied) {
        for ((id, balance) in applied.balances) {
            val account = accounts[id] ?: continue
            locked(account) { accounts.set(account, balance + account.pending) }
        }
        if (problem != null && db.available) problem = null
        if (applied.entries.isNotEmpty()) hooks?.saved(applied.entries.map { SavedChange(it.account.id, it.delta, it.after, it.cause, it.other) })
    }

    private val deposits = ArrayList<Entry>()
    private var depositsQueued = false

    // Network deposits, saved in batches: the first queues a transaction, which takes every deposit
    // waiting when the writer starts it.
    private fun queueDeposit(entry: Entry) {
        val submit = locked(deposits) {
            deposits += entry
            if (depositsQueued) false else {
                depositsQueued = true
                true
            }
        }
        if (!submit) return
        locked(counter) { inFlight++ }
        var batch: List<Entry>? = null
        db.transaction { session ->
            val entries = batch ?: locked(deposits) {
                depositsQueued = false
                deposits.toList().also { deposits.clear() }
            }.also { batch = it }
            for (deposit in entries.sortedBy { it.account.id.toString() }) {
                for (statement in statements.add(deposit.account.id, deposit.delta)) run(session, statement)?.let { return@transaction DbOutcome.Err(it) }
            }
            // Fresh entries, so a try that's run again starts from the same deltas.
            finish(session, entries.map { Entry(it.account, it.delta, 0, it.cause, it.other, it.time) }, null)
        }.onSettled { result, error ->
            locked(counter) { inFlight-- }
            val entries = batch ?: locked(deposits) {
                depositsQueued = false
                deposits.toList().also { deposits.clear() }
            }
            for (deposit in entries) locked(deposit.account) { deposit.account.pending -= deposit.delta }
            if (result is DbOutcome.Ok && result.value != null) {
                apply(result.value!!)
            } else {
                // Never saved: taken back out of memory, since the database never had it.
                val why = (result as? DbOutcome.Err)?.error?.toString() ?: error.toString()
                problem = why
                log.error("Couldn't save ${entries.size} deposits to ${db.description} ($why), so they're taken back out of the balances.")
                for (deposit in entries) locked(deposit.account) { accounts.set(deposit.account, deposit.account.balance - deposit.delta) }
            }
        }
    }

    /**
     * On a network: a new player's account, with the starting balance only if no server made it
     * first. Queued while [Accounts.getOrCreate] makes it, so nothing else is written for it
     * first; settled by [created] once it's indexed.
     */
    private fun create(account: Account, start: Long, cause: Cause): Pending<DbOutcome<Applied?, DbError>> {
        locked(counter) { inFlight++ }
        return db.transaction { session ->
            val time = now()
            for (statement in statements.account(account, time)) run(session, statement)?.let { return@transaction DbOutcome.Err(it) }
            val made = when (val inserted = session.execute(statements.start(account.id, start))) {
                is DbOutcome.Err -> return@transaction inserted
                is DbOutcome.Ok -> inserted.value > 0
            }
            finish(session, if (made && start > 0) listOf(Entry(account, start, 0, cause, null, time)) else emptyList(), null).let { applied ->
                if (applied is DbOutcome.Ok && !made) {
                    // Another server made it first: just its balance.
                    when (val read = balance(session, account.id, lock = false)) {
                        is DbOutcome.Ok -> DbOutcome.Ok(Applied(emptyList(), mapOf(account.id to read.value)))
                        is DbOutcome.Err -> read
                    }
                } else applied
            }
        }
    }

    private fun created(account: Account, result: DbOutcome<Applied?, DbError>?) {
        locked(counter) { inFlight-- }
        when (result) {
            is DbOutcome.Ok -> result.value?.let(::apply)
            is DbOutcome.Err -> {
                problem = result.error.toString()
                log.error("Couldn't make ${account.name}'s account in ${db.description}: ${result.error}")
            }
            null -> Unit
        }
    }

    /**
     * Sets many balances at once, for an import: each to its target, as [cause]. On a network the
     * database sets them, [IMPORT_CHUNK] to a transaction (or [IMPORT_BUDGET_MS] of work) with
     * payments between, waiting out a database that's away, and the other servers read every
     * balance again once it's done. On one server it's in memory at once.
     *
     * @return how many were set
     */
    fun setAll(targets: List<Pair<Account, Long>>, cause: Cause): Pending<Int> {
        if (!shared) {
            var set = 0
            for ((account, balance) in targets) {
                var ok = false
                set(account, balance, cause).onSettled { outcome, _ -> ok = outcome?.success == true }
                if (ok) set++
            }
            return pendingOf(set)
        }
        val done = newPending<Int>()
        val ordered = targets.sortedBy { it.first.id.toString() }
        importChunk(ordered, 0, 0, cause, done)
        return done
    }

    private fun importChunk(targets: List<Pair<Account, Long>>, from: Int, set: Int, cause: Cause, done: Pending<Int>) {
        if (from >= targets.size) {
            db.write(feed?.publishing(RESYNC_TOPIC, "import").orEmpty()).onSettled { _, _ -> done.resolve(set) }
            return
        }
        db.transaction { session ->
            val started = elapsedMillis()
            val time = now()
            val entries = ArrayList<Entry>()
            var next = from
            while (next < targets.size && next - from < IMPORT_CHUNK && (next == from || elapsedMillis() - started < IMPORT_BUDGET_MS)) {
                val (account, target) = targets[next]
                run(session, statements.ensure(account.id))?.let { return@transaction DbOutcome.Err(it) }
                val before = when (val read = balance(session, account.id, lock = true)) {
                    is DbOutcome.Ok -> read.value
                    is DbOutcome.Err -> return@transaction read
                }
                if (before != target) {
                    run(session, statements.swap(account.id, before, target))?.let { return@transaction DbOutcome.Err(it) }
                    entries += Entry(account, target - before, 0, cause, null, time)
                }
                next++
            }
            finish(session, entries, null).let { if (it is DbOutcome.Ok) DbOutcome.Ok(it.value to next) else it as DbOutcome.Err }
        }.onSettled { result, error ->
            if (result is DbOutcome.Ok) {
                val (applied, next) = result.value
                applied?.let(::apply)
                // To the back of the queue, so payments go between chunks.
                importChunk(targets, next, set + (next - from), cause, done)
            } else {
                log.error("An import stopped partway in ${db.description}: ${(result as? DbOutcome.Err)?.error ?: error}. $set balances were set.")
                done.resolve(set)
            }
        }
    }

    /**
     * Keeps this server's memory in step with the others': accounts another server changed are
     * read again, and everything is read again when the feed says changes may have been missed,
     * or after another server's import. Before the feed starts.
     */
    fun listen() {
        val feed = feed ?: return
        feed.subscribe(ACCOUNT_TOPIC) { payload -> PlayerRef.parseOrNull(payload)?.let(::refresh) }
        feed.subscribe(RESYNC_TOPIC) { resync() }
        feed.onResync { resync() }
    }

    /** Reads an account another server changed again, after every write queued before it. */
    fun refresh(id: PlayerRef) {
        db.session { session ->
            val name = when (val rows = session.query(statements.accountRow(id))) {
                is DbOutcome.Ok -> rows.value.firstOrNull()
                is DbOutcome.Err -> return@session rows
            }
            when (val read = balance(session, id, lock = false)) {
                is DbOutcome.Ok -> DbOutcome.Ok(name to read.value)
                is DbOutcome.Err -> read
            }
        }.onSettled { result, _ ->
            val (row, balance) = (result as? DbOutcome.Ok)?.value ?: return@onSettled
            if (row == null) {
                // Forgotten there: gone here too, unless this server has unsaved deposits for it.
                val account = accounts[id] ?: return@onSettled
                locked(account) { if (account.pending == 0L) accounts.remove(account) }
                return@onSettled
            }
            val account = accounts[id] ?: accounts.load(id, text(row, 0), row.long(1) != 0L, balance)
            locked(account) {
                accounts.renamedElsewhere(account, text(row, 0))
                accounts.set(account, balance + account.pending)
            }
        }
    }

    /** Reads every account and balance again: network mode, when this server may have missed changes. */
    fun resync() {
        db.session { session -> readAll(session) }.onSettled { result, _ ->
            val rows = (result as? DbOutcome.Ok)?.value ?: return@onSettled
            for (stored in rows) {
                val account = accounts.load(stored.id, stored.name, stored.player, stored.balance)
                locked(account) {
                    accounts.renamedElsewhere(account, stored.name)
                    accounts.set(account, stored.balance + account.pending)
                }
            }
        }
    }

    /** How many ledger rows [id] has, and a page of them, newest first: after everything queued so far is saved. */
    fun history(id: PlayerRef, offset: Long, limit: Int): Pending<DbOutcome<Pair<Long, List<HistoryRow>>, DbError>> = db.session { session ->
        val count = when (val rows = session.query(statements.count(id))) {
            is DbOutcome.Ok -> rows.value.firstOrNull()?.long(0) ?: 0
            is DbOutcome.Err -> return@session rows
        }
        when (val rows = session.query(statements.history(id, offset, limit))) {
            is DbOutcome.Err -> rows
            is DbOutcome.Ok -> DbOutcome.Ok(count to rows.value.map { r ->
                HistoryRow(
                    r.long(0) ?: 0, r.long(1) ?: 0, r.long(2) ?: 0, r.long(3) ?: 0,
                    TransactionKind.entries.firstOrNull { it.name == text(r, 4) } ?: TransactionKind.PLUGIN,
                    text(r, 5), text(r, 6), (r.values[7] as? SqlValue.Text)?.value?.let(PlayerRef::parseOrNull),
                )
            })
        }
    }

    /** Accounts whose balance changed in xMoney other than by a starting balance or an import: what an import would set back. */
    fun changedHere(): Pending<DbOutcome<Set<PlayerRef>, DbError>> = db.session { session ->
        when (val rows = session.query(statements.changed())) {
            is DbOutcome.Err -> rows
            is DbOutcome.Ok -> DbOutcome.Ok(rows.value.mapNotNull { PlayerRef.parseOrNull(text(it, 0)) }.toSet())
        }
    }

    /** Deletes everything kept about [id]: its account, balance and ledger rows, here and (on a network) on the other servers. */
    fun forget(id: PlayerRef): Pending<DbOutcome<Long, DbError>> {
        val account = accounts[id]
        if (account != null) locked(account) { accounts.remove(account) }
        return db.transaction { session ->
            var rows = 0L
            for (statement in statements.forget(id)) when (val ran = session.execute(statement)) {
                is DbOutcome.Err -> return@transaction ran
                is DbOutcome.Ok -> rows += ran.value
            }
            for (statement in published(id)) run(session, statement)?.let { return@transaction DbOutcome.Err(it) }
            DbOutcome.Ok(rows)
        }
    }

    /**
     * Deletes ledger rows older than [before] (epoch ms), [PRUNE_SLICE] ids at a time from the
     * oldest row, each slice its own transaction so payments go between, stopping at the first
     * slice with a newer row (rows are written in time order). A slice costs the same however big
     * the ledger is. Completes with how many rows went.
     */
    fun prune(before: Long): Pending<Long> {
        val done = newPending<Long>()
        pruneFrom(0, before, 0, done)
        return done
    }

    private fun pruneFrom(from: Long, before: Long, deleted: Long, done: Pending<Long>) {
        db.transaction { session ->
            val first = when (val rows = session.query(statements.firstFrom(from))) {
                is DbOutcome.Ok -> rows.value.firstOrNull()
                is DbOutcome.Err -> return@transaction rows
            } ?: return@transaction DbOutcome.Ok(null)
            val start = first.long(0) ?: return@transaction DbOutcome.Ok(null)
            if ((first.long(1) ?: 0) >= before) return@transaction DbOutcome.Ok(null)
            val until = start + PRUNE_SLICE
            val gone = when (val ran = session.execute(statements.pruneSlice(start, until, before))) {
                is DbOutcome.Ok -> ran.value
                is DbOutcome.Err -> return@transaction ran
            }
            val newer = when (val rows = session.query(statements.newerIn(start, until, before))) {
                is DbOutcome.Ok -> rows.value.isNotEmpty()
                is DbOutcome.Err -> return@transaction rows
            }
            DbOutcome.Ok(Triple(until, gone.toLong(), newer))
        }.onSettled { result, _ ->
            val step = (result as? DbOutcome.Ok)?.value
            if (result is DbOutcome.Err) log.warn("Pruning old ledger rows stopped (${result.error}); it's tried again tomorrow.")
            if (step == null || step.third) {
                if (deleted + (step?.second ?: 0) > 0) log.info("Deleted ${deleted + (step?.second ?: 0)} ledger rows older than ledger.keep-days.")
                done.resolve(deleted + (step?.second ?: 0))
            } else {
                pruneFrom(step.first, before, deleted + step.second, done)
            }
        }
    }

    /** Completes once everything queued so far is saved (or failed). */
    fun flush(): Pending<Unit> {
        val done = newPending<Unit>()
        db.session { DbOutcome.Ok(Unit) }.onSettled { _, _ -> done.resolve(Unit) }
        return done
    }

    private fun done(outcome: Outcome): Pending<Outcome> = pendingOf(outcome)

    private companion object {
        val TOO_SLOW = DbError(DbError.Kind.OTHER, "too slow to reach its commit")
        val INSUFFICIENT = DbError(DbError.Kind.OTHER, "not enough money")
    }
}
