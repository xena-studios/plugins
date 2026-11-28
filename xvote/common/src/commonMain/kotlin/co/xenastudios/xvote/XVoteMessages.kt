package co.xenastudios.xvote

import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey

/**
 * Every message xVote sends, in `lang/<language>.yml` (English here, in code). The keys are the
 * 1.x config's message settings without `messages.` (`list.header` was `messages.list.header`),
 * and `party.started`, `party.progress` and `reminders.message` at their 1.x paths, so an owner's
 * 1.x wording carries straight over ([co.xenastudios.xvote.config.Legacy]); 1.x's
 * `messages.error.*` are the core's `core.command.*`, and `messages.top.footer` is
 * `core.pages.footer`. The English is 1.x's, word for word. What staff are told (`/xvote status`,
 * `key`, `token`, `import`, `party` and `/vote test`) was English in 1.x's code; it's here now.
 */
public object XVoteMessages : MessageBundle() {
    public val info: MessageKey = message(
        "info", "<prefix>xVote <highlight><version></highlight> on <platform>: <voters> voters from <storage>. <gray>/xvote status | reload | debug | import",
        "/xvote on its own.", placeholders = listOf("version", "platform", "voters", "storage"),
    )

    // ---- /vote ----

    public val listHeader: MessageKey = message(
        "list.header", "<prefix>Vote for the server and get rewards. <highlight><available></highlight> of <sites> sites are open:",
        "/vote: a header, a line per site, and a footer. <site> is the site's name, <link> its",
        "link, <time> how long until it can be voted on again; <available> and <sites> count",
        "them; <total> and <streak> are the player's.",
        placeholders = listOf("available", "sites"),
    )
    public val listAvailable: MessageKey = message(
        "list.available",
        " <click:open_url:'<link>'><hover:show_text:'<gray>Open <link>'><success>●</success> <highlight><site></highlight> <gray>click to vote</gray></hover></click>",
        placeholders = listOf("site", "link"),
    )
    public val listCooldown: MessageKey = message(
        "list.cooldown", " <click:open_url:'<link>'><hover:show_text:'<gray>Open <link>'><gray>✓ <site>, again in <time></gray></hover></click>",
        placeholders = listOf("site", "link", "time"),
    )
    public val listFooter: MessageKey = message(
        "list.footer", "<gray>You have <highlight><total></highlight> votes and a <highlight><streak></highlight>-day streak.",
        placeholders = listOf("total", "streak"),
    )
    public val listParty: MessageKey = message(
        "list.party", "<gray>Vote party: <highlight><votes></highlight> of <needed>, <left> to go.",
        "Under the footer when the vote party is on. <votes>, <needed>, <left>.", placeholders = listOf("votes", "needed", "left"),
    )
    public val listNone: MessageKey = message("list.none", "<prefix>There's nowhere to vote yet.")

    // ---- /vote top ----

    public val topHeader: MessageKey = message(
        "top.header", "<prefix>Top voters <period>:", "/vote top. <rank>, <player>, <votes>; <period> is one of top.periods.", placeholders = listOf("period"),
    )
    public val topLine: MessageKey = message(
        "top.line", " <gray>#<rank></gray> <highlight><player></highlight> <gray><votes> votes", placeholders = listOf("rank", "player", "votes"),
    )
    public val topEmpty: MessageKey = message("top.empty", "<prefix>No votes <period> yet.", placeholders = listOf("period"))
    public val periodDay: MessageKey = message("top.periods.day", "today")
    public val periodWeek: MessageKey = message("top.periods.week", "this week")
    public val periodMonth: MessageKey = message("top.periods.month", "this month")
    public val periodAll: MessageKey = message("top.periods.all", "of all time")

    // ---- items that didn't fit, and /vote claim ----

    public val claimHeld: MessageKey = message(
        "claim.held",
        "<prefix><highlight><count></highlight> reward items didn't fit. Make room, then <click:run_command:'/vote claim'><highlight>/vote claim</highlight></click>.",
        "Reward items that didn't fit, and /vote claim. <count> is how many.", placeholders = listOf("count"),
    )
    public val claimNone: MessageKey = message("claim.none", "<prefix>You have no reward items waiting.")
    public val claimClaimed: MessageKey = message("claim.claimed", "<prefix><success>Here are your <count> reward items.", placeholders = listOf("count"))
    public val claimStillFull: MessageKey = message(
        "claim.still-full", "<prefix><error><count> items still don't fit.</error> Make room, then /vote claim again.",
        "Some still didn't fit.", placeholders = listOf("count"),
    )

    // ---- the vote party and reminders (1.x's config paths) ----

    public val partyStarted: MessageKey = message(
        "party.started", "<prefix><highlight>Vote party!</highlight> <votes> votes, so everyone gets a reward.",
        "Broadcast when a party starts. <votes> is how many it needed.", placeholders = listOf("votes"),
    )
    public val partyProgress: MessageKey = message(
        "party.progress",
        "<prefix><highlight><left></highlight> more votes until the vote party. <click:run_command:'/vote'><highlight>/vote</highlight></click>",
        "Broadcast every party.progress-every votes on the way (config.yml).", placeholders = listOf("left"),
    )
    public val reminder: MessageKey = message(
        "reminders.message",
        "<prefix>You can vote on <highlight><available></highlight> sites for rewards. <click:run_command:'/vote'><highlight>/vote</highlight></click>",
        "Telling a player they can vote: on join and every reminders.every-minutes (config.yml).", placeholders = listOf("available"),
    )

    // ---- /vote test ----

    public val testUsage: MessageKey = message("test.usage", "<prefix><error>Usage:</error> /vote test \\<player> [site]")
    public val testCounted: MessageKey = message(
        "test.counted", "<prefix><success>Test vote for <player> counted</success> from <highlight><site></highlight><via>. <then>",
        "/vote test's replies. <via> says how it was sent; <then> when it's paid.", placeholders = listOf("player", "site", "via", "then"),
    )
    public val testPaidNow: MessageKey = message("test.paid-now", "It's being paid now.")
    public val testPaidLater: MessageKey = message("test.paid-later", "It's paid when they're next online.")
    public val testWaiting: MessageKey = message(
        "test.waiting", "<prefix>Test vote for <highlight><player></highlight> from <highlight><site></highlight> stored<via>. Nobody by that name has joined, so it waits until they first do.",
        placeholders = listOf("player", "site", "via"),
    )
    public val testDuplicate: MessageKey = message(
        "test.duplicate", "<prefix><error>Test vote for <player> dropped as a repeat:</error> they voted on <highlight><site></highlight> within its cooldown. Try another site, or leave the site off.",
        placeholders = listOf("player", "site"),
    )
    public val testInvalid: MessageKey = message("test.invalid", "<prefix><error>\"<player>\" can't be a Minecraft name.", placeholders = listOf("player"))
    public val testCancelled: MessageKey = message("test.cancelled", "<prefix><error>Another plugin cancelled the test vote for <player>.", placeholders = listOf("player"))
    public val testFailed: MessageKey = message("test.failed", "<prefix><error>The test vote for <player> couldn't be stored.</error> The console says why.", placeholders = listOf("player"))
    public val testRefused: MessageKey = message("test.refused", "<prefix><error>The receiver refused the test vote:</error> <reason>", placeholders = listOf("reason"))
    public val testUnreachable: MessageKey = message(
        "test.unreachable", "<prefix><error>Couldn't reach the receiver on port <port>:</error> <reason> Sending the test straight to xVote instead.",
        placeholders = listOf("port", "reason"),
    )
    public val viaReceiver: MessageKey = message("test.via-receiver", " through the receiver on port <port>", placeholders = listOf("port"))
    public val viaDirect: MessageKey = message("test.via-direct", " (not through the receiver)")
    public val viaOff: MessageKey = message("test.via-off", " (the receiver is off)")

    // ---- /xvote status ----

    public val statusHeader: MessageKey = message(
        "status.header",
        "<highlight>xVote status</highlight>\n<highlight>Receiver</highlight>: <receiver>\n<highlight>Database</highlight>: <storage>\n<highlight>Voters</highlight>: <voters>\n<highlight>Vote party</highlight>: <party>",
        "/xvote status. <receiver> is one of status.receiver-*, <party> status.party-*.",
        placeholders = listOf("receiver", "storage", "voters", "party"),
    )
    public val statusListening: MessageKey = message(
        "status.receiver-listening", "<success>listening</success> on port <highlight><port></highlight>, Votifier <versions> (<tokens> tokens)",
        placeholders = listOf("port", "versions", "tokens"),
    )
    public val statusStopped: MessageKey = message(
        "status.receiver-stopped", "<error>stopped</error> on port <port>: the console says why; /xvote reload starts it again", placeholders = listOf("port"),
    )
    public val statusNotListening: MessageKey = message("status.receiver-not-listening", "<error>not listening</error>: the console says why")
    public val statusOff: MessageKey = message("status.receiver-off", "off")
    public val statusNetwork: MessageKey = message("status.network", ", network mode on")
    public val statusPartyOn: MessageKey = message("status.party-on", "<votes> of <needed> votes", placeholders = listOf("votes", "needed"))
    public val statusPartyOff: MessageKey = message("status.party-off", "off")
    public val statusWaiting: MessageKey = message(
        "status.waiting", "<highlight>Waiting</highlight>: <unpaid> votes until their player is online, <unknown> for names that haven't joined",
        placeholders = listOf("unpaid", "unknown"),
    )
    public val statusRecent: MessageKey = message("status.recent", "<highlight>Last votes</highlight> (the site is the service name to put in sites):")
    public val statusVote: MessageKey = message(
        "status.vote", " <gray><ago> ago</gray> <player> from <highlight><site></highlight> <gray>(<source>, <state>)</gray>",
        "One of the last votes. <state> is status.paid, status.unpaid or status.unknown-name.",
        placeholders = listOf("ago", "player", "site", "source", "state"),
    )
    public val statusPaid: MessageKey = message("status.paid", "paid")
    public val statusUnpaid: MessageKey = message("status.unpaid", "waiting")
    public val statusUnknownName: MessageKey = message("status.unknown-name", "name unknown")
    public val statusFailed: MessageKey = message("status.failed", "<error>Couldn't read the database:</error> <reason>", placeholders = listOf("reason"))

    // ---- /xvote key and token ----

    public val keyShown: MessageKey = message(
        "key.shown",
        "<prefix>The public key for lists that use Votifier v1: <click:copy_to_clipboard:'<key>'><hover:show_text:'Click to copy'><highlight>[Click to copy]</highlight></hover></click> <gray>It's also in <file>.",
        "/xvote key, to a player: the key is copied with a click. <key>, <file>.", placeholders = listOf("key", "file"),
    )
    public val keyConsole: MessageKey = message(
        "key.console", "<prefix>The public key for lists that use Votifier v1 (also in <file>):\n<key>", placeholders = listOf("key", "file"),
    )
    public val keyNone: MessageKey = message("key.none", "<prefix><error>There's no key yet.</error> The console says why.")
    public val tokenList: MessageKey = message("token.list", "<prefix>Votifier v2 tokens, by the service name lists send. Keep them secret:")
    public val tokenLine: MessageKey = message(
        "token.line", " <highlight><service></highlight>: <click:copy_to_clipboard:'<value>'><hover:show_text:'Click to copy'><value></hover></click>",
        "One token: <service> and its <value>, copied with a click.", placeholders = listOf("service", "value"),
    )
    public val tokenFooter: MessageKey = message("token.footer", "<gray>/xvote token \\<service> makes one for a list that wants its own.")
    public val tokenOne: MessageKey = message(
        "token.one", "<prefix>The token for <highlight><service></highlight>: <click:copy_to_clipboard:'<value>'><hover:show_text:'Click to copy'><value></hover></click>",
        placeholders = listOf("service", "value"),
    )
    public val tokenMade: MessageKey = message(
        "token.made",
        "<prefix><success>Made a token</success> for <highlight><service></highlight>: <click:copy_to_clipboard:'<value>'><hover:show_text:'Click to copy'><value></hover></click> <gray>Saved in tokens.yml.",
        placeholders = listOf("service", "value"),
    )
    public val tokenBadName: MessageKey = message("token.bad-name", "<prefix><error>A service name is up to 64 letters, digits, spaces and . _ : -")
    public val tokenNone: MessageKey = message("token.none", "<prefix><error>The tokens couldn't be read.</error> The console says why.")
    public val tokenSaveFailed: MessageKey = message("token.save-failed", "<prefix><error>Couldn't save tokens.yml:</error> <reason>", placeholders = listOf("reason"))

    // ---- /xvote import ----

    public val importNoNuVotifier: MessageKey = message(
        "import.nuvotifier-missing", "<prefix><error>There's no NuVotifier folder</error> (plugins/Votifier) with keys or a config to take over.",
    )
    public val importNuVotifier: MessageKey = message(
        "import.nuvotifier",
        "<prefix><success>Took over NuVotifier's setup</success> from <folder>: <what>. xVote's own were kept as .before-import files. Remove NuVotifier before the next restart.",
        "<what> lists what was taken: its keys, its tokens and its port, in English.", placeholders = listOf("folder", "what"),
    )
    public val importNuVotifierFailed: MessageKey = message(
        "import.nuvotifier-failed", "<prefix><error>Couldn't take over NuVotifier's setup:</error> <reason>", placeholders = listOf("reason"),
    )
    public val importFailed: MessageKey = message("import.failed", "<prefix><error>Couldn't import from <source>:</error> <reason>", placeholders = listOf("source", "reason"))
    public val importAgain: MessageKey = message(
        "import.again",
        "<prefix><error><source>'s totals were imported before</error> (<ago> ago). <highlight>/xvote import <command> again</highlight> adds them a second time.",
        placeholders = listOf("source", "ago", "command"),
    )
    public val importDone: MessageKey = message(
        "import.done", "<prefix><success>Imported</success> the all-time totals of <highlight><count></highlight> players from <from>. Day, week and month totals start now.",
        placeholders = listOf("count", "from"),
    )
    public val importStoreFailed: MessageKey = message("import.store-failed", "<prefix><error>Couldn't store the imported totals:</error> <reason>", placeholders = listOf("reason"))
    public val importRunning: MessageKey = message("import.running", "<prefix><error>An import is already running.")

    // ---- /xvote party ----

    public val partyStatus: MessageKey = message("party.status", "<prefix>Vote party: <highlight><votes></highlight> of <needed> votes.", placeholders = listOf("votes", "needed"))
    public val partyOff: MessageKey = message("party.off", "<prefix>The vote party is off. Turn on party.enabled to use it.")
    public val partyForced: MessageKey = message("party.forced", "<prefix><success>Vote party started.")
    public val partyFailed: MessageKey = message("party.failed", "<prefix><error>Couldn't start the party:</error> <reason>", placeholders = listOf("reason"))

    // ---- the proxy ----

    public val proxyInfo: MessageKey = message(
        "proxy.info",
        "<prefix>xVote <highlight><version></highlight> on <platform>: <voters> voters from <storage>; the player's server pays each vote. <gray>/xvoteproxy status | reload | debug",
        "/xvoteproxy on the proxy.", placeholders = listOf("version", "platform", "voters", "storage"),
    )
    public val proxyNoNetwork: MessageKey = message(
        "proxy.no-network",
        "<prefix><error>xVote on the proxy needs the servers' database.</error> Set storage to their MySQL or MariaDB and network.enabled to true, so the servers pay what arrives here.",
        "/xvoteproxy status when the proxy isn't on the servers' database: votes it received would never be paid.",
    )

    /** The core's messages and xVote's, in the order `lang/` files hold them. */
    public val catalog: MessageCatalog = MessageCatalog.of(CoreMessages, this)
}
