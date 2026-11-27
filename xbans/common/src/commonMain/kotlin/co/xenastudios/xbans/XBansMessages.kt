package co.xenastudios.xbans

import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey

/**
 * Every message xBans sends, in `lang/<language>.yml` (English here, in code). The keys are the
 * 1.x config's message settings: `screens.ban` and `broadcasts.ban` as they were, and 1.x's
 * `messages.<name>` without `messages.`, so an owner's 1.x wording carries straight over
 * ([co.xenastudios.xbans.config.Legacy]). 1.x's `messages.error.*` are the core's
 * `core.command.*`. The English is 1.x's, word for word.
 *
 * A message about a punishment fills every placeholder a punishment has (as 1.x did: `<reason>`,
 * `<staff>`, `<id>`, `<expires>`, `<time-left>`, `<duration>`, `<appeal>`...), so an owner can use
 * any of them in any such message, whether or not the English does.
 */
public object XBansMessages : MessageBundle() {
    /** What a punishment fills in. */
    public val PUNISHMENT: List<String> = listOf(
        "id", "player", "staff", "reason", "scope", "date", "expires", "time-left", "length", "duration", "appeal", "type", "status",
    )

    // A message about a punishment: the placeholders its English uses, and the rest of a
    // punishment's offered.
    private fun about(key: String, english: String, vararg comment: String, extra: List<String> = emptyList()): MessageKey {
        val used = tags(english)
        val all = PUNISHMENT + extra
        return message(
            key, english, *comment,
            placeholders = all.filter { it in used }.distinct(),
            optional = all.filter { it !in used }.distinct(),
        )
    }

    // The tags [english] names, `<id>` and `<reason>`: a loop rather than a regex and its lambda.
    private fun tags(english: String): Set<String> {
        val found = HashSet<String>()
        var i = english.indexOf('<')
        while (i >= 0) {
            var end = i + 1
            while (end < english.length && english[end].let { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }) end++
            if (end > i + 1) found += english.substring(i + 1, end)
            i = english.indexOf('<', end)
        }
        return found
    }

    private const val SCREEN_END = "\n\n<gray>Appeal at <white><appeal></white>\n<dark_gray>ID #<id>"

    public val info: MessageKey = message(
        "info", "<prefix>xBans <highlight><version></highlight> on <platform>, with <count> bans and mutes in force from <storage>. <gray>/xbans reload | debug | import",
        "/xbans on its own.", placeholders = listOf("version", "platform", "count", "storage"),
    )

    // ---- screens ----

    public val screenBan: MessageKey = about(
        "screens.ban", "<#FF6B6B><bold>You are banned from this server.</bold>\n\n<gray>Reason: <white><reason>$SCREEN_END",
        "What punished players see. <reason>, <staff>, <id>, <expires> (a date), <time-left>,",
        "<duration> and <appeal> (screens.appeal-link in config.yml) work in each.",
        "A permanent ban, shown when they're kicked and each time they try to join.",
    )
    public val screenTempBan: MessageKey = about(
        "screens.temp-ban",
        "<#FF6B6B><bold>You are banned from this server.</bold>\n\n<gray>Reason: <white><reason>\n<gray>Ends in <white><time-left></white> (<expires>)$SCREEN_END",
    )
    public val screenIpBan: MessageKey = about(
        "screens.ip-ban", "<#FF6B6B><bold>Your IP is banned from this server.</bold>\n\n<gray>Reason: <white><reason>\n<gray>Ends: <white><expires>$SCREEN_END",
        "A ban on their IP rather than their account.",
    )
    public val screenKick: MessageKey = about("screens.kick", "<#EB2F4A><bold>You were kicked.</bold>\n\n<gray>Reason: <white><reason>")
    public val screenNotReady: MessageKey = message(
        "screens.not-ready", "<#EB2F4A>The server is still starting.</#EB2F4A>\n<gray>Try again in a few seconds.",
        "Shown when they can't join because xBans is still loading its bans (only in the first",
        "seconds after a start, or while the database can't be reached at startup).",
    )
    public val muted: MessageKey = about("screens.muted", "<prefix><error>You're muted:</error> <reason>", "Told to a muted player who tries to chat.")
    public val tempMuted: MessageKey = about("screens.temp-muted", "<prefix><error>You're muted</error> for <highlight><time-left></highlight>: <reason>")
    public val youWereMuted: MessageKey = about(
        "screens.you-were-muted", "<prefix><error>You were muted</error> by <highlight><staff></highlight> <duration>: <reason>",
        "Told to a player when they're muted, warned or unmuted, if they're online.",
    )
    public val youWereWarned: MessageKey = about("screens.you-were-warned", "<prefix><error>You were warned</error> by <highlight><staff></highlight>: <reason>")
    public val youWereUnmuted: MessageKey = about("screens.you-were-unmuted", "<prefix><success>You can chat again.</success>")

    // ---- broadcasts ----

    public val broadcastBan: MessageKey = about(
        "broadcasts.ban", "<prefix><highlight><staff></highlight> banned <highlight><player></highlight> <duration>: <reason>",
        "Told to the server when someone is punished. <player>, <staff>, <reason>, <duration>",
        "(\"for 7d\" or \"permanently\", from the words below) and <id>. Silent punishments (-s)",
        "go only to staff with xbans.notify.silent, with silent-prefix in front.",
    )
    public val broadcastIpBan: MessageKey = about("broadcasts.ip-ban", "<prefix><highlight><staff></highlight> IP-banned <highlight><player></highlight> <duration>: <reason>")
    public val broadcastMute: MessageKey = about("broadcasts.mute", "<prefix><highlight><staff></highlight> muted <highlight><player></highlight> <duration>: <reason>")
    public val broadcastIpMute: MessageKey = about("broadcasts.ip-mute", "<prefix><highlight><staff></highlight> IP-muted <highlight><player></highlight> <duration>: <reason>")
    public val broadcastWarn: MessageKey = about("broadcasts.warn", "<prefix><highlight><staff></highlight> warned <highlight><player></highlight>: <reason>")
    public val broadcastKick: MessageKey = about("broadcasts.kick", "<prefix><highlight><staff></highlight> kicked <highlight><player></highlight>: <reason>")
    public val broadcastUnban: MessageKey = about("broadcasts.unban", "<prefix><highlight><staff></highlight> unbanned <highlight><player></highlight>.", "<staff> is who lifted it.")
    public val broadcastUnmute: MessageKey = about("broadcasts.unmute", "<prefix><highlight><staff></highlight> unmuted <highlight><player></highlight>.")
    public val broadcastUnwarn: MessageKey = about("broadcasts.unwarn", "<prefix><highlight><staff></highlight> removed a warning from <highlight><player></highlight>.")
    public val silentPrefix: MessageKey = message("broadcasts.silent-prefix", "<dark_gray>[Silent]</dark_gray> ", "Put after the theme's <prefix> of a silent punishment's broadcast.")
    public val permanently: MessageKey = message("broadcasts.permanently", "permanently", "<duration> for a permanent punishment, and for one with an end (<time> is how long).")
    public val forTime: MessageKey = message("broadcasts.for-time", "for <time>", placeholders = listOf("time"))
    public val bannedJoin: MessageKey = about(
        "broadcasts.banned-join", "<prefix><highlight><player></highlight> tried to join but is banned: <reason>",
        "Told to staff with xbans.notify.banned-join when a banned player tries to join. <player>,",
        "<reason>. Blank for none.",
    )
    public val altJoin: MessageKey = message(
        "broadcasts.alt-join", "<prefix><highlight><player></highlight> shares an IP with banned <highlight><alts></highlight>.",
        "Told to staff with xbans.notify.alts when a player joins who shares an IP with a banned",
        "account. <player>, and <alts>: the banned accounts. Blank for none.",
        placeholders = listOf("player", "alts"),
    )

    // ---- replies to staff, and lookups (1.x's messages.*) ----

    public val notReady: MessageKey = message("not-ready", "<prefix><error>xBans is still opening its database.</error> Try again in a moment.", "xBans is still opening its database, just after a start.")
    public val neverJoined: MessageKey = message("never-joined", "<prefix><error><highlight><player></highlight> has never joined this server.", "<player> is the name typed.", placeholders = listOf("player"))
    public val badIp: MessageKey = message("bad-ip", "<prefix><error>That isn't an IP address.")
    public val noIp: MessageKey = message(
        "no-ip", "<prefix><error>xBans doesn't know <highlight><player></highlight>'s IP yet.",
        "<player>'s IP isn't known (they haven't joined since IPs were recorded).", placeholders = listOf("player"),
    )
    public val ipsOff: MessageKey = message("ips-off", "<prefix><error>IP punishments are off on this server.", "IP commands are off because ips.enabled is false.")
    public val badDuration: MessageKey = message(
        "bad-duration", "<prefix><error><highlight><input></highlight> isn't a length of time.</error> Use 30m, 1d12h, 2mo or perm.",
        "<input> isn't a length of time.", placeholders = listOf("input"),
    )
    public val alreadyBanned: MessageKey = about(
        "already-banned", "<prefix><error><highlight><player></highlight> is already banned until <expires>.</error> Unban them first to shorten it.",
        "<player> is already banned or muted, longer than this would. <expires> is when it ends.",
    )
    public val alreadyMuted: MessageKey = about("already-muted", "<prefix><error><highlight><player></highlight> is already muted until <expires>.</error> Unmute them first to shorten it.")
    public val notBanned: MessageKey = message("not-banned", "<prefix><error><highlight><player></highlight> isn't banned.", placeholders = listOf("player"))
    public val notMuted: MessageKey = message("not-muted", "<prefix><error><highlight><player></highlight> isn't muted.", placeholders = listOf("player"))
    public val noWarnings: MessageKey = message("no-warnings", "<prefix><error><highlight><player></highlight> has no active warnings.", placeholders = listOf("player"))
    public val noSuchId: MessageKey = message(
        "no-such-id", "<prefix><error>There's no punishment <highlight>#<id></highlight> to do that to.",
        "<id> isn't a punishment, or isn't one this command can revoke.", placeholders = listOf("id"),
    )
    public val exempt: MessageKey = message("exempt", "<prefix><error><highlight><player></highlight> can't be punished.", "<player> has xbans.exempt.", placeholders = listOf("player"))
    public val outranks: MessageKey = message(
        "outranks", "<prefix><error><highlight><player></highlight> outranks you.",
        "<player>'s weight (xbans.weight.<n>) is as high as the sender's or higher.", placeholders = listOf("player"),
    )
    public val cantLift: MessageKey = about(
        "cant-lift", "<prefix><error>That was given by someone who outranks you.</error> <dark_gray>#<id>",
        "Revoking a punishment made by someone whose weight was higher than the sender's (the",
        "console's is highest), or replacing one with a ban or mute that's no longer. Staff can",
        "always revoke their own; xbans.unban.override revokes any. <player>, <expires>, <id>.",
    )
    public val overLimit: MessageKey = message(
        "over-limit", "<prefix><error>You can give at most <highlight><max></highlight>.",
        "Over the sender's staff-limits. <max> is the longest they may give.", placeholders = listOf("max"),
    )
    public val tempPermanent: MessageKey = message(
        "temp-permanent", "<prefix><error>The <highlight><template></highlight> template's next step is permanent, so it needs /ban or /mute.",
        "/tempban or /tempmute with a template whose next step is permanent. <template> is its name.", placeholders = listOf("template"),
    )
    public val templateNoPermission: MessageKey = message(
        "template-no-permission", "<prefix><error>You can't use the <highlight><template></highlight> template.",
        "A template's permission is missing. <template> is its name.", placeholders = listOf("template"),
    )
    public val scopeNoPermission: MessageKey = message("scope-no-permission", "<prefix><error>You can't choose the server.", "Choosing a server: needs xbans.scope.")
    public val extraSilentNoPermission: MessageKey = message("extra-silent-no-permission", "<prefix><error>You can't use -S.")
    public val cancelled: MessageKey = message("cancelled", "<prefix><error>Another plugin stopped that.", "Another plugin stopped the punishment.")
    public val failed: MessageKey = message("failed", "<prefix><error>That couldn't be saved.</error> The console says why.", "Couldn't be saved. The console says why.")
    public val pruned: MessageKey = message(
        "pruned", "<prefix><success>Removed</success> <highlight><count></highlight> inactive entries from <player>'s history.",
        "/prunehistory. <count> entries of <player>'s were removed.", placeholders = listOf("count", "player"),
    )
    public val pruneConfirm: MessageKey = message(
        "prune-confirm", "<prefix>That removes <highlight><count></highlight> inactive entries from <player>'s history for good. Run it again with <highlight>confirm</highlight> on the end.",
        "/prunehistory without confirming. <count> would be removed.", placeholders = listOf("count", "player"),
    )
    public val historyHeader: MessageKey = message("history-header", "<prefix><highlight><player></highlight>'s history (<count>):", "/history and /staffhistory. <player>, <count>.", placeholders = listOf("player", "count"))
    public val staffHistoryHeader: MessageKey = message("staff-history-header", "<prefix>Punishments by <highlight><player></highlight> (<count>):", placeholders = listOf("player", "count"))
    public val historyLine: MessageKey = about(
        "history-line",
        "<dark_gray>#<id></dark_gray> <gray><date></gray> <type> <highlight><player></highlight> <dark_gray>·</dark_gray> <length> <dark_gray>·</dark_gray> <white><reason></white> <dark_gray>by</dark_gray> <staff> <status>",
        "One punishment in a list. <id>, <date>, <type> (from types below), <player>, <staff>,",
        "<reason>, <length> (\"7d\", \"permanent\"), <status> (from the words below), <scope>.",
    )
    public val historyEmpty: MessageKey = message("history-empty", "<prefix>Nothing found.")
    public val footer: MessageKey = message(
        "footer",
        "<gray>Page <page> of <pages></gray><has_previous> <click:run_command:'<previous>'><hover:show_text:'<previous>'><highlight>« Previous</highlight></hover></click></has_previous><has_next> <click:run_command:'<next>'><hover:show_text:'<next>'><highlight>Next »</highlight></hover></click></has_next>",
        "Under a list when it has more pages.", placeholders = listOf("page", "pages", "previous", "next", "has_previous", "has_next"),
    )
    public val statusActive: MessageKey = message("status.active", "<#5EE08A>(active, <time> left)</#5EE08A>", "<status> words. <time> is how long is left; <staff> who revoked it.", placeholders = listOf("time"))
    public val statusEnded: MessageKey = message("status.ended", "<dark_gray>(ended)</dark_gray>")
    public val statusRevoked: MessageKey = message("status.revoked", "<dark_gray>(revoked by <staff>)</dark_gray>", placeholders = listOf("staff"))
    public val statusKick: MessageKey = message("status.kick", "")
    public val typeBan: MessageKey = message("types.ban", "<#FF6B6B>Ban</#FF6B6B>", "<type> words.")
    public val typeIpBan: MessageKey = message("types.ip-ban", "<#FF6B6B>IP ban</#FF6B6B>")
    public val typeMute: MessageKey = message("types.mute", "<#FFB86B>Mute</#FFB86B>")
    public val typeIpMute: MessageKey = message("types.ip-mute", "<#FFB86B>IP mute</#FFB86B>")
    public val typeWarn: MessageKey = message("types.warn", "<#F5D76B>Warn</#F5D76B>")
    public val typeKick: MessageKey = message("types.kick", "<#B48CFF>Kick</#B48CFF>")
    public val checkNone: MessageKey = message(
        "check-none", "<prefix><highlight><player></highlight> has nothing active of that kind.",
        "/checkban and /checkmute: a line per active one, then its details.", placeholders = listOf("player"),
    )
    public val check: MessageKey = about(
        "check", "<prefix><type> <highlight>#<id></highlight> on <highlight><player></highlight>, <length>, ends <expires>\n<gray>Reason: <white><reason></white>\n<gray>By <staff> on <date>, scope <scope>",
    )
    public val warningsHeader: MessageKey = message("warnings-header", "<prefix><highlight><player></highlight> has <count> active warnings:", "/warnings. <player>, <count> active.", placeholders = listOf("player", "count"))
    public val warningsNone: MessageKey = message("warnings-none", "<prefix><highlight><player></highlight> has no active warnings.", placeholders = listOf("player"))
    public val banlistHeader: MessageKey = message("banlist-header", "<prefix>Banned on this server (<count>):", "/banlist and /mutelist. <count> on this server.", placeholders = listOf("count"))
    public val mutelistHeader: MessageKey = message("mutelist-header", "<prefix>Muted on this server (<count>):", placeholders = listOf("count"))
    public val listEmpty: MessageKey = message("list-empty", "<prefix>Nobody.")
    public val altsHeader: MessageKey = message(
        "alts-header", "<prefix>Accounts sharing an IP with <highlight><player></highlight> (<count>):",
        "/alts. <player>, <count>. Each account: <name>, with <state> from alt-states (banned,", "muted, online or offline).",
        placeholders = listOf("player", "count"),
    )
    public val altsLine: MessageKey = message("alts-line", "<gray>- <name> <state>", placeholders = listOf("name", "state"))
    public val altsNone: MessageKey = message("alts-none", "<prefix><highlight><player></highlight> shares no IP with anyone.", placeholders = listOf("player"))
    public val altBanned: MessageKey = message("alt-states.banned", "<#FF6B6B>(banned)</#FF6B6B>")
    public val altMuted: MessageKey = message("alt-states.muted", "<#FFB86B>(muted)</#FFB86B>")
    public val altOnline: MessageKey = message("alt-states.online", "<#5EE08A>(online)</#5EE08A>")
    public val altOffline: MessageKey = message("alt-states.offline", "")
    public val ipHistoryHeader: MessageKey = message(
        "ip-history-header", "<prefix><highlight><player></highlight>'s IPs (<count>):",
        "/iphistory. <player>, <count>. Each line: <ip>, <first>, <last> (dates).", placeholders = listOf("player", "count"),
    )
    public val ipHistoryLine: MessageKey = message("ip-history-line", "<gray>- <white><ip></white>, first <first>, last <last>", placeholders = listOf("ip", "first", "last"))
    public val ipHistoryNone: MessageKey = message("ip-history-none", "<prefix>No IPs recorded for <highlight><player></highlight>.", placeholders = listOf("player"))

    // ---- /xbans import ----

    public val importCounted: MessageKey = message(
        "imports.counted",
        "<prefix>Found in <highlight><source></highlight>: <bans> bans, <mutes> mutes, <warnings> warnings, <kicks> kicks and <players> players. Nothing was imported yet: run it again with <highlight>confirm</highlight> on the end.",
        "A dry run. <source>, <bans>, <mutes>, <warnings>, <kicks>, <players> found.",
        placeholders = listOf("source", "bans", "mutes", "warnings", "kicks", "players"),
    )
    public val importStarted: MessageKey = message("imports.started", "<prefix>Importing from <highlight><source></highlight>…", placeholders = listOf("source"))
    public val importDone: MessageKey = message(
        "imports.done", "<prefix><success>Imported</success> <highlight><added></highlight> punishments from <source> (<skipped> already there).",
        "<source>, <added> new punishments, <skipped> already imported.", placeholders = listOf("added", "source", "skipped"),
    )
    public val importFailed: MessageKey = message("imports.failed", "<prefix><error>Couldn't import:</error> <reason>", "<reason> it couldn't, in English.", placeholders = listOf("reason"))
    public val importWildcards: MessageKey = message(
        "imports.wildcards", "<prefix><highlight><count></highlight> wildcard IP bans (such as 192.168.1.*) aren't imported: xBans bans whole addresses only.",
        "A dry run or an import that found LiteBans wildcard IP bans (192.168.1.*), which aren't imported. <count>.", placeholders = listOf("count"),
    )
    public val importRunning: MessageKey = message("imports.running", "<prefix><error>An import is already running.")
    public val importUnknown: MessageKey = message("imports.unknown-source", "<prefix><error>Import from what?</error> One of: <sources>", "<sources>: the ones xBans knows.", placeholders = listOf("sources"))

    // ---- the proxy ----

    public val proxyInfo: MessageKey = message(
        "proxy.info",
        "<prefix>xBans <highlight><version></highlight> on <platform>: <count> bans in force from <storage>, enforced at the proxy's login. <gray>/xbansproxy reload | debug",
        "/xbansproxy on the proxy. <storage>: the database, and whether the servers share it.",
        placeholders = listOf("version", "platform", "count", "storage"),
    )

    /** The core's messages and xBans', in the order `lang/` files hold them. */
    public val catalog: MessageCatalog = MessageCatalog.of(CoreMessages, this)
}
