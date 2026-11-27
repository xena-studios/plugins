package co.xenastudios.core.jvm.db

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.Migrations
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.SqlVersion
import co.xenastudios.core.common.db.Statement
import co.xenastudios.core.common.db.eq
import co.xenastudios.core.common.db.executeAll
import co.xenastudios.core.common.db.values
import co.xenastudios.core.common.family.FamilyBackups
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.jvm.family.JvmFamilyBoard
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The limits of a [JdbcDatabase]'s writer and readers. The defaults are today's plugins'
 * (DECISIONS.md, "xRewind's write path").
 */
public class JdbcLimits(
    /** Most writes in one transaction. */
    public val batch: Int = 1_000,
    /** Writes waiting at which the console is warned that the database is falling behind. */
    public val warnAt: Int = 100_000,
    /** Writes waiting past which new ones are refused ([DbError.Kind.OVERLOADED]) rather than run the server out of memory. */
    public val capacity: Int = 1_000_000,
    /** How long [JdbcDatabase.close] waits for a database that's down before failing what's left. */
    public val closeWaitMillis: Long = 10_000,
    /** The longest pause between tries while the database is down. */
    public val maxBackoffMillis: Long = 30_000,
    /** Reader threads, each with its own connection; `null` for one on SQLite and two on MySQL. */
    public val readers: Int? = null,
) {
    init {
        require(batch >= 1 && warnAt >= 1 && capacity >= warnAt) { "batch and warnAt are positive, capacity at least warnAt" }
    }
}

/**
 * A [Database] on JDBC: SQLite in the plugin's folder (or the family's), or MySQL/MariaDB.
 *
 * **One writer thread, one connection.** Writes are queued ([write]) and the writer runs
 * everything waiting as one transaction, consecutive statements with the same SQL as one JDBC
 * batch (MySQL's `rewriteBatchedStatements` turns those into one multi-row insert). Each
 * transaction also sets this writer's row in `{prefix}meta` to the batch's number, so when a
 * commit's answer is lost (the connection died as it committed) the writer reads that row on a
 * new connection to learn whether it landed, rather than run it twice or report it lost. A batch
 * that fails because the database went away is kept and retried, after a pause that grows to
 * [JdbcLimits.maxBackoffMillis], with one line to the console when it goes and one when it's
 * back. A batch the database refuses is split, and only the write at fault fails.
 *
 * **Readers beside it**, on threads of their own, each keeping one connection: one on SQLite,
 * whose WAL lets a reader and the writer work at once, two on MySQL. That is the whole pool: no
 * HikariCP (about 160 KB, and a pool's checkout and validation for what's a handful of threads
 * that each own one connection). A reader idle for a minute closes its connection and ends.
 *
 * Open it with [open], off the server's threads.
 */
public class JdbcDatabase internal constructor(
    override val dialect: Dialect,
    prefix: String,
    override val description: String,
    private val connector: () -> Connection,
    private val log: Log,
    private val limits: JdbcLimits,
    name: String,
    /**
     * Where the data is, for the family's backups ([FamilyBackups]): the SQLite file's absolute
     * path, or MySQL's `host:port/database`; `null` to stay off the board (tests).
     */
    private val where: String? = null,
) : Database {
    override val sql: Sql = Sql(dialect, prefix)
    private val prefix = prefix

    private val queue = LinkedBlockingQueue<Job>()
    private val dropped = AtomicLong()
    private val marker = "writer:" + randomId()

    @Volatile
    private var accepting = true

    @Volatile
    private var warned = false

    private val closed = newPending<Unit>()
    private val threadName = name
    private val writer = Thread(Runner(null), "$name database writer").apply { isDaemon = true }
    private val readerConnection = ThreadLocal<ReaderConnection>()
    // Every reader connection still open, so close() can be sure none is left: a reader thread
    // closes its own as it exits, which can be after close() would otherwise have answered,
    // and on SQLite an open connection keeps the -wal and -shm files.
    private val openReaders: MutableSet<ReaderConnection> = ConcurrentHashMap.newKeySet()
    private val readerThreads = limits.readers ?: if (dialect.shared) 2 else 1
    // The readers' thread factory, and the handle a backup calls ([FamilyBackups.HANDLE]): one
    // object, so being on the board costs no class at startup.
    private val factory = Runner(null)
    private val readers = ThreadPoolExecutor(
        readerThreads, readerThreads, 60, TimeUnit.SECONDS, LinkedBlockingQueue(), factory,
    ).apply { allowCoreThreadTimeOut(true) }

    // The entry this database put on the family's board, and the board, to take it off on close.
    private var registered: Array<Any?>? = null
    private var registeredOn: JvmFamilyBoard? = null

    /**
     * The writer's thread ([task] `null`), each reader thread (running [task], then closing its
     * connection), the readers' thread factory and the family's backup handle, as one class: a
     * lambda each would be a class each, made as every plugin with a database starts
     * (decisions/2026-11-25-a-lighter-core.md). The handle's work is [DatabaseSnapshot]'s, loaded
     * only when a backup asks.
     */
    private inner class Runner(private val task: Runnable?) : Runnable, ThreadFactory, java.util.function.Function<Any?, Any?> {
        override fun run() {
            if (task == null) return runWriter()
            try {
                task.run()
            } finally {
                readerConnection.get()?.close()
                readerConnection.remove()
            }
        }

        override fun newThread(task: Runnable): Thread = Thread(Runner(task), "$threadName database reader").apply { isDaemon = true }

        override fun apply(request: Any?): Any? = DatabaseSnapshot.answer(this@JdbcDatabase, where, request)
    }

    /** One [read]'s work, on a reader thread. */
    private inner class Read<T>(private val work: (SqlSession) -> Outcome<T, DbError>, private val done: Pending<Outcome<T, DbError>>) : Runnable {
        override fun run() {
            done.resolve(runRead(work))
        }
    }

    // The writer's own: only its thread touches these.
    private var connection: JdbcSession? = null
    private var sequence = 0L
    private var down: DbError? = null
    private var backoff = 0L
    private var deadline = Long.MAX_VALUE

    /** How many writes are waiting for the writer. */
    public val waiting: Int get() = queue.size

    /** How many writes were refused because too many were waiting, since it opened. */
    public val refused: Long get() = dropped.get()

    override val available: Boolean get() = down == null

    override fun write(statements: List<Statement>): Pending<Outcome<Unit, DbError>> {
        val done = newPending<Outcome<Unit, DbError>>()
        enqueue(Write(statements, done))
        return done
    }

    override fun <T> transaction(work: (SqlSession) -> Outcome<T, DbError>): Pending<Outcome<T, DbError>> {
        val done = newPending<Outcome<T, DbError>>()
        enqueue(Work(work, done, transactional = true))
        return done
    }

    override fun <T> session(work: (SqlSession) -> Outcome<T, DbError>): Pending<Outcome<T, DbError>> {
        val done = newPending<Outcome<T, DbError>>()
        enqueue(Work(work, done, transactional = false))
        return done
    }

    override fun <T> read(work: (SqlSession) -> Outcome<T, DbError>): Pending<Outcome<T, DbError>> {
        val done = newPending<Outcome<T, DbError>>()
        if (!accepting) return pendingOf(Outcome.Err(CLOSED))
        try {
            readers.execute(Read(work, done))
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            done.resolve(Outcome.Err(CLOSED))
        }
        return done
    }

    override fun close(): Pending<Unit> {
        synchronized(this) {
            if (accepting) {
                accepting = false
                queue.add(Stop)
                readers.shutdown()
            }
        }
        return closed
    }

    internal fun start(): JdbcDatabase = apply {
        writer.start()
        register()
    }

    /**
     * Puts this database on the family's board for backups ([FamilyBackups]), if the platform
     * found a board ([JvmFamilyBoard.server]) and it knows [where] it is. No class of its own: an
     * array and the board's map, which the player cache has already loaded.
     */
    private fun register() {
        val at = where ?: return
        val board = JvmFamilyBoard.server ?: return
        val sqlite = dialect is Dialect.Sqlite
        val entry = arrayOfNulls<Any>(FamilyBackups.SIZE)
        entry[FamilyBackups.KIND] = if (sqlite) FamilyBackups.SQLITE else FamilyBackups.MYSQL
        entry[FamilyBackups.PLUGIN] = threadName
        entry[FamilyBackups.WHERE] = at
        entry[FamilyBackups.DESCRIPTION] = description
        entry[FamilyBackups.PREFIX] = prefix
        entry[FamilyBackups.HANDLE] = if (sqlite) factory else null
        board.map(FamilyBackups.MAP).put("$threadName $at", entry)
        registered = entry
        registeredOn = board
    }

    /** Takes this database's entry off the board, as it closes; another plugin's on the same file stays. */
    private fun unregister() {
        val entry = registered ?: return
        registeredOn?.map(FamilyBackups.MAP)?.remove("$threadName $where", entry)
        registered = null
    }

    private fun enqueue(job: Job) {
        if (!accepting) return job.fail(CLOSED)
        val size = queue.size
        if (size >= limits.capacity) {
            if (dropped.getAndIncrement() == 0L || !warned) {
                warned = true
                log.warn("$size writes are waiting for $description, so new ones are refused until it catches up.")
            }
            return job.fail(DbError(DbError.Kind.OVERLOADED, "$size writes are already waiting for $description"))
        }
        if (size + 1 >= limits.warnAt && !warned) {
            warned = true
            log.warn("${size + 1} writes are waiting for $description. Is it down, or too slow for this server?")
        }
        queue.add(job)
    }

    private fun runWriter() {
        try {
            while (true) {
                val job = queue.take()
                if (job === Stop) break
                if (warned && queue.size < limits.warnAt / 2) warned = false
                val jobs = arrayListOf(job)
                if (job is Write) {
                    while (jobs.size < limits.batch) {
                        jobs += queue.peek() as? Write ?: break
                        queue.poll()
                    }
                }
                try {
                    when (job) {
                        is Write -> runBatch(jobs.map { it as Write })
                        is Work<*> -> runWork(job)
                        else -> Unit
                    }
                } catch (e: RuntimeException) {
                    // A bug of ours: say so, and keep the writer for everything else.
                    log.error("The database writer failed", e)
                    jobs.forEach { it.fail(DbError(DbError.Kind.OTHER, "the writer failed: $e")) }
                    connection?.let { closeQuietly(it.connection) }
                    connection = null
                }
            }
        } finally {
            var left = queue.poll()
            while (left != null) {
                left.fail(CLOSED)
                left = queue.poll()
            }
            // The readers stopped taking work when close() shut their pool down; wait for any
            // read still running, then close what their threads haven't yet. Before the
            // writer's own connection: on SQLite the last connection to close checkpoints and
            // removes the -wal and -shm files, which a reader (query_only) can't.
            try {
                readers.awaitTermination(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            // A copy through toTypedArray, which a concurrent set makes safely: toList() asks the
            // size, then the iterator, so a reader closing itself between the two (its thread
            // ending as the pool shuts down) made it throw, the writer thread died, and close()
            // never answered.
            openReaders.toTypedArray().forEach { it.close() }
            connection?.let { session ->
                try {
                    session.execute(sql.delete(Migrations.Meta, Migrations.Meta.key eq marker))
                    Unit
                } finally {
                    closeQuietly(session.connection)
                }
            }
            connection = null
            unregister()
            closed.resolve(Unit)
        }
    }

    /** Commits [batch] as one transaction, or settles each write with why it can't be. */
    private fun runBatch(batch: List<Write>) {
        var busy = 0
        while (true) {
            val session = writerSession() ?: if (stopping()) return batch.forEach { it.fail(CLOSED) } else continue
            val number = ++sequence
            val error = commit(session, number) { session.executeBatched(batch.flatMap { it.statements }) }
            if (error == null) {
                batch.forEach { it.done.resolve(OK) }
                return
            }
            when {
                error === LANDED -> {
                    batch.forEach { it.done.resolve(OK) }
                    return
                }
                lost(session, error) -> continue
                error.kind == DbError.Kind.BUSY && ++busy < 5 -> pause(50L * busy)
                batch.size == 1 -> {
                    log.warn("$description refused a write, which is dropped: ${error.detail}")
                    batch.single().fail(error)
                    return
                }
                // The database refused something in the batch: find which, and keep the rest.
                else -> return batch.forEach { runBatch(listOf(it)) }
            }
        }
    }

    private fun <T> runWork(job: Work<T>) {
        var busy = 0
        while (true) {
            val session = writerSession() ?: if (stopping()) return job.fail(CLOSED) else continue
            if (!job.transactional) {
                val result = JdbcSession.contain { job.work(session) }
                if (result is Outcome.Err && result.error.kind == DbError.Kind.UNAVAILABLE && lost(session, result.error)) continue
                job.done.resolve(result)
                return
            }
            val number = ++sequence
            var result: Outcome<T, DbError>? = null
            val error = commit(session, number) {
                val ran = JdbcSession.contain { job.work(session) }
                result = ran
                if (ran is Outcome.Err) throw Refused(ran.error)
            }
            when {
                error == null || error === LANDED -> {
                    job.done.resolve(result!!)
                    return
                }
                lost(session, error) -> continue
                error.kind == DbError.Kind.BUSY && ++busy < 5 -> pause(50L * busy)
                else -> {
                    job.done.resolve(Outcome.Err(error))
                    return
                }
            }
        }
    }

    /**
     * Runs [work] and this writer's marker update in one transaction and commits it. Returns
     * `null` if it committed, [LANDED] if the commit's answer was lost but the marker shows it
     * landed, or why it didn't.
     */
    private inline fun commit(session: JdbcSession, number: Long, work: () -> Unit): DbError? {
        val connection = session.connection
        var committing = false
        try {
            connection.autoCommit = false
            work()
            val marked = session.executeAll(markerRow(number.toString()))
            if (marked is Outcome.Err) return marked.error.also { session.rollbackQuietly() }
            committing = true
            connection.commit()
            connection.autoCommit = true
            return null
        } catch (e: Refused) {
            session.rollbackQuietly()
            restoreAutoCommit(connection)
            return e.error
        } catch (e: SQLException) {
            val error = JdbcSession.errorOf(e, dialect)
            session.rollbackQuietly()
            restoreAutoCommit(connection)
            if (committing) {
                // The commit was sent, and its answer lost: the marker says whether it landed.
                closeQuietly(connection)
                this.connection = null
                return if (landed(number)) LANDED else error.copy(kind = DbError.Kind.UNAVAILABLE)
            }
            return error
        }
    }

    /** Whether batch [number] committed, read on a new connection; waits for the database as long as it takes, or until closing. */
    private fun landed(number: Long): Boolean {
        while (true) {
            val session = writerSession() ?: if (stopping()) return false else continue
            when (val read = session.query(markerQuery())) {
                is Outcome.Ok -> return read.value.firstOrNull()?.get(Migrations.Meta.value) == number.toString()
                is Outcome.Err -> if (!lost(session, read.error)) return false
            }
        }
    }

    /**
     * This writer's row in the meta table, read to learn whether a commit landed. On MySQL a
     * locking read (`LOCK IN SHARE MODE`): a commit the server is still making when the answer
     * was lost (an fsync stall, semi-sync replication) holds the row it changed, so the read
     * waits for it rather than see the old number and run the batch a second time. A plain read
     * would take InnoDB's snapshot, from before that commit. SQLite's file lock does the same.
     */
    internal fun markerQuery(): Statement {
        val select = sql.select(Migrations.Meta, Migrations.Meta.key eq marker, listOf(Migrations.Meta.value))
        return if (dialect == Dialect.MySql) Statement(select.sql + " LOCK IN SHARE MODE", select.args) else select
    }

    /**
     * Whether [error] means the connection is gone, rather than the statement refused. If so,
     * it's closed, the database is marked down, and the writer pauses before the next try.
     */
    private fun lost(session: JdbcSession, error: DbError): Boolean {
        val gone = error.kind == DbError.Kind.UNAVAILABLE || !valid(session.connection)
        if (!gone) return false
        closeQuietly(session.connection)
        connection = null
        wentDown(error)
        return true
    }

    /** The writer's connection, opened if it has none; `null` (after a pause) if the database can't be reached. */
    private fun writerSession(): JdbcSession? {
        connection?.let { return it }
        if (stopping()) return null
        return try {
            val opened = JdbcSession(connector(), dialect)
            connection = opened
            down?.let { log.info("$description is back; ${queue.size + 1} writes waiting are going through.") }
            down = null
            backoff = 0
            opened
        } catch (e: SQLException) {
            wentDown(JdbcSession.errorOf(e, dialect).copy(kind = DbError.Kind.UNAVAILABLE))
            null
        }
    }

    /**
     * Marks the database down and pauses before the next try: not at all the first time (a
     * connection MySQL closed, or a restart, usually reconnects at once), then from 250 ms,
     * doubling to [JdbcLimits.maxBackoffMillis].
     */
    private fun wentDown(error: DbError) {
        if (down == null) {
            log.warn("Lost $description (${error.detail}). Writes wait and are tried again until it's back.")
        }
        down = error
        if (backoff > 0) pause(backoff)
        backoff = if (backoff == 0L) minOf(250, limits.maxBackoffMillis) else minOf(backoff * 2, limits.maxBackoffMillis)
    }

    /** Whether the writer should give up on the database: closing, and past the wait. */
    private fun stopping(): Boolean {
        if (accepting) return false
        if (deadline == Long.MAX_VALUE) deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.closeWaitMillis)
        return down != null && System.nanoTime() > deadline
    }

    private fun pause(millis: Long) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        while (System.nanoTime() < until) {
            if (!accepting && down != null && stopping()) return
            try {
                Thread.sleep(minOf(50, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime()) + 1))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** This writer's row in the meta table, set to [value]: the number of the batch being committed. */
    private fun markerRow(value: String): List<Statement> = sql.upsert(values(Migrations.Meta) {
        it[Migrations.Meta.key] = marker
        it[Migrations.Meta.value] = value
    })

    private fun <T> runRead(work: (SqlSession) -> Outcome<T, DbError>): Outcome<T, DbError> {
        repeat(2) { attempt ->
            val reader = try {
                readerConnection.get()?.takeIf { it.usable() } ?: ReaderConnection(connector(), dialect).also { readerConnection.set(it) }
            } catch (e: SQLException) {
                return Outcome.Err(JdbcSession.errorOf(e, dialect).copy(kind = DbError.Kind.UNAVAILABLE))
            }
            val result = JdbcSession.contain { work(reader.session) }
            reader.used()
            if (result is Outcome.Err && attempt == 0 && (result.error.kind == DbError.Kind.UNAVAILABLE || !valid(reader.session.connection))) {
                reader.close()
                readerConnection.remove()
                return@repeat
            }
            return result
        }
        return Outcome.Err(DbError(DbError.Kind.UNAVAILABLE, "$description couldn't be read"))
    }

    /** A reader thread's connection, checked before use once it's been idle a while (MySQL closes idle ones). */
    private inner class ReaderConnection(connection: Connection, dialect: Dialect) {
        val session = JdbcSession(connection, dialect)
        private var lastUsed = System.nanoTime()

        init {
            openReaders += this
            // Readers never write: on SQLite, say so, so a write by mistake fails here (3.8+).
            if (dialect is Dialect.Sqlite) {
                try {
                    connection.createStatement().use { it.execute("PRAGMA query_only = 1") }
                } catch (e: SQLException) {
                    // Older SQLite: the convention will have to do.
                }
            }
        }

        fun usable(): Boolean =
            System.nanoTime() - lastUsed < TimeUnit.SECONDS.toNanos(30) || valid(session.connection).also { if (!it) close() }

        fun used() {
            lastUsed = System.nanoTime()
        }

        fun close() {
            openReaders -= this
            closeQuietly(session.connection)
        }
    }

    private sealed class Job {
        abstract fun fail(error: DbError)
    }

    private class Write(val statements: List<Statement>, val done: Pending<Outcome<Unit, DbError>>) : Job() {
        override fun fail(error: DbError) {
            done.resolve(Outcome.Err(error))
        }
    }

    private class Work<T>(
        val work: (SqlSession) -> Outcome<T, DbError>,
        val done: Pending<Outcome<T, DbError>>,
        val transactional: Boolean,
    ) : Job() {
        override fun fail(error: DbError) {
            done.resolve(Outcome.Err(error))
        }
    }

    private object Stop : Job() {
        override fun fail(error: DbError) = Unit
    }

    /** Work that returned an error, carried out of the transaction to roll it back. */
    private class Refused(val error: DbError) : RuntimeException(null, null, false, false)

    public companion object {
        private val OK: Outcome<Unit, DbError> = Outcome.Ok(Unit)
        private val CLOSED = DbError(DbError.Kind.CLOSED, "the database is closed")
        private val LANDED = DbError(DbError.Kind.OTHER, "committed")

        /**
         * Opens the database [settings] describe, checks it can be reached, sets SQLite up for
         * one writer and many readers, and starts the writer. Waits for the database: call it off
         * the server's threads. Nothing is migrated: that's the plugin's [Migrations], next.
         *
         * @param sqliteFile where the SQLite file is, for [DatabaseSettings.Type.SQLITE]: the
         *     plugin's folder, or `plugins/Xena/` for the family's shared one
         * @param name the plugin's name, for its threads
         */
        @JvmStatic
        @JvmOverloads
        public fun open(
            settings: DatabaseSettings,
            sqliteFile: Path,
            drivers: Drivers,
            log: Log,
            name: String = "Xena",
            limits: JdbcLimits = JdbcLimits(),
        ): Outcome<JdbcDatabase, DbError> = openWith(settings, sqliteFile, drivers, log, name, limits, Dialect.Sqlite.MINIMUM)

        /**
         * [open], with [wrap] around the connector: for tests that break connections on cue
         * (a plugin's own crash and outage tests), and [sqliteMinimum] in place of
         * [Dialect.Sqlite.MINIMUM]. Not for plugins' code.
         */
        @JvmStatic
        public fun openWith(
            settings: DatabaseSettings,
            sqliteFile: Path,
            drivers: Drivers,
            log: Log,
            name: String,
            limits: JdbcLimits,
            sqliteMinimum: SqlVersion,
            wrap: (() -> Connection) -> () -> Connection = Unwrapped,
        ): Outcome<JdbcDatabase, DbError> {
            if (!Sql.validPrefix(settings.prefix)) {
                return Outcome.Err(DbError(DbError.Kind.BAD_STATEMENT, "storage.table-prefix can only use lowercase letters, digits and _"))
            }
            // The config's pool.readers, when it says, over the limits' own.
            val pooled = settings.readers?.let {
                JdbcLimits(limits.batch, limits.warnAt, limits.capacity, limits.closeWaitMillis, limits.maxBackoffMillis, it)
            } ?: limits
            return when (settings.type) {
                DatabaseSettings.Type.SQLITE -> openSqlite(settings, sqliteFile, drivers, log, name, pooled, sqliteMinimum, wrap)
                DatabaseSettings.Type.MYSQL -> openMySql(settings, drivers, log, name, pooled, wrap)
            }
        }

        private fun openSqlite(
            settings: DatabaseSettings, file: Path, drivers: Drivers, log: Log, name: String, limits: JdbcLimits,
            minimum: SqlVersion, wrap: (() -> Connection) -> () -> Connection,
        ): Outcome<JdbcDatabase, DbError> {
            try {
                Files.createDirectories(file.toAbsolutePath().parent)
            } catch (e: java.io.IOException) {
                return Outcome.Err(DbError(DbError.Kind.UNAVAILABLE, "couldn't make the folder for $file: $e"))
            }
            val url = "jdbc:sqlite:" + file.toAbsolutePath()
            val reasons = ArrayList<String>()
            for (candidate in drivers.sqlite()) {
                val found = when (candidate) {
                    is Outcome.Ok -> candidate.value
                    is Outcome.Err -> {
                        reasons += candidate.error.detail
                        continue
                    }
                }
                val connector = wrap(Connector(found.driver, url, null))
                val version = try {
                    connector().use { connection ->
                        val version = connection.createStatement().use { s ->
                            s.executeQuery("SELECT sqlite_version()").use { if (it.next()) SqlVersion.parse(it.getString(1)) else null }
                        } ?: SqlVersion(0, 0, 0)
                        if (version >= minimum) {
                            connection.createStatement().use {
                                // Lets purges give space back bit by bit. Only takes effect on a new
                                // file, and only before the switch to WAL writes its header.
                                it.execute("PRAGMA auto_vacuum = INCREMENTAL")
                                // Readers don't wait for the writer, nor the writer for readers.
                                it.execute("PRAGMA journal_mode = WAL")
                            }
                        }
                        version
                    }
                } catch (e: SQLException) {
                    return Outcome.Err(DbError(DbError.Kind.UNAVAILABLE, "couldn't open the SQLite database ${file.fileName}: ${e.message}"))
                }
                if (version < minimum) {
                    reasons += "${found.source} SQLite is $version, older than $minimum"
                    continue
                }
                val database = JdbcDatabase(Dialect.Sqlite(version), settings.prefix, "the SQLite database ${file.fileName}",
                    connector, log, limits, name, file.toAbsolutePath().normalize().toString())
                return prepare(database, "SQLite $version (${found.source} driver)", log)
            }
            return Outcome.Err(DbError(DbError.Kind.NO_DRIVER, "no SQLite driver could be used" +
                (if (reasons.isEmpty()) "; this server has none, so install sqlite-jdbc ${DriverLibrary.SQLITE.coordinates.substringAfterLast(':')} or newer" else ": " + reasons.joinToString("; "))))
        }

        private fun openMySql(
            settings: DatabaseSettings, drivers: Drivers, log: Log, name: String, limits: JdbcLimits,
            wrap: (() -> Connection) -> () -> Connection,
        ): Outcome<JdbcDatabase, DbError> {
            val mysql = settings.mysql
            val extra = mysql.properties.trim()
            val url = "jdbc:mysql://${mysql.host}:${mysql.port}/${mysql.database}" +
                // Batched inserts as one statement: many times faster for a writer.
                "?rewriteBatchedStatements=true&characterEncoding=utf8" + (if (extra.isEmpty()) "" else "&$extra")
            val properties = Properties()
            properties.setProperty("user", mysql.username)
            properties.setProperty("password", mysql.password)
            // Without these a connection whose packets are dropped waits forever, and so does the
            // writer. Ten seconds to connect; five minutes for one reply, since purges run long.
            // An owner's own value in storage.mysql.properties wins.
            defaultTo(properties, extra, "connectTimeout", "10000")
            defaultTo(properties, extra, "socketTimeout", "300000")
            val description = "MySQL at ${mysql.host}:${mysql.port}/${mysql.database}"
            val reasons = ArrayList<String>()
            for (candidate in drivers.mysql()) {
                val found = when (candidate) {
                    is Outcome.Ok -> candidate.value
                    is Outcome.Err -> {
                        reasons += candidate.error.detail
                        continue
                    }
                }
                val connector = wrap(Connector(found.driver, url, properties))
                try {
                    connector().close()
                } catch (e: SQLException) {
                    return Outcome.Err(DbError(DbError.Kind.UNAVAILABLE, "couldn't connect to $description: ${e.message}"))
                }
                return prepare(JdbcDatabase(Dialect.MySql, settings.prefix, description, connector, log, limits, name,
                    "${mysql.host}:${mysql.port}/${mysql.database}"),
                    "MySQL (${found.source} driver)", log)
            }
            return Outcome.Err(DbError(DbError.Kind.NO_DRIVER, "no MySQL driver could be used" +
                (if (reasons.isEmpty()) "; this server has none, so install mysql-connector-j" else ": " + reasons.joinToString("; "))))
        }

        // The connections' maker, and openWith's default wrap, as classes rather than lambdas
        // (see Runner).
        private object Unwrapped : (() -> Connection) -> () -> Connection {
            override fun invoke(connect: () -> Connection): () -> Connection = connect
        }

        /** Connects to [url]: SQLite's way without [properties], MySQL's with them. */
        private class Connector(private val driver: Driver, private val url: String, private val properties: Properties?) : () -> Connection {
            override fun invoke(): Connection = if (properties == null) sqliteConnection(driver, url)
            else driver.connect(url, properties) ?: throw SQLException("the MySQL driver doesn't accept $url")
        }

        private fun sqliteConnection(driver: Driver, url: String): Connection {
            val connection = driver.connect(url, Properties()) ?: throw SQLException("the SQLite driver doesn't accept $url")
            try {
                connection.createStatement().use {
                    // Wait up to 5 seconds for another writer (the family sharing one file).
                    it.execute("PRAGMA busy_timeout = 5000")
                    // Safe with WAL: a power cut can lose the last moments, never corrupt the file.
                    it.execute("PRAGMA synchronous = NORMAL")
                }
            } catch (e: SQLException) {
                closeQuietly(connection)
                throw e
            }
            return connection
        }

        /** Makes the meta table and this writer's row in it, then starts the writer. */
        internal fun prepare(database: JdbcDatabase, what: String, log: Log): Outcome<JdbcDatabase, DbError> {
            val ready = try {
                database.connector().use { connection ->
                    val session = JdbcSession(connection, database.dialect)
                    for (statement in database.sql.createTable(Migrations.Meta)) {
                        val made = session.execute(statement)
                        if (made is Outcome.Err && made.error.kind != DbError.Kind.ALREADY_EXISTS) return@use made
                    }
                    session.executeAll(database.markerRow("0"))
                }
            } catch (e: SQLException) {
                Outcome.Err(JdbcSession.errorOf(e, database.dialect))
            }
            if (ready is Outcome.Err) return ready
            log.info("Using ${database.description}: $what.")
            return Outcome.Ok(database.start())
        }

        /** Sets [key] unless the owner's extra connection settings already do. */
        internal fun defaultTo(properties: Properties, owner: String, key: String, value: String) {
            if (owner.split('&').none { it.trim().startsWith("$key=", ignoreCase = true) }) properties.setProperty(key, value)
        }

        /**
         * Whether [connection] still works. Drivers older than JDBC 4, such as the SQLite driver
         * Spigot 1.8.8 bundles, have no `isValid`; they're asked with a `SELECT 1` instead.
         */
        internal fun valid(connection: Connection): Boolean = try {
            !connection.isClosed && connection.isValid(2)
        } catch (e: SQLException) {
            false
        } catch (e: AbstractMethodError) {
            answers(connection)
        }

        private fun answers(connection: Connection): Boolean = try {
            val statement = connection.createStatement()
            try {
                statement.execute("SELECT 1")
            } finally {
                statement.close()
            }
            true
        } catch (e: SQLException) {
            false
        }

        private fun restoreAutoCommit(connection: Connection) {
            try {
                if (!connection.isClosed) connection.autoCommit = true
            } catch (e: SQLException) {
                // A broken connection; it's replaced.
            }
        }

        private fun closeQuietly(connection: Connection) {
            try {
                connection.close()
            } catch (e: SQLException) {
                // Closing a broken connection: nothing to do.
            }
        }

        private fun randomId(): String {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }
    }
}
