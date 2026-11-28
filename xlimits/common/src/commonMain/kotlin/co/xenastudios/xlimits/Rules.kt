package co.xenastudios.xlimits

import co.xenastudios.core.common.config.Config
import co.xenastudios.xlimits.api.SlowAction
import co.xenastudios.xlimits.api.SpawnRoute
import co.xenastudios.xlimits.api.XLimits
import co.xenastudios.xlimits.config.LimitTypes
import co.xenastudios.xlimits.config.Profile
import co.xenastudios.xlimits.config.XLimitsConfig
import kotlin.time.Duration

/**
 * The config, worked out once as it loads into what the checks read: each limit with what it
 * matches, by index, so a check is a few array reads. A new one is made on every reload, with a
 * new [generation]; counts kept for the old one are counted again.
 */
public class Rules internal constructor(config: Config, public val generation: Int, actions: Set<SlowAction>) {
    /** An entity limit: [name], its [cap], and what it matches: built-in groups by [mask], or entity [types]. */
    public class EntityRule(public val name: String, public val cap: Int, internal val mask: Int, internal val types: Set<String>) {
        internal fun matches(kind: EntityKind): Boolean = (mask != 0 && kind.isIn(mask)) || kind.id in types
    }

    /** A farm rule: past [threshold], the mobs it matches are slowed by [actions]. */
    public class FarmRule(public val name: String, public val threshold: Int, public val actions: Set<SlowAction>, internal val match: EntityRule)

    /** A block limit: [name], its [cap], and the block [ids] it counts together. */
    public class BlockRule(public val name: String, public val cap: Int, public val ids: Set<String>)

    /** A load profile, by [name], in the config's order: a later one wins. */
    public class NamedProfile(public val name: String, public val profile: Profile)

    /** What one [EntityKind] matches under these rules. */
    public class Matched internal constructor(internal val generation: Int, internal val entity: IntArray, internal val farm: IntArray)

    public val entities: List<EntityRule>
    public val farms: List<FarmRule>
    public val blocks: List<BlockRule>
    public val profiles: List<NamedProfile>

    /** Block id to the indices of the [blocks] rules that count it. */
    internal val blockIndex: Map<String, IntArray>

    /** Every block id a block rule counts. */
    public val blockIds: Set<String>

    internal val routes: BooleanArray
    internal val exemptWorlds: Set<String>
    internal val overrides: Map<String, Map<String, Int>>
    internal val villagers: Boolean

    /** Primed TNT and falling blocks per chunk, `-1` with `tnt` off. */
    internal val tnt: Int
    internal val falling: Int

    /** Portal entities per window per chunk, `-1` with `portals` off. */
    internal val portalEntities: Int
    internal val portalWindow: Long

    /** Pets per owner, `-1` with `pets` off, and per type. */
    internal val pets: Int
    internal val petsPerType: Map<String, Int>

    /** Redstone changes per window per block, `-1` with `redstone` off. */
    internal val redstoneChanges: Int
    internal val redstoneWindow: Long

    internal val profileMinimum: Long
    internal val alertCooldown: Long
    internal val alerts: Boolean

    /** What's wrong in the config that a schema can't see, in English, for the console. */
    public val problems: List<String>

    init {
        val problems = ArrayList<String>()
        val groups = config[XLimitsConfig.entities.groups]
        fun entityRule(name: String, cap: Int): EntityRule {
            Category.GROUPS[name]?.let { return EntityRule(name, cap, it, emptySet()) }
            groups[name]?.let { return EntityRule(name, cap, 0, it.toSet()) }
            return EntityRule(name, cap, 0, setOf(name))
        }

        entities = if (config[XLimitsConfig.entities.enabled]) config[XLimitsConfig.entities.limits].map { (name, cap) -> entityRule(name, cap) } else emptyList()

        farms = if (config[XLimitsConfig.farms.enabled]) {
            config[XLimitsConfig.farms.rules].map { (name, rule) ->
                val missing = rule.actions - actions
                if (missing.isNotEmpty()) {
                    problems += "farms.rules.$name: ${missing.joinToString { LimitTypes.actionName(it) }} can't be done on this server, so it's left out."
                }
                FarmRule(name, rule.threshold, rule.actions intersect actions, entityRule(name, rule.threshold))
            }.filter { it.actions.isNotEmpty() }
        } else {
            emptyList()
        }

        val blockGroups = config[XLimitsConfig.blocks.groups]
        blocks = if (config[XLimitsConfig.blocks.enabled]) {
            config[XLimitsConfig.blocks.limits].map { (name, cap) -> BlockRule(name, cap, blockGroups[name]?.toSet() ?: setOf(name)) }
        } else {
            emptyList()
        }
        val index = HashMap<String, MutableList<Int>>()
        blocks.forEachIndexed { i, rule -> for (id in rule.ids) index.getOrPut(id) { ArrayList() } += i }
        blockIndex = index.mapValues { it.value.toIntArray() }
        blockIds = index.keys.toSet()

        val routeList = config[XLimitsConfig.entities.routes]
        routes = BooleanArray(SpawnRoute.entries.size) { SpawnRoute.entries[it] in routeList }
        exemptWorlds = config[XLimitsConfig.worlds.exempt].map { it.trim() }.toSet()
        overrides = config[XLimitsConfig.worlds.overrides]
        villagers = config[XLimitsConfig.farms.villagers]

        val t = XLimitsConfig.tnt
        tnt = if (config[t.enabled]) config[t.primed] else -1
        falling = if (config[t.enabled]) config[t.falling] else -1
        val p = XLimitsConfig.portals
        portalEntities = if (config[p.enabled]) config[p.entities] else -1
        portalWindow = config[p.window].millis
        val pe = XLimitsConfig.pets
        pets = if (config[pe.enabled]) config[pe.limit] else -1
        petsPerType = config[pe.perType]
        val r = XLimitsConfig.redstone
        redstoneChanges = if (config[r.enabled]) config[r.changes] else -1
        redstoneWindow = config[r.window].millis

        val pr = XLimitsConfig.profiles
        profiles = if (config[pr.enabled]) config[pr.list].map { NamedProfile(it.key, it.value) } else emptyList()
        profileMinimum = config[pr.minimumTime].millis
        for (named in profiles) {
            val prof = named.profile
            val wrongWay = when (prof.trigger) {
                co.xenastudios.xlimits.config.Trigger.TPS_BELOW -> prof.off < prof.on
                else -> prof.off > prof.on
            }
            if (wrongWay) problems += "profiles.list.${named.name}: off is on the wrong side of on, so it would never turn off; it turns off at its on value instead."
        }
        alerts = config[XLimitsConfig.alerts.enabled]
        alertCooldown = config[XLimitsConfig.alerts.cooldown].millis
        this.problems = problems
    }

    /** What [kind] matches here, worked out the first time it's asked under these rules. */
    internal fun matched(kind: EntityKind): Matched {
        val known = kind.matched
        if (known != null && known.generation == generation) return known
        val entity = entities.indices.filter { entities[it].matches(kind) }.toIntArray()
        val farm = farms.indices.filter { farms[it].match.matches(kind) && (villagers || !kind.isIn(Category.VILLAGER)) }.toIntArray()
        return Matched(generation, entity, farm).also { kind.matched = it }
    }

    /** Whether [route] is refused at a cap. */
    internal fun refuses(route: SpawnRoute): Boolean = routes[route.ordinal]

    /** The limit [name] has in [world] before profiles and providers: [cap], or the world's override. */
    internal fun base(world: String, name: String, cap: Int): Int = overrides[world]?.get(name) ?: cap

    /** Whether anything at all is limited: with nothing, every listener is off. */
    public val empty: Boolean
        get() = entities.isEmpty() && farms.isEmpty() && blocks.isEmpty() && tnt < 0 && portalEntities < 0 && pets < 0 && redstoneChanges < 0

    internal companion object {
        const val NONE: Int = XLimits.NO_LIMIT

        private val lock = Any()
        private var generations = 0

        /**
         * A generation no rules had before, in this server's run: an [EntityKind]'s matches are
         * kept by generation, and the kinds outlive a plugin that's disabled and enabled again.
         */
        fun nextGeneration(): Int = co.xenastudios.core.common.locked(lock) { ++generations }

        private val Duration.millis: Long get() = inWholeMilliseconds
    }
}
