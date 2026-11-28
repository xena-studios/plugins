package co.xenastudios.xbackup.config

import co.xenastudios.core.common.config.MigrationScope
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.xbackup.XBackupMessages

/**
 * What 1.x configs had that 2.0 keeps elsewhere. 1.x kept its shared errors and two broadcasts in
 * `config.yml` (under `messages`); 2.0 keeps every message in `lang/<language>.yml`. A message an
 * owner edited is carried into `lang/en.yml` ([messages]); one still on what 1.0.0 and 1.0.1
 * shipped isn't. A broadcast 1.x said (given any text) is switched on in `broadcasts`. The theme
 * moves into `messages.theme`, where a colour still on 1.x's default becomes `shared`, and
 * `update-checker.enabled` becomes `updates.check`.
 */
public object Legacy {
    /** A 1.x message setting: its path in the old config, where it goes, and the text 1.x shipped for it. */
    public class Moved(public val path: String, public val to: MessageKey, public val shipped: String)

    /** Every 1.x message setting, with its 1.0.0 and 1.0.1 default (the same in both). */
    public val MOVED: List<Moved> = listOf(
        Moved("messages.error.player-only", CoreMessages.commandPlayersOnly, "<prefix><error>Only players can use this command."),
        Moved("messages.error.no-permission", CoreMessages.commandNoPermission, "<prefix><error>You don't have permission to do that."),
        Moved("messages.error.player-not-found", CoreMessages.commandUnknownPlayer, "<prefix><error>No player named <highlight><player></highlight> is online."),
        Moved("messages.error.usage", CoreMessages.commandUsage, "<prefix><error>Usage:</error> <usage>"),
        Moved("messages.error.failed", CoreMessages.commandError, "<prefix><error>Something went wrong. The console has the details."),
        // 1.x shipped both blank: nothing said, unless the owner wrote something.
        Moved("messages.broadcast-start", XBackupMessages.broadcastStart, ""),
        Moved("messages.broadcast-done", XBackupMessages.broadcastDone, ""),
    )

    /** 1.x's theme, which every colour still on is left to the family (`shared`). */
    private val THEME_DEFAULTS = mapOf(
        "prefix" to "<#5AA9FF>»</#5AA9FF> ",
        "base" to "<gray>",
        "highlight" to "<#5AA9FF>",
        "success" to "<#5EE08A>",
        "error" to "<#FF6B6B>",
    )

    /** The messages an owner edited in a 1.x config ([root]), by their 2.0 key, as they wrote them: what `lang/en.yml` starts with. */
    public fun messages(root: YamlNode.Mapping): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (moved in MOVED) {
            val text = when (val node = get(root, moved.path)) {
                is YamlNode.Scalar -> node.content
                else -> continue
            }
            if (text == moved.shipped || text.isBlank()) continue
            out[moved.to.key] = text
        }
        return out
    }

    /**
     * 1.x to 2.0: messages out of the config (the loader has put the edited ones in
     * `lang/en.yml`), the broadcasts switched on if 1.x said them, the theme into
     * `messages.theme` and the update checker into `updates`. If any of xBackup's own wording
     * was edited, `messages.language` becomes `en`, so every player keeps reading the owner's words.
     */
    internal fun toTwo(scope: MigrationScope) {
        val edited = messages(scope.root).isNotEmpty()
        for ((path, switch) in listOf("messages.broadcast-start" to "broadcasts.start", "messages.broadcast-done" to "broadcasts.done")) {
            val text = (scope[path] as? YamlNode.Scalar)?.content
            if (!text.isNullOrBlank() && switch !in scope) scope[switch] = "true"
        }
        for (moved in MOVED) scope.remove(moved.path)
        scope.remove("update-checker.message")
        scope.move("update-checker.enabled", "updates.check")
        removeIfEmpty(scope, "update-checker")
        removeIfEmpty(scope, "messages.error")
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
