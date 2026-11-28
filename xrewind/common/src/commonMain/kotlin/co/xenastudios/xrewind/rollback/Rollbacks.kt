package co.xenastudios.xrewind.rollback

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.reject
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.xrewind.Shown
import co.xenastudios.xrewind.XRewindMessages
import co.xenastudios.xrewind.XRewindPlugin
import co.xenastudios.xrewind.api.Plan
import co.xenastudios.xrewind.api.RollbackResult
import co.xenastudios.xrewind.api.event.RollbackEvent
import co.xenastudios.xrewind.api.event.RollbackFinishedEvent
import co.xenastudios.xrewind.config.XRewindConfig
import co.xenastudios.xrewind.log.Record
import co.xenastudios.xrewind.query.Center
import co.xenastudios.xrewind.query.Params
import co.xenastudios.xrewind.query.Search
import co.xenastudios.xrewind.store.Lookup
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

/**
 * Rollbacks, restores, previews and undo (1.x's `Rollbacks`). One runs at a time, so two can't
 * fight over the same blocks; working out what to change happens off the server's threads.
 *
 * **A restart doesn't lose one.** As a command's rollback or restore starts, what it is (its
 * parameters, where it was centred, the time it reaches up to) is written to
 * `rollback-running.txt` in the plugin's folder, and each chunk's rows are marked as that chunk is
 * done. A server that stops partway leaves the file; the next start reads it and carries on with
 * what isn't marked yet, then deletes it. A plugin's plan (which may keep to a region by code) and
 * an undo can't be planned again, so a restart reports those in the console instead.
 */
public class Rollbacks(private val plugin: XRewindPlugin) {
    private val lock = Any()
    private var running = false

    @Volatile
    private var applier: Applier? = null
    private val previews = concurrentMap<PlayerRef, RewindPlan>()
    private val undos = concurrentMap<String, RewindPlan>()
    private val pending = ArrayList<Pending<RollbackResult>>()

    private val texts get() = plugin.texts

    private fun claimRunning(): Boolean = locked(lock) { if (running) false else true.also { running = true } }

    private fun release() {
        locked(lock) { running = false }
    }

    /** Whether one is running, for `/xrewind status` and tests. */
    public val isRunning: Boolean get() = locked(lock) { running }

    // ---- commands ----

    /** `/xrewind rollback` (or with [restore], `restore`): what [search] matches, rolled back or previewed. */
    public fun run(source: CommandSource, search: Search, center: Center?, restore: Boolean) {
        val config = plugin.config
        if (search.seconds == 0L) return texts.reply(source, XRewindMessages.needTime)
        var asked = search
        var radius = search.radius
        if (center == null && (radius >= 0 || radius == Search.UNSET || radius == Search.WORLD && search.world == null)) {
            return texts.reply(source, XRewindMessages.invalid, "reason" to XRewindMessages.consoleRollback)
        }
        if (radius == Search.UNSET) {
            radius = config[XRewindConfig.rollback.defaultRadius]
            asked = asked.withRadius(radius)
        }
        val max = config[XRewindConfig.rollback.maxRadius]
        if ((radius < 0 || radius > max) && !source.hasPermission(GLOBAL_PERMISSION)) {
            return texts.reply(source, XRewindMessages.tooFar, "max" to max)
        }
        val viewer = source.player
        if (search.preview && viewer == null) return texts.reply(source, CoreMessages.commandPlayersOnly)
        val db = plugin.database ?: return texts.reply(source, XRewindMessages.notReady)
        if (!search.preview && !claimRunning()) return texts.reply(source, XRewindMessages.busy)
        asked = asked.until(plugin.now())
        val final = asked
        plan(db, final, center, restore).onSettled { result, error ->
            val plan = (result as? Outcome.Ok)?.value
            when {
                plan == null -> {
                    plugin.log.warn("A rollback for ${source.name} failed: ${(result as? Outcome.Err)?.error ?: error}")
                    texts.reply(source, CoreMessages.commandError)
                    if (!final.preview) release()
                }
                plan.changes.isEmpty() -> {
                    texts.reply(source, XRewindMessages.nothing)
                    if (!final.preview) release()
                }
                final.preview -> preview(viewer!!, plan, center?.world)
                else -> plugin.platform.scheduler.run(Where.Global) {
                    val job = Job(if (restore) "restore" else "rollback", source.name, final.until ?: plugin.now(), final.radius, center, final.args)
                    if (allowed(plan, source)) {
                        write(job)
                        execute(source, plan, final.silent, null, job)
                    }
                }
            }
        }
    }

    /**
     * What rolling back (or restoring) [search] would do, worked out off the server's threads:
     * everything logged so far is written first. No radius limit here: the caller decides.
     */
    public fun plan(db: Database, search: Search, center: Center?, restore: Boolean): Pending<Outcome<RewindPlan, String>> {
        val out = newPending<Outcome<RewindPlan, String>>()
        plugin.writer.flush(2_000).onSettled { _, _ ->
            val now = plugin.now()
            db.read { session ->
                val blocks = if (search.wantsBlocks) Lookup.allBlocks(session, db.sql, search, center, now, restore) else Outcome.Ok(emptyList())
                if (blocks is Outcome.Err) return@read blocks
                val containers = if (search.wantsContainers) Lookup.allContainers(session, db.sql, search, center, now, restore) else Outcome.Ok(emptyList())
                if (containers is Outcome.Err) return@read containers
                Outcome.Ok(Planner.plan((blocks as Outcome.Ok).value, (containers as Outcome.Ok).value, restore,
                    plugin.server::readState, plugin.server::itemType, search.filter))
            }.onSettled { result, error ->
                out.resolve(when (result) {
                    is Outcome.Ok -> Outcome.Ok(result.value)
                    is Outcome.Err -> Outcome.Err(result.error.toString())
                    null -> Outcome.Err(error.toString())
                })
            }
        }
        return out
    }

    /** `/xrewind apply`: the player's preview, for real. */
    public fun apply(source: CommandSource) {
        val player = source.player ?: return texts.reply(source, CoreMessages.commandPlayersOnly)
        val plan = previews[player] ?: return texts.reply(source, XRewindMessages.noPreview)
        if (!claimRunning()) return texts.reply(source, XRewindMessages.busy)
        plugin.platform.scheduler.run(Where.Global) {
            if (allowed(plan, source)) {
                // Taken only once it goes ahead: a cancelled one is still there for /xrewind cancel.
                previews.remove(player)
                execute(source, plan, false, null, null)
            }
        }
    }

    /** `/xrewind cancel`: the player's preview dropped, the real blocks shown again. */
    public fun cancel(source: CommandSource) {
        val player = source.player ?: return texts.reply(source, CoreMessages.commandPlayersOnly)
        val plan = previews.remove(player) ?: return texts.reply(source, XRewindMessages.noPreview)
        val world = plugin.server.position(player)?.world
        val byChunk = LinkedHashMap<String, MutableList<Change>>()
        for (change in plan.changes) {
            if (change.isItems || change.spot.world != world) continue
            byChunk.getOrPut("${change.spot.chunkX}|${change.spot.chunkZ}") { ArrayList() } += change
        }
        for (changes in byChunk.values) {
            val first = changes[0].spot
            val at = co.xenastudios.core.api.Location(co.xenastudios.core.api.WorldRef.of(first.world), first.x.toDouble(), first.y.toDouble(), first.z.toDouble())
            plugin.platform.scheduler.run(Where.At(at)) {
                val real = changes.mapNotNull { c -> plugin.server.stateAt(c.spot)?.let { Shown(c.spot, it) } }
                plugin.server.preview(player, real)
            }
        }
        texts.reply(source, XRewindMessages.cancelled)
    }

    /** `/xrewind undo`: the sender's last rollback or restore, reverted. */
    public fun undo(source: CommandSource) {
        val key = keyOf(source)
        val plan = undos[key] ?: return texts.reply(source, XRewindMessages.noUndo)
        if (!claimRunning()) return texts.reply(source, XRewindMessages.busy)
        plugin.platform.scheduler.run(Where.Global) {
            if (allowed(plan, source)) {
                undos.remove(key)
                execute(source, plan, false, null, null)
            }
        }
    }

    private fun keyOf(source: CommandSource): String = source.player?.toString() ?: source.name

    private fun preview(player: PlayerRef, plan: RewindPlan, world: String?) {
        // Previews of players who left without applying or cancelling go, as a new one comes.
        for (p in previews.keys.toList()) if (!plugin.server.isOnline(p)) previews.remove(p)
        previews[player] = plan
        val shown = plan.changes.filter { !it.isItems && it.spot.world == world }.map { Shown(it.spot, it.target!!) }
        plugin.server.preview(player, shown)
        texts.send(player, XRewindMessages.preview, "count" to plan.changes.size)
    }

    // ---- the API ----

    /**
     * Applies [plan] for another plugin, under the same one-at-a-time rule as the commands. The
     * caller claimed it; the claim is given back if it isn't applied after all.
     */
    public fun applyForApi(plan: RewindPlan): Pending<RollbackResult> {
        val future = newPending<RollbackResult>()
        if (!claimRunning()) {
            plan.unclaim()
            future.reject(IllegalStateException("Another rollback is running; try again when it's done"))
            return future
        }
        locked(lock) { pending += future }
        plugin.platform.scheduler.run(Where.Global, retired = {
            release()
            plan.unclaim()
            future.reject(IllegalStateException("xRewind is stopping"))
        }) {
            if (allowed(plan, null)) {
                execute(null, plan, true, future, null)
            } else {
                plan.unclaim()
                future.reject(kotlin.coroutines.cancellation.CancellationException("A RollbackEvent listener cancelled it"))
            }
        }
        return future
    }

    // ---- applying ----

    /**
     * Asks other plugins whether it may go ahead, with running claimed; on a no, gives running back
     * and tells the command's sender. The event is made only when something listens.
     */
    private fun allowed(plan: RewindPlan, source: CommandSource?): Boolean {
        if (!plugin.hasListeners(RollbackEvent.TYPE)) return true
        val event = plugin.bus.fire(RollbackEvent.TYPE, RollbackEvent(plan.asSpent(), source?.player, source?.name))
        if (!event.isCancelled) return true
        release()
        source?.let { texts.reply(it, XRewindMessages.stopped) }
        return false
    }

    /** On the global thread, with running claimed and [allowed] asked. [source] is `null` for the API. */
    private fun execute(source: CommandSource?, plan: RewindPlan, silent: Boolean, future: Pending<RollbackResult>?, job: Job?) {
        val verb = when (plan.kind) {
            Plan.Kind.ROLLBACK -> XRewindMessages.verbRollback
            Plan.Kind.RESTORE -> XRewindMessages.verbRestore
            Plan.Kind.UNDO -> XRewindMessages.verbUndo
        }
        var progress: Task? = null
        val made = Applier(
            plugin.platform, plugin.server, plugin.log, plan, plugin.config[XRewindConfig.rollback.msPerTick],
            chunkDone = { blocks, containers ->
                // Only the rows of changes really made: unreadable or unreached ones stay as they
                // were, so a later rollback can still reach them.
                if (blocks.isNotEmpty()) plugin.queue.add(Record.Mark("block", blocks, plan.markRolledBack))
                if (containers.isNotEmpty()) plugin.queue.add(Record.Mark("container", containers, plan.markRolledBack))
            },
            finished = { result -> finished(source, plan, silent, future, job, result, verb, progress) },
        )
        applier = made
        val player = source?.player
        if (player != null && !silent) {
            progress = plugin.platform.scheduler.run(Where.on(player), 1.seconds, 1.seconds) {
                val line = texts.render(player, XRewindMessages.progress, "verb" to texts.word(player, verb), "done" to made.applied, "total" to made.total)
                if (line != null) plugin.server.actionBar(player, line)
            }
        }
        made.start()
    }

    private fun finished(
        source: CommandSource?, plan: RewindPlan, silent: Boolean, future: Pending<RollbackResult>?, job: Job?,
        result: Applier.Result, verb: co.xenastudios.core.common.i18n.MessageKey, progress: Task?,
    ) {
        applier = null
        progress?.cancel()
        val undo = RewindPlan(Plan.Kind.UNDO, result.previous, !plan.markRolledBack, 0, spent = source != null)
        if (source != null && !result.aborted) {
            if (plan.kind == Plan.Kind.UNDO) undos.remove(keyOf(source)) else undos[keyOf(source)] = undo
        }
        // Stopped by the server stopping: the job file stays, for the next start to carry on.
        if (job != null && !result.aborted) delete()
        release()
        val skipped = plan.unreadable + result.missingWorld
        val done = RollbackResult(result.applied, skipped, result.took.inWholeMilliseconds, undo)
        locked(lock) { future?.let { pending -= it } }
        if (future != null || plugin.hasListeners(RollbackFinishedEvent.TYPE)) {
            plugin.platform.scheduler.run(Where.Global) {
                if (plugin.hasListeners(RollbackFinishedEvent.TYPE)) {
                    plugin.bus.fire(RollbackFinishedEvent.TYPE, RollbackFinishedEvent(plan.asSpent(), done, source?.player, source?.name))
                }
                future?.resolve(done)
            }
        }
        if (!silent && source != null) {
            val seconds = result.took.inWholeMilliseconds / 1000.0
            texts.reply(source, XRewindMessages.done, "verb" to texts.word(source.player, verb), "count" to result.applied, "seconds" to seconds(seconds))
            if (skipped > 0) texts.reply(source, XRewindMessages.skipped, "count" to skipped)
        }
    }

    // ---- a restart partway ----

    /** One command's rollback or restore, as written down while it runs. */
    internal class Job(val kind: String, val by: String, val until: Int, val radius: Int, val center: Center?, val args: List<String>) {
        fun text(): String = buildString {
            append("# xRewind: a rollback or restore that was running. xRewind carries it on as it starts, then deletes this.\n")
            append("kind: ").append(kind).append('\n')
            append("by: ").append(by).append('\n')
            append("until: ").append(until).append('\n')
            append("radius: ").append(radius).append('\n')
            center?.let { append("center: ").append(it.world).append(' ').append(it.x).append(' ').append(it.y).append(' ').append(it.z).append('\n') }
            for (arg in args) append("arg: ").append(arg).append('\n')
        }

        companion object {
            fun read(text: String): Job? {
                var kind: String? = null
                var by = "CONSOLE"
                var until: Int? = null
                var radius = Search.UNSET
                var center: Center? = null
                val args = ArrayList<String>()
                for (line in text.lines()) {
                    if (line.startsWith("#") || ':' !in line) continue
                    val key = line.substringBefore(':')
                    val value = line.substringAfter(':').trim()
                    when (key) {
                        "kind" -> kind = value
                        "by" -> by = value
                        "until" -> until = value.toIntOrNull()
                        "radius" -> radius = value.toIntOrNull() ?: Search.UNSET
                        "center" -> {
                            val parts = value.split(' ')
                            if (parts.size == 4) center = Center(parts[0], parts[1].toIntOrNull() ?: 0, parts[2].toIntOrNull() ?: 0, parts[3].toIntOrNull() ?: 0)
                        }
                        "arg" -> args += value
                    }
                }
                if (kind != "rollback" && kind != "restore") return null
                return Job(kind, by, until ?: return null, radius, center, args)
            }
        }
    }

    private fun write(job: Job) {
        plugin.platform.scheduler.run(Where.Async) {
            val wrote = plugin.platform.storage.writeText(JOB, job.text())
            if (wrote is Outcome.Err) plugin.log.warn("Couldn't write ${JOB}: a restart before this rollback finishes won't carry it on (${wrote.error.detail}).")
        }
    }

    private fun delete() {
        plugin.platform.scheduler.run(Where.Async) { plugin.platform.storage.delete(JOB) }
    }

    /** Carries on a rollback or restore a restart stopped, once the database is open. Off the server's threads. */
    internal fun resume(db: Database) {
        val text = (plugin.platform.storage.readText(JOB) as? Outcome.Ok)?.value ?: return
        val job = Job.read(text)
        if (job == null) {
            plugin.log.warn("$JOB can't be read, so the rollback it was for isn't carried on. Look its area up and run it again; the file is deleted.")
            plugin.platform.storage.delete(JOB)
            return
        }
        val parsed = Params.parse(job.args) { plugin.server.known(it) }
        val search = (parsed as? Outcome.Ok)?.value?.withRadius(job.radius)?.until(job.until) ?: run {
            plugin.log.warn("$JOB's parameters can't be read any more (${job.args.joinToString(" ")}); the ${job.kind} isn't carried on.")
            plugin.platform.storage.delete(JOB)
            return
        }
        if (!claimRunning()) return
        plugin.log.info("A ${job.kind} ${job.by} ran (${job.args.joinToString(" ")}) was stopped by a restart: carrying on with what it hadn't done.")
        plan(db, search, job.center, job.kind == "restore").onSettled { result, error ->
            val plan = (result as? Outcome.Ok)?.value
            if (plan == null || plan.changes.isEmpty()) {
                if (plan == null) plugin.log.warn("The ${job.kind} couldn't be carried on: ${(result as? Outcome.Err)?.error ?: error}") else delete()
                release()
                return@onSettled
            }
            plugin.platform.scheduler.run(Where.Global) { execute(plugin.console, plan, false, null, job) }
        }
    }

    /**
     * As xRewind stops: a running one stops between blocks (a command's carries on at the next
     * start), the API's still waiting fail, and running is given back.
     */
    public fun shutdown() {
        applier?.abort()
        val waiting = locked(lock) { pending.toList().also { pending.clear() } }
        for (future in waiting) future.reject(IllegalStateException("xRewind was disabled before the rollback finished"))
        previews.clear()
        release()
    }

    public companion object {
        public const val GLOBAL_PERMISSION: String = "xrewind.rollback.global"

        /** Where a running rollback is written down: `plugins/xRewind/rollback-running.txt`. */
        public val JOB: StoragePath = StoragePath.of(XRewindPlugin.JOB_FILE)

        /** Seconds for a message: two decimals under a second, so a quick one reads 0.04, not 0.0. */
        public fun seconds(seconds: Double): String {
            val scaled = if (seconds < 1) kotlin.math.round(seconds * 100) / 100 else kotlin.math.round(seconds * 10) / 10
            val text = scaled.toString()
            val decimals = if (seconds < 1) 2 else 1
            val dot = text.indexOf('.')
            return if (dot < 0) text + "." + "0".repeat(decimals) else text + "0".repeat((decimals - (text.length - dot - 1)).coerceAtLeast(0))
        }
    }
}
