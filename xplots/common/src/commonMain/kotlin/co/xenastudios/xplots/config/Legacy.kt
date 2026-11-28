package co.xenastudios.xplots.config

import co.xenastudios.core.common.config.MigrationScope
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.xplots.XPlotsMessages

/**
 * What 1.x configs had that 2.0 keeps elsewhere. 1.x kept every text in `config.yml`: its
 * messages under `messages`, its signs' lines under `signs.free`, `rented` and `expiring`, and its
 * menus' titles, names and lore under `menus`. 2.0 keeps them in `lang/<language>.yml`, at the same
 * paths less `messages.` (a list's lines joined with `<newline>`). A text an owner edited is
 * carried into `lang/en.yml` ([messages]); one still on what 1.x shipped isn't, so it gets 2.0's
 * (the same English). The theme moves into `messages.theme`, where a colour still on 1.x's default
 * becomes the family's, and `update-checker.enabled` becomes `updates.check`. Everything else
 * stays where it is, so the 1.x jar put back reads it.
 */
public object Legacy {

    /** A 1.x text: its path in the old config, where it goes, and what 1.x shipped there (a list's lines joined). */
    public class Moved(public val path: String, public val to: MessageKey, public val shipped: String)

    /** 2.0's own messages, which 1.x didn't have. */
    private val NEW = setOf(
        "info", "loading", "renewed", "renew-failed", "renew-on", "renew-off",
        "menus.plot.renew-on.name", "menus.plot.renew-on.lore", "menus.plot.renew-off.name", "menus.plot.renew-off.lore",
    )

    /** Every 1.x text, with its 1.0.0 to 1.0.2 default (the same in each). */
    public val MOVED: List<Moved> = buildList {
        add(Moved("messages.error.player-only", CoreMessages.commandPlayersOnly, "<prefix><error>Only players can use this command."))
        add(Moved("messages.error.no-permission", CoreMessages.commandNoPermission, "<prefix><error>You don't have permission to do that."))
        add(Moved("messages.error.player-not-found", CoreMessages.commandUnknownPlayer, "<prefix><error>No player named <highlight><player></highlight> is online."))
        add(Moved("messages.error.usage", CoreMessages.commandUsage, "<prefix><error>Usage:</error> <usage>"))
        add(Moved("messages.error.failed", CoreMessages.commandError, "<prefix><error>Something went wrong. The console has the details."))
        // 2.0 kept 1.x's English and its path for every text 1.x had; staff replies were code, not config.
        for (key in XPlotsMessages.messages) {
            val k = key.key
            if (k in NEW || k.startsWith("staff.") || k.startsWith("rollback.")) continue
            val path = if (k.startsWith("signs.") || k.startsWith("menus.")) k else "messages.$k"
            add(Moved(path, key, key.english))
        }
    }

    /** 1.x's theme, which every colour still on is left to the family. */
    private val THEME_DEFAULTS = mapOf(
        "prefix" to "<#A9D750>»</#A9D750> ",
        "base" to "<gray>",
        "highlight" to "<#A9D750>",
        "success" to "<#5EE08A>",
        "error" to "<#FF6B6B>",
    )

    /** Settings 1.x had that 2.0 drops, with no text to carry: the core's update checker has its own message. */
    private val DROPPED = listOf("update-checker.message")

    /**
     * The texts an owner edited in a 1.x config ([root]), by their 2.0 key, as they wrote them (a
     * list's lines joined with `<newline>`): what `lang/en.yml` starts with.
     */
    public fun messages(root: YamlNode.Mapping): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (moved in MOVED) {
            val text = text(get(root, moved.path) ?: continue) ?: continue
            if (text == moved.shipped) continue
            out[moved.to.key] = text
        }
        return out
    }

    private fun text(node: YamlNode): String? = when (node) {
        is YamlNode.Scalar -> node.content
        is YamlNode.Null -> ""
        is YamlNode.Sequence -> node.items.joinToString("<newline>") { (it as? YamlNode.Scalar)?.content ?: "" }
        else -> null
    }

    /**
     * 1.x to 2.0: texts out of the config (the loader has put the edited ones in `lang/en.yml`),
     * the theme into `messages.theme` and the update checker into `updates`. If any text was
     * edited, `messages.language` becomes `en`, so every player keeps reading the owner's words.
     */
    internal fun toTwo(scope: MigrationScope) {
        val edited = messages(scope.root).isNotEmpty()
        for (moved in MOVED) scope.remove(moved.path)
        for (path in DROPPED) scope.remove(path)
        scope.move("update-checker.enabled", "updates.check")
        removeIfEmpty(scope, "update-checker")
        removeIfEmpty(scope, "messages.error")
        for (name in THEME_DEFAULTS.keys) {
            val node = scope["theme.$name"] ?: continue
            scope.remove("theme.$name")
            if (node is YamlNode.Scalar && node.content == THEME_DEFAULTS[name]) continue
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
