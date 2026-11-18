package co.xenastudios.xstats.config;

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
import java.time.DayOfWeek;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** xStats' config.yml. Its defaults are {@link #DEFAULTS}; the docs show the file they produce. */
public record XSConfig(
    @Comment({
        "The look xStats' messages share. The default messages use these as tags, so changing",
        "a colour here restyles them all. Write a style as MiniMessage tags: <gray>,",
        "<#F77FA5>, or <gold><bold>."})
    Theme theme,
    @Comment("What players are told.") Messages messages,
    @Comment("The commands players use. Blank permission means everyone may.") Commands commands,
    @Comment({
        "The stats xStats keeps, in the order /stats and the website show them. Each has:",
        "  name: what players see.",
        "  source: where the numbers come from:",
        "    one of Minecraft's own stats, as /scoreboard names them: player_kills, jump,",
        "      mined:diamond_ore, killed:zombie, crafted:* (every item). Add several with +.",
        "    xstats:playtime (time online, not counting time standing still with the camera",
        "      unmoved) or xstats:sessions (times joined).",
        "    papi:%placeholder%: a PlaceholderAPI value, such as papi:%vault_eco_balance%,",
        "      read every save and when the player leaves.",
        "  unit: auto, count, time, distance or damage. auto works it out from the source.",
        "  decimals: for papi values with decimals, such as 2 for a balance.",
        "  periods: also count it per day, week and month, for leaderboards and the website.",
        "    Each day a player's stat changes is one row in the database, so only turn it on",
        "    for stats you want leaderboards of.",
        "  in-game: show it on /stats and /leaderboard. Placeholders always work.",
        "  website: show it on the website and in the web API.",
        "An id is 1 to 32 lowercase letters, digits and _. Placeholders use it: %xstats_kills%."})
    Map<String, StatSettings> stats,
    @Comment("How stats are read and saved.") Collecting collecting,
    Leaderboards leaderboards,
    @Comment("Days, weeks and months, for daily, weekly and monthly leaderboards.") Periods periods,
    @Comment({
        "The stats website and its JSON API, served by xStats itself on its own port. Off until",
        "you turn it on: see https://docs.xenastudios.co/xstats/website/ for safe setups,",
        "behind a reverse proxy or a tunnel."})
    Web web,
    @Comment({
        "This server's name in the database, for networks: each server's stats are kept apart",
        "under it and added up for the network. Letters, digits, - and _."})
    String serverName,
    @Comment({
        "Where stats are kept. sqlite needs nothing set up. Use mysql for a network, so every",
        "server shares one set of stats and one website. A change here applies on restart."})
    StorageSettings storage,
    @Comment({
        "For servers sharing one MySQL database: each server hears about the others' saves",
        "and /stats private, and its leaderboards count the whole network."})
    NetworkSettings network,
    @Comment("Tells you when a new version is out.") UpdateCheckerSettings updateChecker) implements XenaConfig {

    @Override
    public ErrorMessages errorMessages() {
        return messages.error();
    }

    public enum Unit { AUTO, COUNT, TIME, DISTANCE, DAMAGE }

    public record StatSettings(
        String name,
        String source,
        Unit unit,
        @Range(min = 0, max = 4) int decimals,
        boolean periods,
        boolean inGame,
        boolean website) {}

    public record Commands(
        @Comment({
            "/stats [player]: a player's stats. /stats private hides your own from the website",
            "(xstats.private, everyone has it)."})
        Command stats,
        @Comment({
            "/leaderboard <stat> [daily|weekly|monthly|all] [page]: a leaderboard. Without",
            "xUtilities, whose /top this would clash with, you can add top to its aliases."})
        Command leaderboard) {}

    public record Command(boolean enabled, List<String> aliases, String permission) implements Toggle {}

    public record Collecting(
        @Comment({
            "Minutes between saves. Each save reads online players' stats, a few each tick,",
            "writes what changed, and works out every leaderboard. A crash loses at most this",
            "much of playtime and placeholder stats; Minecraft's own stats are never lost."})
        @Range(min = 1, max = 60) int saveEveryMinutes,
        @Comment("Players read per tick during a save. Lower spreads a save over more ticks.")
        @Range(min = 1, max = 50) int readsPerTick,
        @Comment({
            "Leave out of playtime the minutes a player didn't move their camera at all, as when",
            "they're away from the keyboard. Checked at each save, by comparing where they look."})
        boolean afkAware,
        @Comment({
            "On first start, read every player's history from the world's stats folder, so",
            "leaderboards aren't empty on day one. /xstats import runs it again."})
        boolean importOnFirstStart) {}

    public record Leaderboards(
        @Comment("Places kept on each leaderboard: for /leaderboard, placeholders and the website.")
        @Range(min = 3, max = 1000) int size,
        @Comment({
            "Leave players banned with /ban out of leaderboards. Players with",
            "xstats.leaderboard.exclude are always left out, such as staff; nobody has it unless",
            "given it, operators included."})
        boolean excludeBanned,
        @Comment("Places on each page of /leaderboard.") @Range(min = 1, max = 30) int perPage,
        @Comment("Leaderboards in the world: Paper text displays, placed with /xstats board.") Boards boards) {}

    public record Boards(
        @Comment("Places each in-world board shows.") @Range(min = 1, max = 20) int lines,
        @Comment("The top line. <stat> and <period> are filled in.") Message title,
        @Comment("Each place: <rank>, <player>, <value>.") Message line,
        @Comment("When nobody is on it yet.") Message empty) {}

    public record Periods(
        @Comment({
            "The time zone days start in, such as Europe/London or America/New_York. Blank uses",
            "the server's own."})
        String timezone,
        @Comment("The day weeks start on.") DayOfWeek weekStarts,
        @Comment({
            "Days to keep each day's numbers. Older days are added into their month, so monthly",
            "and all-time numbers stay right while the database stays small."})
        @Range(min = 35, max = 3650) int keepDailyDays,
        @Comment("How periods are named to players.") PeriodNames names) {}

    public record PeriodNames(String daily, String weekly, String monthly, String all) {}

    public record Web(
        @Comment("Serve the website and the API.") boolean enabled,
        @Comment({
            "The address to listen on. 0.0.0.0 is every network the machine has; 127.0.0.1 only",
            "this machine, for a reverse proxy or tunnel on the same machine."})
        String bind,
        @Comment({
            "The port. On a shared host, use a port your host gave the server (an extra",
            "allocation), or put the site behind your own domain with a tunnel."})
        @Range(min = 1, max = 65535) int port,
        @Comment({
            "The site's address as players open it, such as https://stats.example.com. /stats links",
            "to it, and /xstats web export writes it into the files. Blank leaves the link out."})
        String publicUrl,
        @Comment("Serve the website itself. Off serves only the JSON API, for a site hosted elsewhere.")
        boolean site,
        @Comment({
            "Sites hosted elsewhere that may read the API from a visitor's browser, such as",
            "https://example.com. \"*\" lets any site."})
        List<String> corsOrigins,
        @Comment({
            "Requests one visitor may make each minute. A visit to a page is a few; more are",
            "answered 429 until the minute is up."})
        @Range(min = 10, max = 10_000) int requestsPerMinute,
        @Comment("Requests handled at once. More wait for a moment, then get 503.")
        @Range(min = 1, max = 512) int maxConcurrent,
        @Comment({
            "Addresses of your reverse proxy or tunnel. Requests from them are counted against",
            "the visitor's address in X-Forwarded-For (or CF-Connecting-IP) instead, so the rate",
            "limit is per visitor. Leave it empty when visitors connect directly."})
        List<String> trustedProxies,
        Avatars avatars,
        @Comment("How the site looks.") Branding branding) {}

    public record Avatars(
        @Comment({
            "Where players' heads come from: {uuid}, {name} and {size} are filled in. The default",
            "is mc-heads.net; https://crafatar.com/avatars/{uuid}?size={size}&overlay works too."})
        String url,
        @Comment({
            "Fetch heads through xStats and keep them, so visitors' browsers never contact the",
            "service above. Heads are kept in memory for a day."})
        boolean proxy) {}

    public record Branding(
        @Comment("The site's title, such as your server's name. Blank shows \"Server stats\".") String name,
        @Comment("A line under the title. Blank for none.") String tagline,
        @Comment({
            "A logo: a link (https://...), or a PNG, JPEG, SVG or WebP file in plugins/xStats/web/,",
            "such as logo.png. Blank for none."})
        String logo,
        Colour accent,
        Colour background,
        Colour surface,
        Colour text) {}

    public record Messages(
        ErrorMessages error,
        @Comment({
            "/stats. <player> is whose; <first-join> and <last-seen> are when; <playtime> and <kd>",
            "(kills per death) are filled in when the playtime, kills and deaths stats exist."})
        Message profileHeader,
        @Comment("One stat. <stat>, <value>, and <rank>: their place on the all-time board, or blank.")
        Message profileLine,
        @Comment("How <rank> looks when they have one.") Message profileRank,
        @Comment("A section another plugin adds. <title> is its heading.") Message profileSection,
        @Comment("One of its lines: <label> and <value>.") Message profileSectionLine,
        @Comment("After the stats, when web.public-url is set. <url> is their page.") Message profileLink,
        @Comment("<player> is the name typed.") Message neverJoined,
        Message privateOn,
        Message privateOff,
        @Comment("/leaderboard. <stat>, <period>.") Message topHeader,
        @Comment("<rank>, <player>, <value>.") Message topLine,
        @Comment("The line for the player reading it, when they're on the page.") Message topLineSelf,
        Message topEmpty,
        @Comment("<stat> is what was typed; <stats> the ones there are.") Message unknownStat,
        @Comment("<stat> has no daily, weekly or monthly numbers.") Message noPeriods,
        @Comment("Under a page of /leaderboard. See the docs for <next> and <previous>.") Message pageFooter,
        @Comment("Before the database is open, just after startup.") Message notReady) {}

    private static Map<String, StatSettings> defaultStats() {
        Map<String, StatSettings> stats = new LinkedHashMap<>();
        stats.put("playtime", stat("Playtime", "xstats:playtime", true));
        stats.put("sessions", stat("Sessions", "xstats:sessions", false));
        stats.put("kills", stat("Player kills", "player_kills", true));
        stats.put("deaths", stat("Deaths", "deaths", true));
        stats.put("mob_kills", stat("Mobs killed", "mob_kills", true));
        stats.put("blocks_mined", stat("Blocks mined", "mined:*", true));
        stats.put("distance", stat("Distance travelled", "walk_one_cm + sprint_one_cm + crouch_one_cm + swim_one_cm"
            + " + walk_on_water_one_cm + walk_under_water_one_cm + climb_one_cm + fly_one_cm + aviate_one_cm + horse_one_cm"
            + " + boat_one_cm + minecart_one_cm + pig_one_cm + strider_one_cm + happy_ghast_one_cm + nautilus_one_cm", true));
        stats.put("fish_caught", stat("Fish caught", "fish_caught", true));
        stats.put("jumps", stat("Jumps", "jump", false));
        stats.put("damage_dealt", stat("Damage dealt", "damage_dealt", false));
        stats.put("animals_bred", stat("Animals bred", "animals_bred", false));
        stats.put("villager_trades", stat("Villager trades", "traded_with_villager", false));
        stats.put("items_enchanted", stat("Items enchanted", "enchant_item", false));
        stats.put("raids_won", stat("Raids won", "raid_win", false));
        return stats;
    }

    private static StatSettings stat(String name, String source, boolean periods) {
        return new StatSettings(name, source, Unit.AUTO, 0, periods, true, true);
    }

    public static final XSConfig DEFAULTS = new XSConfig(
        new Theme(
            "<#F77FA5>»</#F77FA5> ",
            TextStyle.parse("<gray>"),
            TextStyle.parse("<#F77FA5>"),
            TextStyle.parse("<#5EE08A>"),
            TextStyle.parse("<#FF6B6B>")),
        new Messages(
            ErrorMessages.DEFAULTS,
            Message.of("<prefix><highlight><player></highlight>'s stats <dark_gray>·</dark_gray> joined <white><first-join></white>, last seen <white><last-seen></white>"),
            Message.of("  <gray><stat>:</gray> <white><value></white><rank>"),
            Message.of(" <dark_gray>#<rank></dark_gray>"),
            Message.of("<prefix><highlight><title>"),
            Message.of("  <gray><label>:</gray> <white><value>"),
            Message.of("<prefix><click:open_url:'<url>'><hover:show_text:'Open in your browser'><highlight><underlined>See it on the website</underlined></highlight></hover></click>"),
            Message.of("<prefix><error><highlight><player></highlight> has never played here."),
            Message.of("<prefix><success>Your stats are hidden</success> from the website. Leaderboards in game still show you."),
            Message.of("<prefix>Your stats are <highlight>on the website</highlight> again."),
            Message.of("<prefix><highlight><stat></highlight> <dark_gray>·</dark_gray> <period>"),
            Message.of("  <gray><rank>.</gray> <white><player></white> <dark_gray>-</dark_gray> <highlight><value>"),
            Message.of("  <highlight><rank>. <player></highlight> <dark_gray>-</dark_gray> <highlight><value>"),
            Message.of("<prefix>Nobody's on this leaderboard yet."),
            Message.of("<prefix><error>There's no stat called <highlight><stat></highlight>.</error> Try: <stats>"),
            Message.of("<prefix><error><highlight><stat></highlight> is only counted all time."),
            Message.of(ChatPages.FOOTER),
            Message.of("<prefix><error>Stats are still loading. Try again in a moment.")),
        new Commands(
            new Command(true, List.of(), ""),
            new Command(true, List.of("lb"), "")),
        defaultStats(),
        new Collecting(5, 4, true, true),
        new Leaderboards(100, true, 10,
            new Boards(10,
                Message.of("<#F77FA5><bold><stat></bold></#F77FA5>\n<gray><period>"),
                Message.of("<gray><rank>.</gray> <white><player></white> <#F77FA5><value>"),
                Message.of("<gray>Nobody yet"))),
        new Periods("", DayOfWeek.MONDAY, 90, new PeriodNames("Today", "This week", "This month", "All time")),
        new Web(false, "0.0.0.0", 8804, "", true, List.of("*"), 120, 32, List.of(),
            new Avatars("https://mc-heads.net/avatar/{uuid}/{size}", false),
            new Branding("", "", "", Colour.of("#F77FA5"), Colour.of("#0F1419"), Colour.of("#1A222B"), Colour.of("#E6EDF3"))),
        "main",
        StorageSettings.defaults("xstats_"),
        NetworkSettings.DEFAULTS,
        UpdateCheckerSettings.DEFAULTS);

    public static final ConfigSpec<XSConfig> SPEC = ConfigSpec.builder(XSConfig.class, DEFAULTS)
        .header(
            "xStats: player stats, leaderboards and a website.",
            "Docs: https://docs.xenastudios.co/xstats/configuration/",
            "",
            "Apply changes with /xstats reload. A bad value falls back to its default with a",
            "warning in the console; xStats never stops over config. All text is MiniMessage:",
            "https://docs.xenastudios.co/start/formatting/")
        .codec(Colour.class, Colour.CODEC)
        .template(StatSettings.class, new StatSettings("", "", Unit.AUTO, 0, false, true, true))
        .version(1)
        .build();
}
