package co.xenastudios.xplots

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
import co.xenastudios.xplots.model.Settings

/** xPlots' permissions: 1.x's, with 1.x's defaults, and `xplots.rollback` (2.0). `xplots.admin` is the admin subcommands'. */
public class XPlotsPermissions internal constructor() : PermissionSet(XPlotsPlugin.PLUGIN_ID) {
    public val use: Permission = permission("use", "Renting plots, and /plots.", PermissionDefault.EVERYONE)
    public val warmupBypass: Permission = permission("warmup.bypass", "Teleporting with /plots home and /plots warp without the warmup.")
    public val bypass: Permission = permission("bypass", "Building and opening anything in any field.")
    public val rollback: Permission = permission(
        "rollback", "Undoing what a member did in your own plot with /plots rollback (needs xRewind 2.0).", PermissionDefault.EVERYONE,
    )
}

/** Builds the command tree, and runs each command (in [Actions], made the first time one runs). */
internal class Commands(private val plugin: XPlotsPlugin) {
    lateinit var permissions: XPlotsPermissions
        private set

    fun tree(): CommandTree {
        val perms = XPlotsPermissions()
        permissions = perms
        val admin = plugin.admin.admin
        val fields = Names(plugin, FIELDS)
        val plotIds = Names(plugin, PLOTS)
        val players = Names(plugin, PLAYERS)
        return commands {
            root("plots", "Your plots: rent, members, teleport.", perms.use, listOf("plot")) {
                playerOnly()
                executes(Run(MINE))
                literal("add", "Adds a member to your plot: the one you're standing in, or your only one.") {
                    playerOnly()
                    executes(Run(ADD, argument("player", players, "Who: they must have played here.")))
                }
                literal("remove", "Removes a member from your plot.") {
                    playerOnly()
                    executes(Run(REMOVE, argument("player", Names(plugin, MEMBERS), "Which member.")))
                }
                literal("home", "Teleports to your plot, after the warmup.") {
                    playerOnly()
                    executes(Run(HOME, optional("plot", Names(plugin, OWNED), "Which of your plots.")))
                }
                literal("warp", "Teleports to a field's warp.") {
                    playerOnly()
                    executes(Run(WARP, optional("field", Names(plugin, WARPS), "Which field.")))
                }
                literal("rollback", "Undoes what a player did in your plot, with xRewind: the last day, or as long as you say.", perms.rollback) {
                    playerOnly()
                    executes(Run(
                        ROLLBACK_OWN, argument("player", players, "Whose changes."),
                        optional("time", ArgumentTypes.duration(kotlin.time.Duration.ZERO), "How far back: 30m, 6h, 2d. A day if left out."),
                    ))
                }
            }
            root(XPlotsPlugin.PLUGIN_ID, "Rentable plots, set up in a minute: xPlots' staff commands.") {
                executes(Run(INFO))
                literal("info", "Shows xPlots' version, its fields and where they're kept.", admin) { executes(Run(INFO)) }
                literal("wand", "The selection wand.", admin) {
                    playerOnly()
                    executes(Run(WAND))
                }
                literal("select", "Selects by typing the corners, as from the console; with none, your WorldEdit selection.", admin) {
                    executes(Run(SELECT, optional("corners", ArgumentTypes.text, "<world> <x1> <y1> <z1> <x2> <y2> <z2>")))
                }
                literal("field", "Fields: create, delete, list, info, set, signs, setwarp.", admin) {
                    literal("create", "Splits the selection into a field of plots and previews it.") {
                        executes(Run(FIELD_CREATE, argument("name", ArgumentTypes.word, "The field's name: plots are named after it."),
                            argument("options", Options, "size:<n or WxD> and path:<n>, such as size:5 path:2.")))
                    }
                    literal("delete", "Deletes a field and its plots, after you confirm.") { executes(Run(FIELD_DELETE, argument("field", fields, "Which field."))) }
                    literal("list", "Every field, and how many of its plots are rented.") { executes(Run(FIELD_LIST)) }
                    literal("info", "A field's size and its settings.") { executes(Run(FIELD_INFO, argument("field", fields, "Which field."))) }
                    literal("set", "Changes one of a field's settings.") {
                        executes(Run(FIELD_SET, argument("field", fields, "Which field."), argument("setting", SettingKeys, "Which setting."),
                            argument("value", SettingValue, "Its value, or default for the config's.")))
                    }
                    literal("signs", "Moves every sign in the field to where its sign settings say.") { executes(Run(FIELD_SIGNS, argument("field", fields, "Which field."))) }
                    literal("setwarp", "Sets the field's warp where you stand, or removes it.") {
                        executes(Run(FIELD_WARP, argument("field", fields, "Which field."), optional("remove", ArgumentTypes.choice("remove"), "remove takes it away.")))
                    }
                }
                literal("plot", "Plots: create, delete, info, evict, reset, setowner, addmember, removemember, rollback.", admin) {
                    literal("create", "Makes one plot from the selection, in the field.") { executes(Run(PLOT_CREATE, argument("field", fields, "Which field."))) }
                    literal("delete", "Deletes a plot.") { executes(Run(PLOT_DELETE, optional("plot", plotIds, "Which plot: the one you stand in if left out."))) }
                    literal("info", "A plot's size, owner, time left and members.") { executes(Run(PLOT_INFO, optional("plot", plotIds, "Which plot."))) }
                    literal("evict", "Ends its rent now.") { executes(Run(PLOT_EVICT, optional("plot", plotIds, "Which plot."))) }
                    literal("reset", "Puts it back as it was made. The renter keeps it.") { executes(Run(PLOT_RESET, optional("plot", plotIds, "Which plot."))) }
                    literal("setowner", "Gives a plot to a player.") {
                        executes(Run(PLOT_OWNER, argument("plot", plotIds, "Which plot."), argument("player", players, "Who.")))
                    }
                    literal("addmember", "Adds a member, ignoring slots.") {
                        executes(Run(PLOT_ADD, argument("plot", plotIds, "Which plot."), argument("player", players, "Who.")))
                    }
                    literal("removemember", "Removes a member.") {
                        executes(Run(PLOT_REMOVE, argument("plot", plotIds, "Which plot."), argument("player", players, "Who.")))
                    }
                    literal("rollback", "Undoes what a player did in the plot, with xRewind.") {
                        executes(Run(PLOT_ROLLBACK, argument("plot", plotIds, "Which plot."), argument("time", ArgumentTypes.duration(kotlin.time.Duration.ZERO), "How far back: 30m, 6h, 2d."),
                            argument("player", players, "Whose changes.")))
                    }
                }
                literal("confirm", "Makes what's waiting: a previewed field, or deleting one.", admin) { executes(Run(CONFIRM)) }
                literal("cancel", "Drops what's waiting.", admin) { executes(Run(CANCEL)) }
                mount(plugin.admin)
            }
        }
    }

    /** Every command's handler, as one class told apart by [id], rather than a lambda each. */
    private inner class Run(private val id: Int, private vararg val args: Argument<*>) : CommandHandler {
        override fun run(context: CommandContext) {
            val values = args.map { context.getOrNull(it) }
            actions().run(id, context, values)
        }
    }

    private var made: Actions? = null

    fun actions(): Actions = made ?: Actions(plugin).also { made = it }

    /** Names completed from what xPlots knows: fields, plots, players. Any word is taken; the action says if it's wrong. */
    private class Names(private val plugin: XPlotsPlugin, private val kind: Int) : ArgumentType<String> {
        override val name: String get() = "name"

        override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> = Outcome.Ok(input)

        override fun suggest(source: CommandSource): List<String> = try {
            when (kind) {
                FIELDS -> plugin.plots.fields().map { it.name }.sorted()
                PLOTS -> plugin.plots.plotIds()
                PLAYERS -> {
                    val viewer = source.player
                    plugin.server.online().filter { viewer == null || plugin.ui.visible(viewer, it) }.mapNotNull(plugin.server::name).sorted()
                }
                MEMBERS -> source.player?.let { p -> plugin.plots.owned(p).flatMap { it.lease?.members?.values.orEmpty() } }.orEmpty()
                OWNED -> source.player?.let { p -> plugin.plots.owned(p).map { it.id } }.orEmpty()
                else -> plugin.plots.fields().filter { it.warp != null }.map { it.name }.sorted()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private object Options : ArgumentType<String> {
        override val name: String get() = "options"
        override val greedy: Boolean get() = true

        override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> = Outcome.Ok(input)

        override fun suggest(source: CommandSource): List<String> = listOf("size:5", "size:5x7", "path:1", "path:2")
    }

    private object SettingKeys : ArgumentType<String> {
        override val name: String get() = "setting"
        override val choices: List<String> get() = Settings.KEYS

        override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> = Outcome.Ok(input.lowercase())
    }

    private object SettingValue : ArgumentType<String> {
        override val name: String get() = "value"
        override val greedy: Boolean get() = true

        override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> = Outcome.Ok(input)

        override fun suggest(source: CommandSource): List<String> = listOf("default", "true", "false", "3d", "1w", "100") +
            Settings.values("sign-position") + Settings.values("sign-rotation")
    }

    companion object {
        const val MINE = 0
        const val ADD = 1
        const val REMOVE = 2
        const val HOME = 3
        const val WARP = 4
        const val ROLLBACK_OWN = 5
        const val INFO = 10
        const val WAND = 11
        const val SELECT = 12
        const val FIELD_CREATE = 20
        const val FIELD_DELETE = 21
        const val FIELD_LIST = 22
        const val FIELD_INFO = 23
        const val FIELD_SET = 24
        const val FIELD_SIGNS = 25
        const val FIELD_WARP = 26
        const val PLOT_CREATE = 30
        const val PLOT_DELETE = 31
        const val PLOT_INFO = 32
        const val PLOT_EVICT = 33
        const val PLOT_RESET = 34
        const val PLOT_OWNER = 35
        const val PLOT_ADD = 36
        const val PLOT_REMOVE = 37
        const val PLOT_ROLLBACK = 38
        const val CONFIRM = 40
        const val CANCEL = 41

        private const val FIELDS = 0
        private const val PLOTS = 1
        private const val PLAYERS = 2
        private const val MEMBERS = 3
        private const val OWNED = 4
        private const val WARPS = 5
    }
}
