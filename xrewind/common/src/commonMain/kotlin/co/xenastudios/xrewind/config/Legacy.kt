package co.xenastudios.xrewind.config

import co.xenastudios.core.common.config.MigrationScope
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.xrewind.XRewindMessages

/**
 * What 1.x configs had that 2.0 keeps elsewhere. 1.x kept every message in `config.yml` under
 * `messages`; 2.0 keeps them in `lang/<language>.yml`. A message an owner edited is carried into
 * `lang/en.yml` ([messages]); one still on the text 1.x shipped isn't, so it gets 2.0's (the same
 * English). The theme moves into `messages.theme`, where a colour still on 1.x's default becomes
 * the family's, and `update-checker.enabled` becomes `updates.check`. `log`, `rollback`, `purge`
 * and `storage` stay as they are.
 */
public object Legacy {

    /** A 1.x message setting: its path in the old config, where it goes, and the text 1.x shipped for it. */
    public class Moved(public val path: String, public val to: MessageKey, public val shipped: String)

    /** 2.0's own messages, which 1.x didn't have. */
    private val NEW = setOf("info", "params", "status", "upkeep.import-not-here")

    /** Every 1.x message setting, with its 1.0.0 to 1.1.1 default (the same in each). */
    public val MOVED: List<Moved> = buildList {
        add(Moved("messages.error.player-only", CoreMessages.commandPlayersOnly, "<prefix><error>Only players can use this command."))
        add(Moved("messages.error.no-permission", CoreMessages.commandNoPermission, "<prefix><error>You don't have permission to do that."))
        add(Moved("messages.error.player-not-found", CoreMessages.commandUnknownPlayer, "<prefix><error>No player named <highlight><player></highlight> is online."))
        add(Moved("messages.error.usage", CoreMessages.commandUsage, "<prefix><error>Usage:</error> <usage>"))
        add(Moved("messages.error.failed", CoreMessages.commandError, "<prefix><error>Something went wrong. The console has the details."))
        // 2.0 kept 1.x's English and its path for every message 1.x had.
        for (key in XRewindMessages.messages) {
            if (key.key in NEW || key.key.substringBefore('.') in NEW) continue
            add(Moved("messages." + key.key, key, key.english))
        }
    }

    /** 1.x's theme, which every colour still on is left to the family. */
    private val THEME_DEFAULTS = mapOf(
        "prefix" to "<#B48CFF>»</#B48CFF> ",
        "base" to "<gray>",
        "highlight" to "<#B48CFF>",
        "success" to "<#5EE08A>",
        "error" to "<#FF6B6B>",
    )

    /** Settings 1.x had that 2.0 drops, with no message to carry: the core's update checker has its own. */
    private val DROPPED = listOf("update-checker.message")

    /**
     * The messages an owner edited in a 1.x config ([root]), by their 2.0 key, as they wrote them:
     * what `lang/en.yml` starts with. Messages still on the text 1.x shipped aren't here.
     */
    public fun messages(root: YamlNode.Mapping): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (moved in MOVED) {
            val text = when (val node = get(root, moved.path)) {
                is YamlNode.Scalar -> node.content
                is YamlNode.Null -> ""
                else -> continue
            }
            if (text == moved.shipped) continue
            out[moved.to.key] = text
        }
        return out
    }

    /**
     * 1.x to 2.0: messages out of the config (the loader has put the edited ones in
     * `lang/en.yml`), the theme into `messages.theme` and the update checker into `updates`. If any
     * message was edited, `messages.language` becomes `en`, so every player keeps reading the
     * owner's words, as in 1.x.
     */
    internal fun toTwo(scope: MigrationScope) {
        val edited = messages(scope.root).isNotEmpty()
        for (moved in MOVED) scope.remove(moved.path)
        for (path in DROPPED) scope.remove(path)
        scope.move("update-checker.enabled", "updates.check")
        removeIfEmpty(scope, "update-checker")
        for (section in listOf(
            "messages.error", "messages.lookup.words", "messages.lookup", "messages.rollback.verbs", "messages.rollback", "messages.upkeep",
        )) removeIfEmpty(scope, section)
        for ((name, default) in THEME_DEFAULTS) {
            val node = scope["theme.$name"] ?: continue
            scope.remove("theme.$name")
            if (node is YamlNode.Scalar && node.content == default) continue
            if ("messages.theme.$name" !in scope) scope["messages.theme.$name"] = node
        }
        removeIfEmpty(scope, "theme")
        removeIfEmpty(scope, "messages")
        if (edited && "messages.language" !in scope) scope["messages.language"] = "en"
    }

    private fun removeIfEmpty(scope: MigrationScope, path: String) {
        val node = scope[path]
        if (node is YamlNode.Null || node is YamlNode.Mapping && node.entries.isEmpty()) scope.remove(path)
    }

    private fun get(root: YamlNode.Mapping, path: String): YamlNode? {
        var node: YamlNode = root
        for (key in path.split('.')) node = (node as? YamlNode.Mapping)?.get(key) ?: return null
        return node
    }
}
