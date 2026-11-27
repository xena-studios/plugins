package co.xenastudios.xvanish

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
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.xvanish.api.VanishCause
import co.xenastudios.xvanish.api.XVanish
import co.xenastudios.xvanish.config.XVanishConfig

/**
 * xVanish's permissions. `xvanish.level.<n>` aren't declared one by one: any number up to
 * `vanish.max-level` is read. With `vanish.other-plugins-permissions`, SuperVanish's and
 * PremiumVanish's nodes are declared too, each granting xVanish's.
 */
public class XVanishPermissions internal constructor(otherPlugins: Boolean) : PermissionSet(XVanishPlugin.PLUGIN_ID) {
    public val vanish: Permission = permission("vanish", "Vanish and show again with /vanish.")
    public val others: Permission = permission("vanish.others", "Vanish and show other players: /vanish <player>, /vanish on <player>.")
    public val list: Permission = permission("list", "See who's vanished with /vanish list.")
    public val see: Permission = node(XVanish.SEE_PERMISSION, "See vanished players whose level is at most yours.")
    public val join: Permission = permission("join", "Join vanished, and silently, every time.")
    public val login: Permission = permission("login", "Send a fake join or quit message with /vanish login and /vanish logout.")
    public val pickup: Permission = permission("pickup", "Pick up items and experience while vanished, with /vanish pickup.")

    init {
        if (otherPlugins) {
            for (plugin in listOf("sv", "pv")) {
                node("$plugin.use", "SuperVanish's and PremiumVanish's /vanish (other-plugins-permissions).", children = grants(vanish))
                node("$plugin.see", "Theirs: see vanished players.", children = grants(see))
                node("$plugin.list", "Theirs: /vanish list.", children = grants(list))
                node("$plugin.login", "Theirs: /vanish login.", children = grants(login))
                node("$plugin.logout", "Theirs: /vanish logout.", children = grants(login))
                node("$plugin.toggleitems", "Theirs: /vanish pickup.", children = grants(pickup))
                node("$plugin.others", "Theirs: vanish other players.", children = grants(others))
                node("$plugin.joinvanished", "Theirs: join vanished.", children = grants(join))
            }
        }
    }
}

/** Builds `/vanish` and `/xvanish` from the config, and runs them. */
internal class Commands(private val plugin: XVanishPlugin) {
    lateinit var permissions: XVanishPermissions
        private set

    var warnings: List<String> = emptyList()
        private set

    fun tree(config: Config): CommandTree {
        val perms = XVanishPermissions(config[XVanishConfig.vanish.otherPluginsPermissions])
        permissions = perms
        val problems = ArrayList<String>()
        val aliases = config[XVanishConfig.vanish.aliases].map { it.lowercase() }.distinct().filter { alias ->
            val ok = alias != "vanish" && alias != XVanishPlugin.PLUGIN_ID && Regex("[a-z0-9][a-z0-9_-]*").matches(alias)
            if (!ok) problems += "vanish.aliases: /$alias can't be an alias of /vanish, so it's left out."
            ok
        }
        warnings = problems
        return commands {
            root(XVanishPlugin.PLUGIN_ID, "Shows xVanish's version, and holds its admin subcommands.") {
                executes(Run(INFO))
                literal("info", "Shows xVanish's version.") { executes(Run(INFO)) }
                mount(plugin.admin)
            }
            root("vanish", "Vanish, show again, or see who's vanished.", perms.vanish, aliases) {
                val what = optional("what", Words(plugin, WHAT), "on, off, list, login, logout or pickup; or a player, to vanish or show them.")
                val who = optional("player", Words(plugin, PLAYERS), "With on or off: whom.")
                executes(Run(VANISH, what, who))
            }
        }
    }

    private inner class Run(private val id: Int, private vararg val args: Argument<*>) : CommandHandler {
        @Suppress("UNCHECKED_CAST")
        private fun word(ctx: CommandContext, index: Int): String? = ctx.getOrNull(args[index] as Argument<String>)

        override fun run(context: CommandContext) {
            when (id) {
                INFO -> plugin.texts.reply(
                    context.source, XVanishMessages.info,
                    "version" to plugin.version, "platform" to plugin.platform.name, "count" to plugin.vanished().size,
                )
                else -> vanish(context.source, word(context, 0)?.lowercase(), word(context, 1))
            }
        }
    }

    private fun vanish(source: CommandSource, what: String?, who: String?) {
        val p = permissions
        val texts = plugin.texts
        when (what) {
            null, "toggle" -> self(source, null, who)
            "on" -> self(source, true, who)
            "off" -> self(source, false, who)
            "list" -> if (!source.hasPermission(p.list.node)) texts.reply(source, CoreMessages.commandNoPermission) else list(source)
            "login", "logout" -> {
                val player = source.player ?: return texts.reply(source, CoreMessages.commandPlayersOnly)
                if (!source.hasPermission(p.login.node)) return texts.reply(source, CoreMessages.commandNoPermission)
                plugin.fakeByHand(player, joined = what == "login")
                texts.reply(source, if (what == "login") XVanishMessages.loginSent else XVanishMessages.logoutSent)
            }
            "pickup" -> {
                val player = source.player ?: return texts.reply(source, CoreMessages.commandPlayersOnly)
                if (!source.hasPermission(p.pickup.node)) return texts.reply(source, CoreMessages.commandNoPermission)
                texts.reply(source, if (plugin.togglePickup(player)) XVanishMessages.pickupOn else XVanishMessages.pickupOff)
            }
            // A player's name: vanish or show them.
            else -> self(source, null, what)
        }
    }

    // /vanish [on|off] [player]: [vanished] null to toggle.
    private fun self(source: CommandSource, vanished: Boolean?, who: String?) {
        val texts = plugin.texts
        val target = if (who == null) {
            source.player ?: return texts.reply(source, CoreMessages.commandPlayersOnly)
        } else {
            if (!source.hasPermission(permissions.others.node)) return texts.reply(source, CoreMessages.commandNoPermission)
            plugin.find(who, source) ?: return texts.reply(source, CoreMessages.commandUnknownPlayer, "player" to who)
        }
        val to = vanished ?: !plugin.isVanished(target)
        plugin.change(target, to, silent = false, cause = VanishCause.COMMAND, by = source)
    }

    private fun list(source: CommandSource) {
        val texts = plugin.texts
        val here = plugin.vanished()
        val elsewhere = plugin.network?.elsewhere(here).orEmpty()
        if (here.isEmpty() && elsewhere.isEmpty()) return texts.reply(source, XVanishMessages.listNone)
        texts.reply(source, XVanishMessages.listHeader, "count" to here.size + elsewhere.size)
        for (player in here.sortedBy { plugin.server.name(it)?.lowercase() }) {
            texts.reply(source, XVanishMessages.listEntry, "player" to (plugin.server.name(player) ?: player.toString()), "level" to plugin.level(player))
        }
        for (name in elsewhere.sortedBy { it.lowercase() }) texts.reply(source, XVanishMessages.listElsewhere, "player" to name)
    }

    private companion object {
        const val INFO = 0
        const val VANISH = 1
        const val WHAT = 0
        const val PLAYERS = 1
    }
}

/** `/vanish`'s words, completed: its subcommands and the players the source can see, or only players. */
internal class Words(private val plugin: XVanishPlugin, private val kind: Int) : ArgumentType<String> {
    override val name: String get() = "word"

    override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> = Outcome.Ok(input)

    override fun suggest(source: CommandSource): List<String> = try {
        val names = plugin.names(source)
        if (kind == 0) SUBCOMMANDS + names else names
    } catch (e: Exception) {
        emptyList()
    }

    private companion object {
        val SUBCOMMANDS = listOf("on", "off", "list", "login", "logout", "pickup")
    }
}
