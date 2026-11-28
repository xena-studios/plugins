package co.xenastudios.xlimits

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.command.Argument
import co.xenastudios.core.common.command.ArgumentTypes
import co.xenastudios.core.common.command.CommandContext
import co.xenastudios.core.common.command.CommandHandler
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.CommandTree
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.xlimits.api.ChunkRef
import co.xenastudios.xlimits.api.XLimits
import co.xenastudios.xlimits.config.XLimitsConfig
import kotlin.time.Duration.Companion.seconds

/** xLimits' permissions. */
public class XLimitsPermissions internal constructor() : PermissionSet(XLimitsPlugin.PLUGIN_ID) {
    public val here: Permission = permission("here", "See what's in your chunk against each limit with /xlimits here.")
    public val top: Permission = permission("top", "Find the busiest chunks with /xlimits top and /xlimits near.")
    public val teleport: Permission = permission("teleport", "Teleport to a chunk from /xlimits top and the alerts.")
    public val restore: Permission = permission("restore", "Put every slowed mob back to normal with /xlimits restore.")
    public val alerts: Permission = node(XLimitsPlugin.ALERTS, "Be told when a chunk reaches a limit and when a load profile turns on or off.")
    public val bypass: Permission = node(
        XLimits.BYPASS_PERMISSION, "Place blocks, use spawn eggs, breed and tame past every limit. No one has it until it's given, operators included.",
        PermissionDefault.NOBODY,
    )
}

/** Builds `/xlimits` from the config, and runs it. */
internal class Commands(private val plugin: XLimitsPlugin) {
    val permissions = XLimitsPermissions()

    var warnings: List<String> = emptyList()
        private set

    fun tree(config: Config): CommandTree {
        val perms = permissions
        val problems = ArrayList<String>()
        val aliases = config[XLimitsConfig.command.aliases].map { it.lowercase() }.distinct().filter { alias ->
            val ok = alias != XLimitsPlugin.PLUGIN_ID && Regex("[a-z0-9][a-z0-9_-]*").matches(alias)
            if (!ok) problems += "command.aliases: /$alias can't be an alias of /xlimits, so it's left out."
            ok
        }
        warnings = problems
        return commands {
            root(XLimitsPlugin.PLUGIN_ID, "Shows xLimits' version, and holds its commands.", aliases = aliases) {
                executes(Run(INFO))
                literal("info", "Shows xLimits' version.") { executes(Run(INFO)) }
                literal("here", "What's in your chunk against each limit, the load profile on, and the server's own settings.", perms.here) {
                    playerOnly()
                    executes(Run(HERE))
                }
                literal("top", "The busiest loaded chunks, each clickable to teleport.", perms.top) {
                    val what = optional("what", ArgumentTypes.choice(*Busiest.NAMES.toTypedArray()), "entities, blocks or redstone.")
                    executes(Run(TOP, what))
                }
                literal("near", "The busiest chunks around one player.", perms.top) {
                    val who = argument("player", ArgumentTypes.player(PlayersOf(plugin)), "Whom.")
                    val what = optional("what", ArgumentTypes.choice(*Busiest.NAMES.toTypedArray()), "entities, blocks or redstone.")
                    executes(Run(NEAR, who, what))
                }
                literal("restore", "Puts every slowed mob back to normal and pauses farm slowing until a reload: run it before removing xLimits.", perms.restore) {
                    confirm(30.seconds)
                    executes(Run(RESTORE))
                }
                literal("tp", "Teleports you to a place, as the alerts' and /xlimits top's buttons do.", perms.teleport) {
                    playerOnly()
                    val world = argument("world", ArgumentTypes.word, "The world.")
                    val x = argument("x", ArgumentTypes.integer(), "x.")
                    val y = argument("y", ArgumentTypes.integer(), "y.")
                    val z = argument("z", ArgumentTypes.integer(), "z.")
                    executes(Run(TP, world, x, y, z))
                }
                mount(plugin.admin)
            }
        }
    }

    private inner class Run(private val id: Int, private vararg val args: Argument<*>) : CommandHandler {
        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> arg(ctx: CommandContext, index: Int): T? = ctx.getOrNull(args[index] as Argument<T>)

        override fun run(context: CommandContext) {
            val source = context.source
            when (id) {
                INFO -> plugin.texts.reply(
                    source, XLimitsMessages.info, "version" to plugin.version, "platform" to plugin.platform.name, "count" to plugin.limits().size,
                )
                HERE -> here(source, context.player)
                TOP -> top(source, null, what(arg(context, 0)))
                NEAR -> top(source, arg<PlayerRef>(context, 0), what(arg(context, 1)))
                RESTORE -> restore(source)
                else -> {
                    val world = arg<String>(context, 0)!!
                    val at = Location(WorldRef.of(world), arg<Int>(context, 1)!! + 0.5, arg<Int>(context, 2)!!.toDouble(), arg<Int>(context, 3)!! + 0.5)
                    if (!plugin.server.teleport(context.player, at)) plugin.texts.reply(source, XLimitsMessages.unknownWorld, "world" to world)
                }
            }
        }
    }

    private fun what(word: String?): Int = Busiest.NAMES.indexOf(word ?: "entities").coerceAtLeast(0)

    // /xlimits here: on the player's thread, which owns the chunk they stand in.
    private fun here(source: CommandSource, player: PlayerRef) {
        plugin.platform.scheduler.now(Where.on(player)) {
            val location = plugin.server.location(player) ?: return@now
            val chunk = ChunkRef.of(location)
            val r = plugin.rules
            val texts = plugin.texts
            if (location.world.name in r.exemptWorlds) return@now texts.reply(source, XLimitsMessages.hereExempt)
            val entry = plugin.counted(chunk, r, force = true)
            val report = Report(source, player, location, entry, r)
            // Blocks are counted afresh, off the server's threads, so the report is exact.
            if (r.blocks.isNotEmpty()) plugin.countBlocks(entry, r, then = report) else report()
        }
    }

    private inner class Report(
        private val source: CommandSource,
        private val player: PlayerRef,
        private val location: Location,
        private val entry: ChunkEntry,
        private val r: Rules,
    ) : () -> Unit {
        override fun invoke() {
            val texts = plugin.texts
            val chunk = entry.chunk
            val profile = plugin.profileFor(entry, r)?.name ?: XLimitsPlugin.NORMAL
            texts.reply(source, XLimitsMessages.hereHeader, "world" to chunk.world.name, "x" to chunk.x.toString(), "z" to chunk.z.toString(), "profile" to profile)
            var lines = 0
            val world = chunk.world.name
            fun line(name: String, count: Int, base: Int) {
                val limit = plugin.limitAt(entry, location, name, r.base(world, name, base), r)
                val max: Any = if (limit == XLimits.NO_LIMIT) texts.render(player, XLimitsMessages.unlimited) ?: "-" else limit
                texts.reply(source, if (limit != XLimits.NO_LIMIT && count >= limit) XLimitsMessages.hereFull else XLimitsMessages.hereLine, "limit" to name, "count" to count, "max" to max)
                lines++
            }
            r.entities.forEachIndexed { i, rule -> line(rule.name, entry.entities[i], rule.cap) }
            r.farms.forEachIndexed { i, rule ->
                if (entry.slowed[i]) {
                    texts.reply(source, XLimitsMessages.hereSlowed, "limit" to rule.name, "count" to entry.farm[i], "max" to rule.threshold)
                    lines++
                }
            }
            val blocks = entry.blocks
            r.blocks.forEachIndexed { i, rule -> line(rule.name, blocks?.get(i) ?: 0, rule.cap) }
            if (r.tnt >= 0) {
                line("tnt", entry.tnt, r.tnt)
                line("falling_blocks", entry.falling, r.falling)
            }
            if (r.pets >= 0) line("pets", plugin.pets.count(player.toString()), r.pets)
            if (lines == 0) texts.reply(source, XLimitsMessages.hereNone)
            val settings = plugin.server.serverSettings(chunk)
            if (settings.isNotEmpty()) texts.reply(source, XLimitsMessages.hereServer, "settings" to settings.joinToString(", ") { "${it.first} ${it.second}" })
        }
    }

    // /xlimits top and near: every chunk counted on its own thread, then sorted.
    private fun top(source: CommandSource, around: PlayerRef?, what: Int) {
        val texts = plugin.texts
        texts.reply(source, XLimitsMessages.topCounting)
        plugin.server.busiest(what, around) { stats ->
            val ranked = stats.map { stat ->
                val score = plugin.chunks.find(stat.chunk)?.redstoneScore ?: 0
                if (score == 0) stat else ChunkStat(stat.chunk, stat.entities, stat.blocks, score)
            }.filter { what != Busiest.REDSTONE || it.redstone > 0 }.sortedByDescending { it.of(what) }.take(LISTED)
            if (ranked.isEmpty()) return@busiest texts.reply(source, XLimitsMessages.topNone)
            val whatText = Busiest.NAMES[what]
            if (around != null) {
                texts.reply(source, XLimitsMessages.nearHeader, "player" to (plugin.server.name(around) ?: around.toString()), "what" to whatText)
            } else {
                texts.reply(source, XLimitsMessages.topHeader, "what" to whatText)
            }
            ranked.forEachIndexed { i, stat ->
                val c = stat.chunk
                val redstone: Any = if (stat.redstone > 0) texts.render(source.player, XLimitsMessages.topRedstone, "count" to stat.redstone) ?: "" else ""
                texts.reply(
                    source, XLimitsMessages.topLine, "rank" to (i + 1), "world" to c.world.name, "x" to c.x.toString(), "z" to c.z.toString(),
                    "bx" to ((c.x shl 4) + 8).toString(), "bz" to ((c.z shl 4) + 8).toString(),
                    "entities" to stat.entities, "blocks" to stat.blocks, "redstone" to redstone,
                )
            }
        }
    }

    private fun restore(source: CommandSource) {
        plugin.farmsPaused = true
        plugin.texts.reply(source, XLimitsMessages.restoreStarted)
        plugin.server.restoreAll { count -> plugin.texts.reply(source, XLimitsMessages.restoreDone, "count" to count) }
    }

    private companion object {
        const val INFO = 0
        const val HERE = 1
        const val TOP = 2
        const val NEAR = 3
        const val RESTORE = 4
        const val TP = 5

        /** How many chunks `/xlimits top` lists. */
        const val LISTED = 10
    }
}

// The online players `/xlimits near` completes, as the plugin's source of them sees them.
private class PlayersOf(private val plugin: XLimitsPlugin) : co.xenastudios.core.common.command.OnlinePlayers {
    override fun find(name: String, viewer: CommandSource): PlayerRef? = plugin.find(name, viewer)
    override fun names(viewer: CommandSource): List<String> = plugin.names(viewer)
}
