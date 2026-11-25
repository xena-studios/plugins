package co.xenastudios.xutilities.config

import co.xenastudios.core.common.config.MigrationScope
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.xutilities.XUtilitiesMessages

/**
 * What 1.x and 2.x configs had that 3.0 keeps elsewhere. 2.x kept every message in
 * `config.yml`; 3.0 keeps them in `lang/<language>.yml`. A message an owner edited is carried
 * into `lang/en.yml` ([messages]); one still on a text 1.x or 2.x shipped isn't, so it gets 3.0's.
 * The theme moves into `messages.theme`, where a colour still on 2.x's default becomes `shared`.
 */
public object Legacy {

    /** A 2.x message setting: its path in the old config, where it goes, and the texts that mean "never edited". */
    public class Moved(public val path: String, public val to: MessageKey, public val shipped: Set<String>)

    private val modes = listOf("survival", "creative", "adventure", "spectator")
    private val stations = listOf("anvil", "craft", "grinder", "loom", "smith", "stonecutter", "cartography")

    /** Every 2.x message setting, with the defaults 2.0 and 1.x shipped for it. */
    public val MOVED: List<Moved> = buildList {
        fun add(path: String, to: MessageKey, vararg shipped: String) {
            this += Moved(path, to, shipped.toSet())
        }
        add("messages.error.player-only", CoreMessages.commandPlayersOnly, "<prefix><error>Only players can use this command.", "<red>Only players can use this command.</red>")
        add("messages.error.no-permission", CoreMessages.commandNoPermission, "<prefix><error>You don't have permission to do that.", "<red>You don't have permission to do that.</red>")
        add(
            "messages.error.player-not-found", CoreMessages.commandUnknownPlayer,
            "<prefix><error>No player named <highlight><player></highlight> is online.", "<red>That player could not be found.</red>",
        )
        add("messages.error.usage", CoreMessages.commandUsage, "<prefix><error>Usage:</error> <usage>")
        add("messages.error.failed", CoreMessages.commandError, "<prefix><error>Something went wrong. The console has the details.")
        add("messages.state.enabled", XUtilitiesMessages.stateEnabled, "enabled")
        add("messages.state.disabled", XUtilitiesMessages.stateDisabled, "disabled")
        val gamemode = mapOf(
            "survival" to (XUtilitiesMessages.survivalSelf to XUtilitiesMessages.survivalOthers),
            "creative" to (XUtilitiesMessages.creativeSelf to XUtilitiesMessages.creativeOthers),
            "adventure" to (XUtilitiesMessages.adventureSelf to XUtilitiesMessages.adventureOthers),
            "spectator" to (XUtilitiesMessages.spectatorSelf to XUtilitiesMessages.spectatorOthers),
        )
        for (mode in modes) {
            val (self, others) = gamemode.getValue(mode)
            add("commands.$mode.message-self", self, "<prefix>Your gamemode is now <highlight><mode></highlight>.", "<gray>Your gamemode is now <aqua><mode></aqua>.</gray>")
            add(
                "commands.$mode.message-others", others,
                "<prefix>Set <highlight><player></highlight>'s gamemode to <highlight><mode></highlight>.",
                "<gray>Set <yellow><player></yellow>'s gamemode to <aqua><mode></aqua>.</gray>",
            )
        }
        add("commands.fly.message-on-self", XUtilitiesMessages.flyOnSelf, "<prefix>Flight <success><state></success>.", "<gray>Flight <green><state></green>.</gray>")
        add("commands.fly.message-off-self", XUtilitiesMessages.flyOffSelf, "<prefix>Flight <error><state></error>.", "<gray>Flight <red><state></red>.</gray>")
        add(
            "commands.fly.message-on-others", XUtilitiesMessages.flyOnOthers,
            "<prefix>Flight <success><state></success> for <highlight><player></highlight>.", "<gray>Flight <green><state></green> for <yellow><player></yellow>.</gray>",
        )
        add(
            "commands.fly.message-off-others", XUtilitiesMessages.flyOffOthers,
            "<prefix>Flight <error><state></error> for <highlight><player></highlight>.", "<gray>Flight <red><state></red> for <yellow><player></yellow>.</gray>",
        )
        add("commands.speed.type-flying", XUtilitiesMessages.speedFlying, "flying")
        add("commands.speed.type-walking", XUtilitiesMessages.speedWalking, "walking")
        add("commands.speed.message-self", XUtilitiesMessages.speedSelf, "<prefix>Your <type> speed is now <highlight><speed></highlight>.", "<gray>Speed set to <aqua><speed></aqua>.</gray>")
        add(
            "commands.speed.message-others", XUtilitiesMessages.speedOthers,
            "<prefix>Set <highlight><player></highlight>'s <type> speed to <highlight><speed></highlight>.", "<gray>Set <yellow><player></yellow>'s speed to <aqua><speed></aqua>.</gray>",
        )
        add("commands.speed.message-invalid", XUtilitiesMessages.speedInvalid, "<prefix><error>Speed must be from <min> to <max>.", "<red>Speed must be between <min> and <max>.</red>")
        add("commands.top.message-success", XUtilitiesMessages.topDone, "<prefix>Teleported to the top.", "<gray>Teleported to the highest block.</gray>")
        add("commands.top.message-unsafe", XUtilitiesMessages.topUnsafe, "<prefix><error>There's no safe block to stand on here.", "<red>No safe block to teleport to here.</red>")
        add(
            "commands.setrotation.message-self", XUtilitiesMessages.setrotationSelf,
            "<prefix>Now facing yaw <highlight><yaw></highlight>, pitch <highlight><pitch></highlight>.",
            "<gray>Rotation set to yaw <aqua><yaw></aqua>, pitch <aqua><pitch></aqua>.</gray>",
        )
        add(
            "commands.setrotation.message-others", XUtilitiesMessages.setrotationOthers,
            "<prefix><highlight><player></highlight> is now facing yaw <highlight><yaw></highlight>, pitch <highlight><pitch></highlight>.",
            "<gray>Set <yellow><player></yellow>'s rotation to yaw <aqua><yaw></aqua>, pitch <aqua><pitch></aqua>.</gray>",
        )
        add("commands.ping.message-self", XUtilitiesMessages.pingSelf, "<prefix>Your ping is <highlight><ping>ms</highlight>.", "<gray>Your ping is <aqua><ping></aqua>ms.</gray>")
        add(
            "commands.ping.message-others", XUtilitiesMessages.pingOthers,
            "<prefix><highlight><player></highlight>'s ping is <highlight><ping>ms</highlight>.", "<gray><yellow><player></yellow>'s ping is <aqua><ping></aqua>ms.</gray>",
        )
        add("commands.back.message-success", XUtilitiesMessages.backDone, "<prefix>Teleported back to your previous location.", "<gray>Teleported to your previous location.</gray>")
        add("commands.back.message-none", XUtilitiesMessages.backNone, "<prefix><error>You have nowhere to go back to.", "<red>You have no previous location to return to.</red>")
        add("commands.heal.message-self", XUtilitiesMessages.healSelf, "<prefix>You've been <success>healed</success>.", "<green>You have been healed.</green>")
        add("commands.heal.message-others", XUtilitiesMessages.healOthers, "<prefix><success>Healed</success> <highlight><player></highlight>.", "<green>Healed <yellow><player></yellow>.</green>")
        add("commands.feed.message-self", XUtilitiesMessages.feedSelf, "<prefix>You've been <success>fed</success>.", "<green>You have been fed.</green>")
        add("commands.feed.message-others", XUtilitiesMessages.feedOthers, "<prefix><success>Fed</success> <highlight><player></highlight>.", "<green>Fed <yellow><player></yellow>.</green>")
        add("commands.repair.message-self", XUtilitiesMessages.repairSelf, "<prefix>Your gear has been <success>repaired</success>.", "<green>Repaired your held item(s).</green>")
        add(
            "commands.repair.message-others", XUtilitiesMessages.repairOthers,
            "<prefix><success>Repaired</success> <highlight><player></highlight>'s gear.", "<green>Repaired <yellow><player></yellow>'s item(s).</green>",
        )
        add("commands.repair.message-nothing", XUtilitiesMessages.repairNothing, "<prefix><error>Nothing needs repairing.", "<red>Nothing to repair.</red>")
        add("commands.clear.message-self", XUtilitiesMessages.clearSelf, "<prefix>Your inventory has been <success>cleared</success>.", "<gray>Your inventory has been cleared.</gray>")
        add(
            "commands.clear.message-others", XUtilitiesMessages.clearOthers,
            "<prefix><success>Cleared</success> <highlight><player></highlight>'s inventory.", "<gray>Cleared <yellow><player></yellow>'s inventory.</gray>",
        )
        add(
            "commands.enderchest.message-others", XUtilitiesMessages.enderchestOthers,
            "<prefix>Opened <highlight><player></highlight>'s ender chest.", "<gray>Opening <yellow><player></yellow>'s ender chest.</gray>",
        )
        add(
            "commands.enderchest.message-too-far", XUtilitiesMessages.enderchestTooFar,
            "<prefix><error><highlight><player></highlight> is too far away to open their ender chest.",
        )
        val opened = mapOf(
            "anvil" to XUtilitiesMessages.anvilOpened, "craft" to XUtilitiesMessages.craftOpened, "grinder" to XUtilitiesMessages.grinderOpened,
            "loom" to XUtilitiesMessages.loomOpened, "smith" to XUtilitiesMessages.smithOpened, "stonecutter" to XUtilitiesMessages.stonecutterOpened,
            "cartography" to XUtilitiesMessages.cartographyOpened,
        )
        for (station in stations) add("commands.$station.message", opened.getValue(station), "")
        add("commands.trash.title", XUtilitiesMessages.trashTitle, "Disposal")
        add("commands.trash.message", XUtilitiesMessages.trashOpened, "")
    }

    /** 2.x's theme, which every colour still on is left to the family (`shared`). */
    private val THEME_DEFAULTS = mapOf(
        "prefix" to "<#B48CFF>»</#B48CFF> ",
        "base" to "<gray>",
        "highlight" to "<#B48CFF>",
        "success" to "<#5EE08A>",
        "error" to "<#FF6B6B>",
    )

    /** Settings 2.x had that 3.0 drops, with no message to carry. */
    private val DROPPED = listOf("update-checker.message")

    /**
     * The messages an owner edited in a 1.x or 2.x config ([root]), by their 3.0 key, as they
     * wrote them: what `lang/en.yml` starts with. Messages still on a text a release shipped
     * aren't here, so they get 3.0's.
     */
    public fun messages(root: YamlNode.Mapping): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (moved in MOVED) {
            val text = when (val node = get(root, moved.path)) {
                is YamlNode.Scalar -> node.content
                is YamlNode.Null -> ""
                else -> continue
            }
            if (text in moved.shipped) continue
            out[moved.to.key] = text
        }
        return out
    }

    /** The config version [root] says it is, or `null` if it doesn't. */
    public fun version(root: YamlNode.Mapping): Int? = (root["config-version"] as? YamlNode.Scalar)?.content?.trim()?.toIntOrNull()

    /**
     * 2.x to 3.0: messages out of the config (the loader has put the edited ones in
     * `lang/en.yml`), and the theme into `messages.theme`. If any message was edited, this
     * plugin's `messages.language` becomes `en`, so every player keeps reading the owner's words
     * as they did in 2.x; `auto` gives players the shipped translations instead.
     */
    internal fun toThree(scope: MigrationScope) {
        val edited = messages(scope.root).isNotEmpty()
        for (moved in MOVED) scope.remove(moved.path)
        for (path in DROPPED) scope.remove(path)
        // 2.x's update checker is the core's now: `update-checker.enabled` is `updates.check`.
        scope.move("update-checker.enabled", "updates.check")
        val checker = scope["update-checker"]
        if (checker is YamlNode.Mapping && checker.entries.isEmpty() || checker is YamlNode.Null) scope.remove("update-checker")
        // The sections the messages left, if nothing else is in them.
        for (section in listOf("messages.error", "messages.state")) {
            val node = scope[section]
            if (node == null || (node is YamlNode.Mapping && node.entries.isEmpty()) || node is YamlNode.Null) scope.remove(section)
        }
        for ((name, default) in THEME_DEFAULTS) {
            val node = scope["theme.$name"] ?: continue
            scope.remove("theme.$name")
            if (node is YamlNode.Scalar && node.content == default) continue
            if ("messages.theme.$name" !in scope) scope["messages.theme.$name"] = node
        }
        val theme = scope["theme"]
        if (theme is YamlNode.Mapping && theme.entries.isEmpty() || theme is YamlNode.Null) scope.remove("theme")
        if (edited && "messages.language" !in scope) scope["messages.language"] = "en"
    }

    /** 1.x's example links, which 2.0 gave a new look: a file still on one gets 2.0's. */
    internal fun refreshLinks(scope: MigrationScope) {
        val old = mapOf(
            "discord" to "<blue>Join our Discord: <click:open_url:'https://discord.gg/example'><hover:show_text:'<gray>Click to open'><underlined>discord.gg/example</underlined></hover></click></blue>",
            "store" to "<gold>Visit our store: <click:open_url:'https://store.example.com'><hover:show_text:'<gray>Click to open'><underlined>store.example.com</underlined></hover></click></gold>",
            "website" to "<aqua>Visit our website: <click:open_url:'https://example.com'><hover:show_text:'<gray>Click to open'><underlined>example.com</underlined></hover></click></aqua>",
        )
        for ((name, text) in old) {
            val path = "info-commands.$name.message"
            val node = scope[path] as? YamlNode.Scalar ?: continue
            if (node.content == text) scope[path] = LinkCommand.DEFAULTS.getValue(name).message
        }
    }

    private fun get(root: YamlNode.Mapping, path: String): YamlNode? {
        var node: YamlNode = root
        for (key in path.split('.')) node = (node as? YamlNode.Mapping)?.get(key) ?: return null
        return node
    }
}
