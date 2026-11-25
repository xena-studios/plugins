package co.xenastudios.zchat.config

import co.xenastudios.core.common.config.MigrationScope
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.zchat.ZChatMessages

/**
 * What 2.x and 3.x configs had that 4.0 keeps elsewhere. 3.x kept every message in
 * `config.yml`; 4.0 keeps them in `lang/<language>.yml`. A message an owner edited is carried
 * into `lang/en.yml` ([messages]); one still on a text 2.x or 3.x shipped isn't, so it gets
 * 4.0's. The theme moves into `messages.theme`, where a colour still on 3.x's default becomes
 * `shared`, and `update-checker.enabled` becomes `updates.check`.
 */
public object Legacy {

    /** A 3.x message setting: its path in the old config, where it goes, and the texts that mean "never edited". */
    public class Moved(public val path: String, public val to: MessageKey, public val shipped: Set<String>)

    /** Every 3.x message setting, with the defaults 3.x and 2.x shipped for it. */
    public val MOVED: List<Moved> = buildList {
        fun add(path: String, to: MessageKey, vararg shipped: String) {
            this += Moved(path, to, shipped.toSet())
        }
        add("messages.error.player-only", CoreMessages.commandPlayersOnly, "<prefix><error>Only players can use this command.", "<red>Only players can use this command.</red>")
        add("messages.error.no-permission", CoreMessages.commandNoPermission, "<prefix><error>You don't have permission to do that.", "<red>You don't have permission to do that.</red>")
        add("messages.error.player-not-found", CoreMessages.commandUnknownPlayer, "<prefix><error>No player named <highlight><player></highlight> is online.")
        add("messages.error.usage", CoreMessages.commandUsage, "<prefix><error>Usage:</error> <usage>")
        add("messages.error.failed", CoreMessages.commandError, "<prefix><error>Something went wrong. The console has the details.")
        add(
            "filter.message-blocked", ZChatMessages.filterBlocked,
            "<prefix><error>Your message was blocked by the chat filter.", "<red>Your message was blocked by the chat filter.</red>",
        )
        val command = "filter.command"
        add("$command.message-added", ZChatMessages.filterAdded, "<prefix>Added <highlight><word></highlight> to the filter.")
        add(
            "$command.message-added-tip", ZChatMessages.filterAddedTip,
            "<prefix>It catches <highlight><word></highlight> as a whole word. Add <highlight><word>*</highlight> to catch plurals and longer words too.",
        )
        add("$command.message-removed", ZChatMessages.filterRemoved, "<prefix>Removed <highlight><word></highlight> from the filter.")
        add("$command.message-already", ZChatMessages.filterAlready, "<prefix><error><highlight><word></highlight> is already in the filter.")
        add("$command.message-not-found", ZChatMessages.filterNotFound, "<prefix><error><highlight><word></highlight> isn't in the filter.")
        add(
            "$command.message-invalid", ZChatMessages.filterInvalid,
            "<prefix><error>A filter word is one word, with <highlight>*</highlight> only at its start or end. For anything else, use filter.patterns in config.yml.",
        )
        add("$command.message-list", ZChatMessages.filterList, "<prefix>Filtered words (<highlight><count></highlight>): <words>")
        add("$command.message-list-empty", ZChatMessages.filterListEmpty, "<prefix>No words are filtered yet.")
        add("$command.message-test-passes", ZChatMessages.filterTestPasses, "<prefix><success>That passes the filter.")
        add("$command.message-test-censored", ZChatMessages.filterTestCensored, "<prefix>That would show as: <white><message>")
        add("$command.message-test-blocked", ZChatMessages.filterTestBlocked, "<prefix>That would be <error>blocked</error>.")
        add(
            "cooldown.message", ZChatMessages.cooldownWait,
            "<prefix><error>Wait <highlight><seconds>s</highlight> before chatting again.", "<red>Please wait <yellow><seconds></yellow>s before chatting again.</red>",
        )
        add("anti-repeat.message", ZChatMessages.antiRepeatBlocked, "<prefix><error>You just said that.")
        add(
            "clearchat.message-cleared", ZChatMessages.clearchatCleared,
            "<prefix>Chat was cleared by <highlight><player></highlight>.", "<gray>Chat was cleared by <yellow><player></yellow>.</gray>",
        )
        add(
            "mutechat.message-muted", ZChatMessages.mutechatMuted,
            "<prefix>Chat has been <error>muted</error> by <highlight><player></highlight>.", "<gray>Chat has been <red>muted</red> by <yellow><player></yellow>.</gray>",
        )
        add(
            "mutechat.message-unmuted", ZChatMessages.mutechatUnmuted,
            "<prefix>Chat has been <success>unmuted</success> by <highlight><player></highlight>.",
            "<gray>Chat has been <green>unmuted</green> by <yellow><player></yellow>.</gray>",
        )
        add("mutechat.message-blocked", ZChatMessages.mutechatBlocked, "<prefix><error>Chat is muted right now.", "<red>Chat is currently muted.</red>")
        add("togglechat.message-on", ZChatMessages.togglechatOn, "<prefix>Chat is now <success>shown</success>.", "<gray>Chat is now <green>shown</green>.</gray>")
        add(
            "togglechat.message-off", ZChatMessages.togglechatOff,
            "<prefix>Chat is now <error>hidden</error>. Run it again to show it.", "<gray>Chat is now <red>hidden</red>.</gray>",
        )
        add("private-messages.reply.message-nobody", ZChatMessages.replyNobody, "<prefix><error>You have nobody to reply to.")
        add("private-messages.socialspy.message-on", ZChatMessages.socialspyOn, "<prefix>Social spy <success>on</success>.")
        add("private-messages.socialspy.message-off", ZChatMessages.socialspyOff, "<prefix>Social spy <error>off</error>.")
    }

    /** 3.x's theme, which every colour still on is left to the family (`shared`). */
    private val THEME_DEFAULTS = mapOf(
        "prefix" to "<#B48CFF>»</#B48CFF> ",
        "base" to "<gray>",
        "highlight" to "<#B48CFF>",
        "success" to "<#5EE08A>",
        "error" to "<#FF6B6B>",
    )

    /** Settings 3.x had that 4.0 drops, with no message to carry: the core's update checker has its own. */
    private val DROPPED = listOf("update-checker.message")

    /** The sections 2.x and 3.x had, to tell their files from zChat 1.x's, which was another plugin. */
    private val SECTIONS = listOf(
        "theme", "messages", "formatting", "filter", "cooldown", "anti-repeat", "mentions",
        "clearchat", "mutechat", "togglechat", "private-messages", "update-checker",
    )

    /**
     * The messages an owner edited in a 2.x or 3.x config ([root]), by their 4.0 key, as they
     * wrote them: what `lang/en.yml` starts with. Messages still on a text a release shipped
     * aren't here, so they get 4.0's.
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
     * Whether [root] is zChat 1.x's: no `config-version` and none of 2.x's sections. 1.x was a
     * different plugin, so its file is set aside, not carried forward, as 3.x did.
     */
    public fun isZChatOne(root: YamlNode.Mapping): Boolean =
        version(root) == null && root.entries.isNotEmpty() && SECTIONS.none { root[it] != null }

    /**
     * 3.x to 4.0: messages out of the config (the loader has put the edited ones in
     * `lang/en.yml`), the theme into `messages.theme` and the update checker into `updates`. If
     * any message was edited, zChat's `messages.language` becomes `en`, so every player keeps
     * reading the owner's words, as in 3.x; `auto` gives players the shipped translations.
     */
    internal fun toThree(scope: MigrationScope) {
        val edited = messages(scope.root).isNotEmpty()
        for (moved in MOVED) scope.remove(moved.path)
        for (path in DROPPED) scope.remove(path)
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
