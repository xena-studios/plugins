package co.xenastudios.zchat

import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey

/**
 * Every message zChat sends, in `lang/<language>.yml` (English here, in code). The keys are the
 * 3.x config's message settings, by section: `cooldown.wait` was `cooldown.message`, so an
 * owner's 3.x wording carries straight over ([co.xenastudios.zchat.config.Legacy]). The chat
 * formats aren't messages: they're the server's look, in `config.yml`.
 */
public object ZChatMessages : MessageBundle() {

    /** `/zchat` with no subcommand. */
    public val info: MessageKey = message(
        "info", "<prefix>zChat <highlight><version></highlight> on <platform>. <gray>/zchat reload | debug",
        "/zchat (or /zc) on its own.",
        placeholders = listOf("version", "platform"),
    )

    public val filterBlocked: MessageKey = message(
        "filter.blocked", "<prefix><error>Your message was blocked by the chat filter.",
        "A message the filter stopped (filter.mode: block).",
    )
    public val filterAdded: MessageKey = message(
        "filter.added", "<prefix>Added <highlight><word></highlight> to the filter.",
        "/filter add. <word> is the word.", placeholders = listOf("word"),
    )
    public val filterAddedTip: MessageKey = message(
        "filter.added-tip",
        "<prefix>It catches <highlight><word></highlight> as a whole word. Add <highlight><word>*</highlight> to catch plurals and longer words too.",
        "Sent after adding a word without *, as a reminder that plurals and longer words aren't caught. Blank for none.",
        placeholders = listOf("word"),
    )
    public val filterRemoved: MessageKey = message(
        "filter.removed", "<prefix>Removed <highlight><word></highlight> from the filter.", "/filter remove.", placeholders = listOf("word"),
    )
    public val filterAlready: MessageKey = message(
        "filter.already", "<prefix><error><highlight><word></highlight> is already in the filter.", "/filter add, for a word already there.",
        placeholders = listOf("word"),
    )
    public val filterNotFound: MessageKey = message(
        "filter.not-found", "<prefix><error><highlight><word></highlight> isn't in the filter.", "/filter remove, for a word that isn't there.",
        placeholders = listOf("word"),
    )
    public val filterInvalid: MessageKey = message(
        "filter.invalid",
        "<prefix><error>A filter word is one word, with <highlight>*</highlight> only at its start or end. For anything else, use filter.patterns in config.yml.",
        "For a word with spaces, or * anywhere but its ends.",
    )
    public val filterList: MessageKey = message(
        "filter.list", "<prefix>Filtered words (<highlight><count></highlight>): <words>",
        "/filter list. <count> is how many words, <words> the words.", placeholders = listOf("count", "words"),
    )
    public val filterListEmpty: MessageKey = message("filter.list-empty", "<prefix>No words are filtered yet.", "/filter list with no words.")
    public val filterTestPasses: MessageKey = message("filter.test-passes", "<prefix><success>That passes the filter.", "/filter test <message>: nothing caught.")
    public val filterTestCensored: MessageKey = message(
        "filter.test-censored", "<prefix>That would show as: <white><message>",
        "/filter test <message>, censored. <message> is how it would show.", placeholders = listOf("message"),
    )
    public val filterTestBlocked: MessageKey = message("filter.test-blocked", "<prefix>That would be <error>blocked</error>.", "/filter test <message>, blocked.")
    public val filterSaveFailed: MessageKey = message(
        "filter.save-failed", "<prefix><error>Couldn't save config.yml, so the filter is unchanged. The console says why.",
        "/filter add or remove couldn't write config.yml.",
    )

    public val cooldownWait: MessageKey = message(
        "cooldown.wait", "<prefix><error>Wait <highlight><seconds>s</highlight> before chatting again.",
        "Chatting again too soon. <seconds> is the whole seconds left.", placeholders = listOf("seconds"),
    )
    public val antiRepeatBlocked: MessageKey = message("anti-repeat.blocked", "<prefix><error>You just said that.", "The same message again, too soon.")

    public val clearchatCleared: MessageKey = message(
        "clearchat.cleared", "<prefix>Chat was cleared by <highlight><player></highlight>.",
        "/clearchat, to everyone. <player> is who cleared it.", placeholders = listOf("player"),
    )
    public val mutechatMuted: MessageKey = message(
        "mutechat.muted", "<prefix>Chat has been <error>muted</error> by <highlight><player></highlight>.",
        "/mutechat, to everyone. <player> is who muted it.", placeholders = listOf("player"),
    )
    public val mutechatUnmuted: MessageKey = message(
        "mutechat.unmuted", "<prefix>Chat has been <success>unmuted</success> by <highlight><player></highlight>.",
        "/mutechat again, to everyone.", placeholders = listOf("player"),
    )
    public val mutechatBlocked: MessageKey = message("mutechat.blocked", "<prefix><error>Chat is muted right now.", "Shown to a player who talks while chat is muted.")

    public val togglechatOn: MessageKey = message("togglechat.on", "<prefix>Chat is now <success>shown</success>.", "/togglechat, showing chat again.")
    public val togglechatOff: MessageKey = message(
        "togglechat.off", "<prefix>Chat is now <error>hidden</error>. Run it again to show it.", "/togglechat, hiding other players' chat.",
    )

    public val replyNobody: MessageKey = message("reply.nobody", "<prefix><error>You have nobody to reply to.", "/reply when nobody has messaged you, or you them.")
    public val socialspyOn: MessageKey = message("socialspy.on", "<prefix>Social spy <success>on</success>.", "/socialspy turned on.")
    public val socialspyOff: MessageKey = message("socialspy.off", "<prefix>Social spy <error>off</error>.", "/socialspy turned off.")

    /** Every message the lang files hold: the core's, then zChat's own. */
    public val catalog: MessageCatalog = MessageCatalog.of(CoreMessages, this)
}
