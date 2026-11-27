package co.xenastudios.xbans

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
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.api.XBansApi
import co.xenastudios.xbans.config.Cmd
import co.xenastudios.xbans.config.XBansConfig

/**
 * xBans' permissions: every command's node the config names, and the ones 1.x declared, with 1.x's
 * defaults. Built again on every reload; two settings naming the same node share one.
 */
public class XBansPermissions internal constructor(private val admin: PermissionSet) : PermissionSet(XBansPlugin.PLUGIN_ID) {
    public val scope: Permission = permission("scope", "Choose the server a punishment applies on (server:<name>).")
    public val extraSilent: Permission = permission("extrasilent", "Punish with -S: only the console is told.")
    public val notifyBroadcast: Permission = permission("notify.broadcast", "Hear punishments announced.", PermissionDefault.EVERYONE)
    public val notifySilent: Permission = permission("notify.silent", "Hear silent punishments (-s).")
    public val notifyBannedJoin: Permission = permission("notify.banned-join", "Hear when a banned player tries to join.")
    public val notifyAlts: Permission = permission("notify.alts", "Hear when a player joins who shares an IP with a banned account.")
    public val exempt: Permission = permission("exempt", "Can't be punished. Not for operators unless given.", PermissionDefault.NOBODY)
    public val bypassTemplates: Permission = permission("limit.bypass-templates", "A template's own lengths aren't held to your staff-limits group.", PermissionDefault.NOBODY)
    public val override: Permission = permission("unban.override", "Lift (or replace) any punishment, whoever gave it.")
    public val warningsOthers: Permission = permission("warnings.others", "See another player's warnings with /warnings <player>.")
    public val staff: Permission = node(XBansApi.STAFF_PERMISSION, "Counted as staff by other plugins (xVanish), through xBans' API.")

    internal fun of(node: String, description: String, default: PermissionDefault = PermissionDefault.OP): Permission? {
        if (node.isEmpty()) return null
        admin[node]?.let { return it }
        return this[node] ?: node(node, description, default)
    }
}

/** Builds the command tree from the config, and runs each command (in [Actions], made the first time one runs). */
internal class Commands(private val plugin: XBansPlugin) {
    lateinit var permissions: XBansPermissions
        private set

    /** What building the last tree found wrong: aliases that clash. */
    var warnings: List<String> = emptyList()
        private set

    private class Building(val config: Config, val perms: XBansPermissions, val problems: MutableList<String>, val taken: MutableSet<String>)

    private var building: Building? = null

    /** One punishment command: what it makes, whether it needs a length, whether it's by IP. */
    class Kind(val name: String, val type: PunishmentType, val temp: Boolean, val ip: Boolean)

    fun tree(config: Config): CommandTree {
        val perms = XBansPermissions(plugin.admin.permissions)
        val problems = ArrayList<String>()
        val taken = HashSet<String>()
        building = Building(config, perms, problems, taken)
        val c = XBansConfig.commands
        val ips = config[XBansConfig.ips.enabled]
        val proxy = plugin.server.proxy
        val root = if (proxy) PROXY_ROOT else XBansPlugin.PLUGIN_ID
        val rootAliases = if (proxy) listOf("xbp") else emptyList()
        val tree = commands {
            taken += root
            taken += rootAliases
            root(root, if (proxy) "Shows xBans' version on the proxy, and holds its admin subcommands." else "Shows xBans' version, and holds its admin subcommands.", aliases = rootAliases) {
                executes(Run(INFO))
                literal("info", "Shows xBans' version.") { executes(Run(INFO)) }
                val sources = plugin.imports?.ids.orEmpty()
                if (sources.isNotEmpty()) {
                    literal("import", "Imports punishments from another plugin: a dry run with counts, until confirmed.", plugin.admin.admin) {
                        val source = argument("source", ArgumentTypes.choice(*sources.toTypedArray()), "Where from: ${sources.joinToString(", ")}.")
                        val confirm = optional("confirm", ArgumentTypes.choice("confirm"), "Imports, rather than counting what it would.")
                        executes(Run(IMPORT, source, confirm))
                    }
                }
                mount(plugin.admin)
            }
            val kinds = listOf(
                Kind("ban", PunishmentType.BAN, false, false) to c.ban,
                Kind("tempban", PunishmentType.BAN, true, false) to c.tempban,
                Kind("ipban", PunishmentType.BAN, false, true) to c.ipban,
                Kind("tempipban", PunishmentType.BAN, true, true) to c.tempipban,
                Kind("mute", PunishmentType.MUTE, false, false) to c.mute,
                Kind("tempmute", PunishmentType.MUTE, true, false) to c.tempmute,
                Kind("ipmute", PunishmentType.MUTE, false, true) to c.ipmute,
                Kind("tempipmute", PunishmentType.MUTE, true, true) to c.tempipmute,
                Kind("warn", PunishmentType.WARN, false, false) to c.warn,
                Kind("kick", PunishmentType.KICK, false, false) to c.kick,
            )
            for ((kind, section) in kinds) {
                if (kind.ip && !ips) continue
                add(section, description(kind), PermissionDefault.OP) {
                    val target = argument("player", Words(plugin, Words.PLAYERS), if (kind.ip) "The player, or an IP address." else "The player.")
                    val rest = optional("reason", Words(plugin, Words.REASON, kind), REASON_HELP)
                    executes(Run(PUNISH, target, rest, kind = kind))
                }
            }
            for ((section, type) in listOf(c.unban to PunishmentType.BAN, c.unmute to PunishmentType.MUTE, c.unwarn to PunishmentType.WARN)) {
                val what = when (type) {
                    PunishmentType.BAN -> "ban"
                    PunishmentType.MUTE -> "mute"
                    else -> "newest warning"
                }
                add(section, "Revoke a player's $what.", PermissionDefault.OP) {
                    val target = argument("player", Words(plugin, if (type == PunishmentType.WARN) Words.PLAYERS else if (type == PunishmentType.BAN) Words.BANNED else Words.MUTED), "The player, an IP address, or #id.")
                    val rest = optional("reason", Words(plugin, Words.FLAGS), "-s for silently, server:<name> for one server, then why.")
                    executes(Run(REVOKE, target, rest, type = type))
                }
            }
            for ((section, staff) in listOf(c.history to false, c.staffhistory to true)) {
                add(section, if (staff) "The punishments a staff member made, newest first." else "A player's punishments, newest first.", PermissionDefault.OP) {
                    val target = argument(if (staff) "staff" else "player", Words(plugin, Words.PLAYERS), if (staff) "The staff member, or console." else "The player.")
                    val rest = optional("filter", Words(plugin, Words.TYPES), "bans, mutes, warnings or kicks, and a page.")
                    executes(Run(if (staff) STAFFHISTORY else HISTORY, target, rest))
                }
            }
            for ((section, type) in listOf(c.checkban to PunishmentType.BAN, c.checkmute to PunishmentType.MUTE)) {
                add(section, "Whether a player is ${if (type == PunishmentType.BAN) "banned" else "muted"}, and why.", PermissionDefault.OP) {
                    executes(Run(CHECK, argument("player", Words(plugin, Words.PLAYERS), "The player, an IP address, or #id."), type = type))
                }
            }
            add(c.warnings, "Active warnings: your own, or a player's.", PermissionDefault.EVERYONE) {
                val first = optional("player", Words(plugin, Words.WARNED), "Whose: yours without one; another's needs xbans.warnings.others.")
                executes(Run(WARNINGS, first, optional("page", ArgumentTypes.integer(1, 999_999), "Which page.")))
            }
            for ((section, type) in listOf(c.banlist to PunishmentType.BAN, c.mutelist to PunishmentType.MUTE)) {
                add(section, "Everyone ${if (type == PunishmentType.BAN) "banned" else "muted"} on this server.", PermissionDefault.OP) {
                    executes(Run(LIST, optional("page", ArgumentTypes.integer(1, 999_999), "Which page."), type = type))
                }
            }
            add(c.prunehistory, "Delete a player's punishments that are no longer in force.", PermissionDefault.OP) {
                val target = argument("player", Words(plugin, Words.PLAYERS), "The player.")
                executes(Run(PRUNE, target, optional("options", Words(plugin, Words.PRUNE), "How far back (30d), then confirm to delete.")))
            }
            if (ips) {
                add(c.alts, "Accounts that share an IP with a player.", PermissionDefault.OP) {
                    val target = argument("player", Words(plugin, Words.PLAYERS), "The player, or an IP address.")
                    executes(Run(ALTS, target, optional("page", ArgumentTypes.integer(1, 999_999), "Which page.")))
                }
                add(c.iphistory, "The IPs a player has joined from.", PermissionDefault.OP) {
                    val target = argument("player", Words(plugin, Words.PLAYERS), "The player.")
                    executes(Run(IPHISTORY, target, optional("page", ArgumentTypes.integer(1, 999_999), "Which page.")))
                }
            }
        }
        // 1.x's nodes, declared with their defaults.
        for ((_, limit) in config[XBansConfig.staffLimits.groups]) perms.of(limit.permission, "Held to a staff-limits group's longest ban and mute.", PermissionDefault.NOBODY)
        for ((name, template) in config[XBansConfig.templates]) perms.of(template.permissionFor(name), "Use the $name template.")
        building = null
        permissions = perms
        warnings = problems
        return tree
    }

    private fun description(kind: Kind): String {
        val what = when (kind.type) {
            PunishmentType.BAN -> if (kind.ip) "IP-ban" else "Ban"
            PunishmentType.MUTE -> if (kind.ip) "IP-mute" else "Mute"
            PunishmentType.WARN -> "Warn"
            PunishmentType.KICK -> "Kick"
        }
        return "$what a player" + if (kind.temp) " for a while." else "."
    }

    // A root command from its config section, unless it's off or its name is taken.
    private inline fun CommandTreeBuilder.add(section: Cmd, description: String, default: PermissionDefault, block: CommandBuilder.() -> Unit) {
        val b = building!!
        if (!b.config[section.enabled]) return
        val name = section.command
        if (name in b.taken) {
            b.problems += "/$name is also another of xBans' commands' alias, so it's skipped. Remove that alias."
            return
        }
        val aliases = b.config[section.aliases].filter { it != name }.distinct().filter { alias ->
            (alias !in b.taken).also { free -> if (!free) b.problems += "${section.path}.aliases: /$alias is already another xBans command's, so it's left out." }
        }
        b.taken += name
        b.taken += aliases
        root(name, description, b.perms.of(b.config[section.permission], "Use /$name.", default), aliases, block)
    }

    /**
     * Every command's handler, as one class told apart by [id], rather than a lambda each: the JVM
     * makes a class of every lambda as it's made (decisions/2026-11-25-a-lighter-core.md).
     */
    private inner class Run(
        private val id: Int,
        private vararg val args: Argument<*>,
        private val kind: Kind? = null,
        private val type: PunishmentType? = null,
    ) : CommandHandler {
        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> arg(ctx: CommandContext, index: Int): T? = ctx.getOrNull(args[index] as Argument<T>)

        override fun run(context: CommandContext) {
            val a = plugin.actions()
            when (id) {
                INFO -> a.info(context.source)
                IMPORT -> a.importFrom(context.source, arg<String>(context, 0)!!, arg<String>(context, 1) != null)
                PUNISH -> a.punish(context.source, kind!!, Args.words(arg<String>(context, 0)!!) + Args.words(arg<String>(context, 1)))
                REVOKE -> a.revoke(context.source, type!!, listOf(arg<String>(context, 0)!!) + Args.words(arg<String>(context, 1)))
                HISTORY, STAFFHISTORY -> a.history(context.source, arg<String>(context, 0)!!, Args.words(arg<String>(context, 1)), id == STAFFHISTORY)
                CHECK -> a.check(context.source, arg<String>(context, 0)!!, type!!)
                WARNINGS -> a.warnings(context.source, arg(context, 0), arg(context, 1))
                LIST -> a.list(context.source, type!!, arg<Int>(context, 0) ?: 1)
                PRUNE -> a.prune(context.source, arg<String>(context, 0)!!, Args.words(arg<String>(context, 1)))
                ALTS -> a.alts(context.source, arg<String>(context, 0)!!, arg<Int>(context, 1) ?: 1)
                IPHISTORY -> a.ipHistory(context.source, arg<String>(context, 0)!!, arg<Int>(context, 1) ?: 1)
            }
        }
    }

    internal companion object {
        const val INFO = 0
        const val IMPORT = 1
        const val PUNISH = 2
        const val REVOKE = 3
        const val HISTORY = 4
        const val STAFFHISTORY = 5
        const val CHECK = 6
        const val WARNINGS = 7
        const val LIST = 8
        const val PRUNE = 9
        const val ALTS = 10
        const val IPHISTORY = 11

        /** The proxy's own root command, so a player's `/xbans reload` still reaches their server. */
        const val PROXY_ROOT = "xbansproxy"

        const val REASON_HELP = "Flags (-s silent, -S console only, -p public, -g every server, -I by IP, server:<name>), then a length " +
            "(30m, 1d12h, 2mo, perm), a template, and the reason; -- before a reason that looks like a flag."
    }
}

/**
 * What a command's words complete to, and how they're read (as typed: xBans reads them itself, the
 * LiteBans way). One class for every kind, rather than an object each.
 */
internal class Words(private val plugin: XBansPlugin, private val kind: Int, private val command: Commands.Kind? = null) : ArgumentType<String> {
    override val name: String get() = if (greedy) "text" else "word"
    override val greedy: Boolean get() = kind >= REASON

    override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> =
        if (input.isBlank()) Outcome.Err(ArgumentError.Invalid("nothing was typed")) else Outcome.Ok(input)

    override fun suggest(source: CommandSource): List<String> {
        val now = plugin.now()
        return when (kind) {
            PLAYERS -> plugin.players.names(source)
            BANNED, MUTED -> plugin.backend?.active?.names(if (kind == BANNED) PunishmentType.BAN else PunishmentType.MUTE, now, 50) ?: plugin.players.names(source)
            WARNED -> if (source.hasPermission(plugin.permissions.warningsOthers.node)) plugin.players.names(source) else emptyList()
            TYPES -> listOf("bans", "mutes", "warnings", "kicks")
            PRUNE -> listOf("30d", "confirm")
            FLAGS -> listOf("-s")
            else -> {
                val type = command?.type ?: return listOf("-s", "-p")
                val out = mutableListOf("-s", "-p")
                if (source.hasPermission(plugin.permissions.extraSilent.node)) out += "-S"
                if (type != PunishmentType.KICK) {
                    out += if (command.temp) listOf("1h", "1d", "7d", "30d") else listOf("1h", "1d", "7d", "30d", "perm")
                    for ((name, template) in plugin.config[XBansConfig.templates]) {
                        if (template.type == type && source.hasPermission(template.permissionFor(name))) out += name
                    }
                    if (type != PunishmentType.WARN && source.hasPermission(plugin.permissions.scope.node)) {
                        out += "server:" + plugin.config[XBansConfig.scopes.serverName]
                        out += "server:global"
                    }
                }
                out
            }
        }
    }

    companion object {
        const val PLAYERS = 0
        const val BANNED = 1
        const val MUTED = 2
        const val WARNED = 3

        // Greedy from here: the rest of the line.
        const val REASON = 10
        const val FLAGS = 11
        const val TYPES = 12
        const val PRUNE = 13
    }
}
