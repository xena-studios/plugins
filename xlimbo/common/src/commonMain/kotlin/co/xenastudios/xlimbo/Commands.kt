package co.xenastudios.xlimbo

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.Argument
import co.xenastudios.core.common.command.ArgumentError
import co.xenastudios.core.common.command.ArgumentType
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
import co.xenastudios.core.api.onSettled
import co.xenastudios.xlimbo.api.ReturnCause
import co.xenastudios.xlimbo.config.XLimboConfig

/**
 * xLimbo's permissions: `/join`'s, named in the config (`xlimbo.join` by default, which everyone
 * has), and the staff commands'. `xlimbo.admin` (the core's) grants reload, debug and forget, as
 * 2.x's `xlimbo.admin` granted reload and info.
 */
public class XLimboPermissions internal constructor(joinNode: String) : PermissionSet(XLimboPlugin.PLUGIN_ID) {
    public val join: Permission? = joinNode.takeIf { it.isNotEmpty() }?.let { node(it, "Use /join to go back.", PermissionDefault.EVERYONE) }
    public val list: Permission = permission("list", "See who's in limbo and their place in line: /xlimbo list.")
    public val send: Permission = permission("send", "Send a player into limbo on this server: /xlimbo send <player>.")
    public val back: Permission = permission("back", "Send a player, or everyone, back: /xlimbo back <player|all>.")
}

/** Builds `/xlimbo` and `/join` from the config, and runs them. */
internal class Commands(private val plugin: XLimboPlugin) {
    lateinit var permissions: XLimboPermissions
        private set

    fun tree(config: Config): CommandTree {
        val c = XLimboConfig.joinCommand
        val perms = XLimboPermissions(config[c.permission])
        permissions = perms
        val aliases = config[c.aliases].map { it.lowercase() }.distinct().filter { it != "join" && it != XLimboPlugin.PLUGIN_ID && WORD.matches(it) }
        return commands {
            root(XLimboPlugin.PLUGIN_ID, "Shows xLimbo's version, and holds its admin subcommands.") {
                executes(Run(INFO))
                literal("info", "Shows xLimbo's version, and who's in limbo.") { executes(Run(INFO)) }
                literal("list", "Lists who's in limbo, and their place in line.", perms.list) { executes(Run(LIST)) }
                literal("send", "Sends a player into limbo on this server.", perms.send) {
                    val who = argument("player", Players(plugin, all = false), "The player to send.")
                    executes(Run(SEND, who))
                }
                literal("back", "Sends a player back, or everyone with all: through the queue while it's on.", perms.back) {
                    val who = argument("player", Players(plugin, all = true), "The player to send back, or all.")
                    executes(Run(BACK, who))
                }
                mount(plugin.admin)
            }
            if (config[c.enabled]) {
                root("join", "Go back to the main server.", perms.join, aliases) {
                    playerOnly()
                    executes(Run(JOIN))
                }
            }
        }
    }

    private inner class Run(private val id: Int, private vararg val args: Argument<*>) : CommandHandler {
        @Suppress("UNCHECKED_CAST")
        private fun word(ctx: CommandContext, index: Int): String = ctx[args[index] as Argument<String>]

        override fun run(context: CommandContext) {
            val source = context.source
            when (id) {
                INFO -> plugin.texts.reply(
                    source, XLimboMessages.info,
                    "version" to plugin.version, "platform" to plugin.platform.name,
                    "count" to plugin.inLimboFor(source.player).size, "waiting" to waiting(source),
                )
                LIST -> list(source)
                SEND -> send(source, word(context, 0))
                BACK -> back(source, word(context, 0))
                else -> {
                    val player = context.player
                    plugin.platform.scheduler.now(Where.Global) { plugin.join(player, source::reply) }
                }
            }
        }
    }

    // Those in line [source] can see.
    private fun waiting(source: CommandSource): Int {
        val positions = plugin.returns?.positions ?: return 0
        return positions.keys.count { it == source.player || !plugin.server.vanishedFrom(source.player, it) }
    }

    private fun list(source: CommandSource) {
        val texts = plugin.texts
        val here = plugin.inLimboFor(source.player)
        if (here.isEmpty()) return texts.reply(source, XLimboMessages.listNone)
        texts.reply(source, XLimboMessages.listHeader, "count" to here.size, "waiting" to waiting(source))
        val positions = plugin.returns?.positions.orEmpty()
        val named = here.map { (plugin.server.name(it) ?: it.toString()) to positions[it] }
        for ((name, place) in named.sortedWith(compareBy({ it.second ?: Int.MAX_VALUE }, { it.first.lowercase() }))) {
            if (place != null) texts.reply(source, XLimboMessages.listQueued, "player" to name, "position" to place)
            else texts.reply(source, XLimboMessages.listEntry, "player" to name)
        }
    }

    private fun send(source: CommandSource, who: String) {
        val texts = plugin.texts
        val target = plugin.find(who, source) ?: return texts.reply(source, CoreMessages.commandUnknownPlayer, "player" to who)
        val name = plugin.server.name(target) ?: who
        plugin.platform.scheduler.now(Where.Global) {
            if (plugin.server.isInLimbo(target)) {
                texts.reply(source, XLimboMessages.sendAlready, "player" to name)
            } else {
                plugin.sendToLimboHere(target).onSettled { done, _ ->
                    texts.reply(source, if (done == true) XLimboMessages.sendDone else XLimboMessages.sendRefused, "player" to name)
                }
            }
        }
    }

    private fun back(source: CommandSource, who: String) {
        val texts = plugin.texts
        plugin.platform.scheduler.now(Where.Global) {
            if (who.equals("all", ignoreCase = true)) {
                val here = plugin.server.online().filter(plugin.server::isInLimbo)
                for (player in here) plugin.sendBackHere(player, ReturnCause.ADMIN)
                texts.reply(source, XLimboMessages.backAll, "count" to here.size)
                return@now
            }
            val target = plugin.find(who, source) ?: return@now texts.reply(source, CoreMessages.commandUnknownPlayer, "player" to who)
            val name = plugin.server.name(target) ?: who
            when {
                !plugin.server.isInLimbo(target) -> texts.reply(source, XLimboMessages.backNotHere, "player" to name)
                plugin.sendBackHere(target, ReturnCause.ADMIN) -> texts.reply(source, XLimboMessages.backDone, "player" to name)
                else -> texts.reply(source, XLimboMessages.backRefused, "player" to name)
            }
        }
    }

    private companion object {
        const val INFO = 0
        const val LIST = 1
        const val SEND = 2
        const val BACK = 3
        const val JOIN = 4
        val WORD = Regex("[a-z0-9][a-z0-9_-]*")
    }
}

/** A player's name, completed from those the source can see; with [all], `all` too. */
internal class Players(private val plugin: XLimboPlugin, private val all: Boolean) : ArgumentType<String> {
    override val name: String get() = "player"

    override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> = Outcome.Ok(input)

    override fun suggest(source: CommandSource): List<String> = try {
        val names = plugin.names(source)
        if (all) listOf("all") + names else names
    } catch (e: Exception) {
        emptyList()
    }
}
