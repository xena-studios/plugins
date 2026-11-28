package co.xenastudios.xlimits.config

import co.xenastudios.core.common.config.ConfigGroup
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.ConfigSection
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.family.MessagesSection
import co.xenastudios.core.common.update.UpdatesSection
import co.xenastudios.xlimits.api.SlowAction
import co.xenastudios.xlimits.api.SpawnRoute
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * xLimits' `config.yml`, version 1. Everything applies on `/xlimits reload`, and a feature that's
 * off registers no listener at all. Nothing is stored: counts live in memory, and a slowed mob
 * carries its own marker.
 */
public object XLimitsConfig : ConfigSchema(
    1,
    "xLimits: farm, entity and redstone limits.",
    "Docs: https://plugins.xenastudios.co/xlimits/configuration/",
    "",
    "Apply changes with /xlimits reload. Everything applies at once; nothing that's already",
    "in the world is removed by a limit, only new spawns, placements and the like are refused.",
    "A bad value falls back to its default with a warning in the console. The messages are",
    "in lang/.",
) {
    /** The look and the language: the family's (`plugins/Xena/config.yml`) unless set here. */
    public val messages: MessagesSection = MessagesSection(this)

    public val command: Command = Command(this)
    public val worlds: Worlds = Worlds(this)
    public val entities: Entities = Entities(this)
    public val farms: Farms = Farms(this)
    public val blocks: Blocks = Blocks(this)
    public val redstone: Redstone = Redstone(this)
    public val tnt: Tnt = Tnt(this)
    public val portals: Portals = Portals(this)
    public val pets: Pets = Pets(this)
    public val profiles: Profiles = Profiles(this)
    public val alerts: Alerts = Alerts(this)
    public val updates: UpdatesSection = UpdatesSection(this)

    /** `command:` */
    public class Command(parent: ConfigGroup) : ConfigSection(parent, "command") {
        public val aliases: ConfigKey<List<String>> = stringList("aliases", listOf("limits"), "Other names for /xlimits.")
    }

    /** `worlds:` */
    public class Worlds(parent: ConfigGroup) : ConfigSection(parent, "worlds", "Where the limits apply.") {
        public val exempt: ConfigKey<List<String>> = stringList("exempt", emptyList(), "Worlds xLimits leaves alone, by folder name (world_nether).")
        public val overrides: ConfigKey<Map<String, Map<String, Int>>> = key(
            "overrides", LimitTypes.WORLD_LIMITS, emptyMap(),
            "Limits that differ in one world, by world and then by limit, such as",
            "  world_nether: { hopper: 32, monsters: 40 }",
            "Any entity, block, tnt, falling_blocks, portal_items or pets limit can be set here.",
        )
    }

    /** `entities:` */
    public class Entities(parent: ConfigGroup) : ConfigSection(
        parent, "entities",
        "A cap per chunk on mobs, vehicles, armour stands and item frames. Past it, new ones",
        "don't spawn; nothing already there is removed. Next to Paper's spawn-limits and",
        "per-player-mob-spawns, which cover natural spawning only: this also covers breeding,",
        "spawn eggs, dispensers and placing.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
        public val limits: ConfigKey<Map<String, Int>> = key(
            "limits", LimitTypes.LIMITS,
            linkedMapOf("animals" to 80, "monsters" to 70, "villagers" to 50, "vehicles" to 40, "armor_stands" to 40, "item_frames" to 150),
            "By group or entity type. The groups: animals, monsters, villagers, water (fish,",
            "squid, axolotls), ambient (bats), golems, vehicles (boats and minecarts),",
            "armor_stands, item_frames (with paintings), and your own below. A type is its",
            "Minecraft id: cow, zombified_piglin. Each one that matches a mob is checked.",
        )
        public val groups: ConfigKey<Map<String, List<String>>> = key(
            "groups", LimitTypes.GROUPS, emptyMap(),
            "Groups of your own, by name, each a list of entity types, such as",
            "  raiders: [pillager, vindicator, evoker, ravager]",
        )
        public val routes: ConfigKey<List<SpawnRoute>> = list(
            "routes", ConfigType.enumOf(SpawnRoute.entries),
            listOf(SpawnRoute.NATURAL, SpawnRoute.SPAWNER, SpawnRoute.BREEDING, SpawnRoute.EGG, SpawnRoute.SPAWN_EGG, SpawnRoute.DISPENSER, SpawnRoute.PLACED),
            "Which ways of coming to be the caps refuse: natural, spawner, breeding, egg, spawn-egg,",
            "dispenser, placed and other (golems, plugins, commands, slimes splitting). A route",
            "that isn't listed is never refused.",
        )
    }

    /** `farms:` */
    public class Farms(parent: ConfigGroup) : ConfigSection(
        parent, "farms",
        "Farm slowing: when one group in a chunk passes its threshold, those mobs are slowed",
        "instead of removed, and go back to normal when the count falls. Checked at a spawn or",
        "breed in the chunk and as it loads, never on a timer. Named, tamed and leashed mobs are",
        "left alone. Next to Paper's collisions.max-entity-collisions, which applies to every mob",
        "everywhere: this only touches a crammed farm. Run /xlimits restore before removing xLimits.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
        public val rules: ConfigKey<Map<String, FarmRule>> = key(
            "rules", LimitTypes.FARMS,
            linkedMapOf("animals" to FarmRule(32, setOf(SlowAction.NO_COLLISIONS))),
            "By group or entity type: a threshold, and the actions, any of no-collisions (they",
            "don't push each other), no-ai (they stop pathfinding but still breed, drop and can be",
            "killed) and no-breeding. Such as",
            "  monsters: { threshold: 60, actions: [no-collisions] }",
        )
        public val villagers: ConfigKey<Boolean> = boolean(
            "villagers", false,
            "Slow villagers too. Off, because iron farms and trading halls depend on their AI.",
        )
    }

    /** `blocks:` */
    public class Blocks(parent: ConfigGroup) : ConfigSection(
        parent, "blocks",
        "A cap per chunk on blocks. Placing one past it is refused with a message. Next to",
        "Paper's hopper settings, which make each hopper cheaper but don't limit how many.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
        public val limits: ConfigKey<Map<String, Int>> = key(
            "limits", LimitTypes.LIMITS,
            linkedMapOf("hopper" to 80, "spawner" to 12, "piston" to 128, "sticky_piston" to 128, "observer" to 128),
            "By block id or one of your groups below.",
        )
        public val groups: ConfigKey<Map<String, List<String>>> = key(
            "groups", LimitTypes.GROUPS, emptyMap(),
            "Groups of blocks of your own, each counted together, such as",
            "  redstone: [repeater, comparator, redstone_torch]",
        )
    }

    /** `redstone:` */
    public class Redstone(parent: ConfigGroup) : ConfigSection(
        parent, "redstone",
        "Redstone clocks: how often one block's power changes in a short window. Past the",
        "limit it's broken (it drops as an item) and staff are told where. Next to Paper's",
        "alternate-current, which makes redstone cheaper but doesn't stop a clock left running.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", false)
        public val changes: ConfigKey<Int> = int("changes", 60, "Changes allowed in the window.", min = 4, max = 100_000)
        public val window: ConfigKey<kotlin.time.Duration> = duration("window", 5.seconds, min = 1.seconds, max = 5.minutes)
    }

    /** `tnt:` */
    public class Tnt(parent: ConfigGroup) : ConfigSection(
        parent, "tnt",
        "A cap per chunk on primed TNT and falling sand, gravel and concrete powder. Past it,",
        "TNT stays a block and sand doesn't fall. Off by default, because cannon servers set",
        "their own numbers. Next to Paper's max-tnt-per-tick, which is server-wide, so one chunk",
        "can use all of it.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", false)
        public val primed: ConfigKey<Int> = int("primed", 64, "Primed TNT per chunk.", min = 0, max = 100_000)
        public val falling: ConfigKey<Int> = int("falling", 128, "Falling blocks per chunk.", min = 0, max = 100_000)
    }

    /** `portals:` */
    public class Portals(parent: ConfigGroup) : ConfigSection(
        parent, "portals",
        "A rate limit on items and other entities going through the portals of one chunk, so",
        "item spam and portal chunk loaders can't flood the other side. Players and what they",
        "ride are never limited. Off by default.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", false)
        public val entities: ConfigKey<Int> = int("entities", 40, "Entities allowed through in the window.", min = 1, max = 100_000)
        public val window: ConfigKey<kotlin.time.Duration> = duration("window", 10.seconds, min = 1.seconds, max = 10.minutes)
    }

    /** `pets:` */
    public class Pets(parent: ConfigGroup) : ConfigSection(
        parent, "pets",
        "A cap on how many tamed mobs one player owns. Past it, taming fails with a message;",
        "pets they already have are kept. Counted in memory from the owner on each mob, as",
        "they're seen. Off by default.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", false)
        public val limit: ConfigKey<Int> = int("limit", 50, "Pets of every kind together.", min = 0, max = 100_000)
        public val perType: ConfigKey<Map<String, Int>> = key("per-type", LimitTypes.LIMITS, emptyMap(), "Per entity type, such as { wolf: 10, cat: 10 }.")
    }

    /** `profiles:` */
    public class Profiles(parent: ConfigGroup) : ConfigSection(
        parent, "profiles",
        "Load profiles: stricter limits that turn on while the server is busy. A profile turns",
        "on when its condition holds, stays on for minimum-time, and turns off at its off value.",
        "It only tightens new spawns, placements and the farm thresholds. On Folia each region",
        "has its own profile, from its own TPS. Off by default.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", false)
        public val minimumTime: ConfigKey<kotlin.time.Duration> = duration("minimum-time", 2.minutes, min = 5.seconds, max = 1.hours)
        public val list: ConfigKey<Map<String, Profile>> = key(
            "list", LimitTypes.PROFILES,
            linkedMapOf(
                "busy" to Profile(Trigger.PLAYERS_ONLINE_ABOVE, 80.0, 70.0, 0.8, emptyMap()),
                "strained" to Profile(Trigger.TPS_BELOW, 17.0, 19.0, 0.5, emptyMap()),
            ),
            "By name; a later one wins over an earlier one. when: players-online-above, tps-below",
            "or mspt-above (Paper; Folia has no MSPT, so use tps-below there). on and off: the",
            "values it turns on and off at. scale: every limit and threshold times this. limits:",
            "limits set outright, such as { animals: 30, hopper: 40 }.",
        )
    }

    /** `alerts:` */
    public class Alerts(parent: ConfigGroup) : ConfigSection(
        parent, "alerts",
        "Staff with xlimits.alerts are told the first time a chunk reaches a limit, with a",
        "button to teleport there, and when a load profile turns on or off.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
        public val cooldown: ConfigKey<kotlin.time.Duration> = duration("cooldown", 5.minutes, "At most once per chunk this often.", min = 10.seconds, max = 24.hours)
        public val whoBuilt: ConfigKey<String> = string(
            "who-built", "xrewind lookup radius:16 world:<world> x:<x> y:<y> z:<z>",
            "With xRewind installed, the alert's \"who built this\" button runs this command at",
            "the place. Blank for no button.",
        )
    }
}

/** How a profile's condition reads the server. */
public enum class Trigger {
    PLAYERS_ONLINE_ABOVE,
    TPS_BELOW,
    MSPT_ABOVE,
}

/** A farm slowing rule: past [threshold] in one chunk, the mobs are slowed by [actions]. */
public class FarmRule(public val threshold: Int, public val actions: Set<SlowAction>) {
    override fun equals(other: Any?): Boolean = other is FarmRule && other.threshold == threshold && other.actions == actions
    override fun hashCode(): Int = threshold * 31 + actions.hashCode()
    override fun toString(): String = "FarmRule($threshold, $actions)"
}

/** A load profile: on while [trigger] passes [on], off again at [off]; it scales and sets limits. */
public class Profile(
    public val trigger: Trigger,
    public val on: Double,
    public val off: Double,
    public val scale: Double,
    public val limits: Map<String, Int>,
) {
    override fun equals(other: Any?): Boolean = other is Profile && other.trigger == trigger && other.on == on && other.off == off &&
        other.scale == scale && other.limits == limits
    override fun hashCode(): Int = ((trigger.hashCode() * 31 + on.hashCode()) * 31 + off.hashCode()) * 31 + limits.hashCode()
    override fun toString(): String = "Profile($trigger, $on, $off, $scale, $limits)"
}
