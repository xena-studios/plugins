package co.xenastudios.xbackup

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.command.CommandContext
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.common.text.Component
import co.xenastudios.xbackup.store.RestorePaths
import co.xenastudios.xbackup.store.Sizes
import co.xenastudios.xbackup.store.Snapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What `/backup`'s subcommands do. Every one only queues a job or reads what the engine keeps in
 * memory, so none waits on the disk; replies arrive when the job is done, on the sender's thread.
 * 1.x's replies, in `lang/`.
 */
internal class Actions(private val plugin: XBackupPlugin) {
    private val texts get() = plugin.texts
    private val engine get() = plugin.engine

    private fun say(source: CommandSource, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        plugin.platform.scheduler.now(source.player?.let { Where.on(it) } ?: Where.Global) { texts.reply(source, message, *placeholders) }
    }

    private fun lines(source: CommandSource, parts: List<Component>) {
        plugin.platform.scheduler.now(source.player?.let { Where.on(it) } ?: Where.Global) { for (line in parts) source.reply(line) }
    }

    /** Runs [done] with the job's value, or says why it failed or was skipped. */
    private fun <T> reply(source: CommandSource, job: Pending<T>, done: (T) -> Unit) {
        job.onSettled { value, error ->
            when {
                error is Skipped -> say(source, XBackupMessages.skipped, "reason" to error.reason)
                error != null -> say(source, XBackupMessages.failed, "reason" to describe(error))
                else -> try {
                    @Suppress("UNCHECKED_CAST")
                    done(value as T)
                } catch (e: Exception) {
                    say(source, XBackupMessages.failed, "reason" to describe(e))
                }
            }
        }
    }

    /** The list of backups, read first if it isn't in memory yet (just after a start). */
    private fun whenReady(source: CommandSource, then: () -> Unit) {
        if (engine.ready) return then()
        say(source, XBackupMessages.reading)
        reply(source, engine.whenReady()) { then() }
    }

    private fun ago(source: CommandSource, time: kotlin.time.Instant): Component {
        val since = plugin.now() - time
        return if (since < 60.seconds) texts.part(source.player, XBackupMessages.justNow) else texts.part(source.player, XBackupMessages.ago, "time" to since)
    }

    private fun chunks(source: CommandSource): Component {
        val read = engine.lastChunksRead
        return when (read) {
            0L -> texts.part(source.player, XBackupMessages.chunksNone)
            1L -> texts.part(source.player, XBackupMessages.chunksOne)
            else -> texts.part(source.player, XBackupMessages.chunksMany, "count" to read)
        }
    }

    // A backup by id prefix or latest; null (having said why) if there's no such one.
    private fun find(source: CommandSource, text: String): Snapshot? {
        val list = engine.snapshots
        if (list.isEmpty()) return null.also { say(source, XBackupMessages.noBackups) }
        val query = text.trim().lowercase()
        if (query == "latest" || query == "last") return list.last()
        if (query.length < 4) return null.also { say(source, XBackupMessages.idTooShort) }
        val found = list.filter { it.id?.hex()?.startsWith(query) == true }
        return when (found.size) {
            0 -> null.also { say(source, XBackupMessages.idUnknown, "id" to query) }
            1 -> found[0]
            else -> null.also { say(source, XBackupMessages.idAmbiguous, "id" to query) }
        }
    }

    fun info(context: CommandContext) {
        texts.reply(
            context.source, XBackupMessages.info, "version" to plugin.version, "platform" to plugin.platform.name, "backups" to engine.snapshots.size,
            "size" to Sizes.format(engine.repositoryBytes), "repository" to engine.repositoryPath,
        )
    }

    fun now(context: CommandContext, reason: String?) {
        val source = context.source
        val busy = engine.activity
        if (busy != null) texts.reply(source, XBackupMessages.backingUpAfter, "activity" to busy) else texts.reply(source, XBackupMessages.backingUp)
        reply(source, engine.backup(reason?.trim()?.ifEmpty { null } ?: "manual", false)) { snapshot ->
            say(
                source, XBackupMessages.backedUp, "id" to snapshot.key.shortHex(), "files" to snapshot.files, "size" to Sizes.format(snapshot.bytes),
                "added" to Sizes.format(snapshot.added), "chunks" to chunks(source), "time" to snapshot.durationMillis.milliseconds, "pause" to snapshot.pauseMillis,
            )
            if (snapshot.warnings.isNotEmpty()) say(source, XBackupMessages.backedUpWarnings, "count" to snapshot.warnings.size)
        }
    }

    fun list(context: CommandContext, page: Int?) {
        val source = context.source
        whenReady(source) {
            val snapshots = engine.snapshots
            if (snapshots.isEmpty()) return@whenReady say(source, XBackupMessages.listEmpty)
            val pages = (snapshots.size + PAGE - 1) / PAGE
            val at = (page ?: 1).coerceIn(1, pages)
            val out = ArrayList<Component>()
            out += texts.part(source.player, XBackupMessages.listHeader, "count" to snapshots.size, "size" to Sizes.format(engine.repositoryBytes))
            val from = snapshots.size - 1 - (at - 1) * PAGE
            var i = from
            while (i > from - PAGE && i >= 0) {
                val s = snapshots[i]
                out += texts.part(
                    source.player, XBackupMessages.listLine, "id" to s.key.shortHex(), "time" to Snapshot.iso(s.time), "ago" to ago(source, s.time),
                    "reason" to s.reason, "size" to Sizes.format(s.bytes), "added" to Sizes.format(s.added),
                    "pinned" to (if (engine.pinned(s.key)) texts.part(source.player, XBackupMessages.listPinned) else Component.EMPTY),
                    "warned" to (if (s.warnings.isEmpty()) Component.EMPTY else texts.part(source.player, XBackupMessages.listWarned)),
                )
                i--
            }
            if (at < pages) out += texts.part(source.player, XBackupMessages.listNext, "page" to at, "pages" to pages, "next" to (at + 1).toString())
            lines(source, out)
        }
    }

    fun status(context: CommandContext) {
        val source = context.source
        whenReady(source) {
            val p = source.player
            val snapshots = engine.snapshots
            val out = ArrayList<Component>()
            out += texts.part(p, XBackupMessages.statusRepository, "path" to engine.repositoryPath, "count" to snapshots.size, "size" to Sizes.format(engine.repositoryBytes))
            val last = snapshots.lastOrNull()
            if (last == null) {
                out += texts.part(p, XBackupMessages.statusLastNone)
            } else {
                out += texts.part(p, XBackupMessages.statusLast, "ago" to ago(source, last.time), "files" to last.files, "added" to Sizes.format(last.added), "time" to last.durationMillis.milliseconds)
                out += texts.part(p, XBackupMessages.statusPause, "pause" to last.pauseMillis, "off" to last.autosaveOffMillis.milliseconds)
            }
            val next = engine.nextBackup
            out += if (next == null) texts.part(p, XBackupMessages.statusNextOff) else texts.part(p, XBackupMessages.statusNext, "time" to maxOf(Duration.ZERO, next - plugin.now()) + 1.seconds)
            val activity = engine.activity
            out += if (activity == null) texts.part(p, XBackupMessages.statusRunningNone) else texts.part(p, XBackupMessages.statusRunning, "activity" to activity)
            val check = engine.lastCheck
            out += if (check == null) texts.part(p, XBackupMessages.statusCheckNever) else texts.part(p, XBackupMessages.statusCheck, "ago" to ago(source, check.first), "result" to check.second)
            for ((name, remote) in plugin.settings.remotes) {
                val state: Any = if (!remote.enabled) XBackupMessages.statusRemoteOff else engine.remoteStatus[name] ?: XBackupMessages.statusRemoteNever
                out += texts.part(p, XBackupMessages.statusRemote, "name" to name, "type" to remote.type, "state" to state)
            }
            if (engine.family.isNotEmpty()) out += texts.part(p, XBackupMessages.statusFamily, "list" to engine.family.joinToString(", "))
            if (engine.external.isNotEmpty()) out += texts.part(p, XBackupMessages.statusExternal, "list" to engine.external.joinToString("; "))
            val problem = engine.problem
            out += if (problem == null) texts.part(p, XBackupMessages.statusProblemNone) else texts.part(p, XBackupMessages.statusProblem, "problem" to problem)
            lines(source, out)
            reply(source, engine.pendingRestore()) { pending ->
                if (pending != null) say(source, XBackupMessages.statusRestore, "id" to pending.snapshot.take(8), "count" to pending.paths)
            }
        }
    }

    fun verify(context: CommandContext, full: Boolean) {
        val source = context.source
        texts.reply(source, if (full) XBackupMessages.verifyingAll else XBackupMessages.verifyingSample)
        reply(source, engine.verify(full)) { report ->
            if (report.ok) {
                say(source, XBackupMessages.verifyOk, "checked" to report.packsChecked, "total" to report.packsTotal, "size" to Sizes.format(report.bytesChecked), "backups" to report.snapshots)
            } else {
                say(source, XBackupMessages.verifyProblems, "count" to report.problems.size, "first" to report.problems[0])
            }
        }
    }

    fun restore(context: CommandContext, first: String, rest: List<String>) {
        val source = context.source
        if (first.equals("cancel", ignoreCase = true)) {
            reply(source, engine.cancelRestore()) { cancelled -> say(source, if (cancelled) XBackupMessages.restoreCancelled else XBackupMessages.restoreNoneWaiting) }
            return
        }
        whenReady(source) {
            val snapshot = find(source, first) ?: return@whenReady
            val paths = ArrayList<String>()
            var confirm = false
            for (arg in rest) {
                if (arg.equals("confirm", ignoreCase = true)) {
                    confirm = true
                } else {
                    try {
                        paths += RestorePaths.normalise(arg)
                    } catch (e: IllegalArgumentException) {
                        return@whenReady say(source, XBackupMessages.badPath)
                    }
                }
            }
            if (paths.isEmpty()) paths += ""
            val what: Any = if (paths == listOf("")) XBackupMessages.restoreEverything else paths.joinToString(", ")
            val id = snapshot.key.shortHex()
            if (!confirm) {
                reply(source, engine.plan(snapshot, paths)) { plan ->
                    val out = ArrayList<Component>()
                    out += texts.part(
                        source.player, XBackupMessages.restorePlan, "what" to what, "id" to id, "ago" to ago(source, snapshot.time), "changed" to plan.changed,
                        "chunks" to (if (plan.changedChunks > 0) texts.part(source.player, XBackupMessages.restoreChunks, "count" to plan.changedChunks) else Component.EMPTY),
                        "added" to plan.added, "removed" to plan.removed, "replaced" to engine.replacedFolder,
                    )
                    for (example in plan.examples) out += texts.part(source.player, XBackupMessages.restoreExample, "line" to example)
                    val command = ("/backup restore $id " + paths.joinToString(" ").trim() + " confirm").replace("  ", " ")
                    out += texts.part(source.player, XBackupMessages.restoreButton, "command" to command)
                    lines(source, out)
                }
                return@whenReady
            }
            say(source, XBackupMessages.restoreStaging, "what" to what, "id" to id)
            reply(source, engine.stage(snapshot, paths)) { staged ->
                say(source, XBackupMessages.restoreStaged, "files" to staged.files, "size" to Sizes.format(staged.bytes))
                if (staged.jars.isNotEmpty()) say(source, XBackupMessages.restoreJars, "count" to staged.jars.size, "first" to (staged.jars[0] + if (staged.jars.size > 1) ", ..." else ""))
            }
        }
    }

    fun prune(context: CommandContext) {
        val source = context.source
        texts.reply(source, XBackupMessages.pruning)
        reply(source, engine.prune()) { pruned -> say(source, XBackupMessages.pruned, "removed" to pruned.removed, "kept" to pruned.kept, "size" to Sizes.format(pruned.freed)) }
    }

    fun pin(context: CommandContext, id: String, pinned: Boolean) {
        val source = context.source
        whenReady(source) {
            val snapshot = find(source, id) ?: return@whenReady
            reply(source, engine.pin(snapshot, pinned)) { say(source, if (pinned) XBackupMessages.pinned else XBackupMessages.unpinned, "id" to snapshot.key.shortHex()) }
        }
    }

    fun forget(context: CommandContext, id: String, confirm: Boolean) {
        val source = context.source
        whenReady(source) {
            val snapshot = find(source, id) ?: return@whenReady
            val short = snapshot.key.shortHex()
            if (!confirm) return@whenReady say(source, XBackupMessages.forgetConfirm, "id" to short, "ago" to ago(source, snapshot.time), "command" to "/backup forget $short confirm")
            reply(source, engine.forget(snapshot)) { say(source, XBackupMessages.forgotten, "id" to short) }
        }
    }

    fun copy(context: CommandContext, name: String, rest: List<String>) {
        val source = context.source
        if (name !in plugin.settings.remotes) return texts.reply(source, XBackupMessages.noRemote, "name" to name)
        if (rest.firstOrNull().equals("tidy", ignoreCase = true)) {
            val confirm = rest.getOrNull(1).equals("confirm", ignoreCase = true)
            // Tidying removes backups, maybe the only copies left: it takes /backup forget's permission too.
            if (confirm && !source.hasPermission(plugin.permissions.forget.node)) return texts.reply(source, XBackupMessages.tidyNeedsForget)
            return tidy(source, name, confirm)
        }
        texts.reply(source, XBackupMessages.copying, "name" to name)
        reply(source, engine.push(name)) { result ->
            say(source, XBackupMessages.copied, "name" to name, "sent" to result.filesSent, "size" to Sizes.format(result.bytesSent), "removed" to result.filesDeleted)
            result.held?.let { say(source, XBackupMessages.copyHeld, "held" to it) }
        }
    }

    // The way out when a copy keeps everything: lists what only the remote has, then removes it once confirmed.
    private fun tidy(source: CommandSource, name: String, confirm: Boolean) {
        texts.reply(source, if (confirm) XBackupMessages.tidyApplying else XBackupMessages.tidyLooking, "name" to name)
        reply(source, engine.tidy(name, confirm)) { tidy ->
            val p = source.player
            if (confirm) {
                say(source, XBackupMessages.tidied, "name" to name, "backups" to tidy.extra.size, "packs" to tidy.packs, "size" to Sizes.format(tidy.bytes))
                if (tidy.keptPacks > 0) say(source, XBackupMessages.tidiedKept, "packs" to tidy.keptPacks)
                return@reply
            }
            if (tidy.extra.isEmpty() && tidy.packs == 0) return@reply say(source, XBackupMessages.tidyNothing, "name" to name)
            val out = ArrayList<Component>()
            out += texts.part(p, XBackupMessages.tidyHeader, "name" to name, "count" to tidy.extra.size)
            for (s in tidy.extra.take(5)) out += texts.part(p, XBackupMessages.tidyLine, "id" to s.key.shortHex(), "ago" to ago(source, s.time), "reason" to s.reason)
            if (tidy.extra.size > 5) out += texts.part(p, XBackupMessages.tidyMore, "count" to tidy.extra.size - 5)
            out += texts.part(
                p, XBackupMessages.tidyFooter, "size" to Sizes.format(tidy.bytes),
                "kept" to (if (tidy.keptPacks == 0) Component.EMPTY else texts.part(p, XBackupMessages.tidyKeeping, "packs" to tidy.keptPacks)),
                "command" to "/backup copy $name tidy confirm",
            )
            lines(source, out)
        }
    }

    fun link(context: CommandContext, name: String, code: String?) {
        val source = context.source
        val remote = plugin.settings.remotes[name] ?: return texts.reply(source, XBackupMessages.noRemote, "name" to name)
        val type = remote.type.trim().lowercase()
        if (type !in listOf("drive", "onedrive", "dropbox")) return texts.reply(source, XBackupMessages.notADrive, "name" to name, "type" to type)
        engine.link(name, code) { step ->
            when (step) {
                is LinkStep.OpenLink -> say(source, XBackupMessages.linkDropbox, "url" to step.url, "name" to name)
                is LinkStep.EnterCode -> say(source, XBackupMessages.linkDevice, "url" to step.url, "code" to step.code)
                LinkStep.Linked -> say(source, XBackupMessages.linked, "name" to name)
                is LinkStep.Failed -> say(source, XBackupMessages.linkFailed, "name" to name, "reason" to step.reason)
            }
        }
    }

    fun repair(context: CommandContext) {
        val source = context.source
        texts.reply(source, XBackupMessages.repairing)
        reply(source, engine.repair()) { rebuilt ->
            say(source, XBackupMessages.repaired, "blobs" to rebuilt.blobs, "packs" to rebuilt.packs)
            if (rebuilt.damaged.isNotEmpty()) say(source, XBackupMessages.repairDamaged, "count" to rebuilt.damaged.size)
        }
    }

    companion object {
        private const val PAGE = 8

        /** An error as people should read it. */
        fun describe(error: Throwable): String {
            var e = error
            while (e !is JobFailed && e.cause != null && e.message.isNullOrBlank()) e = e.cause!!
            val message = e.message
            return if (message.isNullOrBlank()) e::class.simpleName ?: "error" else message
        }
    }
}
