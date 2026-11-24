package co.xenastudios.core.common.db

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Clock
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.every
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A plugin's `network` section: whether servers sharing its MySQL database tell each other about changes. */
public data class NetworkSettings(
    /** On when several servers share this plugin's MySQL or MariaDB database. Does nothing on SQLite. */
    public val enabled: Boolean = false,
    /** How often each server checks for the others' changes. */
    public val checkEvery: Duration = 3.seconds,
)

/** One change another server published, as read back. [created] is by that server's clock. */
public data class Change(public val id: Long, public val topic: String, public val payload: String, public val created: Instant)

/**
 * Changes one server tells the others about, for plugins on several servers sharing a MySQL or
 * MariaDB database: a row in the plugin's `{prefix}changes` table, which every other server reads
 * within [NetworkSettings.checkEvery] and hands to whatever subscribed to its topic. A ban, a
 * payment, a renamed home: the server that made it changed its own memory already; the others
 * hear about it here and update theirs. (DECISIONS.md, "Network mode: one change feed in the
 * database", whose protocol this keeps, table and all.)
 *
 * Off unless the settings turn it on and the database is shared ([Dialect.shared]): then
 * publishing adds nothing and nothing is checked, so a single server pays nothing.
 *
 * - **Published with the change.** [publishing] gives the statement to add to the same
 *   [Database.write] as the change itself, or [publish] runs it in the caller's transaction, so
 *   the others never hear of a change that rolled back.
 * - **One index range per check**, by the table's key, at most [BATCH] rows; this server's own
 *   rows (a random [origin] per start) are skipped, and subscribers get the rest in order, on the
 *   database's reader thread: one that touches the world moves to the right thread itself.
 * - **Late commits aren't lost.** Ids are handed out on insert, not on commit, so a row can appear
 *   after a newer one was read. A skipped id is asked for again on each check for [GAP_WAIT];
 *   a jump of over [MAX_GAP] ids isn't waited on at all.
 * - **Changes from before a start aren't replayed**: it starts from the newest row.
 * - **Pruned to [KEEP], never empty**: once a minute, by key range below the newest row seen.
 * - **A server that may have missed changes reads again**: [onResync] runs when checks work
 *   after failing, when none worked for half of [KEEP], and after a jump too big to wait for.
 */
public class ChangeFeed private constructor(
    private val database: Database?,
    /** This server's id in the table, new on each start. */
    public val origin: String,
    private val period: Duration,
    private val scheduler: Scheduler?,
    private val clock: Clock?,
    private val log: Log?,
) {
    private val lock = Any()
    private val subscribers = HashMap<String, List<(Change) -> Unit>>()
    private var resyncs: List<() -> Unit> = emptyList()
    private val cursor = FeedCursor(0)
    private var checking = false
    private var failing = false
    private var lastWorked = Duration.ZERO
    private var lastPrune = Duration.ZERO

    @Volatile
    private var task: Task? = null

    @Volatile
    private var closed = false

    /** Whether this server shares changes with others. */
    public val isOn: Boolean get() = database != null

    /**
     * Hands [topic]'s changes from other servers to [subscriber], oldest first, on the
     * database's reader thread. Subscribe before [start].
     */
    public fun subscribe(topic: String, subscriber: (Change) -> Unit) {
        locked(lock) { subscribers[topic] = subscribers[topic].orEmpty() + subscriber }
    }

    /**
     * Runs [resync] whenever this server may have missed changes, so it reads again from its
     * tables what it keeps in memory. After that check's changes are delivered. Subscribe before
     * [start].
     */
    public fun onResync(resync: () -> Unit) {
        locked(lock) { resyncs = resyncs + resync }
    }

    /**
     * The statement that tells the other servers about a change: add it to the same
     * [Database.write] as the change. Empty when the feed is off.
     *
     * @param topic what changed, such as `ban`; 1 to 64 characters
     * @param payload what they need to find it, such as the player's uuid; up to [MAX_PAYLOAD]
     */
    public fun publishing(topic: String, payload: String): List<Statement> {
        val database = database ?: return emptyList()
        require(topic.isNotEmpty() && topic.length <= 64 && payload.length <= MAX_PAYLOAD) {
            "a change's topic is 1 to 64 characters and its payload at most $MAX_PAYLOAD; send an id and let the others read the rest"
        }
        return listOf(database.sql.insert(values(Changes) {
            it[Changes.origin] = origin
            it[Changes.topic] = topic
            it[Changes.payload] = payload
            it[Changes.created] = clock!!.now().toEpochMilliseconds()
        }))
    }

    /** [publishing], run in [session]: inside the caller's [Database.transaction]. */
    public fun publish(session: SqlSession, topic: String, payload: String): Outcome<Unit, DbError> =
        when (val ran = session.executeAll(publishing(topic, payload))) {
            is Outcome.Ok -> Outcome.Ok(Unit)
            is Outcome.Err -> ran
        }

    /** Starts checking for the other servers' changes, every [NetworkSettings.checkEvery], off the server's threads. */
    public fun start() {
        val scheduler = scheduler ?: return
        locked(lock) {
            if (task == null && !closed) task = scheduler.every(Where.Async, period) { check() }
        }
    }

    /** Stops checking. Changes already published stay for the other servers. */
    public fun close() {
        closed = true
        task?.cancel()
        task = null
    }

    /**
     * One check, as the repeating task runs it: reads what's new, delivers it, prunes once a
     * minute. Settles once it's done; does nothing if one is running. A failure is logged once,
     * until a check works again.
     */
    public fun check(): Pending<Unit> {
        val database = database ?: return pendingOf(Unit)
        val now = clock!!.elapsed()
        val missed: Boolean = locked(lock) {
            if (closed || checking) {
                null
            } else {
                checking = true
                failing || now - lastWorked > KEEP / 2
            }
        } ?: return pendingOf(Unit)
        val done = newPending<Unit>()
        database.read { session -> poll(session, database.sql, now) }.onSettled { result, error ->
            try {
                if (result is Outcome.Ok) worked(result.value, missed, now) else failed(result?.errorOrNull()?.toString() ?: error.toString())
            } finally {
                locked(lock) { checking = false }
                done.resolve(Unit)
            }
        }
        return done
    }

    private fun poll(session: SqlSession, sql: Sql, now: Duration): Outcome<List<Change>, DbError> {
        val columns = listOf(Changes.id, Changes.origin, Changes.topic, Changes.payload, Changes.created)
        val waiting = locked(lock) { cursor.waiting(BATCH) }
        val late = if (waiting.isEmpty()) emptyList() else when (val rows = session.query(sql.select(Changes, Changes.id within waiting, columns))) {
            is Outcome.Ok -> rows.value
            is Outcome.Err -> return rows
        }
        val high = locked(lock) { cursor.high }
        val fresh = when (val rows = session.query(sql.select(Changes, Changes.id gt high, columns, listOf(Changes.id.ascending), BATCH))) {
            is Outcome.Ok -> rows.value
            is Outcome.Err -> return rows
        }
        val found = (late + fresh).map { FeedCursor.Found(change(it), it[Changes.origin]) }
        return Outcome.Ok(locked(lock) { cursor.accept(found, origin, now) })
    }

    private fun worked(changes: List<Change>, missed: Boolean, now: Duration) {
        val (recovered, behind, prune) = locked(lock) {
            lastWorked = now
            val recovered = failing
            failing = false
            val prune = if (now - lastPrune >= PRUNE_EVERY) {
                lastPrune = now
                cursor.pruneBelow()
            } else {
                null
            }
            Triple(recovered, cursor.takeBehind(), prune)
        }
        if (recovered) log!!.info("Network mode is reading other servers' changes again.")
        val subscribed = locked(lock) { subscribers.toMap() }
        for (change in changes) {
            for (subscriber in subscribed[change.topic].orEmpty()) {
                contained("Handling another server's '${change.topic}' change") { subscriber(change) }
            }
        }
        if (missed || behind) {
            for (resync in locked(lock) { resyncs }) contained("Reading again after missed network changes") { resync() }
        }
        if (prune != null) {
            val before = clock!!.now().toEpochMilliseconds() - KEEP.inWholeMilliseconds
            database!!.write(database.sql.delete(Changes, (Changes.id lt prune) and (Changes.created lt before)))
        }
    }

    private fun failed(why: String) {
        val first = locked(lock) { (!failing).also { failing = true } }
        if (first) {
            log!!.warn("Couldn't read other servers' changes from ${database!!.description} ($why); trying again every ${period.inWholeSeconds}s.")
        }
    }

    private inline fun contained(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Throwable) {
            if (isFatal(e)) throw e
            log!!.warn("$what failed", e)
        }
    }

    /** The table every plugin's feed uses, `{prefix}changes`: the same columns as today's plugins'. */
    public object Changes : Table("changes") {
        public val id: Column<Long> = rowId()
        public val origin: Column<String> = column("origin", ColumnType.text(16))
        public val topic: Column<String> = column("topic", ColumnType.text(64))
        public val payload: Column<String> = column("payload", ColumnType.text(MAX_PAYLOAD))
        /** The publishing server's clock, in milliseconds since 1970. */
        public val created: Column<Long> = column("created", ColumnType.LONG)
    }

    public companion object {
        /** Most rows read per check; more wait for the next one. */
        public const val BATCH: Int = 500

        /** Longest payload, in characters. Send ids and let subscribers read the rest. */
        public const val MAX_PAYLOAD: Int = 1024

        /** How long a missing id is waited for: a transaction that took its id but hasn't committed. */
        public val GAP_WAIT: Duration = 15.seconds

        /** Ids skipped at once beyond which they aren't waited for (a big rollback, or another cluster's numbering). */
        public const val MAX_GAP: Int = 1_000

        /** Most missing ids waited for at once; the oldest are given up first. */
        public const val MAX_WAITING: Int = 5_000

        /** How long a change stays in the table. */
        public val KEEP: Duration = 10.minutes

        /** How often each server prunes. */
        public val PRUNE_EVERY: Duration = 1.minutes

        /** A feed that's off: publishing adds nothing and subscribers never hear anything. */
        public fun off(): ChangeFeed = ChangeFeed(null, "", Duration.ZERO, null, null, null)

        /**
         * Opens the feed [settings] ask for, making its table and starting from its newest row if
         * it's on; then [start] it. With network mode on but an SQLite database it's off, and the
         * console is told why.
         */
        public fun open(
            database: Database,
            settings: NetworkSettings,
            scheduler: Scheduler,
            clock: Clock,
            log: Log,
            origin: String = Uuid.random().toHexString().substring(0, 16),
        ): Pending<Outcome<ChangeFeed, DbError>> {
            if (!settings.enabled) return pendingOf(Outcome.Ok(off()))
            if (!database.dialect.shared) {
                log.warn("network.enabled is on, but the database is ${database.dialect.description}. Servers can only " +
                    "share changes through a MySQL or MariaDB database they all use; until then, network mode is off.")
                return pendingOf(Outcome.Ok(off()))
            }
            return openOnAny(database, settings, scheduler, clock, log, origin)
        }

        /**
         * [open] on any database, SQLite too, with [NetworkSettings.enabled] taken as given: for
         * tests and tools, where two databases on one SQLite file stand in for two servers.
         */
        public fun openOnAny(
            database: Database,
            settings: NetworkSettings,
            scheduler: Scheduler,
            clock: Clock,
            log: Log,
            origin: String,
        ): Pending<Outcome<ChangeFeed, DbError>> {
            require(settings.checkEvery.isPositive()) { "network.check-every must be positive" }
            val feed = ChangeFeed(database, origin, settings.checkEvery, scheduler, clock, log)
            return database.session { session ->
                when (val made = session.executeAll(database.sql.createTable(Changes))) {
                    is Outcome.Err -> made
                    is Outcome.Ok -> session.queryOne(database.sql.raw("SELECT MAX(id) FROM {p}changes")) { it.long(0) ?: 0 }
                }
            }.let { started ->
                val opened = newPending<Outcome<ChangeFeed, DbError>>()
                started.onSettled { result, _ ->
                    opened.resolve(when (result) {
                        is Outcome.Ok -> {
                            val now = clock.elapsed()
                            locked(feed.lock) {
                                feed.cursor.high = result.value ?: 0
                                feed.lastWorked = now
                                feed.lastPrune = now
                            }
                            Outcome.Ok(feed)
                        }
                        is Outcome.Err -> result
                        null -> Outcome.Err(DbError(DbError.Kind.OTHER, "the database failed"))
                    })
                }
                opened
            }
        }

        private fun change(row: Row) = Change(row[Changes.id], row[Changes.topic], row[Changes.payload],
            Instant.fromEpochMilliseconds(row[Changes.created]))
    }
}

/**
 * Which ids a [ChangeFeed] has read, and which it's still waiting for. Pure, so the protocol is
 * tested on every platform without a database.
 */
internal class FeedCursor(var high: Long) {
    class Found(val change: Change, val origin: String)

    // Missing id to when it was first missed. Ids are added in ascending order, so the first is the oldest.
    private val gaps = LinkedHashMap<Long, Duration>()
    private var behind = false

    /** How many ids it's waiting for. */
    val waitingFor: Int get() = gaps.size

    /** The ids to ask for again, oldest first. */
    fun waiting(limit: Int): List<Long> = gaps.keys.take(limit)

    /**
     * Takes what a check found (late rows first, then new ones in id order) and returns the
     * changes to deliver, in order: everything new or late that isn't [own].
     */
    fun accept(found: List<Found>, own: String, now: Duration): List<Change> {
        val deliver = ArrayList<Change>()
        for (row in found) {
            val id = row.change.id
            if (id > high) {
                if (id - high - 1 <= ChangeFeed.MAX_GAP) {
                    for (missing in high + 1 until id) gaps[missing] = now
                } else {
                    behind = true
                }
                high = id
            } else if (gaps.remove(id) == null) {
                continue
            }
            if (row.origin != own) deliver += row.change
        }
        gaps.values.removeAll { now - it > ChangeFeed.GAP_WAIT }
        val over = gaps.size - ChangeFeed.MAX_WAITING
        if (over > 0) gaps.keys.take(over).forEach { gaps.remove(it) }
        return deliver
    }

    /** Whether ids jumped too far to wait for since the last call. */
    fun takeBehind(): Boolean = behind.also { behind = false }

    /** Rows below this may be pruned: the newest row stays, and so does anything still waited for. */
    fun pruneBelow(): Long = gaps.keys.firstOrNull()?.let { minOf(it, high) } ?: high
}
