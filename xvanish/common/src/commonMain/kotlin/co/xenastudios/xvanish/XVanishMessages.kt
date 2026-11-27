package co.xenastudios.xvanish

import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey

/** Every message xVanish sends, in `lang/<language>.yml` (English here, in code). */
public object XVanishMessages : MessageBundle() {
    public val info: MessageKey = message(
        "info", "<prefix>xVanish <highlight><version></highlight> on <platform>: <count> vanished. <gray>/xvanish reload | debug | forget | import",
        "/xvanish on its own.", placeholders = listOf("version", "platform", "count"),
    )

    public val vanished: MessageKey = message("vanish.on", "<prefix><success>You're vanished.</success> Only staff who may see you can.", "To a player who vanished.")
    public val shown: MessageKey = message("vanish.off", "<prefix>You're <highlight>visible</highlight> again.", "To a player who showed again.")
    public val vanishedOther: MessageKey = message("vanish.on-other", "<prefix><highlight><player></highlight> is vanished now.", "To whoever vanished another player.", placeholders = listOf("player"))
    public val shownOther: MessageKey = message("vanish.off-other", "<prefix><highlight><player></highlight> is visible again.", placeholders = listOf("player"))
    public val alreadyVanished: MessageKey = message("vanish.already-on", "<prefix><error><highlight><player></highlight> is already vanished.", placeholders = listOf("player"))
    public val alreadyShown: MessageKey = message("vanish.already-off", "<prefix><error><highlight><player></highlight> isn't vanished.", placeholders = listOf("player"))
    public val refused: MessageKey = message("vanish.refused", "<prefix><error>Another plugin stopped that.", "A plugin cancelled the vanish or unvanish.")
    public val joinedVanished: MessageKey = message("vanish.joined", "<prefix>You joined <success>vanished</success>. Nobody was told.", "To a player who joined vanished.")
    public val staffVanished: MessageKey = message(
        "staff.vanished", "<prefix><gray><player> vanished.", "To the players who can see them, with vanish.notify-staff.", placeholders = listOf("player"),
    )
    public val staffShown: MessageKey = message("staff.shown", "<prefix><gray><player> is visible again.", placeholders = listOf("player"))

    public val listHeader: MessageKey = message("list.header", "<prefix>Vanished (<count>):", "/vanish list, before the names.", placeholders = listOf("count"))
    public val listEntry: MessageKey = message(
        "list.entry", " <highlight><player></highlight> <dark_gray>level <level></dark_gray>", "One line per vanished player here.", placeholders = listOf("player", "level"),
    )
    public val listElsewhere: MessageKey = message(
        "list.elsewhere", " <gray><player></gray> <dark_gray>not on this server</dark_gray>", "A player the network has vanished who isn't on this server.", placeholders = listOf("player"),
    )
    public val listNone: MessageKey = message("list.none", "<prefix>Nobody is vanished.")

    public val fakeJoin: MessageKey = message(
        "fake.join", "<yellow><player> joined the game",
        "What everyone sees as a vanished player shows again, or with /vanish login. Blank for nothing.", placeholders = listOf("player"),
    )
    public val fakeQuit: MessageKey = message(
        "fake.quit", "<yellow><player> left the game",
        "What everyone sees as a player vanishes, or with /vanish logout.", placeholders = listOf("player"),
    )
    public val loginSent: MessageKey = message("vanish.login-sent", "<prefix>Sent a fake join message.")
    public val logoutSent: MessageKey = message("vanish.logout-sent", "<prefix>Sent a fake quit message.")
    public val bossBar: MessageKey = message("boss-bar", "You are vanished", "The boss bar's title while vanished. Blank for no boss bar.")
    public val pickupOn: MessageKey = message("pickup.on", "<prefix>You <success>pick up</success> items and experience while vanished now.")
    public val pickupOff: MessageKey = message("pickup.off", "<prefix>You no longer pick up items and experience while vanished.")
    public val chatHeld: MessageKey = message(
        "chat.held", "<prefix><error>You're vanished: everyone would see that.</error> Say it again within 15 seconds to send it.",
        "A vanished player's public chat message, held back (chat.confirm-public).",
    )


    /** Every message the lang files hold: the core's, then xVanish's own. */
    public val catalog: MessageCatalog = MessageCatalog.of(CoreMessages, this)
}
