package co.xenastudios.xteleport.config

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
import co.xenastudios.core.common.family.MessagesSection
import co.xenastudios.core.common.family.StorageSection
import co.xenastudios.core.common.update.UpdatesSection

/**
 * xTeleport's `config.yml`, version 2 (xTeleport 2.0). Every setting 1.x had keeps its path and
 * its meaning, so a 1.x config carries forward with nothing but its messages moved (to `lang/`),
 * its theme into `messages.theme` and `update-checker` into `updates`; the 1.x jar put back
 * reads the rest as it was.
 */
public object XTeleportConfig : ConfigSchema(
    2,
    "xTeleport: every teleport your server needs.",
    "Docs: https://plugins.xenastudios.co/xteleport/configuration/",
    "",
    "Apply changes with /xteleport reload (or /xtp reload). Everything applies except",
    "storage, even turning commands on or off. A bad value falls back to its default with",
    "a warning in the console; xTeleport never stops over config. The messages are in lang/.",
) {
    /** The look and the language: the family's (`plugins/Xena/config.yml`) unless set here. */
    public val messages: MessagesSection = MessagesSection(this)

    public val commands: Commands = Commands(this)
    public val teleports: Teleports = Teleports(this)
    public val homes: Homes = Homes(this)
    public val tpa: Tpa = Tpa(this)
    public val warps: Warps = Warps(this)
    public val spawn: Spawn = Spawn(this)
    public val rtp: Rtp = Rtp(this)

    /** Where homes and warps are kept: 1.x's `storage`, read at startup. */
    public val storage: StorageSection = StorageSection(this, "xteleport_")

    /** The update checker: `updates.check` was 1.x's `update-checker.enabled`. */
    public val updates: UpdatesSection = UpdatesSection(this)

    init {
        // 2.0: messages go to lang/ (Legacy carries the edited ones there), the theme into messages.
        migrate(1) { Legacy.toTwo(this) }
    }

    /** `commands:` each command's settings, at 1.x's paths. */
    public class Commands(parent: ConfigGroup) : ConfigSection(
        parent, "commands",
        "Every command has the same settings:",
        "  enabled            false removes the command entirely, so another plugin can have its name",
        "  aliases            other names for it",
        "  permission         needed to use it; blank (\"\") lets anyone",
        "  permission-others  needed to use it on another player's homes",
        "Commands that teleport also have:",
        "  cooldown           seconds before it can be used again; xteleport.cooldown.bypass skips it",
        "  cost               what each teleport costs, through xMoney (or Vault's economy);",
        "                     xteleport.cost.bypass skips it. Taken once the warmup is over, and",
        "                     given back if the teleport fails.",
    ) {
        public val sethome: CommandSettings = CommandSettings(this, "sethome", "/sethome [name]: sets a home where you stand. Setting one again moves it.")
        public val home: TravelSettings = TravelSettings(this, "home", others = true, comment = arrayOf("/home [name]: with one home, or one called home, the name can be left out."))
        public val delhome: CommandSettings = CommandSettings(this, "delhome", "/delhome <name>", others = true)
        public val homes: CommandSettings = CommandSettings(this, "homes", "/homes [page]: a list to click. Staff with permission-others: /homes <player>.", others = true)
        public val tpa: TravelSettings = TravelSettings(this, "tpa", comment = arrayOf("/tpa <player>: ask to teleport to someone. The cost is paid by whoever asked."))
        public val tpahere: TravelSettings = TravelSettings(this, "tpahere", comment = arrayOf("/tpahere <player>: ask someone to teleport to you."))
        public val tpaccept: CommandSettings = CommandSettings(this, "tpaccept", "/tpaccept [player]: the newest request, or the one from that player.", aliases = listOf("tpyes"))
        public val tpdeny: CommandSettings = CommandSettings(this, "tpdeny", aliases = listOf("tpno"))
        public val tpacancel: CommandSettings = CommandSettings(this, "tpacancel", "/tpacancel [player]: takes back requests you sent.")
        public val tptoggle: CommandSettings = CommandSettings(this, "tptoggle", "/tptoggle: stop or start getting requests.")
        public val setwarp: CommandSettings = CommandSettings(this, "setwarp")
        public val warp: TravelSettings = TravelSettings(this, "warp", comment = arrayOf("/warp [name]: without a name, lists the warps."))
        public val delwarp: CommandSettings = CommandSettings(this, "delwarp")
        public val warps: CommandSettings = CommandSettings(this, "warps")
        public val setspawn: CommandSettings = CommandSettings(this, "setspawn")
        public val spawn: TravelSettings = TravelSettings(this, "spawn")
        public val rtp: TravelSettings = TravelSettings(this, "rtp", aliases = listOf("wild"), cooldown = 300, comment = arrayOf("/rtp [world]"))
    }

    /** `teleports:` */
    public class Teleports(parent: ConfigGroup) : ConfigSection(parent, "teleports", "How every teleport behaves.") {
        public val warmup: ConfigKey<Int> = int("warmup", 3, "Seconds to stand still before teleporting. 0 teleports at once. xteleport.warmup.bypass skips it.", min = 0, max = 60)
        public val cancelOnDamage: ConfigKey<Boolean> = boolean("cancel-on-damage", true, "Whether taking damage during the warmup cancels it, as moving does.")
        public val checkSafe: ConfigKey<Boolean> = boolean(
            "check-safe", true,
            "Refuse to send players somewhere that is now in lava, inside blocks, over the void,",
            "high in the air or outside the world border, with a message instead of a death:",
            "homes, warps, the spawn, and the player a TPA goes to. The spot's chunk is loaded",
            "in the background to check, never on the server's thread where the server can.",
        )
        public val maxFall: ConfigKey<Int> = int(
            "max-fall", 3,
            "With check-safe, the most blocks a player may fall on arriving. Falls of 3 or less",
            "don't hurt. Water below is safe from any height.",
            min = 0, max = 4096,
        )
    }

    /** `homes:` */
    public class Homes(parent: ConfigGroup) : ConfigSection(parent, "homes") {
        public val defaultLimit: ConfigKey<Int> = int(
            "default-limit", 3,
            "How many homes a player may have. A player with xteleport.homes.<n> may have <n>",
            "instead, and xteleport.homes.unlimited any number; the highest wins.",
            min = 0, max = 10_000,
        )
        public val limits: ConfigKey<Map<String, HomeLimit>> = key(
            "limits", HomeLimit.TYPE, linkedMapOf("vip" to HomeLimit("", 5)),
            "More limits, by permission. A blank permission means group.<name>, which LuckPerms",
            "gives every member of that group, so vip: below works for a LuckPerms group called vip.",
        )
        public val defaultName: ConfigKey<String> = key(
            "default-name", NAME, "home", "The home /sethome sets, and /home goes to, when no name is given.",
        )
        public val bedHome: ConfigKey<Boolean> = boolean(
            "bed-home", true,
            "Let players go to their bed or respawn anchor with /home <bed-name>. It doesn't count",
            "towards their limit, and shows first in /homes.",
        )
        public val bedName: ConfigKey<String> = key("bed-name", NAME, "bed")
        public val perPage: ConfigKey<Int> = int("per-page", 10, "Homes per page in /homes.", min = 1, max = 50)
    }

    /** `tpa:` */
    public class Tpa(parent: ConfigGroup) : ConfigSection(parent, "tpa") {
        public val expireAfter: ConfigKey<Int> = int("expire-after", 60, "Seconds before a request runs out.", min = 5, max = 3600)
    }

    /** `warps:` */
    public class Warps(parent: ConfigGroup) : ConfigSection(parent, "warps") {
        public val perWarpPermission: ConfigKey<Boolean> = boolean(
            "per-warp-permission", false,
            "Each warp needs xteleport.warp.<name> (operators have them all). /warps only lists",
            "the ones a player may use.",
        )
        public val perPage: ConfigKey<Int> = int("per-page", 10, "Warps per page in /warps.", min = 1, max = 50)
    }

    /** `spawn:` */
    public class Spawn(parent: ConfigGroup) : ConfigSection(parent, "spawn") {
        public val onFirstJoin: ConfigKey<Boolean> = boolean("on-first-join", true, "Send players who join for the first time to the spawn set with /setspawn.")
        public val onEveryJoin: ConfigKey<Boolean> = boolean("on-every-join", false, "Send every player there each time they join.")
        public val onRespawn: ConfigKey<Boolean> = boolean("on-respawn", true, "Send players there when they die without a bed or respawn anchor.")
    }

    /** `rtp:` */
    public class Rtp(parent: ConfigGroup) : ConfigSection(parent, "rtp", "/rtp: a random safe spot.") {
        public val defaultWorld: ConfigKey<String> = string(
            "default-world", "",
            "Where /rtp sends players who don't name a world. Blank means the world they're in, if",
            "/rtp may send them there.",
        )
        public val attempts: ConfigKey<Int> = int("attempts", 10, "Spots tried before giving up. Each loads one chunk in the background.", min = 1, max = 50)
        public val searchCooldown: ConfigKey<Int> = int(
            "search-cooldown", 30,
            "Seconds before a player can start another search, whether it found a spot or not,",
            "so nobody can keep the server busy generating chunks. The cooldown under",
            "commands.rtp is the longer wait, after arriving. xteleport.cooldown.bypass skips both.",
            min = 0, max = 86_400,
        )
        public val maxSearches: ConfigKey<Int> = int(
            "max-searches", 3,
            "How many /rtp searches may run at once, server-wide. Anyone over it is told to try",
            "again in a few seconds, so a crowd typing /rtp can't slow the server.",
            min = 1, max = 32,
        )
        public val onlyGenerated: ConfigKey<Boolean> = boolean(
            "only-generated", false,
            "Only pick chunks that already exist, so /rtp never makes the server generate terrain.",
            "Best for worlds pre-generated with a plugin such as Chunky.",
        )
        public val blockedBiomes: ConfigKey<List<String>> = stringList(
            "blocked-biomes",
            listOf("ocean", "deep_ocean", "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean", "cold_ocean", "deep_cold_ocean", "frozen_ocean", "deep_frozen_ocean"),
            "Biomes /rtp never picks, as Minecraft names them.",
        )
        public val worlds: ConfigKey<Map<String, RtpWorld>> = key(
            "worlds", RtpWorld.TYPE, RtpWorld.DEFAULTS,
            "The worlds /rtp may send players to, by name, and where. A world that isn't listed",
            "can't be picked. The Nether is searched below its roof, and the End off its main",
            "island unless allow-main-island is true. Each world has:",
            "  enabled            false keeps /rtp out of this world",
            "  permission         needed to /rtp into this world; blank lets anyone",
            "  center-on-spawn    measure from the world's spawn point, or from center-x and center-z",
            "  min-radius         the nearest and farthest a spot may be from the centre, in blocks;",
            "  max-radius         the world border also limits it",
            "  allow-main-island  the End only: allow spots on the main island, where the dragon is",
            "On Fabric, a world's name is its dimension: minecraft:overworld, minecraft:the_nether,",
            "minecraft:the_end; world, world_nether and world_the_end mean those there too.",
        )
    }
}

/** A home's name in the config: 1 to 32 letters, digits, - or _. A file-level value, made before the schema's sections that use it. */
private val NAME: ConfigType<String> = ConfigType.scalar("name", { it }) { raw ->
    val name = raw.trim()
    if (co.xenastudios.xteleport.api.Names.valid(name)) Outcome.Ok(name) else Outcome.Err("should be 1 to 32 letters, digits, - or _")
}

/** A command's settings: whether it's on, its aliases and its permission, at 1.x's paths (`commands.<name>.*`). */
public open class CommandSettings(
    parent: ConfigGroup,
    name: String,
    vararg comment: String,
    aliases: List<String> = emptyList(),
    others: Boolean = false,
) : ConfigSection(parent, name, *comment) {
    /** The command's name, which can't change. */
    public val command: String = name

    public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
    public val aliases: ConfigKey<List<String>> = list("aliases", CommandTypes.ALIAS, aliases)
    public val permission: ConfigKey<String> = key("permission", CommandTypes.PERMISSION, "xteleport.command.$name")

    /** Needed to use it on another player's homes; `null` for a command without that form. */
    public val permissionOthers: ConfigKey<String>? = if (others) key("permission-others", CommandTypes.PERMISSION, "xteleport.homes.others") else null
}

/** A command that teleports: its settings, and its cooldown and cost. */
public class TravelSettings(
    parent: ConfigGroup,
    name: String,
    aliases: List<String> = emptyList(),
    others: Boolean = false,
    cooldown: Int = 0,
    comment: Array<String> = emptyArray(),
) : CommandSettings(parent, name, *comment, aliases = aliases, others = others) {
    public val cooldown: ConfigKey<Int> = int("cooldown", cooldown, min = 0, max = 86_400)
    public val cost: ConfigKey<Double> = double("cost", 0.0, min = 0.0)

    /** [cooldown] and [cost] as [config] has them. */
    public fun travel(config: Config): Travel = Travel(command, config[this.cooldown], config[cost])
}

/** A teleporting command's cooldown (its key is the command's name) and cost, read. */
public class Travel(public val key: String, public val cooldown: Int, public val cost: Double)

/** A home limit for players with [permission]; blank means `group.<name>`, which LuckPerms gives a group's members. */
public class HomeLimit(public val permission: String, public val homes: Int) {
    override fun equals(other: Any?): Boolean = other is HomeLimit && other.permission == permission && other.homes == homes
    override fun hashCode(): Int = permission.hashCode() * 31 + homes
    override fun toString(): String = "HomeLimit($permission, $homes)"

    internal companion object {
        /** `homes.limits`: a section of limits by name; one it can't read is skipped, the rest kept. */
        val TYPE: ConfigType<Map<String, HomeLimit>> = MapType(0)
    }
}

/** Where `/rtp` may send players in one world. */
public class RtpWorld(
    public val enabled: Boolean = true,
    public val permission: String = "",
    public val centerOnSpawn: Boolean = true,
    public val centerX: Int = 0,
    public val centerZ: Int = 0,
    public val minRadius: Int = 300,
    public val maxRadius: Int = 5000,
    public val allowMainIsland: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is RtpWorld && other.enabled == enabled && other.permission == permission &&
        other.centerOnSpawn == centerOnSpawn && other.centerX == centerX && other.centerZ == centerZ &&
        other.minRadius == minRadius && other.maxRadius == maxRadius && other.allowMainIsland == allowMainIsland

    override fun hashCode(): Int = ((((minRadius * 31 + maxRadius) * 31 + centerX) * 31 + centerZ) * 31 + permission.hashCode()) * 31 + enabled.hashCode()

    override fun toString(): String = "RtpWorld($enabled, $permission, $centerOnSpawn, $centerX, $centerZ, $minRadius..$maxRadius, $allowMainIsland)"

    internal companion object {
        val DEFAULTS: Map<String, RtpWorld> = linkedMapOf(
            "world" to RtpWorld(minRadius = 300, maxRadius = 5000),
            "world_nether" to RtpWorld(minRadius = 100, maxRadius = 1000),
            "world_the_end" to RtpWorld(centerOnSpawn = false, minRadius = 1100, maxRadius = 4000),
        )

        /** `rtp.worlds`: a section of worlds by name, each missing setting its default, as 1.x read them. */
        val TYPE: ConfigType<Map<String, RtpWorld>> = MapType(1)
    }
}

// The two maps' type as one class, told apart by [kind], rather than an object each.
private class MapType<V : Any>(private val kind: Int) : ConfigType<Map<String, V>>() {
    override val name: String get() = if (kind == 0) "home limits" else "rtp worlds"
    override fun empty(): Map<String, V> = emptyMap()

    @Suppress("UNCHECKED_CAST")
    override fun decode(node: YamlNode, context: ConfigContext): Outcome<Map<String, V>, String> {
        if (node is YamlNode.Null) return Outcome.Ok(emptyMap())
        if (node !is YamlNode.Mapping) return Outcome.Err("should be a section of ${if (kind == 0) "limits" else "worlds"} by name")
        val out = LinkedHashMap<String, V>()
        for (entry in node.entries) {
            val section = entry.value as? YamlNode.Mapping ?: continue
            val read = (if (kind == 0) limit(section) else world(section)) ?: continue
            out[entry.key] = read as V
        }
        return Outcome.Ok(out)
    }

    override fun encode(value: Map<String, V>): YamlNode = YamlNode.Mapping(
        value.map { (name, item) ->
            val fields = if (item is HomeLimit) {
                listOf("permission" to YamlNode.Scalar(item.permission), "homes" to YamlNode.Scalar(item.homes.toString()))
            } else {
                val w = item as RtpWorld
                listOf(
                    "enabled" to YamlNode.Scalar(w.enabled.toString()), "permission" to YamlNode.Scalar(w.permission),
                    "center-on-spawn" to YamlNode.Scalar(w.centerOnSpawn.toString()),
                    "center-x" to YamlNode.Scalar(w.centerX.toString()), "center-z" to YamlNode.Scalar(w.centerZ.toString()),
                    "min-radius" to YamlNode.Scalar(w.minRadius.toString()), "max-radius" to YamlNode.Scalar(w.maxRadius.toString()),
                    "allow-main-island" to YamlNode.Scalar(w.allowMainIsland.toString()),
                )
            }
            YamlNode.Mapping.Entry(name, YamlNode.Mapping(fields.map { YamlNode.Mapping.Entry(it.first, it.second) }))
        },
    )

    private fun limit(section: YamlNode.Mapping): HomeLimit? {
        val permission = text(section, "permission") ?: ""
        val homes = number(section, "homes") ?: 0
        if (homes !in 0..10_000) return null
        return HomeLimit(permission.trim().lowercase(), homes)
    }

    private fun world(section: YamlNode.Mapping): RtpWorld? {
        val d = RtpWorld()
        val min = number(section, "min-radius") ?: d.minRadius
        val max = number(section, "max-radius") ?: d.maxRadius
        if (min !in 0..30_000_000 || max !in 1..30_000_000 || min > max) return null
        return RtpWorld(
            flag(section, "enabled") ?: d.enabled, (text(section, "permission") ?: "").trim().lowercase(),
            flag(section, "center-on-spawn") ?: d.centerOnSpawn, number(section, "center-x") ?: 0, number(section, "center-z") ?: 0,
            min, max, flag(section, "allow-main-island") ?: false,
        )
    }

    private fun text(section: YamlNode.Mapping, key: String): String? = (section[key] as? YamlNode.Scalar)?.content

    private fun number(section: YamlNode.Mapping, key: String): Int? = text(section, key)?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

    private fun flag(section: YamlNode.Mapping, key: String): Boolean? = when (text(section, key)?.trim()?.lowercase()) {
        "true", "yes", "on" -> true
        "false", "no", "off" -> false
        else -> null
    }
}
