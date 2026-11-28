package co.xenastudios.xvote

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.Argument
import co.xenastudios.core.common.command.ArgumentError
import co.xenastudios.core.common.command.ArgumentType
import co.xenastudios.core.common.command.ArgumentTypes
import co.xenastudios.core.common.command.CommandContext
import co.xenastudios.core.common.command.CommandHandler
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.CommandTree
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet

/** xVote's permissions, with 1.x's nodes and defaults: `xvote.use` for everyone, `xvote.test` for operators, `xvote.admin` (the core's) for the rest. */
public class XVotePermissions internal constructor() : PermissionSet(XVotePlugin.PLUGIN_ID) {
    public val use: Permission = permission("use", "/vote, /vote top and /vote claim.", PermissionDefault.EVERYONE)
    public val test: Permission = permission("test", "/vote test: send a signed test vote through the receiver.")
}

/**
 * xVote's commands, 1.x's: `/vote [top [period] [page] | claim | test <player> [site]]` and
 * `/xvote` with `status`, `key`, `token`, `import` and `party` beside the core's `reload`, `debug`
 * and `forget`. On a proxy, `/xvoteproxy` (`/xvp`), so a player's `/xvote` still reaches their
 * server, with what the proxy has: the receiver and `/xvoteproxy test`. What each does is in
 * [Actions], made the first time one runs.
 */
internal class Commands(private val plugin: XVotePlugin) {
    val permissions: XVotePermissions = XVotePermissions()

    fun tree(): CommandTree {
        val admin = plugin.admin.admin
        val proxy = plugin.server.proxy
        return commands {
            if (!proxy) {
                root("vote", "Where to vote, and the top voters.", permissions.use) {
                    executes(Run(LIST))
                    literal("top", "The top voters: today, this week, this month or of all time.") {
                        val period = optional("period", Words(plugin, Words.PERIOD), "day, week, month or all, or a page.")
                        val page = optional("page", ArgumentTypes.integer(1, 100_000), "Which page.")
                        executes(Run(TOP, period, page))
                    }
                    literal("claim", "Reward items that didn't fit.") {
                        playerOnly()
                        executes(Run(CLAIM))
                    }
                    literal("test", "Sends a signed test vote through the receiver, end to end.", permissions.test) {
                        val player = argument("player", Words(plugin, Words.PLAYER), "The name the vote is for.")
                        val site = optional("site", Words(plugin, Words.SITE), "A site's key in sites; leave it off for a test with no cooldown.")
                        executes(Run(TEST, player, site))
                    }
                }
            }
            val root = if (proxy) PROXY_ROOT else XVotePlugin.PLUGIN_ID
            root(root, if (proxy) "Shows xVote's version on the proxy, and holds its admin subcommands." else "Shows xVote's version, and holds its admin subcommands.",
                aliases = if (proxy) listOf("xvp") else emptyList()) {
                executes(Run(INFO))
                literal("info", "Shows xVote's version.") { executes(Run(INFO)) }
                literal("status", "The receiver, the database, votes waiting and the last votes.", admin) { executes(Run(STATUS)) }
                literal("key", "The public key lists that use Votifier v1 ask for.", admin) { executes(Run(KEY)) }
                literal("token", "Votifier v2 tokens: every one, one list's, or a new one for a list.", admin) {
                    executes(Run(TOKEN, optional("service", ArgumentTypes.text, "The service name a list sends.")))
                }
                if (proxy) {
                    literal("test", "Sends a signed test vote through the proxy's receiver.", permissions.test) {
                        val player = argument("player", Words(plugin, Words.PLAYER), "The name the vote is for.")
                        val site = optional("site", Words(plugin, Words.SITE), "A site's key in sites.")
                        executes(Run(TEST, player, site))
                    }
                }
                if (!proxy) {
                    literal("party", "The vote party's count, or start it now.", admin) {
                        executes(Run(PARTY, optional("start", ArgumentTypes.choice("start"), "Starts a party now.")))
                    }
                }
                if (plugin.imports != null) {
                    literal("import", "Takes over NuVotifier's keys and tokens, or adds VotingPlugin's or SuperbVote's totals.", admin) {
                        val source = argument("source", ArgumentTypes.choice("nuvotifier", "votingplugin", "superbvote"), "Where from.")
                        val again = optional("again", ArgumentTypes.choice("again"), "Adds totals imported before a second time.")
                        executes(Run(IMPORT, source, again))
                    }
                }
                mount(plugin.admin)
            }
        }
    }

    /**
     * Every command's handler, as one class told apart by [id], rather than a lambda each: the JVM
     * makes a class of every lambda as it's made (decisions/2026-11-25-a-lighter-core.md).
     */
    private inner class Run(private val id: Int, private vararg val args: Argument<*>) : CommandHandler {
        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> arg(ctx: CommandContext, index: Int): T? = ctx.getOrNull(args[index] as Argument<T>)

        override fun run(context: CommandContext) {
            val a = plugin.actions()
            val source = context.source
            when (id) {
                INFO -> a.info(source)
                LIST -> a.list(source)
                TOP -> a.top(source, arg(context, 0), arg(context, 1))
                CLAIM -> a.claim(source)
                TEST -> a.test(source, arg<String>(context, 0)!!, arg(context, 1))
                STATUS -> a.status(source)
                KEY -> a.key(source)
                TOKEN -> a.token(source, arg(context, 0))
                PARTY -> a.party(source, arg<String>(context, 0) != null)
                IMPORT -> a.importFrom(source, arg<String>(context, 0)!!, arg<String>(context, 1) != null)
            }
        }
    }

    companion object {
        const val INFO = 0
        const val LIST = 1
        const val TOP = 2
        const val CLAIM = 3
        const val TEST = 4
        const val STATUS = 5
        const val KEY = 6
        const val TOKEN = 7
        const val PARTY = 8
        const val IMPORT = 9

        /** The proxy's own root command, so a player's `/xvote` still reaches their server. */
        const val PROXY_ROOT = "xvoteproxy"
    }
}

/** What a command's words complete to: one class for every kind, rather than an object each. */
internal class Words(private val plugin: XVotePlugin, private val kind: Int) : ArgumentType<String> {
    override val name: String get() = "word"

    override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> =
        if (input.isBlank()) Outcome.Err(ArgumentError.Invalid("nothing was typed")) else Outcome.Ok(input)

    override fun suggest(source: CommandSource): List<String> = when (kind) {
        PERIOD -> listOf("day", "week", "month", "all")
        PLAYER -> plugin.players.names(source)
        else -> plugin.rewards.sites.map { it.key }
    }

    companion object {
        const val PERIOD = 0
        const val PLAYER = 1
        const val SITE = 2
    }
}

/** What the commands do, made the first time one runs. */
internal fun XVotePlugin.actions(): Actions = Actions.of(this)
