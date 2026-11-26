package co.xenastudios.xteleport

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
import co.xenastudios.xteleport.config.CommandSettings
import co.xenastudios.xteleport.config.XTeleportConfig

/**
 * xTeleport's permissions: the bypasses, the unlimited homes, and every node the config names
 * (each command's, each `permission-others`), declared with its default. Built again on every
 * reload; two settings naming the same node share one.
 */
public class XTeleportPermissions internal constructor(private val admin: PermissionSet) : PermissionSet(XTeleportPlugin.PLUGIN_ID) {
    public val warmupBypass: Permission = permission("warmup.bypass", "Teleport without waiting out the warmup.")
    public val cooldownBypass: Permission = permission("cooldown.bypass", "Teleport without the commands' cooldowns, /rtp's search cooldown included.")
    public val costBypass: Permission = permission("cost.bypass", "Teleport without paying the commands' costs.")
    public val unlimitedHomes: Permission = permission("homes.unlimited", "Have any number of homes.")
    public val tptoggleBypass: Permission = permission("tptoggle.bypass", "Send teleport requests to players who turned them off.")

    internal fun of(node: String, description: String, default: PermissionDefault = PermissionDefault.OP): Permission? {
        if (node.isEmpty()) return null
        admin[node]?.let { return it }
        return this[node] ?: node(node, description, default)
    }
}

/** Builds the command tree from the config, and runs each command (in [Actions], made the first time one runs). */
internal class Commands(private val plugin: XTeleportPlugin) {
    /** The permissions the last tree checks. */
    lateinit var permissions: XTeleportPermissions
        private set

    /** What building the last tree found wrong: aliases that clash. */
    var warnings: List<String> = emptyList()
        private set

    private class Building(val config: Config, val perms: XTeleportPermissions, val problems: MutableList<String>, val taken: MutableSet<String>)

    private var building: Building? = null

    fun tree(config: Config): CommandTree {
        val perms = XTeleportPermissions(plugin.admin.permissions)
        val problems = ArrayList<String>()
        val taken = HashSet<String>()
        building = Building(config, perms, problems, taken)
        val c = XTeleportConfig.commands
        val words = Words(plugin)
        val tree = commands {
            taken += XTeleportPlugin.PLUGIN_ID
            taken += "xtp"
            root(XTeleportPlugin.PLUGIN_ID, "Shows xTeleport's version, and holds its admin subcommands.", aliases = listOf("xtp")) {
                executes(Run(INFO))
                literal("info", "Shows xTeleport's version.") { executes(Run(INFO)) }
                literal("import", "Imports homes, warps and the spawn from EssentialsX: a dry run until confirmed.", plugin.admin.admin) {
                    val source = argument("source", ArgumentTypes.choice("essentials"), "The plugin to import from.")
                    val confirm = optional("confirm", ArgumentTypes.choice("confirm"), "Imports, rather than saying what it would do.")
                    executes(Run(IMPORT, source, confirm))
                }
                literal("rtpcheck", "Runs /rtp's search in a world, teleporting no one, to try its settings.", plugin.admin.admin) {
                    val world = argument("world", Words(plugin, Words.WORLDS), "The world, as rtp.worlds names it.")
                    val runs = optional("searches", ArgumentTypes.integer(1, 20), "How many searches, one after another.")
                    executes(Run(RTPCHECK, world, runs))
                }
                mount(plugin.admin)
            }
            add(c.sethome, "Set a home where you stand.", PermissionDefault.EVERYONE) {
                playerOnly()
                executes(Run(SETHOME, optional("name", ArgumentTypes.word, "The home's name: 1 to 32 letters, digits, - or _.")))
            }
            add(c.home, "Teleport to one of your homes.", PermissionDefault.EVERYONE) {
                playerOnly()
                executes(Run(HOME, optional("name", Words(plugin, Words.HOMES_AND_BED), "Which home; staff may type <player>:<home>.")))
            }
            add(c.delhome, "Delete one of your homes.", PermissionDefault.EVERYONE) {
                playerOnly()
                executes(Run(DELHOME, argument("name", Words(plugin, Words.HOMES), "Which home; staff may type <player>:<home>.")))
            }
            add(c.homes, "List your homes.", PermissionDefault.EVERYONE) {
                val first = optional("page", words, "A page, or with permission-others, whose homes.")
                executes(Run(HOMES, first, optional("of", ArgumentTypes.word, "The page of another player's homes.")))
            }
            for (here in listOf(false, true)) {
                add(if (here) c.tpahere else c.tpa, if (here) "Ask a player to teleport to you." else "Ask to teleport to a player.", PermissionDefault.EVERYONE) {
                    playerOnly()
                    executes(Run(if (here) TPAHERE else TPA, argument("player", Words(plugin, Words.PLAYERS), "Who to ask.")))
                }
            }
            for (accept in listOf(true, false)) {
                add(if (accept) c.tpaccept else c.tpdeny, if (accept) "Accept a teleport request." else "Deny a teleport request.", PermissionDefault.EVERYONE) {
                    playerOnly()
                    executes(Run(if (accept) TPACCEPT else TPDENY, optional("player", Words(plugin, Words.REQUESTS), "Whose request: the newest without one.")))
                }
            }
            add(c.tpacancel, "Take back teleport requests you sent.", PermissionDefault.EVERYONE) {
                playerOnly()
                executes(Run(TPACANCEL, optional("player", Words(plugin, Words.PLAYERS), "Who the request was for: all of them without one.")))
            }
            add(c.tptoggle, "Stop or start getting teleport requests.", PermissionDefault.EVERYONE) {
                playerOnly()
                executes(Run(TPTOGGLE))
            }
            add(c.setwarp, "Set a warp where you stand.", PermissionDefault.OP) {
                playerOnly()
                executes(Run(SETWARP, argument("name", Words(plugin, Words.WARPS), "The warp's name: 1 to 32 letters, digits, - or _.")))
            }
            add(c.warp, "Teleport to a warp.", PermissionDefault.EVERYONE) {
                executes(Run(WARP, optional("name", Words(plugin, Words.WARPS), "Which warp: without one, the list.")))
            }
            add(c.delwarp, "Delete a warp.", PermissionDefault.OP) {
                executes(Run(DELWARP, argument("name", Words(plugin, Words.WARPS), "Which warp.")))
            }
            add(c.warps, "List the warps.", PermissionDefault.EVERYONE) {
                executes(Run(WARPS, optional("page", ArgumentTypes.word, "Which page.")))
            }
            add(c.setspawn, "Set the spawn where you stand.", PermissionDefault.OP) {
                playerOnly()
                executes(Run(SETSPAWN))
            }
            add(c.spawn, "Teleport to the spawn.", PermissionDefault.EVERYONE) {
                playerOnly()
                executes(Run(SPAWN))
            }
            add(c.rtp, "Teleport to a random safe spot.", PermissionDefault.EVERYONE) {
                playerOnly()
                executes(Run(RTP, optional("world", Words(plugin, Words.RTP_WORLDS), "Which world: the one you're in, or rtp.default-world, without one.")))
            }
        }
        for (section in listOf(c.home, c.delhome, c.homes)) {
            if (config[section.enabled]) section.permissionOthers?.let { perms.of(config[it], "Use /${section.command} on another player's homes.") }
        }
        building = null
        permissions = perms
        warnings = problems
        return tree
    }

    // A root command from its config section, unless it's off or its name is taken.
    private inline fun CommandTreeBuilder.add(section: CommandSettings, description: String, default: PermissionDefault, block: CommandBuilder.() -> Unit) {
        val b = building!!
        if (!b.config[section.enabled]) return
        val name = section.command
        if (name in b.taken) {
            b.problems += "/$name is also another of xTeleport's commands' alias, so it's skipped. Remove that alias."
            return
        }
        val aliases = b.config[section.aliases].filter { it != name }.distinct().filter { alias ->
            (alias !in b.taken).also { free -> if (!free) b.problems += "${section.path}.aliases: /$alias is already another xTeleport command's, so it's left out." }
        }
        b.taken += name
        b.taken += aliases
        root(name, description, b.perms.of(b.config[section.permission], "Use /$name.", default), aliases, block)
    }

    /**
     * Every command's handler, as one class told apart by [id], rather than a lambda each: the JVM
     * makes a class of every lambda as it's made (decisions/2026-11-25-a-lighter-core.md).
     */
    private inner class Run(private val id: Int, private vararg val args: Argument<*>) : CommandHandler {
        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> arg(ctx: CommandContext, index: Int): T? = ctx.getOrNull(args[index] as Argument<T>)

        override fun run(context: CommandContext) {
            val a = actions()
            when (id) {
                INFO -> a.info(context)
                IMPORT -> a.importFrom(context, arg<String>(context, 1) != null)
                RTPCHECK -> a.rtpCheck(context, arg<String>(context, 0)!!, arg<Int>(context, 1) ?: 1)
                SETHOME -> a.homes.sethome(context, arg(context, 0))
                HOME -> a.homes.home(context, arg(context, 0))
                DELHOME -> a.homes.delhome(context, arg<String>(context, 0)!!)
                HOMES -> a.homes.list(context, arg(context, 0), arg(context, 1))
                TPA, TPAHERE -> a.tpa.request(context, arg<String>(context, 0)!!, id == TPAHERE)
                TPACCEPT, TPDENY -> a.tpa.answer(context, arg(context, 0), id == TPACCEPT)
                TPACANCEL -> a.tpa.cancel(context, arg(context, 0))
                TPTOGGLE -> a.tpa.toggle(context)
                SETWARP -> a.warps.setwarp(context, arg<String>(context, 0)!!)
                WARP -> a.warps.warp(context, arg(context, 0))
                DELWARP -> a.warps.delwarp(context, arg<String>(context, 0)!!)
                WARPS -> a.warps.list(context.source, Actions.page(arg(context, 0)))
                SETSPAWN -> a.warps.setspawn(context)
                SPAWN -> a.warps.spawn(context)
                RTP -> a.rtp(context, arg(context, 0))
            }
        }
    }

    // What the commands do, made the first time one runs: not needed to build the tree, so not
    // loaded as the server starts (decisions/2026-11-25-a-lighter-core.md).
    private var made: Actions? = null

    private fun actions(): Actions = made ?: Actions(plugin).also { made = it }

    private companion object {
        const val INFO = 0
        const val IMPORT = 1
        const val RTPCHECK = 2
        const val SETHOME = 3
        const val HOME = 4
        const val DELHOME = 5
        const val HOMES = 6
        const val TPA = 7
        const val TPAHERE = 8
        const val TPACCEPT = 9
        const val TPDENY = 10
        const val TPACANCEL = 11
        const val TPTOGGLE = 12
        const val SETWARP = 13
        const val WARP = 14
        const val DELWARP = 15
        const val WARPS = 16
        const val SETSPAWN = 17
        const val SPAWN = 18
        const val RTP = 19
    }
}

/**
 * A word, completed from what xTeleport knows: the source's homes (and bed), the warps they may
 * use, the players they can see, those who sent them requests, or the worlds. One class told
 * apart by [kind] rather than an object each; parsing is left to the command, which says what's
 * wrong in xTeleport's own words, as 1.x did.
 */
internal class Words(private val plugin: XTeleportPlugin, private val kind: Int = PLAYERS) : ArgumentType<String> {
    override val name: String get() = if (kind == PLAYERS || kind == REQUESTS) "player" else "word"

    override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> = Outcome.Ok(input)

    override fun suggest(source: CommandSource): List<String> = try {
        plugin.suggestions(source, kind)
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        const val PLAYERS = 0
        const val HOMES = 1
        const val HOMES_AND_BED = 2
        const val WARPS = 3
        const val REQUESTS = 4
        const val WORLDS = 5
        const val RTP_WORLDS = 6
    }
}
