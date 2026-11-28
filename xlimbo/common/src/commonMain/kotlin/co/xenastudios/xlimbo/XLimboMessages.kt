package co.xenastudios.xlimbo

import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey

/** Every message xLimbo sends, in `lang/<language>.yml` (English here, in code). */
public object XLimboMessages : MessageBundle() {
    public val info: MessageKey = message(
        "info", "<prefix>xLimbo <highlight><version></highlight> on <platform>: <count> in limbo, <waiting> waiting to go back. <gray>/xlimbo reload | debug | list | send | back | forget",
        "/xlimbo on its own.", placeholders = listOf("version", "platform", "count", "waiting"),
    )

    public val welcome: MessageKey = message(
        "join.welcome", "<prefix>The server is unavailable right now, so you're waiting here. Type <highlight>/join</highlight> to go back.",
        "Sent to a player when they arrive (2.x's join.custom-join-message). Blank for none.",
    )
    public val sentWelcome: MessageKey = message(
        "join.sent", "<prefix>You're in limbo for now. Type <highlight>/join</highlight> to go back.",
        "Sent to a player sent into limbo on this server (/xlimbo send, the API, xTeleport). Blank for none.",
    )
    public val sending: MessageKey = message(
        "join-command.sending", "<prefix>Connecting you to <highlight><server></highlight>...",
        "/join, as the player goes. <server> is main-server, or the teleport's destination.", placeholders = listOf("server"),
    )
    public val cooldown: MessageKey = message(
        "join-command.cooldown", "<prefix><error>Wait <highlight><seconds>s</highlight> before trying again.",
        "/join used again too soon. <seconds> is the whole seconds left.", placeholders = listOf("seconds"),
    )
    public val notInLimbo: MessageKey = message(
        "join-command.not-in-limbo", "<prefix><error>You're not in limbo.",
        "/join from outside limbo, with send-back.via: teleport.",
    )
    public val autoSending: MessageKey = message(
        "auto-join.sending", "<prefix>Sending you back to the server...",
        "Sent as a player is sent back on their own (auto-join, server-check, the queue). Blank for none.",
    )
    public val serverUp: MessageKey = message(
        "server-check.up", "<prefix><success>The server is back up!</success> Sending everyone back...",
        "To everyone here when server-check sees the main server come back up. Blank for none.",
    )
    public val queueBar: MessageKey = message(
        "queue.action-bar", "<white>Sending you back: <highlight><bold>#<position></bold></highlight> of <total> in line",
        "The action bar while a player waits their turn. Blank shows the hint instead. Vanished players aren't counted.",
        placeholders = listOf("position", "total"),
    )
    public val hint: MessageKey = message(
        "action-bar.hint", "<white>Type <highlight><bold>/join</bold></highlight> to go back to the server",
        "Repeated on every player's action bar (action-bar.enabled).",
    )
    public val returnFailed: MessageKey = message(
        "send-back.failed", "<prefix><error>You couldn't be sent back just now.</error> Try <highlight>/join</highlight> again in a moment.",
        "With send-back.via: teleport, a teleport back that didn't happen (unsafe, or its destination is gone).",
    )

    public val listHeader: MessageKey = message(
        "list.header", "<prefix>In limbo (<count>), <waiting> waiting to go back:",
        "/xlimbo list, before the names. Vanished players are counted only for those who can see them.", placeholders = listOf("count", "waiting"),
    )
    public val listEntry: MessageKey = message("list.entry", " <highlight><player></highlight>", placeholders = listOf("player"))
    public val listQueued: MessageKey = message(
        "list.entry-queued", " <highlight><player></highlight> <dark_gray>#<position> in line</dark_gray>", placeholders = listOf("player", "position"),
    )
    public val listNone: MessageKey = message("list.none", "<prefix>Nobody is in limbo.")

    public val sendDone: MessageKey = message("send.done", "<prefix>Sent <highlight><player></highlight> to limbo.", "/xlimbo send.", placeholders = listOf("player"))
    public val sendAlready: MessageKey = message("send.already", "<prefix><error><highlight><player></highlight> is already in limbo.", placeholders = listOf("player"))
    public val sendRefused: MessageKey = message(
        "send.refused", "<prefix><error><highlight><player></highlight> wasn't sent: another plugin stopped it, or the limbo world isn't ready.",
        placeholders = listOf("player"),
    )

    public val backDone: MessageKey = message("back.done", "<prefix>Sending <highlight><player></highlight> back.", "/xlimbo back <player>.", placeholders = listOf("player"))
    public val backAll: MessageKey = message("back.all", "<prefix>Sending everyone in limbo back (<count>).", "/xlimbo back all.", placeholders = listOf("count"))
    public val backNotHere: MessageKey = message("back.not-in-limbo", "<prefix><error><highlight><player></highlight> isn't in limbo.", placeholders = listOf("player"))
    public val backRefused: MessageKey = message(
        "back.refused", "<prefix><error><highlight><player></highlight> stays: a hold, another plugin, or the main server being down kept them.",
        placeholders = listOf("player"),
    )

    /** Every message the lang files hold: the core's, then xLimbo's own. */
    public val catalog: MessageCatalog = MessageCatalog.of(CoreMessages, this)
}
