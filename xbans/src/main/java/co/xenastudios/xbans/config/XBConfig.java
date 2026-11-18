package co.xenastudios.xbans.config;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.UpdateCheckerSettings;
import co.xenastudios.core.XenaConfig;
import co.xenastudios.core.command.Toggle;
import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.config.Range;
import co.xenastudios.core.db.NetworkSettings;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.core.text.ChatPages;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.TextStyle;
import co.xenastudios.core.text.Theme;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.model.Length;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** xBans' config.yml. Its defaults are {@link #DEFAULTS}; the docs show the file they produce. */
public record XBConfig(
    @Comment({
        "The look xBans' messages share. The default messages use these as tags, so changing",
        "a colour here restyles them all. Write a style as MiniMessage tags: <gray>,",
        "<#FF8A5B>, or <gold><bold>."})
    Theme theme,
    @Comment({
        "What punished players see. <reason>, <staff>, <id>, <expires> (a date), <time-left>,",
        "<duration> and <appeal> (appeal-link below) work in each."})
    Screens screens,
    @Comment({
        "Told to the server when someone is punished. <player>, <staff>, <reason>, <duration>",
        "(\"for 7d\" or \"permanently\", from the words below) and <id>. Silent punishments (-s)",
        "go only to staff with xbans.notify.silent, with silent-prefix in front."})
    Broadcasts broadcasts,
    @Comment("Replies to staff, and lookups.") Messages messages,
    @Comment({
        "Each command can be turned off (it isn't registered, so another plugin can have its",
        "name), given aliases, or a different permission. xBans takes over vanilla /ban,",
        "/pardon, /ban-ip, /pardon-ip, /kick and /banlist, and gives them back when its own",
        "are off."})
    Commands commands,
    @Comment("How punishing works.") Punishing punishing,
    @Comment("What a mute blocks besides chat.") Mutes mutes,
    Warnings warnings,
    @Comment({
        "Templates: /ban Steve hacking follows the \"hacking\" template. Each time the same",
        "player gets the same template, the next step of its ladder applies (the last step",
        "repeats). type is ban, mute or warn. A past punishment counts toward the ladder",
        "until expire-ladder has passed since it was made, unless it was revoked. ip makes",
        "it an IP punishment. A template's permission is needed to use it (blank: the",
        "command's own); template durations aren't held to staff-limits."})
    Map<String, Template> templates,
    @Comment("Who can punish whom, and for how long.") StaffLimits staffLimits,
    @Comment({
        "IP addresses: for IP bans and mutes, /alts and /iphistory. IP addresses are personal",
        "data: see https://docs.xenastudios.co/xbans/privacy/"})
    Ips ips,
    @Comment({
        "Server scopes, for networks: a punishment can apply on every server (global) or on one.",
        "/ban Steve server:survival bans Steve on survival only."})
    Scopes scopes,
    @Comment("Lists: /history, /staffhistory, /warnings, /banlist, /mutelist, /alts.") Lists lists,
    @Comment({
        "Where punishments are kept. sqlite needs nothing set up. Networks point every server",
        "at the same MySQL or MariaDB database and turn network on. A change here applies on",
        "reload: the new database's bans are loaded before it's used."})
    StorageSettings storage,
    @Comment({
        "Network mode: a ban made on one server kicks the player from the others and reaches",
        "their ban lists within check-every seconds."})
    NetworkSettings network,
    @Comment("Tells you when a new version is out.") UpdateCheckerSettings updateChecker) implements XenaConfig {

    @Override
    public ErrorMessages errorMessages() {
        return messages.error();
    }

    public record Screens(
        @Comment("A permanent ban, shown when they're kicked and each time they try to join.") Message ban,
        Message tempBan,
        @Comment("A ban on their IP rather than their account.") Message ipBan,
        Message kick,
        @Comment("<appeal> in the screens.") String appealLink,
        @Comment({
            "Shown when they can't join because xBans is still loading its bans (only in the first",
            "seconds after a start, or while the database can't be reached at startup)."})
        Message notReady,
        @Comment("Told to a muted player who tries to chat.") Message muted,
        Message tempMuted,
        @Comment("Told to a player when they're muted, warned or unmuted, if they're online.") Message youWereMuted,
        Message youWereWarned,
        Message youWereUnmuted) {}

    public record Broadcasts(
        Message ban,
        Message ipBan,
        Message mute,
        Message ipMute,
        Message warn,
        Message kick,
        Message unban,
        Message unmute,
        Message unwarn,
        String silentPrefix,
        @Comment("<duration> for a permanent punishment, and for one with an end (<time> is how long).")
        String permanently,
        String forTime,
        @Comment({
            "Told to staff with xbans.notify.banned-join when a banned player tries to join. <player>,",
            "<reason>. Blank for none."})
        Message bannedJoin,
        @Comment({
            "Told to staff with xbans.notify.alts when a player joins who shares an IP with a banned",
            "account. <player>, and <alts>: the banned accounts. Blank for none."})
        Message altJoin) {}

    public record Messages(
        ErrorMessages error,
        @Comment("xBans is still opening its database, just after a start.") Message notReady,
        @Comment("<player> is the name typed.") Message neverJoined,
        Message badIp,
        @Comment("<player>'s IP isn't known (they haven't joined since IPs were recorded).") Message noIp,
        @Comment("IP commands are off because ips.enabled is false.") Message ipsOff,
        @Comment("<input> isn't a length of time.") Message badDuration,
        @Comment("<player> is already banned or muted, longer than this would. <expires> is when it ends.")
        Message alreadyBanned,
        Message alreadyMuted,
        Message notBanned,
        Message notMuted,
        Message noWarnings,
        @Comment("<id> isn't a punishment, or isn't one this command can revoke.") Message noSuchId,
        @Comment("<player> has xbans.exempt.") Message exempt,
        @Comment("<player>'s weight (xbans.weight.<n>) is as high as the sender's or higher.") Message outranks,
        @Comment("Over the sender's staff-limits. <max> is the longest they may give.") Message overLimit,
        @Comment("A template's permission is missing. <template> is its name.") Message templateNoPermission,
        @Comment("Choosing a server: needs xbans.scope.") Message scopeNoPermission,
        Message extraSilentNoPermission,
        @Comment("Another plugin stopped the punishment.") Message cancelled,
        @Comment("Couldn't be saved. The console says why.") Message failed,
        @Comment("/prunehistory. <count> entries of <player>'s were removed.") Message pruned,
        @Comment("/prunehistory without confirming. <count> would be removed.") Message pruneConfirm,
        @Comment("/history and /staffhistory. <player>, <count>.") Message historyHeader,
        Message staffHistoryHeader,
        @Comment({
            "One punishment in a list. <id>, <date>, <type> (from types below), <player>, <staff>,",
            "<reason>, <length> (\"7d\", \"permanent\"), <status> (from the words below), <scope>."})
        Message historyLine,
        Message historyEmpty,
        Message footer,
        @Comment("<status> words. <time> is how long is left; <staff> who revoked it.")
        StatusWords status,
        TypeWords types,
        @Comment("/checkban and /checkmute: a line per active one, then its details.") Message checkNone,
        Message check,
        @Comment("/warnings. <player>, <count> active.") Message warningsHeader,
        Message warningsNone,
        @Comment("/banlist and /mutelist. <count> on this server.") Message banlistHeader,
        Message mutelistHeader,
        Message listEmpty,
        @Comment({
            "/alts. <player>, <count>. Each account: <name>, with <state> from alt-states (banned,",
            "muted, online or offline)."})
        Message altsHeader,
        Message altsLine,
        Message altsNone,
        AltStates altStates,
        @Comment("/iphistory. <player>, <count>. Each line: <ip>, <first>, <last> (dates).") Message ipHistoryHeader,
        Message ipHistoryLine,
        Message ipHistoryNone,
        ImportMessages imports) {}

    public record StatusWords(String active, String ended, String revoked, String kick) {}

    public record TypeWords(String ban, String ipBan, String mute, String ipMute, String warn, String kick) {}

    public record AltStates(String banned, String muted, String online, String offline) {}

    public record ImportMessages(
        @Comment("A dry run. <source>, <bans>, <mutes>, <warnings>, <kicks>, <players> found.") Message counted,
        Message started,
        @Comment("<source>, <added> new punishments, <skipped> already imported.") Message done,
        @Comment("<reason> it couldn't.") Message failed,
        Message running,
        @Comment("<sources>: the ones xBans knows.") Message unknownSource) {}

    /** The settings every command has: on or off, aliases, permission. */
    public record Cmd(boolean enabled, List<String> aliases, String permission) implements Toggle {}

    public record Commands(
        Cmd ban, Cmd tempban, Cmd ipban, Cmd tempipban, Cmd unban,
        Cmd mute, Cmd tempmute, Cmd ipmute, Cmd tempipmute, Cmd unmute,
        Cmd warn, Cmd unwarn, Cmd kick,
        Cmd history, Cmd staffhistory, Cmd checkban, Cmd checkmute,
        @Comment("Players see their own warnings with this; others' need xbans.warnings.others.") Cmd warnings,
        Cmd banlist, Cmd mutelist, Cmd prunehistory,
        @Comment("Accounts that share an IP with a player: LiteBans' /dupeip.") Cmd alts,
        Cmd iphistory) {}

    public record Punishing(
        @Comment("The reason when none is given.") String defaultReason,
        @Comment("Punishments are silent (-s) unless -p (public) is given.") boolean silentByDefault) {}

    public record Mutes(
        @Comment({
            "Commands a muted player can't use. Aliases and namespaced forms (/essentials:msg) are",
            "blocked too."})
        List<String> blockedCommands,
        @Comment("Muted players can't write on signs.") boolean blockSigns,
        @Comment("Muted players can't write or sign books.") boolean blockBooks) {}

    public record Warnings(
        @Comment("How long a warning lasts when /warn isn't given a duration.") Length defaultDuration,
        @Comment({
            "Commands the console runs when a player reaches a number of active warnings. <player>",
            "is their name; each key is the count."})
        Map<String, WarnAction> actions) {}

    public record WarnAction(List<String> commands) {}

    public record Template(
        PunishmentType type,
        String reason,
        List<Length> ladder,
        Length expireLadder,
        boolean ip,
        boolean silent,
        String permission) {}

    public record StaffLimits(
        @Comment({
            "Weights: a player with xbans.weight.<n> can only be punished by someone with a higher",
            "weight. Players without one can be punished by anyone with the command. A player's",
            "weight is the one they had when they last joined, if they're offline."})
        boolean weights,
        @Comment({
            "Longest ban and mute each group may give. A staff member with a group's permission",
            "gets its limits; with several, the longest. Staff in no group have none. The",
            "permissions aren't given to operators by default."})
        Map<String, Limit> groups) {}

    public record Limit(String permission, Length ban, Length mute) {}

    public record Ips(
        @Comment({
            "Record players' IPs. Off: no IP bans or mutes, /alts or /iphistory, and nothing is",
            "stored."})
        boolean enabled,
        @Comment({
            "Store a salted hash instead of the IP: alts and IP bans still work, but there's no list",
            "of addresses to leak. The salt is in ip-salt.txt in xBans' folder; copy it to every",
            "server of a network. Once on, it stays on for that database: addresses already stored",
            "are hashed."})
        boolean hash,
        @Comment("Tell staff when a player joins who shares an IP with a banned account.") boolean altAlerts) {}

    public record Scopes(
        @Comment("This server's name, for server:<name>. Each server of a network needs its own.") String serverName,
        @Comment("global: punishments apply on every server unless server: says otherwise. local: on this one.")
        DefaultScope defaultScope) {

        public enum DefaultScope { GLOBAL, LOCAL }
    }

    public record Lists(
        @Range(min = 1, max = 50) int perPage,
        @Comment("How dates are written, as in Java's DateTimeFormatter. The server's time zone.") String dateFormat) {}

    private static Cmd cmd(String permission, String... aliases) {
        return new Cmd(true, List.of(aliases), permission);
    }

    private static Template template(PunishmentType type, String reason, String... ladder) {
        return new Template(type, reason, java.util.Arrays.stream(ladder).map(Length::parse).toList(),
            Length.parse("180d"), false, false, "");
    }

    private static <V> Map<String, V> ordered(Object... pairs) {
        Map<String, V> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked") V value = (V) pairs[i + 1];
            map.put((String) pairs[i], value);
        }
        return map;
    }

    private static final String SCREEN_END = "\n\n<gray>Appeal at <white><appeal></white>\n<dark_gray>ID #<id>";

    public static final XBConfig DEFAULTS = new XBConfig(
        new Theme(
            "<#FF8A5B>»</#FF8A5B> ",
            TextStyle.parse("<gray>"),
            TextStyle.parse("<#FF8A5B>"),
            TextStyle.parse("<#5EE08A>"),
            TextStyle.parse("<#FF6B6B>")),
        new Screens(
            Message.of("<#FF6B6B><bold>You are banned from this server.</bold>\n\n<gray>Reason: <white><reason>" + SCREEN_END),
            Message.of("<#FF6B6B><bold>You are banned from this server.</bold>\n\n<gray>Reason: <white><reason>"
                + "\n<gray>Ends in <white><time-left></white> (<expires>)" + SCREEN_END),
            Message.of("<#FF6B6B><bold>Your IP is banned from this server.</bold>\n\n<gray>Reason: <white><reason>"
                + "\n<gray>Ends: <white><expires>" + SCREEN_END),
            Message.of("<#FF8A5B><bold>You were kicked.</bold>\n\n<gray>Reason: <white><reason>"),
            "https://example.com/appeal",
            Message.of("<#FF8A5B>The server is still starting.</#FF8A5B>\n<gray>Try again in a few seconds."),
            Message.of("<prefix><error>You're muted:</error> <reason>"),
            Message.of("<prefix><error>You're muted</error> for <highlight><time-left></highlight>: <reason>"),
            Message.of("<prefix><error>You were muted</error> by <highlight><staff></highlight> <duration>: <reason>"),
            Message.of("<prefix><error>You were warned</error> by <highlight><staff></highlight>: <reason>"),
            Message.of("<prefix><success>You can chat again.</success>")),
        new Broadcasts(
            Message.of("<prefix><highlight><staff></highlight> banned <highlight><player></highlight> <duration>: <reason>"),
            Message.of("<prefix><highlight><staff></highlight> IP-banned <highlight><player></highlight> <duration>: <reason>"),
            Message.of("<prefix><highlight><staff></highlight> muted <highlight><player></highlight> <duration>: <reason>"),
            Message.of("<prefix><highlight><staff></highlight> IP-muted <highlight><player></highlight> <duration>: <reason>"),
            Message.of("<prefix><highlight><staff></highlight> warned <highlight><player></highlight>: <reason>"),
            Message.of("<prefix><highlight><staff></highlight> kicked <highlight><player></highlight>: <reason>"),
            Message.of("<prefix><highlight><staff></highlight> unbanned <highlight><player></highlight>."),
            Message.of("<prefix><highlight><staff></highlight> unmuted <highlight><player></highlight>."),
            Message.of("<prefix><highlight><staff></highlight> removed a warning from <highlight><player></highlight>."),
            "<dark_gray>[Silent]</dark_gray> ",
            "permanently",
            "for <time>",
            Message.of("<prefix><highlight><player></highlight> tried to join but is banned: <reason>"),
            Message.of("<prefix><highlight><player></highlight> shares an IP with banned <highlight><alts></highlight>.")),
        new Messages(
            ErrorMessages.DEFAULTS,
            Message.of("<prefix><error>xBans is still opening its database.</error> Try again in a moment."),
            Message.of("<prefix><error><highlight><player></highlight> has never joined this server."),
            Message.of("<prefix><error>That isn't an IP address."),
            Message.of("<prefix><error>xBans doesn't know <highlight><player></highlight>'s IP yet."),
            Message.of("<prefix><error>IP punishments are off on this server."),
            Message.of("<prefix><error><highlight><input></highlight> isn't a length of time.</error> Use 30m, 1d12h, 2mo or perm."),
            Message.of("<prefix><error><highlight><player></highlight> is already banned until <expires>.</error> Unban them first to shorten it."),
            Message.of("<prefix><error><highlight><player></highlight> is already muted until <expires>.</error> Unmute them first to shorten it."),
            Message.of("<prefix><error><highlight><player></highlight> isn't banned."),
            Message.of("<prefix><error><highlight><player></highlight> isn't muted."),
            Message.of("<prefix><error><highlight><player></highlight> has no active warnings."),
            Message.of("<prefix><error>There's no punishment <highlight>#<id></highlight> to do that to."),
            Message.of("<prefix><error><highlight><player></highlight> can't be punished."),
            Message.of("<prefix><error><highlight><player></highlight> outranks you."),
            Message.of("<prefix><error>You can give at most <highlight><max></highlight>."),
            Message.of("<prefix><error>You can't use the <highlight><template></highlight> template."),
            Message.of("<prefix><error>You can't choose the server."),
            Message.of("<prefix><error>You can't use -S."),
            Message.of("<prefix><error>Another plugin stopped that."),
            Message.of("<prefix><error>That couldn't be saved.</error> The console says why."),
            Message.of("<prefix><success>Removed</success> <highlight><count></highlight> inactive entries from <player>'s history."),
            Message.of("<prefix>That removes <highlight><count></highlight> inactive entries from <player>'s history for good."
                + " Run it again with <highlight>confirm</highlight> on the end."),
            Message.of("<prefix><highlight><player></highlight>'s history (<count>):"),
            Message.of("<prefix>Punishments by <highlight><player></highlight> (<count>):"),
            Message.of("<dark_gray>#<id></dark_gray> <gray><date></gray> <type> <highlight><player></highlight>"
                + " <dark_gray>·</dark_gray> <length> <dark_gray>·</dark_gray> <white><reason></white>"
                + " <dark_gray>by</dark_gray> <staff> <status>"),
            Message.of("<prefix>Nothing found."),
            Message.of(ChatPages.FOOTER),
            new StatusWords("<#5EE08A>(active, <time> left)</#5EE08A>", "<dark_gray>(ended)</dark_gray>",
                "<dark_gray>(revoked by <staff>)</dark_gray>", ""),
            new TypeWords("<#FF6B6B>Ban</#FF6B6B>", "<#FF6B6B>IP ban</#FF6B6B>", "<#FFB86B>Mute</#FFB86B>",
                "<#FFB86B>IP mute</#FFB86B>", "<#F5D76B>Warn</#F5D76B>", "<#B48CFF>Kick</#B48CFF>"),
            Message.of("<prefix><highlight><player></highlight> has nothing active of that kind."),
            Message.of("<prefix><type> <highlight>#<id></highlight> on <highlight><player></highlight>, <length>, ends <expires>"
                + "\n<gray>Reason: <white><reason></white>\n<gray>By <staff> on <date>, scope <scope>"),
            Message.of("<prefix><highlight><player></highlight> has <count> active warnings:"),
            Message.of("<prefix><highlight><player></highlight> has no active warnings."),
            Message.of("<prefix>Banned on this server (<count>):"),
            Message.of("<prefix>Muted on this server (<count>):"),
            Message.of("<prefix>Nobody."),
            Message.of("<prefix>Accounts sharing an IP with <highlight><player></highlight> (<count>):"),
            Message.of("<gray>- <name> <state>"),
            Message.of("<prefix><highlight><player></highlight> shares no IP with anyone."),
            new AltStates("<#FF6B6B>(banned)</#FF6B6B>", "<#FFB86B>(muted)</#FFB86B>", "<#5EE08A>(online)</#5EE08A>", ""),
            Message.of("<prefix><highlight><player></highlight>'s IPs (<count>):"),
            Message.of("<gray>- <white><ip></white>, first <first>, last <last>"),
            Message.of("<prefix>No IPs recorded for <highlight><player></highlight>."),
            new ImportMessages(
                Message.of("<prefix>Found in <highlight><source></highlight>: <bans> bans, <mutes> mutes, <warnings> warnings,"
                    + " <kicks> kicks and <players> players. Nothing was imported yet: run it again with"
                    + " <highlight>confirm</highlight> on the end."),
                Message.of("<prefix>Importing from <highlight><source></highlight>…"),
                Message.of("<prefix><success>Imported</success> <highlight><added></highlight> punishments from <source>"
                    + " (<skipped> already there)."),
                Message.of("<prefix><error>Couldn't import:</error> <reason>"),
                Message.of("<prefix><error>An import is already running."),
                Message.of("<prefix><error>Import from what?</error> One of: <sources>"))),
        new Commands(
            cmd("xbans.ban"), cmd("xbans.tempban"), cmd("xbans.ipban", "banip", "ban-ip"), cmd("xbans.ipban", "tempbanip"),
            cmd("xbans.unban", "pardon", "unbanip", "pardon-ip"),
            cmd("xbans.mute"), cmd("xbans.tempmute"), cmd("xbans.ipmute", "muteip"), cmd("xbans.ipmute", "tempmuteip"),
            cmd("xbans.unmute", "unmuteip"),
            cmd("xbans.warn"), cmd("xbans.unwarn"), cmd("xbans.kick"),
            cmd("xbans.history", "hist"), cmd("xbans.staffhistory", "staffhist"), cmd("xbans.checkban"), cmd("xbans.checkmute"),
            cmd("xbans.warnings"), cmd("xbans.banlist"), cmd("xbans.mutelist"), cmd("xbans.prunehistory"),
            cmd("xbans.alts", "dupeip", "checkalts"), cmd("xbans.iphistory")),
        new Punishing("No reason given", false),
        new Mutes(List.of("msg", "tell", "w", "whisper", "r", "reply", "me", "say", "mail", "m", "t", "pm", "dm",
            "message", "teammsg", "tm"), true, true),
        new Warnings(Length.parse("30d"), ordered(
            "3", new WarnAction(List.of("tempmute <player> 1h -s Reached 3 warnings")),
            "5", new WarnAction(List.of("tempban <player> 1d -s Reached 5 warnings")))),
        ordered(
            "hacking", template(PunishmentType.BAN, "Hacking", "7d", "30d", "permanent"),
            "spam", template(PunishmentType.MUTE, "Spamming", "1h", "1d", "7d"),
            "language", template(PunishmentType.WARN, "Inappropriate language", "30d")),
        new StaffLimits(true, ordered(
            "helper", new Limit("xbans.limit.helper", Length.parse("1d"), Length.parse("1d")),
            "moderator", new Limit("xbans.limit.moderator", Length.parse("30d"), Length.parse("30d")))),
        new Ips(true, false, true),
        new Scopes("server", Scopes.DefaultScope.GLOBAL),
        new Lists(10, "yyyy-MM-dd HH:mm"),
        StorageSettings.defaults("xbans_"),
        NetworkSettings.DEFAULTS,
        UpdateCheckerSettings.DEFAULTS);

    public static final ConfigSpec<XBConfig> SPEC = ConfigSpec.builder(XBConfig.class, DEFAULTS)
        .header(
            "xBans: bans, mutes and warnings, the LiteBans way.",
            "Docs: https://docs.xenastudios.co/xbans/configuration/",
            "",
            "Apply changes with /xbans reload. A bad value falls back to its default with a",
            "warning in the console; xBans never stops over config. All text is MiniMessage:",
            "https://docs.xenastudios.co/start/formatting/")
        .codec(Length.class, Length.CODEC)
        .template(Template.class, new Template(PunishmentType.BAN, "", List.of(Length.PERMANENT), Length.parse("180d"),
            false, false, ""))
        .template(WarnAction.class, new WarnAction(List.of()))
        .template(Limit.class, new Limit("", Length.PERMANENT, Length.PERMANENT))
        .version(1)
        .build();
}
