package co.xenastudios.xmoney.config

import co.xenastudios.core.common.config.MigrationScope
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.xmoney.XMoneyMessages

/**
 * What 1.x configs had that 2.0 keeps elsewhere. 1.x kept every message in `config.yml`; 2.0
 * keeps them in `lang/<language>.yml`. A message an owner edited is carried into `lang/en.yml`
 * ([messages]); one still on the text 1.0.0 and 1.0.1 shipped isn't, so it gets 2.0's. The theme
 * moves into `messages.theme`, where a colour still on 1.x's default becomes `shared`, and
 * `update-checker.enabled` becomes `updates.check`.
 */
public object Legacy {

    /** A 1.x message setting: its path in the old config, where it goes, and the text 1.x shipped for it. */
    public class Moved(public val path: String, public val to: MessageKey, public val shipped: String)

    /** Every 1.x message setting, with its 1.0.0 and 1.0.1 default (the same in both). */
    public val MOVED: List<Moved> = buildList {
        fun add(path: String, to: MessageKey, shipped: String) {
            this += Moved("messages.$path", to, shipped)
        }
        add("error.player-only", CoreMessages.commandPlayersOnly, "<prefix><error>Only players can use this command.")
        add("error.no-permission", CoreMessages.commandNoPermission, "<prefix><error>You don't have permission to do that.")
        add("error.player-not-found", CoreMessages.commandUnknownPlayer, "<prefix><error>No player named <highlight><player></highlight> is online.")
        add("error.usage", CoreMessages.commandUsage, "<prefix><error>Usage:</error> <usage>")
        add("error.failed", CoreMessages.commandError, "<prefix><error>Something went wrong. The console has the details.")
        val m = XMoneyMessages
        for (key in listOf(
            m.balanceOwn, m.balanceOther, m.balanceUnknown,
            m.paySent, m.payReceived, m.paySelf, m.payTooLittle, m.payNotEnough, m.payConfirm, m.payLimit, m.payCancelled, m.payUnavailable,
            m.baltopHeader, m.baltopLine, m.baltopEmpty, m.baltopFooter,
            m.ecoGiven, m.ecoTaken, m.ecoSet, m.ecoReset, m.ecoNotEnough, m.ecoLimit, m.ecoCancelled, m.ecoUnavailable, m.ecoWorking,
            m.ecoHistoryHeader, m.ecoHistoryLine, m.ecoHistoryEmpty, m.ecoHistoryFooter, m.ecoChangeIn, m.ecoChangeOut,
            m.wordStart, m.wordPaidTo, m.wordPaidBy, m.wordGiven, m.wordTaken, m.wordSet, m.wordPlugin, m.wordPluginReason, m.wordImported,
            m.importDryRun, m.importDone, m.importNothing, m.importFailed, m.importRunning, m.importReading, m.importOverwrite,
            m.amountInvalid, m.amountTooPrecise, m.amountTooLarge,
        )) {
            // 2.0 kept 1.x's English and its key, so what 1.x shipped is the English here.
            add(key.key, key, key.english)
        }
    }

    /** 1.x's theme, which every colour still on is left to the family (`shared`). */
    private val THEME_DEFAULTS = mapOf(
        "prefix" to "<#10AC79>»</#10AC79> ",
        "base" to "<gray>",
        "highlight" to "<#10AC79>",
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

    /** The config version [root] says it is, or `null` if it doesn't. */
    public fun version(root: YamlNode.Mapping): Int? = (root["config-version"] as? YamlNode.Scalar)?.content?.trim()?.toIntOrNull()

    /**
     * 1.x to 2.0: messages out of the config (the loader has put the edited ones in
     * `lang/en.yml`), the theme into `messages.theme` and the update checker into `updates`. If
     * any message was edited, `messages.language` becomes `en`, so every player keeps reading the
     * owner's words, as in 1.x; `auto` gives players the shipped translations.
     */
    internal fun toTwo(scope: MigrationScope) {
        val edited = messages(scope.root).isNotEmpty()
        for (moved in MOVED) scope.remove(moved.path)
        for (path in DROPPED) scope.remove(path)
        scope.move("update-checker.enabled", "updates.check")
        removeIfEmpty(scope, "update-checker")
        for (section in listOf("messages.error", "messages.balance", "messages.pay", "messages.baltop", "messages.eco.words", "messages.eco", "messages.imports", "messages.amount")) {
            removeIfEmpty(scope, section)
        }
        for ((name, default) in THEME_DEFAULTS) {
            val node = scope["theme.$name"] ?: continue
            scope.remove("theme.$name")
            if (node is YamlNode.Scalar && node.content == default) continue
            if ("messages.theme.$name" !in scope) scope["messages.theme.$name"] = node
        }
        removeIfEmpty(scope, "theme")
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
