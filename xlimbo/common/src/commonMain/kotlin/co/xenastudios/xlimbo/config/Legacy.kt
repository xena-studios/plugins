package co.xenastudios.xlimbo.config

import co.xenastudios.core.common.config.MigrationScope
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.xlimbo.XLimboMessages

/**
 * What 1.x and 2.x configs had that 3.0 keeps elsewhere. 2.x kept every message in
 * `config.yml`; 3.0 keeps them in `lang/<language>.yml`. A message an owner edited is carried
 * into `lang/en.yml` ([messages]); one still on a text 1.x or 2.x shipped isn't, so it gets 3.0's.
 * The theme moves into `messages.theme`, where a colour still on 2.x's default becomes `shared`.
 */
public object Legacy {

    /** A 2.x message setting: its path in the old config, where it goes, and the texts that mean "never edited". */
    public class Moved(public val path: String, public val to: MessageKey, public val shipped: Set<String>)

    /** Every 2.x message setting, with the defaults 2.0, 2.0.1 and 1.x shipped for it. */
    public val MOVED: List<Moved> = listOf(
        Moved("messages.error.player-only", CoreMessages.commandPlayersOnly, setOf("<prefix><error>Only players can use this command.")),
        Moved("messages.error.no-permission", CoreMessages.commandNoPermission, setOf("<prefix><error>You don't have permission to do that.")),
        Moved("messages.error.player-not-found", CoreMessages.commandUnknownPlayer, setOf("<prefix><error>No player named <highlight><player></highlight> is online.")),
        Moved("messages.error.usage", CoreMessages.commandUsage, setOf("<prefix><error>Usage:</error> <usage>")),
        Moved("messages.error.failed", CoreMessages.commandError, setOf("<prefix><error>Something went wrong. The console has the details.")),
        Moved(
            "join.custom-join-message", XLimboMessages.welcome,
            setOf(
                "<prefix>The server is unavailable right now, so you're waiting here. Type <highlight>/join</highlight> to go back.",
                "<gray>Welcome to <aqua>xLimbo</aqua>. Type <yellow>/join</yellow> to return to the server.</gray>",
            ),
        ),
        Moved("auto-join.message", XLimboMessages.autoSending, setOf("<prefix>Sending you back to the server...", "<green>Sending you to the server...</green>")),
        Moved("server-check.message-up", XLimboMessages.serverUp, setOf("<prefix><success>The server is back up!</success> Sending everyone back...")),
        Moved(
            "queue.action-bar", XLimboMessages.queueBar,
            setOf(
                "<white>Sending you back: <highlight><bold>#<position></bold></highlight> of <total> in line",
                "<gray>Sending you back: <highlight>#<position></highlight> of <total> in line",
            ),
        ),
        Moved(
            "join-command.message-sending", XLimboMessages.sending,
            setOf("<prefix>Connecting you to <highlight><server></highlight>...", "<green>Connecting you to <aqua><server></aqua>...</green>"),
        ),
        Moved(
            "join-command.message-cooldown", XLimboMessages.cooldown,
            setOf("<prefix><error>Wait <highlight><seconds>s</highlight> before trying again.", "<red>Please wait <yellow><seconds></yellow>s before trying again.</red>"),
        ),
        Moved(
            "action-bar.message", XLimboMessages.hint,
            setOf(
                "<white>Type <highlight><bold>/join</bold></highlight> to go back to the server",
                "<gray>Type <highlight>/join</highlight> to go back to the server",
                "<gray>Use <yellow>/join</yellow> to join the server!</gray>",
            ),
        ),
    )

    /** 2.x's theme, which every colour still on is left to the family (`shared`). */
    private val THEME_DEFAULTS = mapOf(
        "prefix" to "<#B48CFF>»</#B48CFF> ",
        "base" to "<gray>",
        "highlight" to "<#B48CFF>",
        "success" to "<#5EE08A>",
        "error" to "<#FF6B6B>",
    )

    /** Settings 2.x had that 3.0 drops, with no message to carry: the update notice is `core.update.available` in `lang/`. */
    private val DROPPED = listOf("update-checker.message")

    /**
     * The messages an owner edited in a 1.x or 2.x config ([root]), by their 3.0 key, as they
     * wrote them: what `lang/en.yml` starts with. Messages still on a text a release shipped
     * aren't here, so they get 3.0's. A blank one (no message) is carried as blank.
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
     * `lang/en.yml`), the theme into `messages.theme` and `update-checker` into `updates`. If any
     * message was edited, xLimbo's `messages.language` becomes `en`, so every player keeps reading
     * the owner's words as they did in 2.x; `auto` gives players the shipped translations instead.
     */
    internal fun toThree(scope: MigrationScope) {
        val edited = messages(scope.root).isNotEmpty()
        for (moved in MOVED) scope.remove(moved.path)
        for (path in DROPPED) scope.remove(path)
        scope.move("update-checker.enabled", "updates.check")
        for (section in listOf("messages.error", "update-checker")) {
            val node = scope[section]
            if (node is YamlNode.Null || (node is YamlNode.Mapping && node.entries.isEmpty())) scope.remove(section)
        }
        for ((name, default) in THEME_DEFAULTS) {
            val node = scope["theme.$name"] ?: continue
            scope.remove("theme.$name")
            if (node is YamlNode.Scalar && node.content == default) continue
            if ("messages.theme.$name" !in scope) scope["messages.theme.$name"] = node
        }
        val theme = scope["theme"]
        if (theme is YamlNode.Null || (theme is YamlNode.Mapping && theme.entries.isEmpty())) scope.remove("theme")
        val messages = scope["messages"]
        if (messages is YamlNode.Null || (messages is YamlNode.Mapping && messages.entries.isEmpty())) scope.remove("messages")
        if (edited && "messages.language" !in scope) scope["messages.language"] = "en"
    }

    private fun get(root: YamlNode.Mapping, path: String): YamlNode? {
        var node: YamlNode = root
        for (key in path.split('.')) node = (node as? YamlNode.Mapping)?.get(key) ?: return null
        return node
    }
}
