package co.xenastudios.xrewind

import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandContext
import co.xenastudios.core.common.db.Migrations
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.eq
import co.xenastudios.core.common.db.values
import co.xenastudios.core.common.db.write
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.time.Durations
import co.xenastudios.xrewind.log.StoredTime
import co.xenastudios.xrewind.query.Center
import co.xenastudios.xrewind.query.Params
import co.xenastudios.xrewind.query.Search
import co.xenastudios.xrewind.rollback.Rollbacks
import co.xenastudios.xrewind.store.Purge
import kotlin.time.Duration.Companion.seconds

/** What the commands do, made the first time one runs (not as the server starts). */
internal class Actions(private val plugin: XRewindPlugin) {
    private val texts get() = plugin.texts
    private var importing = false

    fun info(context: CommandContext) {
        if (!context.source.hasPermission(plugin.admin.admin.node)) return texts.reply(context.source, CoreMessages.commandNoPermission)
        texts.reply(context.source, XRewindMessages.info, "version" to plugin.version, "platform" to plugin.platform.name,
            "storage" to (plugin.database?.description ?: texts.word(context.source.player, XRewindMessages.statusNotReady)))
    }

    fun inspect(context: CommandContext) {
        val on = plugin.lookups.inspector.toggle(context.player)
        texts.reply(context.source, if (on) XRewindMessages.inspectorOn else XRewindMessages.inspectorOff)
    }

    private fun center(context: CommandContext): Center? = context.source.player?.let(plugin.server::position)

    private fun parse(context: CommandContext, words: List<String>): Search? = when (val parsed = Params.parse(words, plugin.server::known)) {
        is Outcome.Ok -> parsed.value
        is Outcome.Err -> {
            val reason = texts.render(context.source.player, parsed.error.message, *parsed.error.placeholders)
            texts.reply(context.source, XRewindMessages.invalid, "reason" to (reason ?: parsed.error.message.english))
            null
        }
    }

    fun lookup(context: CommandContext, words: List<String>) {
        val search = parse(context, words) ?: return
        if (search.wantsPrivate && !context.source.hasPermission(plugin.permissions.lookupChat.node)) {
            return texts.reply(context.source, CoreMessages.commandNoPermission)
        }
        plugin.lookups.run(context.source, search, center(context), 1, inspect = false)
    }

    fun page(context: CommandContext, page: Int) = plugin.lookups.page(context.source, page)

    fun near(context: CommandContext) {
        val search = (Params.parse(listOf("r:5", "t:3d"), plugin.server::known) as Outcome.Ok).value
        plugin.lookups.run(context.source, search, center(context), 1, inspect = false)
    }

    fun rollback(context: CommandContext, words: List<String>, restore: Boolean) {
        val search = parse(context, words) ?: return
        plugin.rollbacks.run(context.source, search, center(context), restore)
    }

    fun purge(context: CommandContext, words: List<String>) {
        val search = parse(context, words) ?: return
        val source = context.source
        if (search.seconds == 0L) return texts.reply(source, CoreMessages.commandUsage, "usage" to "/xrewind purge t:<time>")
        if (search.seconds < 86_400) return texts.reply(source, XRewindMessages.purgeTooRecent)
        val db = plugin.database ?: return texts.reply(source, XRewindMessages.notReady)
        texts.reply(source, XRewindMessages.purging)
        plugin.writer.flush(2_000).onSettled { _, _ ->
            val purge = Purge(db, StoredTime.ago(plugin.now(), search.seconds)) { plugin.running }
            purge.run().onSettled { result, error ->
                when {
                    result is Outcome.Ok && purge.interrupted -> {
                        plugin.log.warn("A purge was stopped partway, after deleting ${result.value} changes.")
                        texts.reply(source, XRewindMessages.purgeInterrupted, "count" to result.value)
                    }
                    result is Outcome.Ok -> texts.reply(source, XRewindMessages.purged, "count" to result.value, "time" to Durations.format(search.seconds.seconds))
                    else -> {
                        plugin.log.warn("A purge failed: ${(result as? Outcome.Err)?.error ?: error}")
                        texts.reply(source, CoreMessages.commandError)
                    }
                }
            }
        }
    }

    /** `/xrewind import coreprotect [#again]`: once, unless again, as 1.x recorded it in `{p}meta`. */
    fun import(context: CommandContext, again: Boolean) {
        val source = context.source
        val history = plugin.history ?: return texts.reply(source, XRewindMessages.importNotHere)
        val db = plugin.database ?: return texts.reply(source, XRewindMessages.notReady)
        val claimed = locked(this) { if (importing) false else true.also { importing = true } }
        if (!claimed) return texts.reply(source, XRewindMessages.importRunning)
        val sql: Sql = db.sql
        db.read { it.query(sql.select(Migrations.Meta, Migrations.Meta.key eq IMPORTED, listOf(Migrations.Meta.value))) }.onSettled { read, _ ->
            val before = ((read as? Outcome.Ok)?.value?.firstOrNull())?.get(Migrations.Meta.value)
            if (before != null && !again) {
                locked(this) { importing = false }
                return@onSettled texts.reply(source, XRewindMessages.importedBefore, "date" to before)
            }
            plugin.platform.scheduler.run(Where.Async) { runImport(source, history, db) }
        }
    }

    private fun runImport(source: co.xenastudios.core.common.command.CommandSource, history: HistoryImport, db: co.xenastudios.core.common.db.Database) {
        try {
            val where = when (val found = history.find()) {
                is Outcome.Ok -> found.value
                is Outcome.Err -> return texts.reply(source, XRewindMessages.importFailed, "reason" to found.error)
            }
            texts.reply(source, XRewindMessages.importing, "source" to where)
            // Recorded before anything's read: a second import, even after this one stops partway,
            // would duplicate what came across, so it needs #again.
            db.write(meta(db, "partway, ${today()}"))
            val started = plugin.platform.clock.elapsed()
            val ran = history.run({ plugin.queue.add(it) }, { plugin.queue.waiting }, { plugin.running })
            when (ran) {
                is Outcome.Err -> {
                    plugin.log.warn("The CoreProtect import failed: ${ran.error}")
                    return texts.reply(source, if (plugin.running) XRewindMessages.importFailed else XRewindMessages.importInterrupted, "reason" to ran.error)
                }
                is Outcome.Ok -> {
                    // Done once what it queued is written: this import's rows, not the queue empty.
                    plugin.writer.flush(600_000).onSettled { _, _ ->
                        db.write(meta(db, today()))
                        val seconds = Rollbacks.seconds((plugin.platform.clock.elapsed() - started).inWholeMilliseconds / 1000.0)
                        plugin.log.info("Imported ${ran.value} changes from CoreProtect in ${seconds}s.")
                        texts.reply(source, XRewindMessages.imported, "count" to ran.value, "seconds" to seconds)
                    }
                }
            }
        } finally {
            locked(this) { importing = false }
        }
    }

    private fun meta(db: co.xenastudios.core.common.db.Database, value: String) = db.sql.upsert(values(Migrations.Meta) {
        it[Migrations.Meta.key] = IMPORTED
        it[Migrations.Meta.value] = value
    })

    private fun today(): String = plugin.platform.clock.now().toString().substringBefore('T')

    fun status(context: CommandContext) {
        val source = context.source
        val player = source.player
        val writer = plugin.writer
        val db = plugin.database
        val batch = if (writer.lastBatchSize == 0) texts.word(player, XRewindMessages.statusNoBatch)
        else texts.render(player, XRewindMessages.statusBatch, "count" to writer.lastBatchSize, "ms" to ((writer.lastBatchMillis * 10).toLong() / 10.0).toString())?.plain() ?: ""
        texts.reply(
            source, XRewindMessages.status,
            "storage" to (db?.description ?: plugin.failure ?: texts.word(player, XRewindMessages.statusNotReady)),
            "written" to writer.written, "waiting" to plugin.queue.waiting.toLong(), "missed" to (plugin.queue.missed + writer.dropped),
            "batch" to batch, "problem" to (writer.problem ?: plugin.failure ?: texts.word(player, XRewindMessages.statusNoProblem)),
        )
    }

    companion object {
        /** 1.x's row in `{p}meta`: when CoreProtect's history was imported. */
        const val IMPORTED = "coreprotect-import"
    }
}
