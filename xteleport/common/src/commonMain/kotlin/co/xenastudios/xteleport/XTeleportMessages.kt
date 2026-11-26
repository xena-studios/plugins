package co.xenastudios.xteleport

import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey

/**
 * Every message xTeleport sends, in `lang/<language>.yml` (English here, in code). The keys are
 * the 1.x config's message settings without `messages.`: `homes.set` was `messages.homes.set`,
 * so an owner's 1.x wording carries straight over ([co.xenastudios.xteleport.config.Legacy]).
 * 1.x's `messages.error.*` are the core's `core.command.*`. What `/xteleport` itself says was
 * written into 1.x's code; it's here now too, so it's translated with the rest.
 */
public object XTeleportMessages : MessageBundle() {
    private const val PAGES =
        " <gray>Page <page> of <pages></gray><has_previous> <click:run_command:'<previous>'><hover:show_text:'<previous>'><highlight>« Previous</highlight></hover></click></has_previous>" +
            "<has_next> <click:run_command:'<next>'><hover:show_text:'<next>'><highlight>Next »</highlight></hover></click></has_next>"
    private val PAGE_PLACEHOLDERS = listOf("page", "pages", "previous", "next", "has_previous", "has_next")
    private const val ANSWER =
        "<click:run_command:'<accept>'><hover:show_text:'<accept>'><success>[Accept]</success></hover></click> " +
            "<click:run_command:'<deny>'><hover:show_text:'<deny>'><error>[Deny]</error></hover></click>"

    public val info: MessageKey = message(
        "info", "<prefix>xTeleport <highlight><version></highlight> on <platform>, with <warps> warps in <storage>. <gray>/xteleport reload | debug | import | rtpcheck",
        "/xteleport on its own.", placeholders = listOf("version", "platform", "warps", "storage"),
    )

    // ---- every teleport ----

    public val warmup: MessageKey = message("teleport.warmup", "<prefix>Teleporting in <highlight><seconds>s</highlight>. Don't move.", "<seconds> to stand still.", placeholders = listOf("seconds"))
    public val countdown: MessageKey = message(
        "teleport.countdown", "Teleporting in <highlight><seconds></highlight>...",
        "On the action bar each second of the warmup. Blank for none. Spigot before 1.9 has no action bar, so none there.", placeholders = listOf("seconds"),
    )
    public val movedCancelled: MessageKey = message("teleport.moved-cancelled", "<prefix><error>Teleport cancelled: you moved.")
    public val damageCancelled: MessageKey = message("teleport.damage-cancelled", "<prefix><error>Teleport cancelled: you took damage.")
    public val cooldown: MessageKey = message("teleport.cooldown", "<prefix><error>You can do that again in <highlight><time></highlight>.", "<time> until it can be used again.", placeholders = listOf("time"))
    public val cantAfford: MessageKey = message("teleport.cant-afford", "<prefix><error>That costs <highlight><cost></highlight>, which you don't have.", "<cost> is what it costs.", placeholders = listOf("cost"))
    public val paid: MessageKey = message("teleport.paid", "<prefix>Paid <highlight><cost></highlight>.", "After paying. <cost>.", placeholders = listOf("cost"))
    public val refunded: MessageKey = message("teleport.refunded", "<prefix>The teleport didn't happen, so you got back <highlight><cost></highlight>.", "The teleport failed after paying. <cost>.", placeholders = listOf("cost"))
    public val unsafe: MessageKey = message("teleport.unsafe", "<prefix><error>That spot isn't safe right now (lava, blocks or the void), so you stayed here.", "The spot is in lava, inside blocks or over the void now.")
    public val worldMissing: MessageKey = message("teleport.world-missing", "<prefix><error>That spot is in <highlight><world></highlight>, which isn't loaded.", "<world> isn't loaded.", placeholders = listOf("world"))
    public val failed: MessageKey = message("teleport.failed", "<prefix><error>The teleport didn't work. Try again.", "The server refused the teleport, or the player left.")
    public val noEconomy: MessageKey = message(
        "teleport.no-economy", "<prefix><error>Teleports cost money here, but the economy isn't working. Tell staff.",
        "Teleports cost money, but neither xMoney nor a Vault economy is there. The console says which.",
    )
    public val unavailable: MessageKey = message("teleport.unavailable", "<prefix><error>Teleports aren't available right now. Try again in a moment.", "The database isn't open yet, or couldn't be opened. The console says why.")
    public val notSaved: MessageKey = message(
        "teleport.not-saved", "<prefix><error>Couldn't save that, so nothing changed. Try again in a moment.",
        "A home, warp, the spawn or /tptoggle couldn't be saved, so nothing changed. The console says why.",
    )

    // ---- homes ----

    public val homeSet: MessageKey = message("homes.set", "<prefix><success>Set home</success> <highlight><home></highlight>.", "<home> is its name.", placeholders = listOf("home"))
    public val homeMoved: MessageKey = message("homes.moved", "<prefix><success>Moved home</success> <highlight><home></highlight> here.", placeholders = listOf("home"))
    public val homeDeleted: MessageKey = message("homes.deleted", "<prefix>Deleted home <highlight><home></highlight>.", placeholders = listOf("home"))
    public val homeTeleported: MessageKey = message("homes.teleported", "<prefix>Welcome to <highlight><home></highlight>.", placeholders = listOf("home"))
    public val homeNotFound: MessageKey = message("homes.not-found", "<prefix><error>You have no home called <highlight><home></highlight>. See <highlight>/homes</highlight>.", placeholders = listOf("home"))
    public val homeLimit: MessageKey = message(
        "homes.limit-reached", "<prefix><error>You can have <highlight><limit></highlight> homes. Delete one, or set one again to move it.",
        "<limit> is how many the player may have.", placeholders = listOf("limit"),
    )
    public val homeBadName: MessageKey = message("homes.bad-name", "<prefix><error>A home's name is 1 to 32 letters, digits, - or _.")
    public val homeReserved: MessageKey = message("homes.reserved-name", "<prefix><error><highlight><home></highlight> is your bed's name. Pick another.", "Naming a home after bed-name, while bed-home is on.", placeholders = listOf("home"))
    public val noBed: MessageKey = message("homes.no-bed", "<prefix><error>You have no bed or respawn anchor to go to.")
    public val noHomes: MessageKey = message("homes.no-homes", "<prefix>You have no homes yet. Set one with <highlight>/sethome</highlight>.")
    public val homesHeader: MessageKey = message("homes.header", "<prefix>Your homes (<count>/<limit>):", "/homes, before the list. <count> and <limit>.", placeholders = listOf("count", "limit"))
    public val homesEntry: MessageKey = message(
        "homes.entry", " <click:run_command:'<command>'><hover:show_text:'<world> <x> <y> <z>'><highlight><home></highlight></hover></click> <dark_gray><world></dark_gray>",
        "One line per home. <home>, <world>, <x> <y> <z>, and <command>: the /home command for it, to click.",
        placeholders = listOf("command", "world", "x", "y", "z", "home"),
    )
    public val homesFooter: MessageKey = message("homes.footer", PAGES, "Under the list when it has more pages.", placeholders = PAGE_PLACEHOLDERS)
    public val homesHeaderOthers: MessageKey = message("homes.header-others", "<prefix><highlight><player></highlight>'s homes (<count>):", "Staff using another player's homes. <player> is whose.", placeholders = listOf("player", "count"))
    public val homeNotFoundOthers: MessageKey = message("homes.not-found-others", "<prefix><error><highlight><player></highlight> has no home called <highlight><home></highlight>.", placeholders = listOf("player", "home"))
    public val noHomesOthers: MessageKey = message("homes.no-homes-others", "<prefix><highlight><player></highlight> has no homes.", placeholders = listOf("player"))
    public val homeDeletedOthers: MessageKey = message("homes.deleted-others", "<prefix>Deleted <highlight><player></highlight>'s home <highlight><home></highlight>.", placeholders = listOf("player", "home"))
    public val neverJoined: MessageKey = message("homes.never-joined", "<prefix><error><highlight><player></highlight> has never played here.", "<player> has never joined this server.", placeholders = listOf("player"))

    // ---- tpa ----

    public val tpaSent: MessageKey = message(
        "tpa.sent", "<prefix>Asked <highlight><player></highlight> if you can teleport to them. It lasts <seconds>s.",
        "To whoever asked. <player> is who was asked, <seconds> how long it lasts.", placeholders = listOf("player", "seconds"),
    )
    public val tpaSentHere: MessageKey = message("tpa.sent-here", "<prefix>Asked <highlight><player></highlight> to teleport to you. It lasts <seconds>s.", placeholders = listOf("player", "seconds"))
    public val tpaReceived: MessageKey = message(
        "tpa.received", "<prefix><highlight><player></highlight> wants to teleport to you. $ANSWER",
        "To whoever was asked. <player> asked; <accept> and <deny> are the commands to answer, to click. <seconds> is how long it lasts.",
        placeholders = listOf("player", "accept", "deny"),
    )
    public val tpaReceivedHere: MessageKey = message("tpa.received-here", "<prefix><highlight><player></highlight> wants you to teleport to them. $ANSWER", placeholders = listOf("player", "accept", "deny"))
    public val tpaAccepted: MessageKey = message("tpa.accepted", "<prefix><highlight><player></highlight> <success>accepted</success> your request.", "To whoever asked.", placeholders = listOf("player"))
    public val tpaYouAccepted: MessageKey = message("tpa.you-accepted", "<prefix><success>Accepted</success> <highlight><player></highlight>'s request.", "To whoever accepted.", placeholders = listOf("player"))
    public val tpaNotReady: MessageKey = message(
        "tpa.not-ready", "<prefix><error><highlight><player></highlight> can't make that teleport yet. The request is still there.",
        "To whoever accepted, when whoever pays is on a cooldown or can't afford it (they're told which). The request stays, to accept again later. <player> asked.",
        placeholders = listOf("player"),
    )
    public val tpaDenied: MessageKey = message("tpa.denied", "<prefix><highlight><player></highlight> <error>denied</error> your request.", placeholders = listOf("player"))
    public val tpaYouDenied: MessageKey = message("tpa.you-denied", "<prefix>Denied <highlight><player></highlight>'s request.", placeholders = listOf("player"))
    public val tpaCancelled: MessageKey = message("tpa.cancelled", "<prefix><highlight><player></highlight> took back their request.", "To whoever was asked, when the request is taken back.", placeholders = listOf("player"))
    public val tpaYouCancelled: MessageKey = message("tpa.you-cancelled", "<prefix>Took back <highlight><count></highlight> request(s).", "<count> requests taken back.", placeholders = listOf("count"))
    public val tpaNoRequests: MessageKey = message("tpa.no-requests", "<prefix><error>You have no teleport requests.")
    public val tpaNoRequestFrom: MessageKey = message("tpa.no-request-from", "<prefix><error><highlight><player></highlight> hasn't asked you anything, or it ran out.", "<player> sent no request, or it ran out.", placeholders = listOf("player"))
    public val tpaNothingToCancel: MessageKey = message("tpa.nothing-to-cancel", "<prefix><error>You have no requests to take back.")
    public val tpaNotYourself: MessageKey = message("tpa.not-yourself", "<prefix><error>You can't ask yourself.")
    public val tpaNotTaking: MessageKey = message("tpa.not-taking", "<prefix><error><highlight><player></highlight> isn't taking teleport requests.", placeholders = listOf("player"))
    public val tpaAlreadyAsked: MessageKey = message(
        "tpa.already-asked", "<prefix><error>You already asked <highlight><player></highlight>. Wait for an answer, or take it back with <highlight>/tpacancel</highlight>.",
        "Asking again while the last request to <player> is still waiting.", placeholders = listOf("player"),
    )
    public val tpaToggledOff: MessageKey = message("tpa.toggled-off", "<prefix>You won't get teleport requests now. <highlight>/tptoggle</highlight> turns them back on.")
    public val tpaToggledOn: MessageKey = message("tpa.toggled-on", "<prefix>You'll get teleport requests again.")
    public val tpaTeleported: MessageKey = message("tpa.teleported", "<prefix>Teleported to <highlight><player></highlight>.", "After arriving. <player> is who they went to.", placeholders = listOf("player"))

    // ---- warps and spawn ----

    public val warpSet: MessageKey = message("warps.set", "<prefix><success>Set warp</success> <highlight><warp></highlight>.", "<warp> is its name.", placeholders = listOf("warp"))
    public val warpMoved: MessageKey = message("warps.moved", "<prefix><success>Moved warp</success> <highlight><warp></highlight> here.", placeholders = listOf("warp"))
    public val warpDeleted: MessageKey = message("warps.deleted", "<prefix>Deleted warp <highlight><warp></highlight>.", placeholders = listOf("warp"))
    public val warpTeleported: MessageKey = message("warps.teleported", "<prefix>Welcome to <highlight><warp></highlight>.", placeholders = listOf("warp"))
    public val warpNotFound: MessageKey = message("warps.not-found", "<prefix><error>There's no warp called <highlight><warp></highlight>. See <highlight>/warps</highlight>.", placeholders = listOf("warp"))
    public val warpBadName: MessageKey = message("warps.bad-name", "<prefix><error>A warp's name is 1 to 32 letters, digits, - or _.")
    public val noWarps: MessageKey = message("warps.no-warps", "<prefix>There are no warps yet.")
    public val warpsHeader: MessageKey = message("warps.header", "<prefix>Warps (<count>):", "/warps, before the list. <count>.", placeholders = listOf("count"))
    public val warpsEntry: MessageKey = message(
        "warps.entry", " <click:run_command:'<command>'><hover:show_text:'<command>'><highlight><warp></highlight></hover></click> <dark_gray><world></dark_gray>",
        "One line per warp. <warp>, <world>, <x> <y> <z>, and <command> to click.", placeholders = listOf("command", "warp", "world"),
    )
    public val warpsFooter: MessageKey = message("warps.footer", PAGES, placeholders = PAGE_PLACEHOLDERS)
    public val spawnSet: MessageKey = message("spawn.set", "<prefix><success>Set the spawn</success> here.")
    public val spawnTeleported: MessageKey = message("spawn.teleported", "<prefix>Welcome to spawn.")

    // ---- rtp ----

    public val rtpSearching: MessageKey = message("rtp.searching", "<prefix>Looking for a safe spot...")
    public val rtpTeleported: MessageKey = message("rtp.teleported", "<prefix>Teleported to <highlight><x> <y> <z></highlight> in <world>.", "After arriving. <world>, <x> <y> <z>.", placeholders = listOf("x", "y", "z", "world"))
    public val rtpNotFound: MessageKey = message("rtp.not-found", "<prefix><error>Couldn't find a safe spot this time. Try again.")
    public val rtpBusy: MessageKey = message("rtp.busy", "<prefix><error>Lots of players are looking for a spot. Try again in a few seconds.", "Too many searches running at once.")
    public val rtpAlreadySearching: MessageKey = message("rtp.already-searching", "<prefix><error>Still looking for your spot.")
    public val rtpWorldNotAllowed: MessageKey = message("rtp.world-not-allowed", "<prefix><error>/rtp can't send you to <highlight><world></highlight>.", "<world> isn't one /rtp may send the player to.", placeholders = listOf("world"))

    // ---- /xteleport import and rtpcheck ----

    public val importNoData: MessageKey = message("import.no-data", "<prefix><error>No EssentialsX data found in <highlight><folder></highlight>.", "/xteleport import essentials, with nothing to import. <folder>.", placeholders = listOf("folder"))
    public val importFound: MessageKey = message(
        "import.found", "<prefix>EssentialsX has <highlight><homes></highlight> homes of <highlight><players></highlight> players, <highlight><warps></highlight> warps and <spawn>.",
        "What the import found. <spawn> is import.a-spawn or import.no-spawn.", placeholders = listOf("homes", "players", "warps", "spawn"),
    )
    public val importASpawn: MessageKey = message("import.a-spawn", "a spawn", "<spawn> in import.found when EssentialsX has a spawn.")
    public val importNoSpawn: MessageKey = message("import.no-spawn", "no spawn", "<spawn> in import.found when it hasn't.")
    public val importDryRun: MessageKey = message(
        "import.dry-run", "<prefix>Nothing was changed. Homes and warps you already have with the same name, and a spawn already set, are kept. To import, run <highlight>/xteleport import essentials confirm</highlight>.",
        "After import.found, without confirm.",
    )
    public val importDone: MessageKey = message(
        "import.done", "<prefix><success>Imported</success> <highlight><homes></highlight> homes, <highlight><warps></highlight> warps<spawn>. Skipped <highlight><skipped></highlight> already here.",
        "<spawn> is import.and-the-spawn, or nothing.", placeholders = listOf("homes", "warps", "spawn", "skipped"),
    )
    public val importAndTheSpawn: MessageKey = message("import.and-the-spawn", " and the spawn", "<spawn> in import.done when the spawn was imported.")
    public val importProblem: MessageKey = message("import.problem", "<prefix><error><problem>", "Something in EssentialsX's files that couldn't be read, in English. <problem>.", placeholders = listOf("problem"))
    public val importFailed: MessageKey = message("import.failed", "<prefix><error>The import failed, and nothing was changed: <reason>", placeholders = listOf("reason"))
    public val importUnknown: MessageKey = message("import.unknown", "<prefix><error>Only EssentialsX can be imported in this version: <highlight>/xteleport import essentials</highlight>.")
    public val rtpCheckNotWorld: MessageKey = message(
        "rtpcheck.not-rtp-world", "<prefix><error>/rtp can't send anyone to <highlight><world></highlight>. Add it under rtp.worlds.",
        "/xteleport rtpcheck <world>, for a world that isn't under rtp.worlds, or isn't loaded.", placeholders = listOf("world"),
    )
    public val rtpCheckBusy: MessageKey = message("rtpcheck.busy", "<prefix><error>The most /rtp searches are already running. Try again in a moment.")
    public val rtpCheckFound: MessageKey = message(
        "rtpcheck.found",
        "<prefix><success>Found a safe spot</success> in <found> of <runs> searches in <world>, after <tries> tries each on average, in <ms> ms each. The last was <highlight><x> <y> <z></highlight>.",
        "What the searches found. The boot tests look for \"Found a safe spot\" in English.",
        placeholders = listOf("found", "runs", "world", "tries", "ms", "x", "y", "z"),
    )
    public val rtpCheckNone: MessageKey = message(
        "rtpcheck.none", "<prefix><error>No safe spot in <world> in <runs> searches of <tries> tries.</error> Check its radii and blocked biomes.",
        placeholders = listOf("world", "runs", "tries"),
    )

    /** Every message the lang files hold: the core's, then xTeleport's own. */
    public val catalog: MessageCatalog = MessageCatalog.of(CoreMessages, this)
}
