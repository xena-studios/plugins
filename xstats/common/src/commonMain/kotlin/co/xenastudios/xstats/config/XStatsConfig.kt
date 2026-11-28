package co.xenastudios.xstats.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.CommandTypes
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.ConfigContext
import co.xenastudios.core.common.config.ConfigGroup
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.ConfigSection
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.db.NetworkSettings
import co.xenastudios.core.common.family.MessagesSection
import co.xenastudios.core.common.family.StorageSection
import co.xenastudios.core.common.update.UpdatesSection
import kotlin.time.Duration.Companion.seconds

/**
 * xStats' `config.yml`, version 2 (xStats 2.0). Every setting 1.x had keeps its path and its
 * meaning, so a 1.x config carries forward with only its messages moved to `lang/` (with the
 * period names and the in-world boards' words), its theme into `messages.theme` and
 * `update-checker` into `updates` ([Legacy]); the 1.x jar put back reads the rest as it was. On the
 * proxy the same file is read: what only a server does (collecting, boards, integrations) does
 * nothing there, and `proxy` is the proxy's own.
 */
public object XStatsConfig : ConfigSchema(
    2,
    "xStats: player stats, leaderboards and a website.",
    "Docs: https://plugins.xenastudios.co/xstats/configuration/",
    "",
    "Apply changes with /xstats reload. A bad value falls back to its default with a",
    "warning in the console; xStats never stops over config. The messages are in lang/.",
) {
    /** The look and the language: the family's (`plugins/Xena/config.yml`) unless set here. */
    public val messages: MessagesSection = MessagesSection(this)

    public val commands: Commands = Commands(this)

    public val stats: ConfigKey<Map<String, StatSettings>> = key(
        "stats", StatsType, defaultStats(),
        "The stats xStats keeps, in the order /stats and the website show them. Each has:",
        "  name: what players see.",
        "  source: where the numbers come from:",
        "    one of Minecraft's own stats, as /scoreboard names them: player_kills, jump,",
        "      mined:diamond_ore, killed:zombie, crafted:* (every item). Add several with +.",
        "      A stat this server's Minecraft doesn't have reads 0.",
        "    xstats:playtime (time online, not counting time standing still with the camera",
        "      unmoved) or xstats:sessions (times joined).",
        "    papi:%placeholder%: a PlaceholderAPI value, such as papi:%vault_eco_balance%,",
        "      read every save and when the player leaves. Bukkit only.",
        "  unit: auto, count, time, distance or damage. auto works it out from the source.",
        "  decimals: for papi values with decimals, such as 2 for a balance.",
        "  periods: also count it per day, week and month, for leaderboards and the website.",
        "    Each day a player's stat changes is one row in the database, so only turn it on",
        "    for stats you want leaderboards of.",
        "  in-game: show it on /stats and /leaderboard. Placeholders always work.",
        "  website: show it on the website and in the web API.",
        "An id is 1 to 32 lowercase letters, digits and _. Placeholders use it: %xstats_kills%.",
    )

    public val collecting: Collecting = Collecting(this)
    public val leaderboards: Leaderboards = Leaderboards(this)
    public val periods: Periods = Periods(this)
    public val web: Web = Web(this)
    public val integrations: Integrations = Integrations(this)
    public val proxy: Proxy = Proxy(this)

    public val serverName: ConfigKey<String> = string(
        "server-name", "main",
        "This server's name in the database, for networks: each server's stats are kept apart",
        "under it and added up for the network. Letters, digits, - and _. Applies on restart.",
    )

    /** Where stats are kept: 1.x's `storage`. */
    public val storage: StorageSection = StorageSection(this, "xstats_")

    public val network: Network = Network(this)

    /** The update checker: `updates.check` was 1.x's `update-checker.enabled`. */
    public val updates: UpdatesSection = UpdatesSection(this)

    init {
        // 2.0: messages go to lang/ (Legacy carries the edited ones there), the theme into messages.
        @Suppress("UNCHECKED_CAST")
        migrate(1, co.xenastudios.xstats.Startup(co.xenastudios.xstats.STARTUP_CONFIG_ONE) as co.xenastudios.core.common.config.MigrationScope.() -> Unit)
    }

    public class Commands(parent: ConfigGroup) : ConfigSection(parent, "commands", "The commands players use. Blank permission means everyone may.") {
        public val stats: CommandSettings = CommandSettings(
            this, "stats", emptyList(),
            "/stats [player]: a player's stats. /stats private hides your own from the website",
            "(xstats.private, everyone has it).",
        )
        public val leaderboard: CommandSettings = CommandSettings(
            this, "leaderboard", listOf("lb"),
            "/leaderboard <stat> [daily|weekly|monthly|all] [page]: a leaderboard. Without",
            "xUtilities, whose /top this would clash with, you can add top to its aliases.",
        )
    }

    public class Collecting(parent: ConfigGroup) : ConfigSection(parent, "collecting", "How stats are read and saved.") {
        public val saveEveryMinutes: ConfigKey<Int> = int(
            "save-every-minutes", 5,
            "Minutes between saves. Each save reads online players' stats, a few each tick,",
            "writes what changed, and works out every leaderboard. A crash loses at most this",
            "much of playtime and placeholder stats; Minecraft's own stats are never lost.",
            min = 1, max = 60,
        )
        public val readsPerTick: ConfigKey<Int> = int(
            "reads-per-tick", 4, "Players read per tick during a save. Lower spreads a save over more ticks.", min = 1, max = 50,
        )
        public val afkAware: ConfigKey<Boolean> = boolean(
            "afk-aware", true,
            "Leave out of playtime the minutes a player didn't move their camera at all, as when",
            "they're away from the keyboard. Checked at each save, by comparing where they look.",
        )
        public val importOnFirstStart: ConfigKey<Boolean> = boolean(
            "import-on-first-start", true,
            "On first start, read every player's history from the world's stats folder, so",
            "leaderboards aren't empty on day one. /xstats import runs it again.",
        )
    }

    public class Leaderboards(parent: ConfigGroup) : ConfigSection(parent, "leaderboards") {
        public val size: ConfigKey<Int> = int(
            "size", 100, "Places kept on each leaderboard: for /leaderboard, placeholders and the website.", min = 3, max = 1000,
        )
        public val excludeBanned: ConfigKey<Boolean> = boolean(
            "exclude-banned", true,
            "Leave players banned with /ban out of leaderboards. Players with",
            "xstats.leaderboard.exclude are always left out, such as staff; nobody has it unless",
            "given it, operators included. So are players xVanish hides, while they're hidden.",
        )
        public val perPage: ConfigKey<Int> = int("per-page", 10, "Places on each page of /leaderboard.", min = 1, max = 30)
        public val boards: Boards = Boards(this)
    }

    public class Boards(parent: ConfigGroup) : ConfigSection(
        parent, "boards",
        "Leaderboards in the world, placed with /xstats board: text displays, so Minecraft",
        "1.19.4 or newer on Paper, Folia and Spigot. Their words are boards.* in lang/.",
    ) {
        public val lines: ConfigKey<Int> = int("lines", 10, "Places each in-world board shows.", min = 1, max = 20)
    }

    public class Periods(parent: ConfigGroup) : ConfigSection(
        parent, "periods",
        "Days, weeks and months, for daily, weekly and monthly leaderboards. Their names are",
        "periods.* in lang/.",
    ) {
        public val timezone: ConfigKey<String> = string(
            "timezone", "",
            "The time zone days start in, such as Europe/London or America/New_York. Blank uses",
            "the server's own. On a network, give every server and the proxy the same one.",
        )
        public val weekStarts: ConfigKey<WeekDay> = enum("week-starts", WeekDay.MONDAY, WeekDay.entries, "The day weeks start on.")
        public val keepDailyDays: ConfigKey<Int> = int(
            "keep-daily-days", 90,
            "Days to keep each day's numbers. Older days are added into their month, so monthly",
            "and all-time numbers stay right while the database stays small.",
            min = 35, max = 3650,
        )
    }

    public class Web(parent: ConfigGroup) : ConfigSection(
        parent, "web",
        "The stats website and its JSON API, served by xStats itself on its own port. Off until",
        "you turn it on: see https://plugins.xenastudios.co/xstats/website/ for safe setups,",
        "behind a reverse proxy or a tunnel. On a network, run it on one server or on the proxy.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", false, "Serve the website and the API.")
        public val bind: ConfigKey<String> = string(
            "bind", "0.0.0.0",
            "The address to listen on. 0.0.0.0 is every network the machine has; 127.0.0.1 only",
            "this machine, for a reverse proxy or tunnel on the same machine.",
        )
        public val port: ConfigKey<Int> = int(
            "port", 8804,
            "The port. On a shared host, use a port your host gave the server (an extra",
            "allocation), or put the site behind your own domain with a tunnel.",
            min = 1, max = 65535,
        )
        public val publicUrl: ConfigKey<String> = string(
            "public-url", "",
            "The site's address as players open it, such as https://stats.example.com. /stats links",
            "to it, and /xstats web export writes it into the files. Blank leaves the link out.",
        )
        public val site: ConfigKey<Boolean> = boolean("site", true, "Serve the website itself. Off serves only the JSON API, for a site hosted elsewhere.")
        public val corsOrigins: ConfigKey<List<String>> = stringList(
            "cors-origins", listOf("*"),
            "Sites hosted elsewhere that may read the API from a visitor's browser, such as",
            "https://example.com. \"*\" lets any site.",
        )
        public val requestsPerMinute: ConfigKey<Int> = int(
            "requests-per-minute", 120,
            "Requests one visitor may make each minute. A visit to a page is a few; more are",
            "answered 429 until the minute is up. Players' heads have a limit of their own, five",
            "times this, since a leaderboard shows many.",
            min = 10, max = 10_000,
        )
        public val maxConcurrent: ConfigKey<Int> = int("max-concurrent", 32, "Requests handled at once. More wait for a moment, then get 503.", min = 1, max = 512)
        public val trustedProxies: ConfigKey<List<String>> = stringList(
            "trusted-proxies", emptyList(),
            "Addresses of your reverse proxy or tunnel, or ranges such as 173.245.48.0/20.",
            "Requests from them are counted against the visitor's address the proxy passes on",
            "(in client-ip-header) instead, so the rate limit is per visitor. Leave it empty when",
            "visitors connect directly.",
        )
        public val clientIpHeader: ConfigKey<String> = string(
            "client-ip-header", "X-Forwarded-For",
            "The header a trusted proxy puts the visitor's address in. X-Forwarded-For suits",
            "nginx, Caddy and most tunnels. CF-Connecting-IP only if Cloudflare is the proxy",
            "listed above, or the one in front of it with nothing reaching yours another way:",
            "visitors can send any header themselves, and only Cloudflare replaces this one.",
        )
        public val avatars: Avatars = Avatars(this)
        public val branding: Branding = Branding(this)
    }

    public class Avatars(parent: ConfigGroup) : ConfigSection(parent, "avatars") {
        public val url: ConfigKey<String> = string(
            "url", "https://mc-heads.net/avatar/{uuid}/{size}",
            "Where players' heads come from: {uuid}, {name} and {size} are filled in. The default",
            "is mc-heads.net; https://crafatar.com/avatars/{uuid}?size={size}&overlay works too.",
        )
        public val proxy: ConfigKey<Boolean> = boolean(
            "proxy", false,
            "Fetch heads through xStats and keep them, so visitors' browsers never contact the",
            "service above. Heads are kept in memory for a day.",
        )
    }

    public class Branding(parent: ConfigGroup) : ConfigSection(parent, "branding", "How the site looks.") {
        public val siteName: ConfigKey<String> = string("name", "", "The site's title, such as your server's name. Blank shows \"Server stats\".")
        public val tagline: ConfigKey<String> = string("tagline", "", "A line under the title. Blank for none.")
        public val logo: ConfigKey<String> = string(
            "logo", "",
            "A logo: a link (https://...), or a PNG, JPEG, SVG or WebP file in plugins/xStats/web/,",
            "such as logo.png. Blank for none.",
        )
        public val accent: ConfigKey<String> = key("accent", ColourType, "#3ec407")
        public val background: ConfigKey<String> = key("background", ColourType, "#0f1419")
        public val surface: ConfigKey<String> = key("surface", ColourType, "#1a222b")
        public val text: ConfigKey<String> = key("text", ColourType, "#e6edf3")
    }

    /** `integrations:` 2.0's: the Xena plugins whose numbers xStats keeps as stats. */
    public class Integrations(parent: ConfigGroup) : ConfigSection(
        parent, "integrations",
        "Stats from the other Xena plugins, kept like xStats' own (on /stats, /leaderboard,",
        "placeholders and the website) once the plugin is installed. Each one can be turned off.",
    ) {
        public val xvote: ConfigKey<Boolean> = boolean("xvote", true, "xvote_votes: votes paid on this server.")
        public val xmoney: ConfigKey<Boolean> = boolean(
            "xmoney", true,
            "xmoney_balance (the balance, the latest any server saw) and xmoney_earned (money",
            "received: payments, rewards, sales).",
        )
        public val xbans: ConfigKey<Boolean> = boolean("xbans", true, "xbans_punishments: bans, mutes, kicks and warnings a player received.")
        public val xteleport: ConfigKey<Boolean> = boolean("xteleport", true, "xteleport_teleports: homes, warps, spawn and tpa.")
        public val xrewind: ConfigKey<Boolean> = boolean("xrewind", true, "xrewind_rollbacks: blocks a member of staff put back with rollbacks.")
        public val xmarket: ConfigKey<Boolean> = boolean("xmarket", true, "xmarket_bought and xmarket_sold: items bought from and sold to the market.")
        public val zchat: ConfigKey<Boolean> = boolean("zchat", true, "zchat_messages: chat messages sent.")
    }

    /** `proxy:` 2.0's: xStats on Velocity, which reads the network's stats from the servers' database. */
    public class Proxy(parent: ConfigGroup) : ConfigSection(
        parent, "proxy",
        "xStats on the proxy (Velocity): every server's stats, read from the database they share",
        "(storage, with network.enabled), for network leaderboards and player lookups wherever",
        "a player is. Read on the proxy only.",
    ) {
        public val stats: ProxyCommand = ProxyCommand(this, "stats", "networkstats", listOf("nstats"), "/networkstats [player]: a player's stats on the whole network.")
        public val leaderboard: ProxyCommand = ProxyCommand(
            this, "leaderboard", "networkleaderboard", listOf("nlb", "networklb"),
            "/networkleaderboard <stat> [period] [page]: a leaderboard of the whole network.",
        )
        public val refreshSeconds: ConfigKey<Int> = int(
            "refresh-seconds", 30,
            "Most often the proxy reads the network's stats again after a server saves. The",
            "servers save every collecting.save-every-minutes, so this rarely matters.",
            min = 5, max = 3600,
        )
    }

    public class ProxyCommand(parent: ConfigGroup, section: String, command: String, defaultAliases: List<String>, comment: String) : ConfigSection(parent, section, comment) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
        public val command: ConfigKey<String> = key("name", CommandTypes.ALIAS, command, "The command's name on the proxy.")
        public val aliases: ConfigKey<List<String>> = list("aliases", CommandTypes.ALIAS, defaultAliases)
        public val permission: ConfigKey<String> = key("permission", CommandTypes.PERMISSION, "")
    }

    /** `network:` 1.x's, with `check-every` in seconds as 1.x wrote it. */
    public class Network(parent: ConfigGroup) : ConfigSection(
        parent, "network",
        "For servers sharing one MySQL database: each server hears about the others' saves",
        "and /stats private, and its leaderboards count the whole network. The proxy needs it.",
        "Applies on restart.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean(
            "enabled", false,
            "On when several servers share this plugin's MySQL or MariaDB database. Leave it off",
            "for a single server: it does nothing on SQLite.",
        )
        public val checkEvery: ConfigKey<Int> = int("check-every", 3, "Seconds between checks for the other servers' changes.", min = 1, max = 60)

        public fun read(config: Config): NetworkSettings = NetworkSettings(config[enabled], config[checkEvery].seconds)
    }

    /** The days of the week, as `periods.week-starts` names them. */
    public enum class WeekDay { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

    /** A stat's `unit`. */
    public enum class UnitSetting { AUTO, COUNT, TIME, DISTANCE, DAMAGE }
}

/** A command's settings: whether it's on, its aliases and its permission, at 1.x's paths (`commands.<name>.*`). */
public class CommandSettings(parent: ConfigGroup, name: String, defaultAliases: List<String>, vararg comment: String) : ConfigSection(parent, name, *comment) {
    /** The command's name, which can't change. */
    public val command: String = name

    public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
    public val aliases: ConfigKey<List<String>> = list("aliases", CommandTypes.ALIAS, defaultAliases)
    public val permission: ConfigKey<String> = key("permission", CommandTypes.PERMISSION, "")
}

/** One stat in `stats:`, as written. */
public class StatSettings(
    public val name: String,
    public val source: String,
    public val unit: XStatsConfig.UnitSetting,
    public val decimals: Int,
    public val periods: Boolean,
    public val inGame: Boolean,
    public val website: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is StatSettings && other.name == name && other.source == source && other.unit == unit &&
        other.decimals == decimals && other.periods == periods && other.inGame == inGame && other.website == website

    override fun hashCode(): Int = (name.hashCode() * 31 + source.hashCode()) * 31 + unit.hashCode()

    override fun toString(): String = "StatSettings($name, $source)"
}

// 1.x's fourteen stats. A function, not a companion object, which would be a class of its own.
private fun defaultStats(): Map<String, StatSettings> {
    val distance = listOf(
        "walk", "sprint", "crouch", "swim", "walk_on_water", "walk_under_water", "climb", "fly", "aviate", "horse",
        "boat", "minecart", "pig", "strider", "happy_ghast", "nautilus",
    ).joinToString(" + ") { it + "_one_cm" }
    fun stat(name: String, source: String, periods: Boolean) = StatSettings(name, source, XStatsConfig.UnitSetting.AUTO, 0, periods, true, true)
    return linkedMapOf(
        "playtime" to stat("Playtime", "xstats:playtime", true),
        "sessions" to stat("Sessions", "xstats:sessions", false),
        "kills" to stat("Player kills", "player_kills", true),
        "deaths" to stat("Deaths", "deaths", true),
        "mob_kills" to stat("Mobs killed", "mob_kills", true),
        "blocks_mined" to stat("Blocks mined", "mined:*", true),
        "distance" to stat("Distance travelled", distance, true),
        "fish_caught" to stat("Fish caught", "fish_caught", true),
        "jumps" to stat("Jumps", "jump", false),
        "damage_dealt" to stat("Damage dealt", "damage_dealt", false),
        "animals_bred" to stat("Animals bred", "animals_bred", false),
        "villager_trades" to stat("Villager trades", "traded_with_villager", false),
        "items_enchanted" to stat("Items enchanted", "enchant_item", false),
        "raids_won" to stat("Raids won", "raid_win", false),
    )
}

// stats: each entry a section, a missing setting its default (1.x's template), one that isn't a
// section skipped. An object, so one class.
private object StatsType : ConfigType<Map<String, StatSettings>>() {
    override val name: String get() = "stats"

    override fun empty(): Map<String, StatSettings> = emptyMap()

    override fun decode(node: YamlNode, context: ConfigContext): Outcome<Map<String, StatSettings>, String> {
        if (node is YamlNode.Null) return Outcome.Ok(emptyMap())
        if (node !is YamlNode.Mapping) return Outcome.Err("should be a section of stats by id")
        val out = LinkedHashMap<String, StatSettings>()
        for (entry in node.entries) {
            val section = entry.value as? YamlNode.Mapping ?: continue
            val unit = text(section, "unit")?.trim()?.lowercase()?.let { u -> XStatsConfig.UnitSetting.entries.firstOrNull { it.name.lowercase() == u } }
            out[entry.key] = StatSettings(
                text(section, "name") ?: "", text(section, "source") ?: "", unit ?: XStatsConfig.UnitSetting.AUTO,
                (text(section, "decimals")?.trim()?.toIntOrNull() ?: 0).coerceIn(0, 4),
                flag(section, "periods", false), flag(section, "in-game", true), flag(section, "website", true),
            )
        }
        return Outcome.Ok(out)
    }

    override fun encode(value: Map<String, StatSettings>): YamlNode = YamlNode.Mapping(
        value.map { (id, stat) ->
            YamlNode.Mapping.Entry(
                id,
                YamlNode.Mapping(
                    listOf(
                        YamlNode.Mapping.Entry("name", YamlNode.Scalar(stat.name)),
                        YamlNode.Mapping.Entry("source", YamlNode.Scalar(stat.source)),
                        YamlNode.Mapping.Entry("unit", YamlNode.Scalar(stat.unit.name.lowercase())),
                        YamlNode.Mapping.Entry("decimals", YamlNode.Scalar(stat.decimals.toString())),
                        YamlNode.Mapping.Entry("periods", YamlNode.Scalar(stat.periods.toString())),
                        YamlNode.Mapping.Entry("in-game", YamlNode.Scalar(stat.inGame.toString())),
                        YamlNode.Mapping.Entry("website", YamlNode.Scalar(stat.website.toString())),
                    ),
                ),
            )
        },
    )

    private fun text(section: YamlNode.Mapping, key: String): String? = (section[key] as? YamlNode.Scalar)?.content

    private fun flag(section: YamlNode.Mapping, key: String, default: Boolean): Boolean = when (text(section, key)?.trim()?.lowercase()) {
        "true", "yes", "on" -> true
        "false", "no", "off" -> false
        else -> default
    }
}

// A website colour, #RRGGBB and nothing else, so a colour can't carry anything into the site's CSS.
private object ColourType : ConfigType<String>() {
    private val HEX = Regex("#[0-9a-fA-F]{6}")

    override val name: String get() = "colour"

    override fun decode(node: YamlNode, context: ConfigContext): Outcome<String, String> {
        val text = (node as? YamlNode.Scalar)?.content?.trim() ?: return Outcome.Err("should be a colour written as #RRGGBB")
        return if (HEX.matches(text)) Outcome.Ok(text.lowercase()) else Outcome.Err("isn't a colour written as #RRGGBB, such as #3EC407")
    }

    override fun encode(value: String): YamlNode = YamlNode.Scalar(value)
}
