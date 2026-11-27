package co.xenastudios.xvote.config

import co.xenastudios.core.common.Outcome
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
 * xVote's `config.yml`, version 2 (xVote 2.0). Every setting 1.x had keeps its path and its
 * meaning, so a 1.x config carries forward with only its messages moved (to `lang/`), its theme
 * into `messages.theme` and `update-checker` into `updates`; the 1.x jar put back reads the rest as
 * it was. A reward's own message and broadcast stay here: they're part of the reward the owner
 * wrote, not one of xVote's messages. On a proxy the same file is read; what only a server does
 * (rewards, the party, reminders) does nothing there.
 */
public object XVoteConfig : ConfigSchema(
    2,
    "xVote: votes in, rewards out.",
    "Docs: https://plugins.xenastudios.co/xvote/configuration/",
    "",
    "Apply changes with /xvote reload. A bad value falls back to its default with a",
    "warning in the console; xVote never stops over config. The messages are in lang/.",
) {
    /** The look and the language: the family's (`plugins/Xena/config.yml`) unless set here. */
    public val messages: MessagesSection = MessagesSection(this)

    public val receiver: Receiver = Receiver(this)

    public val sites: ConfigKey<Map<String, Site>> = key(
        "sites", MapType.SITES, defaultSites(),
        "The sites players vote on, shown by /vote. Each is matched to votes by its service:",
        "the name the list sends, which /xvote status shows for the last votes. name is",
        "what players see. A vote sooner than cooldown-hours after the last one from the same",
        "site is a repeat and is dropped; set it to how often the site lets players vote.",
        "rewards are given for this site only, on top of every-vote's. Votes from a site",
        "that isn't listed still count, with counting.unlisted-cooldown-hours as cooldown.",
    )

    public val rewards: ConfigKey<Map<String, Reward>> = key(
        "rewards", MapType.REWARDS, defaultRewards(),
        "Rewards, by name, to use in triggers, sites and the party. Each can give money",
        "(through xMoney, or Vault's economy on Bukkit), items, console commands, a message, a",
        "broadcast and a sound, with a chance in percent. Items are written like /give: diamond 3,",
        "or diamond_sword[enchantments={sharpness:3}] 1 (components on Paper 1.20.5+ and Fabric).",
        "Items that don't fit wait for /vote claim (on Fabric they drop at the player's feet).",
        "Commands, messages and broadcasts can use <player>, <uuid>, <site>, <total> and <streak>;",
        "in commands, <site> is a listed site's key (planetminecraft), and one that isn't listed",
        "keeps only letters, digits, . _ and -. custom lists reward types other plugins add, as",
        "\"<type> <argument>\".",
    )

    public val triggers: Triggers = Triggers(this)
    public val party: Party = Party(this)
    public val reminders: Reminders = Reminders(this)
    public val counting: Counting = Counting(this)

    /** Where votes are kept: 1.x's `storage`. */
    public val storage: StorageSection = StorageSection(this, "xvote_")

    public val network: Network = Network(this)

    /** The update checker: `updates.check` was 1.x's `update-checker.enabled`. */
    public val updates: UpdatesSection = UpdatesSection(this)

    init {
        // 2.0: messages go to lang/ (Legacy carries the edited ones there), the theme into messages.
        migrate(1, co.xenastudios.xvote.Startup.configOne)
    }

    public class Receiver(parent: ConfigGroup) : ConfigSection(
        parent, "receiver",
        "The Votifier receiver: where server lists send votes. It speaks Votifier v1 and v2,",
        "like NuVotifier, and uses the keys in rsa/ and the tokens in tokens.yml. Don't run",
        "NuVotifier as well: both would want the same port. On a network, turn it on on the",
        "proxy and off on the servers: the proxy receives every vote and the player's server",
        "pays it. Changes apply on /xvote reload.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true, "Off stops listening; votes from /vote test and other plugins still count.")
        public val host: ConfigKey<String> = string("host", "0.0.0.0", "The address to listen on. 0.0.0.0 is every address the machine has.")
        public val port: ConfigKey<Int> = int(
            "port", 8192,
            "The port lists send votes to. It must be open to the internet, and isn't the",
            "server's own port. On a host that gives you one port, see the docs.",
            min = 1, max = 65535,
        )
        public val v1: ConfigKey<Boolean> = boolean("v1", true, "Accept Votifier v1 votes. Most lists still send v1; turn it off only if all yours send v2.")
        public val maxConnections: ConfigKey<Int> = int("max-connections", 256, "Most votes handled at once. More are turned away until one finishes.", min = 1, max = 10_000)
        public val perIpConnections: ConfigKey<Int> = int(
            "per-ip-connections", 4,
            "Most connections open at once from one address, so a few can't take every slot.",
            "0 is no limit. Behind a tunnel (playit.gg, TCPShield) every list arrives from the",
            "tunnel's address: raise this and per-ip-per-minute, or set both to 0.",
            min = 0, max = 10_000,
        )
        public val timeoutSeconds: ConfigKey<Int> = int("timeout-seconds", 10, "Seconds a list has to send its vote before the connection is closed.", min = 1, max = 60)
        public val perIpPerMinute: ConfigKey<Int> = int(
            "per-ip-per-minute", 60, "Most connections one address may make a minute. A list makes one per vote. 0 is no limit.",
            min = 0, max = 100_000,
        )
        public val nuvotifierChannel: ConfigKey<String> = string(
            "nuvotifier-channel", "",
            "On a server behind a proxy that runs NuVotifier instead of xVote: the plugin channel",
            "NuVotifier forwards votes on with its pluginMessaging method (nuvotifier:votes), or",
            "blank for none. A player's client can send on any channel, so only set it when the",
            "proxy drops players' messages on it; NuVotifier's proxy method (a v2 vote to this",
            "receiver with a token) needs nothing here and can't be forged. Bukkit only.",
        )
    }

    public class Triggers(parent: ConfigGroup) : ConfigSection(
        parent, "triggers",
        "Which rewards each vote gives, besides its site's. every-vote: on every vote.",
        "first-vote-of-day: a player's first vote each day. every: on every Nth vote, by N.",
        "streak: on reaching that many days in a row. total: once, on reaching that many votes.",
    ) {
        public val everyVote: ConfigKey<List<String>> = stringList("every-vote", listOf("vote"))
        public val firstVoteOfDay: ConfigKey<List<String>> = stringList("first-vote-of-day", emptyList())
        public val every: ConfigKey<Map<String, List<String>>> = key("every", MapType.MILESTONES, emptyMap())
        public val streak: ConfigKey<Map<String, List<String>>> = key("streak", MapType.MILESTONES, linkedMapOf("7" to listOf("streak")))
        public val total: ConfigKey<Map<String, List<String>>> = key("total", MapType.MILESTONES, emptyMap())
    }

    public class Party(parent: ConfigGroup) : ConfigSection(
        parent, "party",
        "Every so many votes from anyone, a reward for everyone online (who: online), or for",
        "everyone online who voted since the last party (who: voted). On a network the count is",
        "the network's, and every server rewards its own players. The broadcasts are in lang/.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", false)
        public val votes: ConfigKey<Int> = int("votes", 50, "Votes from anyone a party needs.", min = 1, max = 1_000_000)
        public val who: ConfigKey<Who> = enum("who", Who.ONLINE, listOf(Who.ONLINE, Who.VOTED), "online or voted.")
        public val rewards: ConfigKey<List<String>> = stringList("rewards", listOf("party"))
        public val progressEvery: ConfigKey<Int> = int(
            "progress-every", 10, "Broadcast party.progress every this many votes on the way. 0 is never.", min = 0, max = 1_000_000,
        )
    }

    public enum class Who { ONLINE, VOTED }

    public class Reminders(parent: ConfigGroup) : ConfigSection(parent, "reminders", "Telling players they can vote. The words are reminders.message in lang/.") {
        public val onJoin: ConfigKey<Boolean> = boolean("on-join", true, "Remind players who can vote when they join.")
        public val joinDelaySeconds: ConfigKey<Int> = int("join-delay-seconds", 5, "Seconds after joining.", min = 0, max = 600)
        public val everyMinutes: ConfigKey<Int> = int(
            "every-minutes", 0,
            "Also remind everyone online who can vote, every this many minutes. 0 is off, and",
            "then nothing repeats.",
            min = 0, max = 1440,
        )
    }

    public class Counting(parent: ConfigGroup) : ConfigSection(parent, "counting", "How votes are counted.") {
        public val timeZone: ConfigKey<String> = string(
            "time-zone", "server",
            "Days, weeks and months start at midnight here: an id like Europe/London or",
            "America/New_York, or server for the server's own. On a network, give every server",
            "and the proxy the same one.",
        )
        public val weekStarts: ConfigKey<WeekDay> = enum("week-starts", WeekDay.MONDAY, WeekDay.entries, "The day weeks start on.")
        public val bedrockPrefix: ConfigKey<String> = string(
            "bedrock-prefix", ".",
            "Floodgate's prefix for Bedrock players' names. A vote for a name nobody has is",
            "tried with it, so Bedrock players who leave it off are still paid.",
        )
        public val unknownNameDays: ConfigKey<Int> = int(
            "unknown-name-days", 30,
            "Days to keep votes for names that have never joined. They're paid if that name",
            "joins in time. 0 keeps them forever.",
            min = 0, max = 3650,
        )
        public val unlistedCooldownHours: ConfigKey<Int> = int(
            "unlisted-cooldown-hours", 24,
            "The cooldown for sites that aren't in sites: a second vote from one sooner is a",
            "repeat and is dropped, as a listed site's are. 0 drops only exact replays.",
            min = 0, max = 720,
        )
    }

    /** The days of the week, as `counting.week-starts` names them. */
    public enum class WeekDay { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

    /** `network:` 1.x's, with `check-every` in seconds as 1.x wrote it. */
    public class Network(parent: ConfigGroup) : ConfigSection(
        parent, "network",
        "For networks: every server (and the proxy, if xVote is on it) shares one MySQL or",
        "MariaDB database (storage), so a vote that arrives anywhere is paid once, on the server",
        "the player is on, or the first one they join. Applies on restart.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean(
            "enabled", false,
            "On when several servers share this plugin's MySQL or MariaDB database, so a change",
            "made on one (a vote, a party) reaches the others within a few seconds.",
            "Leave it off for a single server: it does nothing on SQLite.",
        )
        public val checkEvery: ConfigKey<Int> = int("check-every", 3, "Seconds between checks for the other servers' changes.", min = 1, max = 60)

        public fun read(config: Config): NetworkSettings = NetworkSettings(config[enabled], config[checkEvery].seconds)
    }
}

/** A site players vote on: `sites.<key>`. */
public class Site(
    public val name: String,
    /** The service name lists send. */
    public val service: String,
    public val link: String,
    public val cooldownHours: Int,
    /** Rewards for this site only, by name. */
    public val rewards: List<String>,
) {
    override fun equals(other: Any?): Boolean = other is Site && other.name == name && other.service == service &&
        other.link == link && other.cooldownHours == cooldownHours && other.rewards == rewards

    override fun hashCode(): Int = (name.hashCode() * 31 + service.hashCode()) * 31 + link.hashCode()

    override fun toString(): String = "Site($name, $service)"
}

/** A reward: `rewards.<name>`. Its message and broadcast are markup, drawn with the theme. */
public class Reward(
    public val chance: Double,
    public val money: Double,
    public val items: List<String>,
    public val commands: List<String>,
    public val message: String,
    public val broadcast: String,
    public val sound: String,
    public val custom: List<String>,
) {
    override fun equals(other: Any?): Boolean = other is Reward && other.chance == chance && other.money == money &&
        other.items == items && other.commands == commands && other.message == message && other.broadcast == broadcast &&
        other.sound == sound && other.custom == custom

    override fun hashCode(): Int = ((items.hashCode() * 31 + commands.hashCode()) * 31 + message.hashCode()) * 31 + money.hashCode()

    override fun toString(): String = "Reward($items, $commands)"
}

// 1.x's sites and rewards. Functions, not a companion object, which would be a class of its own.
private fun defaultSites(): Map<String, Site> = linkedMapOf(
    "planetminecraft" to Site("Planet Minecraft", "PlanetMinecraft.com", "https://www.planetminecraft.com/server/your-server/vote/", 24, emptyList()),
    "minecraft-mp" to Site("Minecraft-MP", "Minecraft-MP.com", "https://minecraft-mp.com/server/00000/vote/", 24, emptyList()),
)

private fun defaultRewards(): Map<String, Reward> = linkedMapOf(
    "vote" to Reward(
        100.0, 0.0, listOf("diamond 1"), emptyList(),
        "<prefix><success>Thanks for voting</success> on <highlight><site></highlight>! Here's a diamond.",
        "<prefix><highlight><player></highlight> voted on <site>. <click:run_command:'/vote'><highlight>/vote</highlight></click> to vote too.",
        "entity.player.levelup", emptyList(),
    ),
    "streak" to Reward(
        100.0, 0.0, listOf("diamond 5"), emptyList(),
        "<prefix>A <highlight><streak>-day</highlight> voting streak! Here are 5 more diamonds.", "", "", emptyList(),
    ),
    "party" to Reward(
        100.0, 0.0, listOf("golden_apple 1"), emptyList(),
        "<prefix><success>Vote party!</success> Here's a golden apple.", "", "entity.firework_rocket.blast", emptyList(),
    ),
)

// The three maps' type as one class, told apart by [kind], rather than an object each: each entry
// a section (a milestone's `rewards:` list), a missing setting its default (1.x's templates), one
// it can't read skipped.
private class MapType<V : Any>(private val kind: Int) : ConfigType<Map<String, V>>() {
    override val name: String get() = when (kind) {
        0 -> "sites"
        1 -> "rewards"
        else -> "milestones"
    }

    override fun empty(): Map<String, V> = emptyMap()

    @Suppress("UNCHECKED_CAST")
    override fun decode(node: YamlNode, context: ConfigContext): Outcome<Map<String, V>, String> {
        if (node is YamlNode.Null) return Outcome.Ok(emptyMap())
        if (node !is YamlNode.Mapping) return Outcome.Err("should be a section of $name by name")
        val out = LinkedHashMap<String, V>()
        for (entry in node.entries) {
            val section = entry.value as? YamlNode.Mapping ?: continue
            val read: Any = when (kind) {
                0 -> Site(
                    text(section, "name") ?: "", text(section, "service") ?: "", text(section, "link") ?: "",
                    (text(section, "cooldown-hours")?.trim()?.toIntOrNull() ?: 24).coerceIn(0, 720), strings(section["rewards"]),
                )
                1 -> Reward(
                    (text(section, "chance")?.trim()?.toDoubleOrNull() ?: 100.0).coerceIn(0.0, 100.0),
                    (text(section, "money")?.trim()?.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0),
                    strings(section["items"]), strings(section["commands"]),
                    text(section, "message") ?: "", text(section, "broadcast") ?: "", text(section, "sound") ?: "",
                    strings(section["custom"]),
                )
                else -> strings(section["rewards"])
            }
            out[entry.key] = read as V
        }
        return Outcome.Ok(out)
    }

    override fun encode(value: Map<String, V>): YamlNode = YamlNode.Mapping(
        value.map { (key, item) ->
            val fields: List<Pair<String, YamlNode>> = when (item) {
                is Site -> listOf(
                    "name" to YamlNode.Scalar(item.name),
                    "service" to YamlNode.Scalar(item.service),
                    "link" to YamlNode.Scalar(item.link),
                    "cooldown-hours" to YamlNode.Scalar(item.cooldownHours.toString()),
                    "rewards" to YamlNode.Sequence(item.rewards.map { YamlNode.Scalar(it) }),
                )
                is Reward -> listOf(
                    "chance" to YamlNode.Scalar(number(item.chance)),
                    "money" to YamlNode.Scalar(number(item.money)),
                    "items" to YamlNode.Sequence(item.items.map { YamlNode.Scalar(it) }),
                    "commands" to YamlNode.Sequence(item.commands.map { YamlNode.Scalar(it) }),
                    "message" to YamlNode.Scalar(item.message),
                    "broadcast" to YamlNode.Scalar(item.broadcast),
                    "sound" to YamlNode.Scalar(item.sound),
                    "custom" to YamlNode.Sequence(item.custom.map { YamlNode.Scalar(it) }),
                )
                else -> listOf("rewards" to YamlNode.Sequence((item as List<*>).map { YamlNode.Scalar(it.toString()) }))
            }
            YamlNode.Mapping.Entry(key, YamlNode.Mapping(fields.map { YamlNode.Mapping.Entry(it.first, it.second) }))
        },
    )

    // 100 and 0, as 1.x wrote them, not 100.0.
    private fun number(value: Double): String = if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

    private fun strings(node: YamlNode?): List<String> = when (node) {
        is YamlNode.Sequence -> node.items.mapNotNull { (it as? YamlNode.Scalar)?.content }
        is YamlNode.Scalar -> if (node.content.isBlank()) emptyList() else listOf(node.content)
        else -> emptyList()
    }

    private fun text(section: YamlNode.Mapping, key: String): String? = (section[key] as? YamlNode.Scalar)?.content

    companion object {
        val SITES: ConfigType<Map<String, Site>> = MapType(0)
        val REWARDS: ConfigType<Map<String, Reward>> = MapType(1)
        val MILESTONES: ConfigType<Map<String, List<String>>> = MapType(2)
    }
}
