package co.xenastudios.xstats

import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey

/**
 * Every message xStats sends, in `lang/<language>.yml` (English here, in code). The players' keys
 * are the 1.x config's message settings without `messages.` (`profile-header` was
 * `messages.profile-header`), the period names are 1.x's `periods.names.*` as `periods.*`, and the
 * in-world boards' words 1.x's `leaderboards.boards.*` as `boards.*`, so an owner's 1.x wording
 * carries straight over ([co.xenastudios.xstats.config.Legacy]); 1.x's `messages.error.*` are the
 * core's `core.command.*`, and `messages.page-footer` is `core.pages.footer`. The English is 1.x's,
 * word for word. What staff are told (`/xstats status`, `save`, `import`, `board`, `web`) was
 * English in 1.x's code; it's here now.
 */
public object XStatsMessages : MessageBundle() {
    public val info: MessageKey = message(
        "info", "<prefix>xStats <highlight><version></highlight> on <platform>: <players> players in <storage>. <gray>/xstats status | save | import | board | web | reload | debug",
        "/xstats on its own.", placeholders = listOf("version", "platform", "players", "storage"),
    )

    // ---- /stats ----

    public val profileHeader: MessageKey = message(
        "profile-header",
        "<prefix><highlight><player></highlight>'s stats <dark_gray>·</dark_gray> joined <white><first-join></white>, last seen <white><last-seen></white>",
        "/stats. <player> is whose; <first-join> and <last-seen> are when; <playtime> and <kd>",
        "(kills per death) are filled in when the playtime, kills and deaths stats exist.",
        placeholders = listOf("player", "first-join", "last-seen"), optional = listOf("playtime", "kd"),
    )
    public val profileLine: MessageKey = message(
        "profile-line", "  <gray><stat>:</gray> <white><value></white><rank>",
        "One stat. <stat>, <value>, and <rank>: their place on the all-time board, or blank.",
        placeholders = listOf("stat", "value", "rank"),
    )
    public val profileRank: MessageKey = message("profile-rank", " <dark_gray>#<rank></dark_gray>", "How <rank> looks when they have one.", placeholders = listOf("rank"))
    public val profileSection: MessageKey = message("profile-section", "<prefix><highlight><title>", "A section another plugin adds. <title> is its heading.", placeholders = listOf("title"))
    public val profileSectionLine: MessageKey = message(
        "profile-section-line", "  <gray><label>:</gray> <white><value>", "One of its lines: <label> and <value>.", placeholders = listOf("label", "value"),
    )
    public val profileLink: MessageKey = message(
        "profile-link",
        "<prefix><click:open_url:'<url>'><hover:show_text:'Open in your browser'><highlight><underlined>See it on the website</underlined></highlight></hover></click>",
        "After the stats, when web.public-url is set. <url> is their page.", placeholders = listOf("url"),
    )
    public val neverJoined: MessageKey = message("never-joined", "<prefix><error><highlight><player></highlight> has never played here.", "<player> is the name typed.", placeholders = listOf("player"))
    public val privateOn: MessageKey = message("private-on", "<prefix><success>Your stats are hidden</success> from the website. Leaderboards in game still show you.")
    public val privateOff: MessageKey = message("private-off", "<prefix>Your stats are <highlight>on the website</highlight> again.")
    public val privateRefused: MessageKey = message("private-refused", "<prefix><error>Another plugin kept your stats as they were.", "/stats private, cancelled by another plugin (2.0).")

    // ---- /leaderboard ----

    public val topHeader: MessageKey = message("top-header", "<prefix><highlight><stat></highlight> <dark_gray>·</dark_gray> <period>", "/leaderboard. <stat>, <period>.", placeholders = listOf("stat", "period"))
    public val topLine: MessageKey = message(
        "top-line", "  <gray><rank>.</gray> <white><player></white> <dark_gray>-</dark_gray> <highlight><value>", "<rank>, <player>, <value>.",
        placeholders = listOf("rank", "player", "value"),
    )
    public val topLineSelf: MessageKey = message(
        "top-line-self", "  <highlight><rank>. <player></highlight> <dark_gray>-</dark_gray> <highlight><value>",
        "The line for the player reading it, when they're on the page.", placeholders = listOf("rank", "player", "value"),
    )
    public val topEmpty: MessageKey = message("top-empty", "<prefix>Nobody's on this leaderboard yet.")
    public val unknownStat: MessageKey = message(
        "unknown-stat", "<prefix><error>There's no stat called <highlight><stat></highlight>.</error> Try: <stats>",
        "<stat> is what was typed; <stats> the ones there are.", placeholders = listOf("stat", "stats"),
    )
    public val noPeriods: MessageKey = message("no-periods", "<prefix><error><highlight><stat></highlight> is only counted all time.", "<stat> has no daily, weekly or monthly numbers.", placeholders = listOf("stat"))
    public val notReady: MessageKey = message("not-ready", "<prefix><error>Stats are still loading. Try again in a moment.", "Before the database is open, just after startup.")

    // ---- words: the periods' names and how times read (1.x's periods.names) ----

    public val periodDaily: MessageKey = message("periods.daily", "Today", "How periods are named to players: on /leaderboard, boards and the website.")
    public val periodWeekly: MessageKey = message("periods.weekly", "This week")
    public val periodMonthly: MessageKey = message("periods.monthly", "This month")
    public val periodAll: MessageKey = message("periods.all", "All time")
    public val now: MessageKey = message("words.now", "now", "<last-seen> on /stats when the player is online.")
    public val ago: MessageKey = message("words.ago", "<time> ago", "<last-seen> on /stats otherwise.", placeholders = listOf("time"))
    public val never: MessageKey = message("words.never", "-", "<first-join> or <last-seen> when there's none.")

    // ---- boards in the world (1.x's leaderboards.boards) ----

    public val boardTitle: MessageKey = message(
        "boards.title", "<#3EC407><bold><stat></bold></#3EC407>\n<gray><period>",
        "The top line of a board in the world. <stat> and <period> are filled in. Boards are",
        "seen by everyone at once, so they're in default-language.", placeholders = listOf("stat", "period"),
    )
    public val boardLine: MessageKey = message(
        "boards.line", "<gray><rank>.</gray> <white><player></white> <#3EC407><value>", "Each place: <rank>, <player>, <value>.",
        placeholders = listOf("rank", "player", "value"),
    )
    public val boardEmpty: MessageKey = message("boards.empty", "<gray>Nobody yet", "When nobody is on it yet.")
    public val boardNoStat: MessageKey = message("boards.no-stat", "No stat called <stat>", "A board whose stat was taken out of the config.", placeholders = listOf("stat"))

    // ---- /xstats status ----

    public val statusLoading: MessageKey = message("status.loading", "<prefix>Stats are still loading.")
    public val statusFailed: MessageKey = message("status.failed", "<prefix><error>The database couldn't be opened:</error> <reason>", placeholders = listOf("reason"))
    public val status: MessageKey = message(
        "status.report",
        "<prefix><highlight>xStats</highlight> on <server>\n<highlight>Storage</highlight>: <storage><network>\n" +
            "<highlight>Players</highlight>: <known> known, <online> online\n<highlight>Stats</highlight>: <stats> kept; Minecraft's own: <table>\n" +
            "<highlight>Last save</highlight>: <saved>\n<highlight>Waiting</highlight>: <waiting> changes<dropped>\n<highlight>Rows</highlight>: <rows>\n" +
            "<highlight>Website</highlight>: <website>\n<highlight>Integrations</highlight>: <integrations>\n<highlight>Problem</highlight>: <problem>",
        "/xstats status: the database, players, the stats, the last save and what waits for the next.",
        placeholders = listOf("server", "storage", "network", "known", "online", "stats", "table", "saved", "waiting", "dropped", "rows", "website", "integrations", "problem"),
    )
    public val statusNetwork: MessageKey = message("status.network", ", network mode on")
    public val statusTable: MessageKey = message("status.table", "all <count> match this server", placeholders = listOf("count"))
    public val statusTableWrong: MessageKey = message("status.table-wrong", "<error><count> don't match:</error> <wrong>", placeholders = listOf("count", "wrong"))
    public val statusTableUnchecked: MessageKey = message(
        "status.table-unchecked", "<count> of this server's, not checked here (Paper 1.20 and newer check them by name)", placeholders = listOf("count"),
    )
    public val statusSaved: MessageKey = message("status.saved", "<ago> ago, <players> players, <millis> ms", placeholders = listOf("ago", "players", "millis"))
    public val statusNotSaved: MessageKey = message("status.not-saved", "not yet")
    public val statusDropped: MessageKey = message("status.dropped", ", <error><count> dropped</error> while too many waited", placeholders = listOf("count"))
    public val statusRows: MessageKey = message("status.rows", "<totals> all-time, <daily> daily, <monthly> monthly", placeholders = listOf("totals", "daily", "monthly"))
    public val statusRowsFailed: MessageKey = message("status.rows-failed", "couldn't count: <reason>", placeholders = listOf("reason"))
    public val statusWebOff: MessageKey = message("status.web-off", "off")
    public val statusWebOn: MessageKey = message("status.web-on", "on <bind>:<port>", placeholders = listOf("bind", "port"))
    public val statusWebDown: MessageKey = message("status.web-down", "<error>not running</error> (the console says why)")
    public val statusNone: MessageKey = message("status.none", "none")
    public val statusProblem: MessageKey = message("status.problem", "<error><reason>", placeholders = listOf("reason"))

    // ---- /xstats save ----

    public val saved: MessageKey = message("save.done", "<prefix><success>Saved</success> <players> players in <millis> ms.", placeholders = listOf("players", "millis"))
    public val saveFailed: MessageKey = message("save.failed", "<prefix><error>The save didn't reach the database:</error> <reason>", placeholders = listOf("reason"))
    public val saveRunning: MessageKey = message("save.running", "<prefix>A save is already running.")

    // ---- /xstats import ----

    public val importReading: MessageKey = message("import.reading", "<prefix>Reading the world's stats files...")
    public val importNoWorld: MessageKey = message("import.no-world", "<prefix>There's no world to import from yet.")
    public val importNothing: MessageKey = message(
        "import.nothing", "<prefix>There's no stats folder in <world> yet, so there's no history to import.", placeholders = listOf("world"),
    )
    public val importDone: MessageKey = message(
        "import.done",
        "<prefix><success>Imported <count> stats</success> for <players> players from <files> files in <millis> ms<skipped>. Players xStats already counted kept their numbers.",
        placeholders = listOf("count", "players", "files", "millis", "skipped"),
    )
    public val importSkipped: MessageKey = message("import.skipped", " (<count> files couldn't be read)", placeholders = listOf("count"))
    public val importStopped: MessageKey = message(
        "import.stopped", "<prefix><error>The import stopped:</error> <reason>. Run /xstats import to try again; nothing is added twice.", placeholders = listOf("reason"),
    )

    // ---- /xstats board ----

    public val boardUnknownStat: MessageKey = message("board.unknown-stat", "<prefix><error>There's no stat called <stat>.", placeholders = listOf("stat"))
    public val boardNoPeriods: MessageKey = message("board.no-periods", "<prefix><error><stat> is only counted all time.", placeholders = listOf("stat"))
    public val boardNoWorld: MessageKey = message("board.no-world", "<prefix><error>There's no world called <world>.", placeholders = listOf("world"))
    public val boardPlaced: MessageKey = message(
        "board.placed", "<prefix><success>Placed board <id></success>: <stat>, <period>. Remove it with <highlight>/xstats board remove <id></highlight>.",
        placeholders = listOf("id", "stat", "period"),
    )
    public val boardFailed: MessageKey = message("board.failed", "<prefix><error>Couldn't save the board:</error> <reason>", placeholders = listOf("reason"))
    public val boardNone: MessageKey = message("board.none", "<prefix>No boards yet. Place one with <highlight>/xstats board add \\<stat></highlight>.")
    public val boardListHeader: MessageKey = message("board.list-header", "<prefix><highlight>Boards</highlight> (<count>):", placeholders = listOf("count"))
    public val boardListLine: MessageKey = message(
        "board.list-line", "  <highlight><id></highlight> <stat>, <period> at <world> <x> <y> <z>", placeholders = listOf("id", "stat", "period", "world", "x", "y", "z"),
    )
    public val boardRemoved: MessageKey = message("board.removed", "<prefix><success>Removed board <id>.", placeholders = listOf("id"))
    public val boardMissing: MessageKey = message(
        "board.missing", "<prefix><error>There's no board <id>.</error> <highlight>/xstats board list</highlight> shows them.", placeholders = listOf("id"),
    )
    public val boardUnsupported: MessageKey = message(
        "board.unsupported", "<prefix><error>Boards in the world are text displays, which this server doesn't have:</error> they need Minecraft 1.19.4 or newer on Paper, Folia or Spigot.",
    )

    // ---- /xstats web ----

    public val webPassed: MessageKey = message("web.passed", "<prefix><success>Web test passed</success>: <count> checks against a test server on this machine.", placeholders = listOf("count"))
    public val webFailed: MessageKey = message("web.failed", "<prefix><error>Web test failed:</error> <reason>", placeholders = listOf("reason"))
    public val webExported: MessageKey = message("web.exported", "<prefix><success>Exported the website</success> to <folder><then>", placeholders = listOf("folder", "then"))
    public val webExportedNoUrl: MessageKey = message("web.exported-no-url", ". Set web.public-url, or edit its config.json, so it finds the API.")
    public val webExportedUrl: MessageKey = message("web.exported-url", ". Its config.json points at <url>.", placeholders = listOf("url"))
    public val webExportFailed: MessageKey = message("web.export-failed", "<prefix><error>Couldn't export the website:</error> <reason>", placeholders = listOf("reason"))
    public val webUnavailable: MessageKey = message("web.unavailable", "<prefix><error>This Java has no built-in web server, so the website can't run here.")

    // ---- the proxy ----

    public val proxyInfo: MessageKey = message(
        "proxy.info",
        "<prefix>xStats <highlight><version></highlight> on <platform>: the network's stats for <players> players, from <storage>. <gray>/xstatsproxy status | refresh | web | reload | debug",
        "/xstatsproxy on the proxy.", placeholders = listOf("version", "platform", "players", "storage"),
    )
    public val proxyStatus: MessageKey = message(
        "proxy.status",
        "<prefix><highlight>xStats</highlight> on the proxy\n<highlight>Storage</highlight>: <storage><network>\n<highlight>Players</highlight>: <known> known, <online> online\n" +
            "<highlight>Servers</highlight>: <servers>\n<highlight>Last read</highlight>: <read>\n<highlight>Website</highlight>: <website>",
        "/xstatsproxy status.", placeholders = listOf("storage", "network", "known", "online", "servers", "read", "website"),
    )
    public val proxyNoNetwork: MessageKey = message(
        "proxy.no-network",
        "<prefix><error>xStats on the proxy needs the servers' database.</error> Set storage to their MySQL or MariaDB and network.enabled to true, so it reads every server's stats.",
        "/xstatsproxy status when the proxy isn't on the servers' database.",
    )
    public val proxyRefreshed: MessageKey = message("proxy.refreshed", "<prefix><success>Read the network's stats</success>: <players> players.", placeholders = listOf("players"))

    /** The core's messages and xStats', in the order `lang/` files hold them. */
    public val catalog: MessageCatalog = MessageCatalog.of(CoreMessages, this)
}
