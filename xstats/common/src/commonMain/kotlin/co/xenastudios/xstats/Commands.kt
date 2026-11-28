package co.xenastudios.xstats

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.Argument
import co.xenastudios.core.common.command.ArgumentError
import co.xenastudios.core.common.command.ArgumentType
import co.xenastudios.core.common.command.ArgumentTypes
import co.xenastudios.core.common.command.CommandBuilder
import co.xenastudios.core.common.command.CommandContext
import co.xenastudios.core.common.command.CommandHandler
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.CommandTree
import co.xenastudios.core.common.command.CommandTreeBuilder
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.xstats.config.XStatsConfig

/**
 * xStats' permissions, 1.x's nodes and defaults: `xstats.private` for everyone, `xstats.staff` for
 * operators, `xstats.leaderboard.exclude` for nobody until given, `xstats.admin` (the core's) for
 * `/xstats`, and the nodes the config names for `/stats` and `/leaderboard`.
 */
public class XStatsPermissions internal constructor(private val admin: PermissionSet) : PermissionSet(XStatsPlugin.PLUGIN_ID) {
    public val private: Permission = permission("private", "/stats private: hide your own stats from the website, or show them again.", PermissionDefault.EVERYONE)
    public val staff: Permission = permission("staff", "On /stats, sections other plugins only show staff, such as a full punishment history.")
    public val exclude: Permission = permission(
        "leaderboard.exclude", "Left out of every leaderboard: for staff who shouldn't compete. Nobody has it until given it.", PermissionDefault.NOBODY,
    )

    internal fun of(node: String, description: String): Permission? {
        if (node.isEmpty()) return null
        admin[node]?.let { return it }
        return this[node] ?: node(node, description, PermissionDefault.EVERYONE)
    }
}

/**
 * xStats' commands, 1.x's: `/stats [player|private]`, `/leaderboard <stat> [period] [page]` (`/lb`)
 * and `/xstats` with `status`, `save`, `import`, `board` and `web` beside the core's `reload`, `debug`
 * and `forget`. On the proxy, `/networkstats`, `/networkleaderboard` and `/xstatsproxy` (`/xsp`), so
 * a player's `/stats` still reaches their server. What each does is in [Actions].
 */
internal class Commands(private val plugin: XStatsPlugin) {
    lateinit var permissions: XStatsPermissions
        private set

    /** What building the last tree found wrong: aliases that clash. */
    var warnings: List<String> = emptyList()
        private set

    fun tree(config: Config): CommandTree {
        val perms = XStatsPermissions(plugin.admin.permissions)
        permissions = perms
        val problems = ArrayList<String>()
        val taken = HashSet<String>()
        val admin = plugin.admin.admin
        val proxy = plugin.server.proxy
        val tree = commands {
            if (proxy) {
                val p = XStatsConfig.proxy
                add(config, p.stats, taken, problems, perms, "A player's stats on the whole network.") {
                    executes(Run(STATS, optional("player", Words(plugin, Words.PLAYER), "Whose: a player who has played on the network.")))
                }
                add(config, p.leaderboard, taken, problems, perms, "A leaderboard of the whole network.") {
                    val stat = argument("stat", Words(plugin, Words.STAT), "The stat's id, or its name with _ for spaces.")
                    val period = optional("period", Words(plugin, Words.PERIOD), "daily, weekly, monthly or all, or a page.")
                    executes(Run(TOP, stat, period, optional("page", ArgumentTypes.integer(1), "Which page.")))
                }
                taken += PROXY_ROOT
                root(PROXY_ROOT, "Shows xStats' version on the proxy, and holds its admin subcommands.", aliases = listOf("xsp")) {
                    executes(Run(INFO))
                    literal("info", "Shows xStats' version.") { executes(Run(INFO)) }
                    literal("status", "The database, the network's players and servers, and when they were last read.", admin) { executes(Run(STATUS)) }
                    literal("refresh", "Reads the network's stats again now.", admin) { executes(Run(SAVE)) }
                    webCommands(admin)
                    mount(plugin.admin)
                }
                return@commands
            }
            val c = XStatsConfig.commands
            addCommand(config, c.stats, taken, problems, perms, "See a player's stats, or hide yours from the website.") {
                executes(Run(STATS, optional("player", Words(plugin, Words.PLAYER_OR_PRIVATE), "Whose: a player who has played here, or private.")))
            }
            addCommand(config, c.leaderboard, taken, problems, perms, "See a leaderboard.") {
                val stat = argument("stat", Words(plugin, Words.STAT), "The stat's id, or its name with _ for spaces.")
                val period = optional("period", Words(plugin, Words.PERIOD), "daily, weekly, monthly or all, or a page.")
                executes(Run(TOP, stat, period, optional("page", ArgumentTypes.integer(1), "Which page.")))
            }
            taken += XStatsPlugin.PLUGIN_ID
            root(XStatsPlugin.PLUGIN_ID, "Shows xStats' version, and holds its admin subcommands.") {
                executes(Run(INFO))
                literal("info", "Shows xStats' version and what's running.") { executes(Run(INFO)) }
                literal("status", "The database, players, the last save, what waits, rows, the website and the stat table.", admin) { executes(Run(STATUS)) }
                literal("save", "Saves now, instead of waiting for the next save.", admin) { executes(Run(SAVE)) }
                literal("import", "Reads players' history from the world's stats files again; never adds anything twice.", admin) { executes(Run(IMPORT)) }
                literal("board", "Leaderboards in the world.", admin) {
                    literal("add", "Places a leaderboard in the world: where you stand, or at the coordinates.") {
                        val stat = argument("stat", Words(plugin, Words.STAT_ANY), "The stat's id.")
                        val period = optional("period", Words(plugin, Words.PERIOD), "daily, weekly, monthly or all.")
                        val world = optional("world", Words(plugin, Words.WORLD), "The world, with x, y and z after it.")
                        val x = optional("x", ArgumentTypes.number(-3.0e7, 3.0e7), "x")
                        val y = optional("y", ArgumentTypes.number(-2048.0, 4096.0), "y")
                        val z = optional("z", ArgumentTypes.number(-3.0e7, 3.0e7), "z")
                        executes(Run(BOARD_ADD, stat, period, world, x, y, z))
                    }
                    literal("list", "Every board, with its id.") { executes(Run(BOARD_LIST)) }
                    literal("remove", "Takes a board down.") { executes(Run(BOARD_REMOVE, argument("id", ArgumentTypes.integer(1), "The board's id, from /xstats board list."))) }
                }
                webCommands(admin)
                mount(plugin.admin)
            }
        }
        warnings = problems
        return tree
    }

    private fun CommandBuilder.webCommands(admin: Permission) {
        literal("web", "The website.", admin) {
            literal("test", "Starts a test copy of the website on this machine, checks every kind of page, and stops it.") { executes(Run(WEB_TEST)) }
            literal("export", "Writes the website to the plugin's site/ folder, to host anywhere.") { executes(Run(WEB_EXPORT)) }
        }
    }

    // A root command from its config section, unless it's off or its name is taken.
    private inline fun CommandTreeBuilder.addCommand(
        config: Config,
        section: co.xenastudios.xstats.config.CommandSettings,
        taken: MutableSet<String>,
        problems: MutableList<String>,
        perms: XStatsPermissions,
        description: String,
        block: CommandBuilder.() -> Unit,
    ) {
        if (!config[section.enabled]) return
        root(section.command, config[section.aliases], config[section.permission], section.path, taken, problems, perms, description, block)
    }

    private inline fun CommandTreeBuilder.add(
        config: Config,
        section: XStatsConfig.ProxyCommand,
        taken: MutableSet<String>,
        problems: MutableList<String>,
        perms: XStatsPermissions,
        description: String,
        block: CommandBuilder.() -> Unit,
    ) {
        if (!config[section.enabled]) return
        root(config[section.command], config[section.aliases], config[section.permission], section.path, taken, problems, perms, description, block)
    }

    private inline fun CommandTreeBuilder.root(
        name: String,
        aliases: List<String>,
        node: String,
        path: String,
        taken: MutableSet<String>,
        problems: MutableList<String>,
        perms: XStatsPermissions,
        description: String,
        block: CommandBuilder.() -> Unit,
    ) {
        if (name in taken) {
            problems += "/$name is also another of xStats' commands' alias, so it's skipped. Remove that alias."
            return
        }
        val kept = aliases.filter { it != name }.distinct().filter { alias ->
            (alias !in taken).also { free -> if (!free) problems += "$path.aliases: /$alias is already another xStats command's, so it's left out." }
        }
        taken += name
        taken += kept
        root(name, description, perms.of(node, "Use /$name."), kept, block)
    }

    /** Every command's handler, as one class told apart by [id], rather than a lambda each (decisions/2026-11-25-a-lighter-core.md). */
    private inner class Run(private val id: Int, private vararg val args: Argument<*>) : CommandHandler {
        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> arg(ctx: CommandContext, index: Int): T? = ctx.getOrNull(args[index] as Argument<T>)

        override fun run(context: CommandContext) {
            val a = plugin.actions()
            val source = context.source
            when (id) {
                INFO -> a.info(source)
                STATS -> a.stats(source, arg(context, 0))
                TOP -> a.top(source, arg<String>(context, 0)!!, arg(context, 1), arg(context, 2))
                STATUS -> a.status(source)
                SAVE -> a.save(source)
                IMPORT -> a.importHistory(source)
                BOARD_ADD -> a.boardAdd(source, arg<String>(context, 0)!!, arg(context, 1), arg(context, 2), arg(context, 3), arg(context, 4), arg(context, 5))
                BOARD_LIST -> a.boardList(source)
                BOARD_REMOVE -> a.boardRemove(source, arg<Int>(context, 0)!!)
                WEB_TEST -> a.webTest(source)
                WEB_EXPORT -> a.webExport(source)
            }
        }
    }

    companion object {
        const val INFO = 0
        const val STATS = 1
        const val TOP = 2
        const val STATUS = 3
        const val SAVE = 4
        const val IMPORT = 5
        const val BOARD_ADD = 6
        const val BOARD_LIST = 7
        const val BOARD_REMOVE = 8
        const val WEB_TEST = 9
        const val WEB_EXPORT = 10

        /** The proxy's own root command, so a player's `/xstats` still reaches their server. */
        const val PROXY_ROOT = "xstatsproxy"
    }
}

/** What a command's words complete to: one class for every kind, rather than an object each. */
internal class Words(private val plugin: XStatsPlugin, private val kind: Int) : ArgumentType<String> {
    override val name: String get() = "word"

    override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> =
        if (input.isBlank()) Outcome.Err(ArgumentError.Invalid("nothing was typed")) else Outcome.Ok(input)

    override fun suggest(source: CommandSource): List<String> = when (kind) {
        PERIOD -> listOf("daily", "weekly", "monthly", "all")
        PLAYER -> plugin.players.names(source)
        PLAYER_OR_PRIVATE -> plugin.players.names(source) + if (plugin.permissions.private.let { source.hasPermission(it.node) }) listOf("private") else emptyList()
        STAT -> plugin.catalog.snapshot.stats.filter { it.inGame }.map { it.id }
        STAT_ANY -> plugin.catalog.snapshot.stats.map { it.id }
        else -> plugin.server.worlds()
    }

    companion object {
        const val PERIOD = 0
        const val PLAYER = 1
        const val PLAYER_OR_PRIVATE = 2
        const val STAT = 3
        const val STAT_ANY = 4
        const val WORLD = 5
    }
}
