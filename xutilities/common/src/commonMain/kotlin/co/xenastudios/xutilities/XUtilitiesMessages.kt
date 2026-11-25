package co.xenastudios.xutilities

import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey

/**
 * Every message xUtilities sends, in `lang/<language>.yml` (English here, in code). The keys are
 * the 2.x config's message settings, by command: `fly.on-self` was `commands.fly.message-on-self`,
 * so an owner's 2.x wording carries straight over ([co.xenastudios.xutilities.config.Legacy]).
 */
public object XUtilitiesMessages : MessageBundle() {

    /** `/xutilities` with no subcommand. */
    public val info: MessageKey = message(
        "info", "<prefix>xUtilities <highlight><version></highlight> on <platform>, with <commands> commands on. <gray>/xutilities reload | debug",
        "/xutilities (or /xu) on its own.",
        placeholders = listOf("version", "platform", "commands"),
    )

    // The words filled in for <state>, and the speed's <type>.
    public val stateEnabled: MessageKey = message("state.enabled", "enabled", "The word for <state> when flight turns on.")
    public val stateDisabled: MessageKey = message("state.disabled", "disabled", "The word for <state> when flight turns off.")

    // The words filled in for <mode>.
    public val modeSurvival: MessageKey = message("mode.survival", "survival", "The word for <mode>: survival.")
    public val modeCreative: MessageKey = message("mode.creative", "creative", "The word for <mode>: creative.")
    public val modeAdventure: MessageKey = message("mode.adventure", "adventure", "The word for <mode>: adventure.")
    public val modeSpectator: MessageKey = message("mode.spectator", "spectator", "The word for <mode>: spectator.")

    public val survivalSelf: MessageKey = self("survival")
    public val survivalOthers: MessageKey = others("survival")
    public val creativeSelf: MessageKey = self("creative")
    public val creativeOthers: MessageKey = others("creative")
    public val adventureSelf: MessageKey = self("adventure")
    public val adventureOthers: MessageKey = others("adventure")
    public val spectatorSelf: MessageKey = self("spectator")
    public val spectatorOthers: MessageKey = others("spectator")

    public val flyOnSelf: MessageKey = message(
        "fly.on-self", "<prefix>Flight <success><state></success>.",
        "/fly turned flight on. <state> is state.enabled.", placeholders = listOf("state"),
    )
    public val flyOffSelf: MessageKey = message(
        "fly.off-self", "<prefix>Flight <error><state></error>.",
        "/fly turned flight off. <state> is state.disabled.", placeholders = listOf("state"),
    )
    public val flyOnOthers: MessageKey = message(
        "fly.on-others", "<prefix>Flight <success><state></success> for <highlight><player></highlight>.",
        "To whoever turned another player's flight on.", placeholders = listOf("state", "player"),
    )
    public val flyOffOthers: MessageKey = message(
        "fly.off-others", "<prefix>Flight <error><state></error> for <highlight><player></highlight>.",
        "To whoever turned another player's flight off.", placeholders = listOf("state", "player"),
    )

    public val speedSelf: MessageKey = message(
        "speed.self", "<prefix>Your <type> speed is now <highlight><speed></highlight>.",
        "/speed. <type> is speed.flying or speed.walking.", placeholders = listOf("type", "speed"),
    )
    public val speedOthers: MessageKey = message(
        "speed.others", "<prefix>Set <highlight><player></highlight>'s <type> speed to <highlight><speed></highlight>.",
        placeholders = listOf("player", "type", "speed"),
    )
    public val speedInvalid: MessageKey = message(
        "speed.invalid", "<prefix><error>Speed must be from <min> to <max>.",
        "/speed with a number outside commands.speed's min and max.", placeholders = listOf("min", "max"),
    )
    public val speedFlying: MessageKey = message("speed.flying", "flying", "The word for <type> while flying.")
    public val speedWalking: MessageKey = message("speed.walking", "walking", "The word for <type> otherwise.")

    public val topDone: MessageKey = message("top.done", "<prefix>Teleported to the top.", "/top took the player up.")
    public val topUnsafe: MessageKey = message("top.unsafe", "<prefix><error>There's no safe block to stand on here.", "/top found nowhere safe.")

    public val setrotationSelf: MessageKey = message(
        "setrotation.self", "<prefix>Now facing yaw <highlight><yaw></highlight>, pitch <highlight><pitch></highlight>.",
        placeholders = listOf("yaw", "pitch"),
    )
    public val setrotationOthers: MessageKey = message(
        "setrotation.others", "<prefix><highlight><player></highlight> is now facing yaw <highlight><yaw></highlight>, pitch <highlight><pitch></highlight>.",
        placeholders = listOf("player", "yaw", "pitch"),
    )

    public val pingSelf: MessageKey = message("ping.self", "<prefix>Your ping is <highlight><ping>ms</highlight>.", placeholders = listOf("ping"))
    public val pingOthers: MessageKey = message(
        "ping.others", "<prefix><highlight><player></highlight>'s ping is <highlight><ping>ms</highlight>.",
        placeholders = listOf("player", "ping"),
    )

    public val backDone: MessageKey = message("back.done", "<prefix>Teleported back to your previous location.", "/back took the player back.")
    public val backNone: MessageKey = message("back.none", "<prefix><error>You have nowhere to go back to.", "/back with nothing to go back to.")

    public val healSelf: MessageKey = message("heal.self", "<prefix>You've been <success>healed</success>.")
    public val healOthers: MessageKey = message("heal.others", "<prefix><success>Healed</success> <highlight><player></highlight>.", placeholders = listOf("player"))
    public val feedSelf: MessageKey = message("feed.self", "<prefix>You've been <success>fed</success>.")
    public val feedOthers: MessageKey = message("feed.others", "<prefix><success>Fed</success> <highlight><player></highlight>.", placeholders = listOf("player"))

    public val repairSelf: MessageKey = message("repair.self", "<prefix>Your gear has been <success>repaired</success>.")
    public val repairOthers: MessageKey = message(
        "repair.others", "<prefix><success>Repaired</success> <highlight><player></highlight>'s gear.", placeholders = listOf("player"),
    )
    public val repairNothing: MessageKey = message("repair.nothing", "<prefix><error>Nothing needs repairing.", "/repair found nothing damaged.")

    public val clearSelf: MessageKey = message("clear.self", "<prefix>Your inventory has been <success>cleared</success>.")
    public val clearOthers: MessageKey = message(
        "clear.others", "<prefix><success>Cleared</success> <highlight><player></highlight>'s inventory.", placeholders = listOf("player"),
    )

    public val enderchestOthers: MessageKey = message(
        "enderchest.others", "<prefix>Opened <highlight><player></highlight>'s ender chest.",
        "/enderchest <player>, to whoever opened it.", placeholders = listOf("player"),
    )
    public val enderchestTooFar: MessageKey = message(
        "enderchest.too-far", "<prefix><error><highlight><player></highlight> is too far away to open their ender chest.",
        "Folia only: the other player is in another region, so their chest can't be shared safely.", placeholders = listOf("player"),
    )

    public val anvilOpened: MessageKey = opened("anvil")
    public val craftOpened: MessageKey = opened("craft")
    public val grinderOpened: MessageKey = opened("grinder")
    public val loomOpened: MessageKey = opened("loom")
    public val smithOpened: MessageKey = opened("smith")
    public val stonecutterOpened: MessageKey = opened("stonecutter")
    public val cartographyOpened: MessageKey = opened("cartography")
    public val menuUnavailable: MessageKey = message(
        "menu.unavailable", "<prefix><error>/<command> can't open its menu on this server: it needs <needs>.",
        "A workstation this server can't open without its block. <needs> says what would, in English.",
        placeholders = listOf("command", "needs"),
    )

    public val trashTitle: MessageKey = message("trash.title", "Disposal", "/trash's menu title.")
    public val trashOpened: MessageKey = message("trash.opened", "", "Sent as /trash opens; blank sends nothing.")

    public val unavailable: MessageKey = message(
        "unavailable", "<prefix><error>/<command> isn't available on this server: it needs <needs>.",
        "A command this server can't do, such as /ping before 1.17 on Spigot. <needs> is in English.",
        placeholders = listOf("command", "needs"),
    )

    /** Every message the lang files hold: the core's, then xUtilities' own. */
    public val catalog: MessageCatalog = MessageCatalog.of(CoreMessages, this)

    private fun self(mode: String): MessageKey = message(
        "$mode.self", "<prefix>Your gamemode is now <highlight><mode></highlight>.",
        "/$mode, to the player whose gamemode changed. <mode> is mode.$mode.", placeholders = listOf("mode"),
    )

    private fun others(mode: String): MessageKey = message(
        "$mode.others", "<prefix>Set <highlight><player></highlight>'s gamemode to <highlight><mode></highlight>.",
        "/$mode <player>, to whoever changed it.", placeholders = listOf("player", "mode"),
    )

    private fun opened(station: String): MessageKey =
        message("$station.opened", "", "Sent as /$station's menu opens; blank sends nothing.")
}
