package co.xenastudios.core.common.admin

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import co.xenastudios.core.common.scheduler.async
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One page of records an [Importer] read, and where the next page starts ([next], `null` after
 * the last). The cursor is the importer's own: a row id, an offset, a file and line.
 */
public class ImportPage<R : Any>(public val records: List<R>, public val next: String?)

/**
 * Reads another plugin's data and writes it as this plugin's: `/xbans import litebans`. A
 * plugin registers one per source with its [Imports]; the core runs it, page by page, and
 * reports progress, counts and the records that failed.
 *
 * - **Idempotent.** [write] adds or replaces ([co.xenastudios.core.common.db.Sql.upsert],
 *   `insertIgnore`), never adds a second copy, so a page written twice (the run stopped after
 *   writing it and before its cursor was saved) changes nothing, and running a finished import
 *   again leaves the data as it was.
 * - **Resumable.** After each page is written, its [ImportPage.next] cursor is saved in the
 *   plugin's folder; a run that stops (the server stops, the other plugin's database goes
 *   away) carries on from there the next time it's started.
 * - **Errors are per record.** [write] fails one record with a reason, and the run goes on;
 *   only a page that can't be read stops it.
 *
 * Every function is called on an async task, one at a time, and answers through a [Pending],
 * so it can read a file or a database there.
 */
public abstract class Importer<R : Any>(
    /** The word `/<plugin> import` takes: `litebans`. Lower-case a-z, 0-9, - and _. */
    public val id: String,
    /** The other plugin's name, as messages show it: `LiteBans`. */
    public val displayName: String,
) {
    init {
        require(ID.matches(id)) { "importer id \"$id\": lower-case a-z, 0-9, - and _" }
    }

    /** Whether the other plugin's data is there to import from. */
    public abstract fun detect(): Pending<Boolean>

    /** How many records there are in all, for progress and the dry run; or why they can't be counted. */
    public abstract fun count(): Pending<Outcome<Long, String>>

    /** Up to [limit] records after [cursor] (`null` for the first page), or why they can't be read. */
    public abstract fun read(cursor: String?, limit: Int): Pending<Outcome<ImportPage<R>, String>>

    /** [record] in a few words, for a failure: `LiteBans ban #12 of Notch`. */
    public abstract fun describe(record: R): String

    /** Why [record] would be skipped (a ban that has expired, an unknown player), or `null` to import it. */
    public open fun skip(record: R): String? = null

    /** Writes one record as this plugin's, adding or replacing it; or says why it can't. */
    public abstract fun write(record: R): Pending<Outcome<Unit, String>>

    override fun toString(): String = id

    private companion object {
        val ID = Regex("[a-z0-9][a-z0-9_-]*")
    }
}

/** What an import did, or a dry run would. */
public data class ImportCounts(
    /** Records read. */
    public val read: Long,
    /** Written, or that would be. */
    public val imported: Long,
    /** Skipped on purpose ([Importer.skip]). */
    public val skipped: Long,
    /** That couldn't be written. */
    public val failed: Long,
)

/** Told how an import is going, from whichever thread it's on. */
public interface ImportListener {
    /** The source has no data here; nothing ran. */
    public fun notFound()

    /** A run (not a dry run) starts, or carries on after [done] records were already handled before. */
    public fun started(total: Long, done: Long, resumed: Boolean)

    /** [done] of [total] records handled, every few seconds. */
    public fun progress(done: Long, total: Long)

    /** [record] couldn't be imported, for [reason]; the run goes on. Only the first few are told; the console has every one. */
    public fun recordFailed(record: String, reason: String)

    /** It finished: [counts] of everything, a dry run's included. */
    public fun finished(counts: ImportCounts, dryRun: Boolean)

    /** It stopped, for [reason]; a run carries on from where it was when it's started again. */
    public fun stopped(reason: String)
}

/**
 * A plugin's importers, and the one import running at a time. `/<plugin> import <source>`
 * completes [ids] and calls [start].
 */
public class Imports(
    private val platform: Platform,
    importers: List<Importer<*>>,
    /** Records per page, each page one round of writes and one saved cursor. */
    private val pageSize: Int = 500,
    /** How often progress is told. */
    private val progressEvery: Duration = 5.seconds,
) {
    private val byId: Map<String, Importer<*>> = importers.associateBy { it.id }
    private val lock = Any()
    private var running: String? = null

    init {
        require(importers.isNotEmpty()) { "an import needs importers" }
        require(byId.size == importers.size) { "two importers with the same id" }
        require(pageSize >= 1) { "a page has at least one record" }
    }

    /** The sources, by [Importer.id], in the order they were given. */
    public val ids: List<String> = importers.map { it.id }

    /** The importer for [id], or `null`. */
    public operator fun get(id: String): Importer<*>? = byId[id]

    /** Whether an import is running. */
    public val busy: Boolean get() = locked(lock) { running != null }

    /**
     * Starts importing from [id] (a dry run counts, changing nothing), on an async task, and
     * tells [listener] how it goes. Returns `false` without starting if an import is running.
     */
    public fun start(id: String, dryRun: Boolean, listener: ImportListener): Boolean {
        val importer = byId[id] ?: throw IllegalArgumentException("no importer $id")
        val claimed = locked(lock) {
            if (running != null) false else {
                running = id
                true
            }
        }
        if (!claimed) return false
        platform.scheduler.async {
            Run(importer, dryRun, listener).begin()
        }
        return true
    }

    private fun checkpoint(importer: Importer<*>): StoragePath = StoragePath.of("imports/${importer.id}.txt")

    private inner class Run<R : Any>(val importer: Importer<R>, val dryRun: Boolean, val listener: ImportListener) {
        var total = 0L
        var read = 0L
        var imported = 0L
        var skipped = 0L
        var failed = 0L
        var told = 0
        var lastProgress = platform.clock.elapsed()

        fun begin() {
            attempt {
                importer.detect().onSettled { found, error ->
                    async {
                        when {
                            error != null -> stop("couldn't look for ${importer.displayName}'s data: $error")
                            found != true -> finish { listener.notFound() }
                            else -> count()
                        }
                    }
                }
            }
        }

        fun count() {
            importer.count().onSettled { counted, error ->
                async {
                    when {
                        error != null -> stop("couldn't count the records: $error")
                        counted is Outcome.Err -> stop(counted.error)
                        else -> {
                            total = (counted as Outcome.Ok).value
                            val saved = if (dryRun) null else loadCheckpoint()
                            if (saved != null) {
                                read = saved.read
                                imported = saved.imported
                                skipped = saved.skipped
                                failed = saved.failed
                            }
                            if (!dryRun) listener.started(total, read, saved != null)
                            page(saved?.cursor)
                        }
                    }
                }
            }
        }

        fun page(cursor: String?) {
            importer.read(cursor, pageSize).onSettled { result, error ->
                async {
                    when {
                        error != null -> stop("couldn't read the records: $error")
                        result is Outcome.Err -> stop(result.error)
                        else -> handle((result as Outcome.Ok).value)
                    }
                }
            }
        }

        fun handle(page: ImportPage<R>) {
            val toWrite = ArrayList<R>()
            for (record in page.records) {
                read++
                if (safe { importer.skip(record) } != null) skipped++ else toWrite += record
            }
            if (dryRun) {
                imported += toWrite.size
                next(page)
                return
            }
            if (toWrite.isEmpty()) {
                saved(page)
                return
            }
            // Every record of the page is written at once (the database's writer batches them),
            // and the cursor is saved only once all have answered.
            val pageLock = Any()
            var left = toWrite.size
            for (record in toWrite) {
                val write = try {
                    importer.write(record)
                } catch (e: Throwable) {
                    if (isFatal(e)) throw e
                    co.xenastudios.core.api.pendingOf(Outcome.Err("it threw ${e::class.simpleName}: ${e.message}"))
                }
                write.onSettled { result, error ->
                    val reason = when {
                        error != null -> error.toString()
                        result is Outcome.Err -> result.error
                        else -> null
                    }
                    val last = locked(pageLock) {
                        if (reason == null) imported++ else failed++
                        left--
                        left == 0
                    }
                    if (reason != null) recordFailed(record, reason)
                    if (last) async { saved(page) }
                }
            }
        }

        fun saved(page: ImportPage<R>) {
            val next = page.next
            if (next != null) {
                when (val written = platform.storage.writeText(checkpoint(importer), Checkpoint(next, read, imported, skipped, failed).write())) {
                    is Outcome.Err -> return stop("couldn't save where it got to: ${written.error.detail}")
                    is Outcome.Ok -> Unit
                }
            }
            next(page)
        }

        fun next(page: ImportPage<R>) {
            val now = platform.clock.elapsed()
            if (now - lastProgress >= progressEvery && page.next != null) {
                lastProgress = now
                listener.progress(read, total)
            }
            if (page.next == null) {
                if (!dryRun) platform.storage.delete(checkpoint(importer))
                val counts = ImportCounts(read, imported, skipped, failed)
                if (!dryRun) platform.log.info("Imported $imported records from ${importer.displayName}: $skipped skipped, $failed failed.")
                finish { listener.finished(counts, dryRun) }
            } else {
                page(page.next)
            }
        }

        fun recordFailed(record: R, reason: String) {
            val described = safe { importer.describe(record) } ?: "a record"
            platform.log.warn("Couldn't import $described from ${importer.displayName}: $reason")
            val tell = locked(lock) { told++ < TOLD_FAILURES }
            if (tell) listener.recordFailed(described, reason)
        }

        fun loadCheckpoint(): Checkpoint? = when (val text = platform.storage.readText(checkpoint(importer))) {
            is Outcome.Ok -> text.value?.let { Checkpoint.read(it) }
            is Outcome.Err -> null
        }

        fun stop(reason: String) {
            platform.log.warn("The import from ${importer.displayName} stopped: $reason")
            finish { listener.stopped(reason) }
        }

        fun finish(tell: () -> Unit) {
            locked(lock) { running = null }
            tell()
        }

        // The importer's callbacks settle on its own threads (a database's); the next step goes
        // back to an async task of the plugin's, so a disabled plugin's import stops there.
        fun async(step: () -> Unit) {
            platform.scheduler.async { attempt(step) }
        }

        fun attempt(step: () -> Unit) {
            try {
                step()
            } catch (e: Throwable) {
                if (isFatal(e)) throw e
                platform.log.error("The import from ${importer.displayName} failed", e)
                finish { listener.stopped("it threw ${e::class.simpleName}: ${e.message}") }
            }
        }
    }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        if (isFatal(e)) throw e
        null
    }

    /** Where a run got to, kept in `imports/<id>.txt` between pages. */
    private class Checkpoint(val cursor: String, val read: Long, val imported: Long, val skipped: Long, val failed: Long) {
        fun write(): String = "cursor: $cursor\nread: $read\nimported: $imported\nskipped: $skipped\nfailed: $failed\n"

        companion object {
            fun read(text: String): Checkpoint? {
                val values = text.lines().filter { ": " in it }.associate { it.substringBefore(": ") to it.substringAfter(": ") }
                return Checkpoint(
                    values["cursor"] ?: return null,
                    values["read"]?.toLongOrNull() ?: 0,
                    values["imported"]?.toLongOrNull() ?: 0,
                    values["skipped"]?.toLongOrNull() ?: 0,
                    values["failed"]?.toLongOrNull() ?: 0,
                )
            }
        }
    }

    private companion object {
        const val TOLD_FAILURES = 5
    }
}
