package co.xenastudios.xrewind

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.time.Durations
import co.xenastudios.xrewind.api.Query
import co.xenastudios.xrewind.api.event.LookupEvent
import co.xenastudios.xrewind.log.Actions
import co.xenastudios.xrewind.query.Center
import co.xenastudios.xrewind.query.Params
import co.xenastudios.xrewind.query.Search
import co.xenastudios.xrewind.store.Lookup
import co.xenastudios.xrewind.store.LoggedRow
import kotlin.time.Duration.Companion.seconds

/**
 * Lookups off the server's threads, the results sent back to whoever asked; each sender's last
 * lookup remembered for `/xrewind page` (1.x's `Lookups`), and the inspector.
 */
internal class Lookups(private val plugin: XRewindPlugin) {
    private class Last(val search: Search, val center: Center?, val inspect: Boolean)

    private val last = concurrentMap<String, Last>()
    private val busy = concurrentMap<String, Boolean>()

    val inspector: Inspector = Inspector()

    private fun keyOf(source: CommandSource): String = source.player?.toString() ?: source.name

    /**
     * Looks up a page and sends it. A sender's second lookup while their first is still running is
     * ignored, so clicking fast with the inspector can't pile them up.
     */
    fun run(source: CommandSource, search: Search, center: Center?, page: Int, inspect: Boolean) {
        if (center == null && search.world == null && search.radius != Search.GLOBAL) {
            return plugin.texts.reply(source, XRewindMessages.invalid, "reason" to XRewindMessages.consoleLookup)
        }
        val db = plugin.database ?: return plugin.texts.reply(source, XRewindMessages.notReady)
        val key = keyOf(source)
        if (busy.put(key, true) != null) return
        if (last.size > 1_000) last.clear()
        last[key] = Last(search, center, inspect)
        if (plugin.hasListeners(LookupEvent.TYPE)) {
            plugin.bus.fire(LookupEvent.TYPE, LookupEvent(source.player, source.name, query(search, center), page))
        }
        plugin.writer.flush(1_000).onSettled { _, _ ->
            val now = plugin.now()
            db.read { session -> Lookup.page(session, db.sql, search, center, now, page, PER_PAGE) }.onSettled { result, error ->
                busy.remove(key)
                when (result) {
                    is Outcome.Ok -> render(source, search, center, page, inspect, result.value.rows, result.value.total, now)
                    else -> {
                        plugin.log.warn("A lookup for ${source.name} failed: ${(result as? Outcome.Err)?.error ?: error}")
                        plugin.texts.reply(source, CoreMessages.commandError)
                    }
                }
            }
        }
    }

    /** Another page of the sender's last lookup. */
    fun page(source: CommandSource, page: Int) {
        val previous = last[keyOf(source)] ?: return plugin.texts.reply(source, XRewindMessages.noPrevious)
        run(source, previous.search, previous.center, page, previous.inspect)
    }

    private fun render(source: CommandSource, search: Search, center: Center?, page: Int, inspect: Boolean, rows: List<LoggedRow>, total: Long, now: Int) {
        val texts = plugin.texts
        if (total == 0L) return texts.reply(source, XRewindMessages.none)
        if (inspect && center != null) {
            texts.reply(source, XRewindMessages.headerAt, "x" to center.x, "y" to center.y, "z" to center.z, "world" to center.world)
        } else {
            texts.reply(source, XRewindMessages.header, "count" to total)
        }
        if (search.count) return
        for (row in rows) line(source.player, row, now)?.let(source::reply)
        val pages = ((total + PER_PAGE - 1) / PER_PAGE).coerceAtMost(MAX_PAGE.toLong()).toInt()
        if (pages > 1) {
            val next = minOf(page + 1, pages)
            texts.reply(source, XRewindMessages.footer, "page" to page, "pages" to pages, "next" to "/xrewind page $next")
        }
    }

    /** One change as a lookup shows it. */
    private fun line(player: PlayerRef?, row: LoggedRow, now: Int): Component? {
        val t = plugin.texts
        val (action, thing) = when (row.table) {
            LoggedRow.Table.SESSION -> t.word(player, if (row.action == Actions.JOIN) XRewindMessages.joined else XRewindMessages.quit) to ""
            LoggedRow.Table.CONTAINER -> t.word(player, if (row.action == Actions.ADD) XRewindMessages.added else XRewindMessages.took) to "${row.amount} ${Params.name(row.thing)}"
            LoggedRow.Table.KILL -> t.word(player, XRewindMessages.killed) to Params.name(row.thing)
            LoggedRow.Table.TEXT -> t.word(player, when (row.action) {
                Actions.SIGN -> XRewindMessages.wrote
                Actions.CHAT -> XRewindMessages.said
                else -> XRewindMessages.ran
            }) to (row.thing ?: "")
            LoggedRow.Table.BLOCK -> {
                val broke = row.action == Actions.BREAK
                t.word(player, if (broke) XRewindMessages.broke else if (row.action == Actions.CLICK) XRewindMessages.clicked else XRewindMessages.placed) to
                    Params.name(if (broke) row.thing else row.after)
            }
        }
        val ago = Durations.format((now - row.time).coerceAtLeast(0).seconds)
        return t.render(
            player, if (row.rolledBack) XRewindMessages.lineUndone else XRewindMessages.line,
            "ago" to ago, "user" to row.user, "action" to action, "block" to thing,
            "x" to row.x.toString(), "y" to row.y.toString(), "z" to row.z.toString(), "world" to row.world,
        )
    }

    /** What a [Search] is as the API's [Query], for [LookupEvent]. */
    private fun query(search: Search, center: Center?): Query {
        val builder = Query.builder()
        builder.users(*search.users.toTypedArray()).excludeUsers(*search.excludedUsers.toTypedArray())
        if (search.seconds > 0) builder.time(search.seconds)
        when {
            search.radius >= 0 && center != null -> builder.radius(co.xenastudios.core.api.WorldRef.of(center.world), center.x, center.y, center.z, search.radius)
            search.radius == Search.GLOBAL -> builder.global()
            else -> (search.world ?: center?.world)?.let { builder.world(co.xenastudios.core.api.WorldRef.of(it)) }
        }
        builder.actions(*search.actions.toTypedArray()).blocks(*search.blocks.toTypedArray()).excludeBlocks(*search.excludedBlocks.toTypedArray())
        return builder.limit(PER_PAGE).build()
    }

    /**
     * The inspector: while it's on for a player, clicking a block shows its history instead of
     * breaking or using it, and placing one shows what was at that spot. The adapter listens only
     * while someone has it on.
     */
    inner class Inspector : Inspecting {
        private val on = concurrentMap<PlayerRef, Boolean>()
        private var listening: AutoCloseable? = null

        /** Turns it on or off for [player]; returns whether it's on now. */
        fun toggle(player: PlayerRef): Boolean {
            val now = on.remove(player) == null
            if (now) on[player] = true
            sync()
            return now
        }

        override fun isInspecting(player: PlayerRef): Boolean = on.containsKey(player)

        override fun inspect(player: PlayerRef, at: Spot) {
            if (!plugin.server.isOnline(player)) {
                on.remove(player)
                sync()
                return
            }
            run(PlayerSource(plugin, player), Search.AT, Center(at.world, at.x, at.y, at.z), 1, inspect = true)
        }

        /** Off for everyone, as the plugin stops. */
        fun clear() {
            on.clear()
            sync()
        }

        private fun sync() = locked(this) {
            if (on.isNotEmpty() && listening == null) listening = plugin.server.inspector(this)
            else if (on.isEmpty() && listening != null) {
                listening?.close()
                listening = null
            }
        }
    }

    companion object {
        const val PER_PAGE = 7

        /** The furthest page: deeper ones would read thousands of rows to show seven. */
        const val MAX_PAGE = 500
    }
}

/** An online player as a command's source, for what they started by clicking (the inspector). */
internal class PlayerSource(private val plugin: XRewindPlugin, override val player: PlayerRef) : CommandSource {
    override val name: String get() = plugin.server.name(player) ?: player.toString()
    override fun hasPermission(node: String): Boolean = true
    override fun reply(message: Component) = plugin.server.send(player, message)
}
