package co.xenastudios.xrewind

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.xrewind.api.XRewind
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingFailure
import co.xenastudios.core.api.reject
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.xrewind.api.Actor
import co.xenastudios.xrewind.api.Entry
import co.xenastudios.xrewind.api.Plan
import co.xenastudios.xrewind.api.Query
import co.xenastudios.xrewind.api.RollbackResult
import co.xenastudios.xrewind.api.event.LookupEvent
import co.xenastudios.xrewind.log.Actions
import co.xenastudios.xrewind.log.Record
import co.xenastudios.xrewind.log.StoredTime
import co.xenastudios.xrewind.log.TextState
import co.xenastudios.xrewind.query.Search
import co.xenastudios.xrewind.rollback.RewindPlan
import co.xenastudios.xrewind.store.Lookup

/** The API's calls, in a class of their own, loaded with the first plugin to call one, not at startup. */
internal object Api {
    fun log(plugin: XRewindPlugin, actor: Actor, world: WorldRef, x: Int, y: Int, z: Int, before: String, after: String) {
        val b = TextState(before)
        val a = TextState(after)
        val action = if (a.isAir && !b.isAir) Actions.BREAK else Actions.PLACE
        if (plugin.filters?.allow(world, x, y, z, actor.name) == false) return
        plugin.queue.add(Record.Block(plugin.now(), actor, world.name, x, y, z, action, b, a))
    }

    fun container(plugin: XRewindPlugin, actor: Actor, world: WorldRef, x: Int, y: Int, z: Int, item: String, bytes: ByteArray?, amount: Int) {
        require(amount != 0) { "An amount isn't 0" }
        val id = if (':' in item) item else "minecraft:$item"
        val data = bytes ?: plugin.server.itemBytes(id) ?: throw IllegalArgumentException("$id isn't an item this server has")
        if (plugin.filters?.allow(world, x, y, z, actor.name) == false) return
        plugin.queue.add(Record.Container(plugin.now(), actor, world.name, x, y, z, if (amount > 0) Actions.ADD else Actions.REMOVE, id, null, data, kotlin.math.abs(amount)))
    }

    fun lookup(plugin: XRewindPlugin, query: Query): Pending<List<Entry>> {
        val db = plugin.database ?: return pendingFailure(IllegalStateException("xRewind's database isn't ready yet"))
        val (search, center) = Search.of(query)
        val out = newPending<List<Entry>>()
        // For an audit log: built only when something listens for it.
        if (plugin.hasListeners(LookupEvent.TYPE)) plugin.bus.fire(LookupEvent.TYPE, LookupEvent(null, null, query, 1))
        plugin.writer.flush(1_000).onSettled { _, _ ->
            val now = plugin.now()
            db.read { session -> Lookup.page(session, db.sql, search, center, now, 1, query.limit, count = false) }.onSettled { result, error ->
                when (result) {
                    is Outcome.Ok -> out.resolve(result.value.rows.filter { r -> query.filter?.includes(r.world, r.x, r.y, r.z) ?: true }.map { r ->
                        Entry(StoredTime.toInstant(r.time), r.user, r.world, r.x, r.y, r.z, r.kind, r.thing ?: "", r.after, r.amount, r.rolledBack)
                    })
                    is Outcome.Err -> out.reject(IllegalStateException("The lookup failed: ${result.error}"))
                    null -> out.reject(error ?: IllegalStateException("The lookup failed"))
                }
            }
        }
        return out
    }

    fun plan(plugin: XRewindPlugin, query: Query, restore: Boolean): Pending<Plan> {
        require(query.seconds > 0) { "A rollback or restore needs a time: Query.builder().time(seconds)" }
        val db = plugin.database ?: return pendingFailure(IllegalStateException("xRewind's database isn't ready yet"))
        val (search, center) = Search.of(query)
        val out = newPending<Plan>()
        plugin.rollbacks.plan(db, search.until(plugin.now()), center, restore).onSettled { result, error ->
            when (result) {
                is Outcome.Ok -> out.resolve(result.value)
                is Outcome.Err -> out.reject(IllegalStateException("Couldn't work out the plan: ${result.error}"))
                null -> out.reject(error ?: IllegalStateException("Couldn't work out the plan"))
            }
        }
        return out
    }

    fun apply(plugin: XRewindPlugin, plan: Plan): Pending<RollbackResult> {
        require(plan is RewindPlan && !plan.spent) { "That plan isn't one of the API's: plan one with planRollback or planRestore, or apply a result's undo" }
        check(plan.claim()) { "That plan was applied already, or is being applied" }
        return plugin.rollbacks.applyForApi(plan)
    }

    fun stop(plugin: XRewindPlugin): Pending<Unit> = with(plugin) {
        running = false
        XRewind.register(null)
        updates?.close()
        updates = null
        madeLookups?.inspector?.clear()
        modules.clear()
        madeHooks?.close()
        madeHooks = null
        madeRollbacks?.shutdown()
        registrar?.clear()
        permissionRegistrar?.sync(emptyList())
        writer.stop()
        val done = newPending<Unit>()
        val db = writer.current ?: return@with pendingOf(Unit)
        writer.drainAll().onSettled { _, _ ->
            val lost = queue.waiting
            if (lost > 0) log.warn("$lost logged changes couldn't be saved before stopping" + (writer.problem?.let { ": $it" } ?: "."))
            db.close().onSettled { _, _ -> done.resolve(Unit) }
        }
        done
    }
}
