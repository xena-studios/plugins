package co.xenastudios.xrewind.query

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.time.Durations
import co.xenastudios.xrewind.XRewindMessages
import co.xenastudios.xrewind.api.Action
import co.xenastudios.xrewind.api.BlockFilter
import co.xenastudios.xrewind.api.Query

/** Where a radius is measured from: the sender, or the block being inspected. */
public class Center(public val world: String, public val x: Int, public val y: Int, public val z: Int) {
    override fun toString(): String = "$world $x $y $z"
}

/** A box, both corners included, in one world. */
public class Box(public val world: String, public val minX: Int, public val minY: Int, public val minZ: Int, public val maxX: Int, public val maxY: Int, public val maxZ: Int)

/**
 * What a lookup, rollback or restore is about, as parsed from CoreProtect-style parameters
 * ([Params]) or built from the API's [Query]: 1.x's query, plus a [box] and a [filter] for one
 * region (xPlots) and an [until] time, so a rollback that carries on after a restart reaches
 * no further forward than it did when it started.
 */
public class Search(
    /** Players or `#sources`, lowercase; empty for anyone. */
    public val users: List<String> = emptyList(),
    public val excludedUsers: List<String> = emptyList(),
    /** How far back, or 0 for no limit. */
    public val seconds: Long = 0,
    /** Blocks around the centre, or [WORLD], [GLOBAL] or [UNSET]. */
    public val radius: Int = UNSET,
    /** The world asked for with `w:`, or `null` for the sender's. */
    public val world: String? = null,
    public val actions: Set<Action> = emptySet(),
    /** Block or item ids to include (`minecraft:oak_log`); empty for all. */
    public val blocks: List<String> = emptyList(),
    public val excludedBlocks: List<String> = emptyList(),
    public val preview: Boolean = false,
    public val count: Boolean = false,
    public val silent: Boolean = false,
    public val box: Box? = null,
    public val filter: BlockFilter? = null,
    /** No change after this stored time; `null` for up to now. */
    public val until: Int? = null,
    /** The parameters as typed, for a rollback written down to carry on after a restart. */
    public val args: List<String> = emptyList(),
) {
    /** The kinds of change it covers: those asked for with `a:`, or the default ones. */
    public val covered: Set<Action> get() = actions.ifEmpty { DEFAULT }

    public fun wants(vararg any: Action): Boolean = any.any { it in covered }

    public val wantsBlocks: Boolean get() = wants(Action.BREAK, Action.PLACE, Action.CLICK)
    public val wantsContainers: Boolean get() = wants(Action.ADD, Action.REMOVE)
    public val wantsKills: Boolean get() = wants(Action.KILL)
    public val wantsText: Boolean get() = wants(Action.SIGN, Action.CHAT, Action.COMMAND)
    public val wantsSessions: Boolean get() = wants(Action.JOIN, Action.QUIT)

    /** Whether it reaches chat or commands, which need their own permission. */
    public val wantsPrivate: Boolean get() = wants(Action.CHAT, Action.COMMAND)

    public fun withRadius(radius: Int): Search = copy(radius = radius)

    public fun until(time: Int): Search = copy(until = time)

    private fun copy(radius: Int = this.radius, until: Int? = this.until): Search = Search(
        users, excludedUsers, seconds, radius, world, actions, blocks, excludedBlocks, preview, count, silent, box, filter, until, args,
    )

    public companion object {
        public const val UNSET: Int = -1

        /** `r:#world`: the whole of one world. */
        public const val WORLD: Int = -2

        /** `r:#global`: every world. */
        public const val GLOBAL: Int = -3

        /** What a query without `a:` covers: blocks and containers, and kills for lookups. */
        public val DEFAULT: Set<Action> = setOf(Action.BREAK, Action.PLACE, Action.ADD, Action.REMOVE, Action.KILL)

        /** A block's whole history, for the inspector: radius 0 around it. */
        public val AT: Search = Search(radius = 0)

        /** The API's [query], with the center it gives, as a search. */
        public fun of(query: Query): Pair<Search, Center?> {
            val center = if (query.area == Query.Area.RADIUS) Center(query.world!!, query.centerX, query.centerY, query.centerZ) else null
            val radius = when (query.area) {
                Query.Area.RADIUS -> query.radius
                Query.Area.WORLD, Query.Area.BOX -> WORLD
                Query.Area.GLOBAL -> GLOBAL
            }
            val box = if (query.area == Query.Area.BOX) Box(query.world!!, query.minX, query.minY, query.minZ, query.maxX, query.maxY, query.maxZ) else null
            return Search(
                query.users, query.excludedUsers, query.seconds, radius, query.world, query.actions,
                query.blocks.toList(), query.excludedBlocks.toList(), box = box, filter = query.filter,
            ) to center
        }
    }
}

/** Why parameters couldn't be read: a message under `params.`, and what fills it. */
public class ParamsError(public val message: MessageKey, public vararg val placeholders: Pair<String, Any>)

/**
 * CoreProtect's parameters, so habits and guides carry over (1.x's `Params`): `u:` players, `t:`
 * time, `r:` radius, `a:` action, `i:` (or `b:`) blocks, `e:` exclusions, `w:` world, and
 * `#preview`, `#count`, `#silent`. [known] says whether a name is a block or item this server
 * has, and gives its id (`minecraft:oak_log`).
 */
public object Params {
    public fun parse(args: List<String>, known: (String) -> String?): Outcome<Search, ParamsError> {
        val users = ArrayList<String>()
        val excludedUsers = ArrayList<String>()
        val blocks = ArrayList<String>()
        val excludedBlocks = ArrayList<String>()
        val actions = LinkedHashSet<Action>()
        var seconds = 0L
        var radius = Search.UNSET
        var world: String? = null
        var preview = false
        var count = false
        var silent = false
        val typed = args.filter { it.isNotBlank() }
        for (arg in typed) {
            val lower = arg.lowercase()
            if (lower.startsWith("#")) {
                when (lower) {
                    "#preview" -> preview = true
                    "#count", "#sum" -> count = true
                    "#silent" -> silent = true
                    else -> return err(XRewindMessages.notAFlag, "arg" to arg)
                }
                continue
            }
            val colon = lower.indexOf(':')
            if (colon <= 0) return err(XRewindMessages.notAParameter, "arg" to arg)
            val key = lower.substring(0, colon)
            val value = arg.substring(colon + 1)
            if (value.isEmpty()) return err(XRewindMessages.needsValue, "arg" to arg)
            when (key) {
                "u", "user", "users" -> users += list(value)
                "t", "time" -> seconds = when (val d = Durations.parse(value)) {
                    is Outcome.Ok -> d.value.inWholeSeconds.takeIf { it > 0 } ?: return err(XRewindMessages.badTime, "value" to value)
                    is Outcome.Err -> return err(XRewindMessages.badTime, "value" to value)
                }
                "r", "radius" -> radius = radiusOf(value) ?: return err(XRewindMessages.badRadius, "value" to value)
                "w", "world" -> world = value
                "a", "action" -> for (action in list(value)) actions += actionsOf(action) ?: return err(XRewindMessages.badAction, "value" to action)
                "i", "b", "include", "block", "blocks" -> for (block in list(value)) blocks += known(block) ?: return err(XRewindMessages.badBlock, "value" to block)
                "e", "exclude" -> for (item in list(value)) {
                    // A block or item name excludes it; anything else, the player or source.
                    val id = if (item.startsWith("#")) null else known(item)
                    if (id != null) excludedBlocks += id else excludedUsers += item
                }
                else -> return err(XRewindMessages.unknownKey, "key" to key)
            }
        }
        return Outcome.Ok(Search(users, excludedUsers, seconds, radius, world, actions, blocks, excludedBlocks, preview, count, silent, args = typed))
    }

    private fun err(message: MessageKey, vararg placeholders: Pair<String, Any>): Outcome.Err<ParamsError> = Outcome.Err(ParamsError(message, *placeholders))

    private fun list(value: String): List<String> = value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun radiusOf(value: String): Int? = when (val lower = value.lowercase()) {
        "#world" -> Search.WORLD
        "#global" -> Search.GLOBAL
        else -> lower.toIntOrNull()?.takeIf { it in 0..1_000_000 }
    }

    private fun actionsOf(name: String): Set<Action>? = when (name) {
        "block" -> setOf(Action.BREAK, Action.PLACE)
        "+block", "place" -> setOf(Action.PLACE)
        "-block", "break" -> setOf(Action.BREAK)
        "click", "interact" -> setOf(Action.CLICK)
        "container" -> setOf(Action.ADD, Action.REMOVE)
        "+container", "add" -> setOf(Action.ADD)
        "-container", "remove", "take" -> setOf(Action.REMOVE)
        "kill" -> setOf(Action.KILL)
        "sign" -> setOf(Action.SIGN)
        "chat" -> setOf(Action.CHAT)
        "command" -> setOf(Action.COMMAND)
        "session" -> setOf(Action.JOIN, Action.QUIT)
        "+session", "login", "join" -> setOf(Action.JOIN)
        "-session", "logout", "quit" -> setOf(Action.QUIT)
        else -> null
    }

    /** What `a:` completes to. */
    public val ACTIONS: List<String> = listOf(
        "block", "+block", "-block", "container", "+container", "-container", "kill", "click", "sign", "chat", "command", "session", "+session", "-session",
    )

    /** A block state as players know it: `minecraft:oak_door[half=lower]` is `oak_door`. */
    public fun name(state: String?): String {
        if (state == null) return ""
        val id = state.substringBefore('[')
        return id.removePrefix("minecraft:")
    }
}
