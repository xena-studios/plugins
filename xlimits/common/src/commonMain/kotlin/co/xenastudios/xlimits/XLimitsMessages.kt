package co.xenastudios.xlimits

import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey

/** Every message xLimits sends, in `lang/<language>.yml` (English here, in code). */
public object XLimitsMessages : MessageBundle() {
    public val info: MessageKey = message(
        "info", "<prefix>xLimits <highlight><version></highlight> on <platform>: <count> limits on. <gray>/xlimits here | top | near | restore | reload | debug | import",
        "/xlimits on its own.", placeholders = listOf("version", "platform", "count"),
    )

    // What a player is told when they're refused.
    public val entityReached: MessageKey = message(
        "limit.entity", "<prefix><error>This chunk has <count> of <max> <limit>.</error> No more can be added here.",
        "To a player whose spawn egg, breeding, boat, minecart, armour stand or item frame was refused.",
        placeholders = listOf("limit", "count", "max"),
    )
    public val blockReached: MessageKey = message(
        "limit.block", "<prefix><error>This chunk has <count> of <max> <limit>.</error> Place it in another chunk.",
        "To a player whose block was refused.", placeholders = listOf("limit", "count", "max"),
    )
    public val tntReached: MessageKey = message(
        "limit.tnt", "<prefix><error>This chunk already has <count> of <max> primed TNT.</error>",
        "To a player whose TNT didn't prime.", placeholders = listOf("count", "max"),
    )
    public val petsReached: MessageKey = message(
        "limit.pets", "<prefix><error>You have <count> of <max> pets<type>.</error> Pets you have are kept.",
        "To a player whose taming failed. <type> is \" of this kind\" for a cap per type, else blank.", placeholders = listOf("count", "max", "type"),
    )
    public val petsOfType: MessageKey = message("limit.pets-of-type", " of this kind", "Filled into limit.pets for a cap per type.")

    // Staff alerts.
    public val alertReached: MessageKey = message(
        "alert.reached",
        "<prefix><gold>⚠</gold> <highlight><limit></highlight> reached at <world> <x>, <z> (<count>/<max>). " +
            "<click:run_command:'/xlimits tp <world> <x> <y> <z>'><hover:show_text:'Teleport there'><highlight>[Teleport]</highlight></hover></click><who_built>",
        "To staff with xlimits.alerts, the first time a chunk reaches a limit (alerts.cooldown).",
        placeholders = listOf("limit", "world", "x", "y", "z", "count", "max", "who_built"),
    )
    public val alertWhoBuilt: MessageKey = message(
        "alert.who-built", " <click:run_command:'/<command>'><hover:show_text:'Look it up in xRewind'><highlight>[Who built this]</highlight></hover></click>",
        "The alert's xRewind button, with xRewind installed.", placeholders = listOf("command"),
    )
    public val alertRedstone: MessageKey = message(
        "alert.redstone",
        "<prefix><gold>⚠</gold> A redstone clock at <world> <x> <y> <z> changed <count> times in <window> and was broken. " +
            "<click:run_command:'/xlimits tp <world> <x> <y> <z>'><hover:show_text:'Teleport there'><highlight>[Teleport]</highlight></hover></click><who_built>",
        "To staff as a redstone clock is stopped.", placeholders = listOf("world", "x", "y", "z", "count", "window", "who_built"),
    )
    public val alertProfileOn: MessageKey = message(
        "alert.profile-on", "<prefix><gold>⚠</gold> Load profile <highlight><profile></highlight> is on<where>: <reason>.",
        "To staff as a load profile turns on.", placeholders = listOf("profile", "where", "reason"),
    )
    public val alertProfileOff: MessageKey = message(
        "alert.profile-off", "<prefix>Load profile <highlight><profile></highlight> is off<where>; <now> now.",
        "To staff as a load profile turns off.", placeholders = listOf("profile", "where", "now"),
    )
    public val profileWhere: MessageKey = message("alert.profile-where", " in the region at <world> <x>, <z>", "On Folia, where a region's profile changed.", placeholders = listOf("world", "x", "z"))
    public val reasonPlayers: MessageKey = message("alert.reason-players", "<value> players online, over <on>", placeholders = listOf("value", "on"))
    public val reasonTps: MessageKey = message("alert.reason-tps", "TPS <value>, under <on>", placeholders = listOf("value", "on"))
    public val reasonMspt: MessageKey = message("alert.reason-mspt", "<value> ms a tick, over <on>", placeholders = listOf("value", "on"))

    // /xlimits here
    public val hereHeader: MessageKey = message(
        "here.header", "<prefix>Chunk <highlight><world> <x>, <z></highlight>, profile <highlight><profile></highlight>:",
        "/xlimits here, first.", placeholders = listOf("world", "x", "z", "profile"),
    )
    public val hereLine: MessageKey = message(
        "here.line", " <gray>•</gray> <limit>: <highlight><count></highlight> of <max>", "One limit in /xlimits here.",
        placeholders = listOf("limit", "count", "max"),
    )
    public val hereFull: MessageKey = message(
        "here.full", " <gray>•</gray> <limit>: <error><count> of <max></error>", "One limit in /xlimits here that's reached.",
        placeholders = listOf("limit", "count", "max"),
    )
    public val hereSlowed: MessageKey = message(
        "here.slowed", " <gray>•</gray> <limit> farm: <highlight>slowed</highlight> (<count>, over <max>)", "A farm rule slowing mobs here.",
        placeholders = listOf("limit", "count", "max"),
    )
    public val hereNone: MessageKey = message("here.none", " <gray>No limits are on.")
    public val hereExempt: MessageKey = message("here.exempt", "<prefix>This world is exempt (worlds.exempt): nothing is limited here.")
    public val hereServer: MessageKey = message("here.server", "<gray>The server's own settings here: <settings>", "Paper's and Spigot's settings next to xLimits'.", placeholders = listOf("settings"))
    public val unlimited: MessageKey = message("here.unlimited", "no limit", "In place of a number for a limit an area has none of.")

    // /xlimits top and near
    public val topHeader: MessageKey = message(
        "top.header", "<prefix>The busiest chunks by <what>:", "/xlimits top and near, first.", placeholders = listOf("what"),
    )
    public val nearHeader: MessageKey = message(
        "near.header", "<prefix>The busiest chunks around <highlight><player></highlight> by <what>:", placeholders = listOf("player", "what"),
    )
    public val topLine: MessageKey = message(
        "top.line",
        " <gray><rank>.</gray> <click:run_command:'/xlimits tp <world> <bx> 100 <bz>'><hover:show_text:'Teleport there'><highlight><world> <x>, <z></highlight></hover></click>: <entities> entities, <blocks> block entities<redstone>",
        "One chunk; clicking it teleports there.", placeholders = listOf("rank", "world", "x", "z", "bx", "bz", "entities", "blocks", "redstone"),
    )
    public val topRedstone: MessageKey = message("top.redstone", ", <count> redstone changes", placeholders = listOf("count"))
    public val topNone: MessageKey = message("top.none", "<prefix>No chunks are loaded.")
    public val topCounting: MessageKey = message("top.counting", "<prefix>Counting the loaded chunks...")

    // /xlimits restore
    public val restoreDone: MessageKey = message(
        "restore.done", "<prefix><success>Restored <count> slowed mobs.</success> Farm slowing is paused until /xlimits reload or a restart.",
        placeholders = listOf("count"),
    )
    public val restoreStarted: MessageKey = message("restore.started", "<prefix>Restoring every slowed mob in the loaded chunks...")

    // /xlimits tp
    public val unknownWorld: MessageKey = message("tp.unknown-world", "<prefix><error>There's no world called <world>.", placeholders = listOf("world"))

    /** Every message the lang files hold: the core's, then xLimits' own. */
    public val catalog: MessageCatalog = MessageCatalog.of(CoreMessages, this)
}
