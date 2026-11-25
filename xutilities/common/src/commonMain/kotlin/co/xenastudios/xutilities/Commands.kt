package co.xenastudios.xutilities

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.Argument
import co.xenastudios.core.common.command.ArgumentError
import co.xenastudios.core.common.command.ArgumentType
import co.xenastudios.core.common.command.ArgumentTypes
import co.xenastudios.core.common.command.CommandBuilder
import co.xenastudios.core.common.command.CommandTreeBuilder
import co.xenastudios.core.common.command.CommandContext
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.CommandTree
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.common.permission.has
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Markup
import co.xenastudios.xutilities.api.event.BackRecordEvent
import co.xenastudios.xutilities.api.event.BackTeleportEvent
import co.xenastudios.xutilities.config.CommandSection
import co.xenastudios.xutilities.config.SoundCommand
import co.xenastudios.xutilities.config.XUtilitiesConfig

/**
 * xUtilities' permissions, as the config names them: each command's, and its `-others` one,
 * declared with the command's default (operators, but everyone for `/ping` and link commands).
 * Two commands given the same node share one. Built again on every reload.
 */
public class XUtilitiesPermissions internal constructor(private val admin: PermissionSet) : PermissionSet(XUtilitiesPlugin.PLUGIN_ID) {
    internal fun of(node: String, description: String, default: PermissionDefault = PermissionDefault.OP): Permission? {
        if (node.isEmpty()) return null
        admin[node]?.let { return it }
        return this[node] ?: node(node, description, default)
    }
}

/** Builds the command tree from the config, and runs each command. */
internal class Commands(private val plugin: XUtilitiesPlugin, private val platform: Platform, private val players: OnlinePlayers) {

    private val server get() = plugin.server
    private val texts get() = plugin.texts

    /** The permissions the last tree checks. */
    lateinit var permissions: XUtilitiesPermissions
        private set

    /** What building the last tree found wrong: link commands that clash. */
    var warnings: List<String> = emptyList()
        private set

    private class Mode(val mode: GameMode, val section: CommandSection, val self: MessageKey, val others: MessageKey, val word: MessageKey)

    fun tree(config: Config): CommandTree {
        val perms = XUtilitiesPermissions(plugin.admin.permissions)
        val problems = ArrayList<String>()
        val c = XUtilitiesConfig.commands

        fun permission(section: CommandSection, default: PermissionDefault = PermissionDefault.OP): Permission? =
            perms.of(config[section.permission], "Use /${section.command}.", default)

        fun others(section: CommandSection): Permission? =
            section.permissionOthers?.let { perms.of(config[it], "Use /${section.command} on other players.") }

        val modes = listOf(
            Mode(GameMode.SURVIVAL, c.survival, XUtilitiesMessages.survivalSelf, XUtilitiesMessages.survivalOthers, XUtilitiesMessages.modeSurvival),
            Mode(GameMode.CREATIVE, c.creative, XUtilitiesMessages.creativeSelf, XUtilitiesMessages.creativeOthers, XUtilitiesMessages.modeCreative),
            Mode(GameMode.ADVENTURE, c.adventure, XUtilitiesMessages.adventureSelf, XUtilitiesMessages.adventureOthers, XUtilitiesMessages.modeAdventure),
            Mode(GameMode.SPECTATOR, c.spectator, XUtilitiesMessages.spectatorSelf, XUtilitiesMessages.spectatorOthers, XUtilitiesMessages.modeSpectator),
        ).filter { config[it.section.enabled] }

        // Every label a root takes, for the link commands' clash check.
        val taken = HashSet<String>()

        fun CommandTreeBuilder.add(name: String, description: String, permission: Permission?, aliases: List<String>, block: CommandBuilder.() -> Unit) {
            if (name in taken) {
                problems += "/$name is also another of xUtilities' commands' alias, so it's skipped. Remove that alias."
                return
            }
            val distinct = aliases.filter { it != name }.distinct().filter { alias ->
                (alias !in taken).also { free -> if (!free) problems += "commands.$name.aliases: /$alias is already another xUtilities command's, so it's left out." }
            }
            taken += name
            taken += distinct
            root(name, description, permission, distinct, block)
        }

        // A command with a [player] form whose only work is [run].
        fun CommandTreeBuilder.simple(section: CommandSection, description: String, run: (CommandContext, PlayerRef) -> Unit) {
            if (!config[section.enabled]) return
            val others = others(section)
            add(section.command, description, permission(section), config[section.aliases]) {
                val target = optional("player", PlayerArgument(players, others), "The player to change, if not you.")
                executes { ctx -> run(ctx, target(ctx, target, others)) }
            }
        }

        fun CommandTreeBuilder.station(section: SoundCommand, station: Station, what: String, opened: MessageKey) {
            if (!config[section.enabled]) return
            add(section.command, "Open $what.", permission(section), config[section.aliases]) {
                playerOnly()
                executes { ctx ->
                    server.features.stations[station]?.let { fail(ctx, XUtilitiesMessages.menuUnavailable, "command" to section.command, "needs" to it) }
                    val player = ctx.player
                    val sound = config[section.sound]
                    onPlayer(player) {
                        server.openStation(player, station)
                        send(player, opened)
                        server.playSound(player, sound)
                    }
                }
            }
        }

        val tree = commands {
            taken += listOf(XUtilitiesPlugin.PLUGIN_ID, "xu")
            root(XUtilitiesPlugin.PLUGIN_ID, "Shows xUtilities' version, and holds its admin subcommands.", aliases = listOf("xu")) {
                executes { ctx -> info(ctx) }
                literal("info", "Shows xUtilities' version and how many commands are on.") { executes { ctx -> info(ctx) } }
                mount(plugin.admin)
            }

            for (mode in modes) {
                val section = mode.section
                add(section.command, "Switch to ${mode.mode.label} mode.", permission(section), config[section.aliases]) {
                    val target = optional("player", PlayerArgument(players, others(section)), "The player to change, if not you.")
                    executes { ctx -> gamemode(ctx, target(ctx, target, others(section)), mode) }
                }
            }
            if (config[c.gamemode.enabled] && modes.isNotEmpty()) {
                add("gamemode", "Switch gamemode.", permission(c.gamemode), config[c.gamemode.aliases]) {
                    val mode = argument("mode", ModeArgument(modes, config) { permission(it) }, "The gamemode: a name, a number or an alias such as gmc.")
                    val target = optional("player", PlayerArgument(players, others(c.gamemode)), "The player to change, if not you.")
                    executes { ctx ->
                        val chosen = ctx[mode]
                        if (!ctx.source.has(permission(chosen.section))) fail(ctx, CoreMessages.commandNoPermission)
                        gamemode(ctx, target(ctx, target, others(c.gamemode)), chosen)
                    }
                }
            }

            simple(c.fly, "Turn flight on or off.") { ctx, target ->
                change(ctx, target) {
                    val on = server.toggleFlight(target)
                    val state = if (on) XUtilitiesMessages.stateEnabled else XUtilitiesMessages.stateDisabled
                    Done(if (on) XUtilitiesMessages.flyOnSelf else XUtilitiesMessages.flyOffSelf, if (on) XUtilitiesMessages.flyOnOthers else XUtilitiesMessages.flyOffOthers, arrayOf("state" to state))
                }
            }

            if (config[c.speed.enabled]) {
                val min = minOf(config[c.speed.min], config[c.speed.max])
                val max = maxOf(config[c.speed.min], config[c.speed.max])
                add("speed", "Set your fly or walk speed.", permission(c.speed), config[c.speed.aliases]) {
                    val speed = argument("speed", SpeedArgument, "From $min to $max: 1 is slow, 2 is walking, 10 is fastest.")
                    val target = optional("player", PlayerArgument(players, others(c.speed)), "The player to change, if not you.")
                    executes { ctx ->
                        val value = ctx[speed].toDoubleOrNull()?.takeIf { it in min.toDouble()..max.toDouble() }
                            ?: fail(ctx, XUtilitiesMessages.speedInvalid, "min" to min, "max" to max)
                        val player = target(ctx, target, others(c.speed))
                        change(ctx, player) {
                            val flying = server.isFlying(player)
                            // 1 to 10 is Minecraft's 0.1 to 1.0.
                            server.setSpeed(player, flying, (value / 10).toFloat())
                            val type = if (flying) XUtilitiesMessages.speedFlying else XUtilitiesMessages.speedWalking
                            Done(XUtilitiesMessages.speedSelf, XUtilitiesMessages.speedOthers, arrayOf("speed" to number(value), "type" to type))
                        }
                    }
                }
            }

            if (config[c.top.enabled]) {
                add("top", "Teleport to the highest block above you.", permission(c.top), config[c.top.aliases]) {
                    playerOnly()
                    executes { ctx -> top(ctx.player) }
                }
            }

            if (config[c.setrotation.enabled]) {
                add("setrotation", "Set which way a player faces.", permission(c.setrotation), config[c.setrotation.aliases]) {
                    val yaw = argument("yaw", ArgumentTypes.number(-360.0, 360.0), "Degrees around: 0 is south, 90 west.")
                    val pitch = argument("pitch", ArgumentTypes.number(-90.0, 90.0), "Degrees up or down: -90 is straight up.")
                    val target = optional("player", PlayerArgument(players, others(c.setrotation)), "The player to turn, if not you.")
                    executes { ctx ->
                        val player = target(ctx, target, others(c.setrotation))
                        val y = ctx[yaw]
                        val p = ctx[pitch]
                        change(ctx, player) {
                            server.setRotation(player, y.toFloat(), p.toFloat())
                            // As typed: a float widened back would say 12.300000190734863.
                            Done(XUtilitiesMessages.setrotationSelf, XUtilitiesMessages.setrotationOthers, arrayOf("yaw" to number(y), "pitch" to number(p)))
                        }
                    }
                }
            }

            if (config[c.ping.enabled]) {
                add("ping", "Show network latency.", permission(c.ping, PermissionDefault.EVERYONE), config[c.ping.aliases]) {
                    val target = optional("player", PlayerArgument(players, others(c.ping)), "Whose ping, if not yours.")
                    executes { ctx ->
                        server.features.ping?.let { fail(ctx, XUtilitiesMessages.unavailable, "command" to "ping", "needs" to it) }
                        val player = target(ctx, target, others(c.ping))
                        val ping = server.ping(player)
                        if (player != ctx.source.player) {
                            texts.reply(ctx.source, XUtilitiesMessages.pingOthers, "player" to (server.name(player) ?: "?"), "ping" to ping)
                        } else {
                            texts.reply(ctx.source, XUtilitiesMessages.pingSelf, "ping" to ping)
                        }
                    }
                }
            }

            if (config[c.back.enabled]) {
                add("back", "Go back to where you were before your last teleport, or where you died.", permission(c.back), config[c.back.aliases]) {
                    playerOnly()
                    executes { ctx -> back(ctx.player) }
                }
            }

            simple(c.heal, "Restore health.") { ctx, target ->
                change(ctx, target) {
                    val healed = server.heal(target, config[c.heal.restoreHunger], config[c.heal.extinguishFire], config[c.heal.clearNegativeEffects])
                    if (healed) Done(XUtilitiesMessages.healSelf, XUtilitiesMessages.healOthers) else null
                }
            }
            simple(c.feed, "Restore hunger.") { ctx, target ->
                change(ctx, target) {
                    server.feed(target)
                    Done(XUtilitiesMessages.feedSelf, XUtilitiesMessages.feedOthers)
                }
            }

            if (config[c.repair.enabled]) {
                add("repair", "Repair the held item, or with all, the whole inventory.", permission(c.repair), config[c.repair.aliases]) {
                    val first = optional("all", RepairArgument(players, others(c.repair)), "all for the whole inventory, or a player.")
                    val second = optional("player", PlayerArgument(players, others(c.repair)), "The player whose gear, after all.")
                    executes { ctx ->
                        val choice = ctx.getOrNull(first)
                        val all = choice is RepairChoice.All
                        val named = (choice as? RepairChoice.Of)?.player
                        if (named != null && ctx.getOrNull(second) != null) fail(ctx, CoreMessages.commandUsage, "usage" to ctx.node.usage)
                        val player = named?.let { check(ctx, it, others(c.repair)) } ?: target(ctx, second, others(c.repair))
                        change(ctx, player, nothing = XUtilitiesMessages.repairNothing) {
                            if (server.repair(player, all) == 0) null else Done(XUtilitiesMessages.repairSelf, XUtilitiesMessages.repairOthers)
                        }
                    }
                }
            }

            simple(c.clear, "Empty an inventory.") { ctx, target ->
                change(ctx, target) {
                    server.clear(target, config[c.clear.includeArmor])
                    Done(XUtilitiesMessages.clearSelf, XUtilitiesMessages.clearOthers)
                }
            }

            if (config[c.enderchest.enabled]) {
                add("enderchest", "Open your Ender Chest, or another player's.", permission(c.enderchest), config[c.enderchest.aliases]) {
                    playerOnly()
                    val target = optional("player", PlayerArgument(players, others(c.enderchest)), "Whose Ender Chest, if not yours.")
                    executes { ctx ->
                        val viewer = ctx.player
                        val owner = target(ctx, target, others(c.enderchest))
                        val sound = config[c.enderchest.sound]
                        onPlayer(viewer) {
                            val name = server.name(owner) ?: return@onPlayer
                            if (!server.openEnderChest(viewer, owner)) {
                                send(viewer, XUtilitiesMessages.enderchestTooFar, "player" to name)
                                return@onPlayer
                            }
                            server.playSound(viewer, sound)
                            if (owner != viewer) send(viewer, XUtilitiesMessages.enderchestOthers, "player" to name)
                        }
                    }
                }
            }

            station(c.anvil, Station.ANVIL, "an anvil", XUtilitiesMessages.anvilOpened)
            station(c.craft, Station.CRAFT, "a crafting table", XUtilitiesMessages.craftOpened)
            station(c.grinder, Station.GRINDER, "a grindstone", XUtilitiesMessages.grinderOpened)
            station(c.loom, Station.LOOM, "a loom", XUtilitiesMessages.loomOpened)
            station(c.smith, Station.SMITH, "a smithing table", XUtilitiesMessages.smithOpened)
            station(c.stonecutter, Station.STONECUTTER, "a stonecutter", XUtilitiesMessages.stonecutterOpened)
            station(c.cartography, Station.CARTOGRAPHY, "a cartography table", XUtilitiesMessages.cartographyOpened)

            if (config[c.trash.enabled]) {
                add("trash", "Open a menu that deletes what's left in it.", permission(c.trash), config[c.trash.aliases]) {
                    playerOnly()
                    executes { ctx ->
                        val player = ctx.player
                        onPlayer(player) {
                            server.openTrash(player, texts.render(player, XUtilitiesMessages.trashTitle) ?: Component.EMPTY)
                            send(player, XUtilitiesMessages.trashOpened)
                            server.playSound(player, config[c.trash.sound])
                        }
                    }
                }
            }

            // The link commands, last: one whose name or alias another command has is skipped.
            val theme = texts.translations.theme
            for ((name, link) in config[XUtilitiesConfig.infoCommands]) {
                if (!link.enabled) continue
                val labels = listOf(name) + link.aliases
                val clash = labels.firstOrNull { it in taken }
                if (clash != null) {
                    problems += "info-commands.$name: /$clash is already one of xUtilities' commands, so this link command is skipped."
                    continue
                }
                taken += labels
                val node = link.permission ?: "xutilities.command.$name"
                val message = theme.chat(Markup.parse(link.message, theme.tags).render())
                root(name, "Show the $name link.", perms.of(node, "Use /$name.", PermissionDefault.EVERYONE), link.aliases.filter { it != name }.distinct()) {
                    executes { ctx -> if (link.message.isNotBlank()) ctx.reply(message) }
                }
            }
        }
        permissions = perms
        warnings = problems
        return tree
    }

    private fun info(ctx: CommandContext) {
        texts.reply(
            ctx.source, XUtilitiesMessages.info,
            "version" to plugin.version, "platform" to platform.name, "commands" to plugin.dispatcher.tree.roots.size,
        )
    }

    private fun gamemode(ctx: CommandContext, target: PlayerRef, mode: Mode) {
        change(ctx, target) {
            server.setGameMode(target, mode.mode)
            Done(mode.self, mode.others, arrayOf("mode" to mode.word))
        }
    }

    private fun top(player: PlayerRef) {
        onPlayer(player) {
            val here = server.location(player) ?: return@onPlayer
            val column = server.column(player)
            val y = column?.let { Top.find(it, here.blockY) }
            if (y == null || y + 1 <= here.y) {
                send(player, XUtilitiesMessages.topUnsafe)
                return@onPlayer
            }
            val to = co.xenastudios.core.api.Location(here.world, here.blockX + 0.5, y + 1.0, here.blockZ + 0.5, here.yaw, here.pitch)
            teleport(player, here, to, XUtilitiesMessages.topDone)
        }
    }

    private fun back(player: PlayerRef) {
        onPlayer(player) {
            val previous = plugin.spots[player]
            if (previous == null || !server.isLoaded(previous.world)) {
                send(player, XUtilitiesMessages.backNone)
                return@onPlayer
            }
            // Made only when something listens.
            if (plugin.bus.hasListeners(BackTeleportEvent.TYPE) && plugin.bus.fire(BackTeleportEvent.TYPE, BackTeleportEvent(player, previous)).isCancelled) return@onPlayer
            val here = server.location(player) ?: return@onPlayer
            teleport(player, here, previous, XUtilitiesMessages.backDone)
        }
    }

    // A teleport by a command: /back hears it from the server where it can, and from here where it can't.
    private fun teleport(player: PlayerRef, from: co.xenastudios.core.api.Location, to: co.xenastudios.core.api.Location, done: MessageKey) {
        server.teleport(player, to) { moved ->
            if (!moved) return@teleport
            if (!server.features.teleportEvents) plugin.spots.record(player, from, BackRecordEvent.Reason.TELEPORT)
            send(player, done)
        }
    }

    /** What a change tells the changed player ([self]) and whoever changed them ([others]), with these placeholders. */
    private class Done(val self: MessageKey, val others: MessageKey, val placeholders: Array<Pair<String, Any>> = emptyArray())

    /**
     * Changes [target] on their own thread, then tells them; when someone else did it, tells
     * them too, with `<player>`. A change that returns `null` did nothing: [nothing] is said to
     * whoever ran the command, if given.
     */
    private fun change(ctx: CommandContext, target: PlayerRef, nothing: MessageKey? = null, action: () -> Done?) {
        val source = ctx.source
        onPlayer(target) {
            val done = action()
            if (done == null) {
                nothing?.let { texts.reply(source, it) }
                return@onPlayer
            }
            send(target, done.self, *done.placeholders)
            if (target != source.player) {
                texts.reply(source, done.others, *done.placeholders, "player" to (server.name(target) ?: "?"))
            }
        }
    }

    private fun send(player: PlayerRef, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        texts.render(player, message, *placeholders)?.let { server.send(player, it) }
    }

    private fun onPlayer(player: PlayerRef, action: () -> Unit) {
        platform.scheduler.now(Where.on(player)) { action() }
    }

    /**
     * The player a `[player]` form names, or whoever ran it: the console must name one. Someone
     * else needs [others] (none needed when it's blank).
     */
    private fun target(ctx: CommandContext, argument: Argument<PlayerRef>, others: Permission?): PlayerRef {
        val target = ctx.getOrNull(argument) ?: ctx.source.player ?: fail(ctx, CoreMessages.commandPlayersOnly)
        return check(ctx, target, others)
    }

    private fun check(ctx: CommandContext, target: PlayerRef, others: Permission?): PlayerRef {
        if (target != ctx.source.player && !ctx.source.has(others)) fail(ctx, CoreMessages.commandNoPermission)
        return target
    }

    private fun fail(ctx: CommandContext, message: MessageKey, vararg placeholders: Pair<String, Any>): Nothing =
        ctx.fail(texts.render(ctx.source, message, *placeholders) ?: Component.EMPTY)

    private companion object {
        /** A number as typed, without a needless `.0`. */
        fun number(value: Double): String =
            if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
    }

    /** A player online, suggested only to those who may name one ([others]). */
    private class PlayerArgument(private val players: OnlinePlayers, private val others: Permission?) : ArgumentType<PlayerRef> {
        override val name: String get() = "player"

        override fun parse(input: String, source: CommandSource): Outcome<PlayerRef, ArgumentError> =
            players.find(input, source)?.let { Outcome.Ok(it) } ?: Outcome.Err(ArgumentError.UnknownPlayer)

        override fun suggest(source: CommandSource): List<String> = if (source.has(others)) players.names(source) else emptyList()
    }

    /** `/speed`'s number, read by the command so a bad one gets `speed.invalid`. */
    private object SpeedArgument : ArgumentType<String> {
        override val name: String get() = "number"
        override val min: Double get() = 1.0
        override val max: Double get() = 10.0

        override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> = Outcome.Ok(input)

        override fun suggest(source: CommandSource): List<String> = listOf("1", "2", "3", "5", "10")
    }

    /** A gamemode by name, number, first letters (`sp`) or one of its command's aliases (`gmc`). */
    private class ModeArgument(private val modes: List<Mode>, private val config: Config, private val permission: (CommandSection) -> Permission?) : ArgumentType<Mode> {
        override val name: String get() = "gamemode"
        override val choices: List<String> = modes.map { it.mode.label }

        override fun parse(input: String, source: CommandSource): Outcome<Mode, ArgumentError> {
            val typed = input.lowercase()
            for (mode in modes) {
                val names = config[mode.section.aliases] + mode.mode.label + mode.mode.id.toString() +
                    (if (mode.mode == GameMode.SPECTATOR) "sp" else mode.mode.label.substring(0, 1))
                if (typed in names) return Outcome.Ok(mode)
            }
            return Outcome.Err(ArgumentError.NotAChoice(choices))
        }

        override fun suggest(source: CommandSource): List<String> = modes.filter { source.has(permission(it.section)) }.map { it.mode.label }
    }

    /** `/repair`'s first word: `all`, or a player. */
    private sealed class RepairChoice {
        object All : RepairChoice()
        class Of(val player: PlayerRef) : RepairChoice()
    }

    private class RepairArgument(private val players: OnlinePlayers, private val others: Permission?) : ArgumentType<RepairChoice> {
        override val name: String get() = "all or player"

        override fun parse(input: String, source: CommandSource): Outcome<RepairChoice, ArgumentError> {
            if (input.equals("all", ignoreCase = true)) return Outcome.Ok(RepairChoice.All)
            return players.find(input, source)?.let { Outcome.Ok(RepairChoice.Of(it)) } ?: Outcome.Err(ArgumentError.UnknownPlayer)
        }

        override fun suggest(source: CommandSource): List<String> = listOf("all") + if (source.has(others)) players.names(source) else emptyList()
    }

}
