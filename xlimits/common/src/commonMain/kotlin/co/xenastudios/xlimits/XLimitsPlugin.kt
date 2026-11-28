package co.xenastudios.xlimits

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.event.Subscription
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.Importer
import co.xenastudios.core.common.admin.Imports
import co.xenastudios.core.common.admin.PasteService
import co.xenastudios.core.common.admin.RecentErrors
import co.xenastudios.core.common.command.AdminCommands
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.ModuleReloader
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleContext
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.xlimits.api.ChunkRef
import co.xenastudios.xlimits.api.LimitKind
import co.xenastudios.xlimits.api.LimitProvider
import co.xenastudios.xlimits.api.SpawnRoute
import co.xenastudios.xlimits.api.XLimits
import co.xenastudios.xlimits.api.XLimitsApi
import co.xenastudios.xlimits.api.event.FarmRestoredEvent
import co.xenastudios.xlimits.api.event.FarmSlowedEvent
import co.xenastudios.xlimits.api.event.LimitReachedEvent
import co.xenastudios.xlimits.api.event.ProfileChangedEvent
import co.xenastudios.xlimits.config.Trigger
import co.xenastudios.xlimits.config.XLimitsConfig
import kotlin.concurrent.Volatile
import kotlin.math.floor

/** What the plugin's files said when they were last read: the config, the lang files, and the problems found. */
public class Loaded(
    public val config: Config,
    public val translations: Translations,
    /** Each problem, in English, already logged. */
    public val problems: List<String> = emptyList(),
)

/** xLimits' files: on the JVM, `JvmFiles`. [load] reads `config.yml` and `lang/`. */
public fun interface Files {
    public fun load(): Loaded
}

/**
 * xLimits, the same on every platform: each adapter makes one with its [Platform] and its
 * [LimitsServer], and calls [start] as the plugin enables.
 *
 * Nothing repeats and nothing is stored. A limit is checked when a limited thing tries to
 * happen (a spawn, a breed, a placement), on the thread that owns the chunk, against counts
 * kept per chunk ([Chunks]): the chunk's entities are counted at most once a second, plus what
 * was allowed since, and its blocks once, then kept up to date from placements and breaks.
 * Farm slowing and load profiles are checked at those same moments.
 */
public class XLimitsPlugin(
    public val platform: Platform,
    public val server: LimitsServer,
    private val files: Files,
    languages: LanguageSource,
    /** Who's online, by name, as each command source can see them. */
    private val players: OnlinePlayers,
    private val registrar: CommandRegistrar? = null,
    private val permissionRegistrar: PermissionRegistrar? = null,
    /** What `/xlimits debug`'s report says about the server. */
    about: () -> Map<String, String> = { emptyMap() },
    /** Where `/xlimits debug` uploads its report; `null` to write a file. */
    paste: PasteService? = null,
    /** FarmControl's and Insights' configs, for `/xlimits import`; `null` where there's none (Fabric). */
    importers: ((XLimitsPlugin) -> List<Importer<*>>)? = null,
    override val version: String = VERSION,
) : XLimitsApi, LimitsView {

    /** Where the plugin's console lines go; the last 50 warnings and errors are kept for `/xlimits debug`. */
    public val log: RecentErrors = RecentErrors(platform.log, platform.clock)

    /** The messages, in each reader's language. */
    public val texts: Texts = Texts(languages, server)

    /** The API's events. On Bukkit the adapter bridges them to Bukkit events. */
    public val bus: SimpleEventBus = SimpleEventBus(log)

    override val events: EventBus get() = bus

    /** The listeners, one module per switch that's on. */
    public val modules: ModuleSet = ModuleSet(platform)

    @Volatile
    public var config: Config = XLimitsConfig.defaults
        private set

    @Volatile
    internal var problems: List<String> = emptyList()
        private set

    /** The config, worked out for the checks. */
    @Volatile
    public var rules: Rules = Rules(XLimitsConfig.defaults, Rules.nextGeneration(), server.features.actions)
        private set

    internal val chunks = Chunks()
    internal val pets = Pets()

    // The server's load profile, where it isn't per region.
    private val serverProfile = ProfileState()

    // On Folia: when each profile's switch was last told to staff, so one region's switching isn't news every time.
    private val profileAlerted = co.xenastudios.core.common.concurrentMap<String, Long>()

    @Volatile
    private var providers: List<LimitProvider> = emptyList()

    /** Whether `/xlimits restore` paused farm slowing, until a reload. */
    @Volatile
    internal var farmsPaused: Boolean = false

    /** Whether the plugin is running: started and not stopped. */
    @Volatile
    public var running: Boolean = false
        private set

    /** `/xlimits reload`, `debug` and `import`. */
    @Suppress("UNCHECKED_CAST")
    public val admin: AdminCommands = AdminCommands(
        PLUGIN_ID, NAME, platform,
        ModuleReloader(modules, reloadFiles = Reloading(0) as () -> List<String>, wanted = Reloading(1) as () -> Map<String, () -> Module>),
        // An object, not a lambda, so its class doesn't load until the command runs (AdminCommands).
        debug = object : () -> Debug {
            override fun invoke() = AdminParts.debug(this@XLimitsPlugin, about, paste)
        },
        imports = importers?.let { Imports(platform, it(this)) },
        text = texts.admin,
    )

    private val commands = Commands(this)

    /** What runs the commands; built again on reload, from the new config. */
    @Volatile
    public var dispatcher: CommandDispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        private set

    /** The permissions. */
    public val permissions: XLimitsPermissions get() = commands.permissions

    /** The platform's update checker, which the adapter starts; every reload gives it the config's settings again. */
    @Volatile
    public var updates: UpdateChecker? = null

    public val updateSettings: UpdateSettings get() = XLimitsConfig.updates.settings(config)

    /**
     * What the adapter does just before the first event could be heard: Bukkit's bridges the API's
     * events to Bukkit events there, so their classes don't load as the server starts.
     */
    @Volatile
    public var beforeFirstEvent: (() -> Unit)? = null

    private fun eventsReady() {
        val bridge = beforeFirstEvent ?: return
        val run = co.xenastudios.core.common.locked(this) { if (beforeFirstEvent === bridge) true.also { beforeFirstEvent = null } else false }
        if (run) bridge()
    }

    // /xlimits reload: the files again, then the modules. One class for both.
    private inner class Reloading(private val kind: Int) : () -> Any {
        override fun invoke(): Any = if (kind == 0) apply(files.load()) else wanted()
    }

    /** Turns the plugin on with what [Files] read: the commands and the listeners for what's configured. */
    public fun start(loaded: Loaded) {
        running = true
        apply(loaded)
        XLimits.register(this)
    }

    /** Turns the plugin off, as the server stops. Slowed mobs stay slowed, marked, for the next start. */
    public fun stop() {
        running = false
        XLimits.register(null)
        updates?.close()
        updates = null
        modules.clear()
        registrar?.clear()
        permissionRegistrar?.sync(emptyList())
        chunks.clear()
        pets.clear()
    }

    /** Uses what [Files] read: the rules worked out again, the commands rebuilt, the listeners for what's on. On the global thread. */
    public fun apply(loaded: Loaded): List<String> {
        config = loaded.config
        texts.translations = loaded.translations
        val rules = Rules(loaded.config, Rules.nextGeneration(), server.features.actions)
        this.rules = rules
        farmsPaused = false
        serverProfile.reset()
        dispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        permissionRegistrar?.sync(permissions.permissions + admin.permissions.permissions)
        val warnings = ArrayList<String>()
        warnings += registrar?.sync(dispatcher).orEmpty() + commands.warnings
        warnings += rules.problems
        warnings += gaps(rules)
        warnings.forEach(log::warn)
        problems = loaded.problems + warnings
        updates?.apply(updateSettings)
        modules.sync(wanted())
        return problems
    }

    // What this server can't do of what's configured, said once per load.
    private fun gaps(rules: Rules): List<String> {
        val out = ArrayList<String>()
        val f = server.features
        for (p in rules.profiles) {
            val trigger = p.profile.trigger
            if (trigger == Trigger.MSPT_ABOVE && server.mspt() == null) {
                out += "profiles.list.${p.name}: this server has no MSPT to read${if (f.perRegion) " (Folia: use tps-below)" else ""}, so it never turns on."
            }
        }
        val wanted = wantedSwitches(rules)
        for (switch in wanted) if (switch !in f.switches) out += "${Switch.NAMES[switch]} limits can't be checked on this server, so they're off here."
        return out
    }

    private fun wantedSwitches(r: Rules): Set<Int> {
        val on = HashSet<Int>()
        if (r.entities.isNotEmpty() || r.farms.isNotEmpty()) on += Switch.ENTITIES
        if (r.farms.isNotEmpty() || r.pets >= 0) on += Switch.CHUNKS
        if (r.blocks.isNotEmpty()) on += Switch.BLOCKS
        if (r.redstoneChanges >= 0) on += Switch.REDSTONE
        if (r.tnt >= 0) on += Switch.TNT
        if (r.portalEntities >= 0) on += Switch.PORTALS
        if (r.pets >= 0) on += Switch.PETS
        return on
    }

    private fun wanted(): Map<String, () -> Module> {
        val on = HashMap<String, () -> Module>()
        for (switch in wantedSwitches(rules)) if (switch in server.features.switches) on[Switch.NAMES[switch]] = Make(switch)
        return on
    }

    // A module's maker, one class told apart by its switch.
    private inner class Make(private val switch: Int) : () -> Module {
        override fun invoke(): Module = Listen(switch)
    }

    private inner class Listen(private val switch: Int) : Module {
        private var handle: AutoCloseable? = null

        override fun enable(context: ModuleContext) {
            handle = server.listen(switch, this@XLimitsPlugin)
        }

        override fun disable() {
            handle?.close()
            handle = null
        }
    }

    // ---- counting ----

    internal fun now(): Long = platform.clock.elapsed().inWholeMilliseconds

    /**
     * [chunk]'s entry with its entities counted at most [COUNT_EVERY] ago ([force] to count now),
     * and farm slowing checked as they are. On [chunk]'s thread.
     */
    internal fun counted(chunk: ChunkRef, r: Rules, force: Boolean = false): ChunkEntry {
        val entry = chunks.entry(chunk)
        entry.resize(r)
        val now = now()
        if (!force && now - entry.countedAt < COUNT_EVERY) return entry
        val census = Census()
        if (!server.census(chunk, census)) return entry
        entry.countedAt = now
        entry.entities.fill(0)
        entry.farm.fill(0)
        entry.tnt = 0
        entry.falling = 0
        var slowedHere: BooleanArray? = null
        for ((kind, box) in census.kinds) {
            val n = box[0]
            if (n == 0) continue
            val m = r.matched(kind)
            for (i in m.entity) entry.entities[i] += n
            for (i in m.farm) {
                entry.farm[i] += n
                if (box[1] > 0) (slowedHere ?: BooleanArray(r.farms.size).also { slowedHere = it })[i] = true
            }
            if (kind.isIn(Category.TNT)) entry.tnt += n
            if (kind.isIn(Category.FALLING)) entry.falling += n
        }
        // Mobs marked as slowed (from before a restart or a reload) are restored if their farm's under.
        slowedHere?.forEachIndexed { i, marked -> if (marked) entry.slowed[i] = true }
        if (r.pets >= 0) for (pet in census.pets) pets.seen(pet.owner.toString(), pet.id, pet.type)
        if (r.farms.isNotEmpty() && !farmsPaused) farms(entry, r)
        return entry
    }

    // Farm slowing for [entry]'s chunk, as its entities were just counted.
    private fun farms(entry: ChunkEntry, r: Rules) {
        val profile = profileFor(entry, r)
        for (i in r.farms.indices) {
            val rule = r.farms[i]
            val threshold = scaled(rule.threshold, profile?.profile?.scale)
            val count = entry.farm[i]
            if (count > threshold) {
                if (!entry.slowed[i] && cancelled(FarmSlowedEvent.TYPE) { FarmSlowedEvent(entry.chunk, rule.name, count, threshold, rule.actions) }) continue
                server.slow(entry.chunk, Selector(r, i), rule.actions, r.villagers, slow = true)
                entry.slowed[i] = true
            } else if (entry.slowed[i] && count <= threshold - maxOf(1, threshold / 10)) {
                val restored = server.slow(entry.chunk, Selector(r, i), rule.actions, r.villagers, slow = false)
                entry.slowed[i] = false
                if (restored > 0 && bus.hasListeners(FarmRestoredEvent.TYPE)) {
                    eventsReady()
                    bus.fire(FarmRestoredEvent.TYPE, FarmRestoredEvent(entry.chunk, rule.name, restored))
                }
            }
        }
    }

    // Picks the mobs of one farm rule, for the adapter: an object, not a lambda per check.
    private class Selector(private val rules: Rules, private val rule: Int) : (EntityKind) -> Boolean {
        override fun invoke(kind: EntityKind): Boolean = rule in rules.matched(kind).farm
    }

    // Whether a pre-event [make] makes, fired only when something listens, was cancelled.
    private inline fun <E : co.xenastudios.core.api.event.Cancellable> cancelled(type: co.xenastudios.core.api.event.EventType<E>, make: () -> E): Boolean {
        if (!bus.hasListeners(type)) return false
        eventsReady()
        return bus.fire(type, make()).isCancelled
    }

    // ---- limits: the config's, then the profile's, then the providers' ----

    /** The limit [name] has at [location] in [entry]'s chunk, from its [base] in the config. */
    internal fun limitAt(entry: ChunkEntry, location: Location, name: String, base: Int, r: Rules): Int {
        var limit = base
        val profile = profileFor(entry, r)
        if (profile != null) limit = profile.profile.limits[name] ?: scaled(limit, profile.profile.scale)
        for (provider in providers) {
            limit = try {
                provider.limit(location, name, limit)
            } catch (e: Exception) {
                if (co.xenastudios.core.common.isFatal(e)) throw e
                log.warn("A limit provider failed for $name at $location, so it's left out: $e")
                limit
            }
        }
        return limit
    }

    private fun scaled(limit: Int, scale: Double?): Int =
        if (scale == null || limit == XLimits.NO_LIMIT) limit else floor(limit * scale).toInt().coerceAtLeast(0)

    /** The load profile on for [entry]'s chunk now, checked at most once a second: the server's, or on Folia the chunk's region's. */
    internal fun profileFor(entry: ChunkEntry, r: Rules): Rules.NamedProfile? {
        if (r.profiles.isEmpty()) return null
        val perRegion = server.features.perRegion
        val state = if (perRegion) entry.profile else serverProfile
        val now = now()
        if (state.due(now)) {
            val needsTps = r.profiles.any { it.profile.trigger == Trigger.TPS_BELOW }
            val needsMspt = r.profiles.any { it.profile.trigger == Trigger.MSPT_ABOVE }
            val readings = Readings(
                server.onlineCount(),
                if (needsTps) server.tps(entry.chunk) ?: Double.NaN else Double.NaN,
                if (needsMspt) server.mspt() ?: Double.NaN else Double.NaN,
            )
            val was = state.check(r.profiles, readings, now, r.profileMinimum)
            if (was != null) profileChanged(r, was, state.active, entry.chunk, readings, perRegion)
        }
        return r.profiles.getOrNull(state.active)
    }

    private fun profileName(r: Rules, index: Int): String = r.profiles.getOrNull(index)?.name ?: NORMAL

    private fun profileChanged(r: Rules, from: Int, to: Int, chunk: ChunkRef, readings: Readings, perRegion: Boolean) {
        val fromName = profileName(r, from)
        val toName = profileName(r, to)
        log.info("Load profile ${if (to >= 0) "$toName is on" else "$fromName is off"}${if (perRegion) " in the region at $chunk" else ""}.")
        if (bus.hasListeners(ProfileChangedEvent.TYPE)) {
            eventsReady()
            bus.fire(ProfileChangedEvent.TYPE, ProfileChangedEvent(fromName, toName, chunk, perRegion))
        }
        if (!r.alerts) return
        if (perRegion) {
            val key = "$fromName>$toName"
            val now = now()
            val last = profileAlerted[key]
            if (last != null && now - last < r.alertCooldown) return
            profileAlerted[key] = now
        }
        val where = if (perRegion) WherePart(chunk) else null
        if (to >= 0) {
            val p = r.profiles[to].profile
            val (reason, value) = when (p.trigger) {
                Trigger.PLAYERS_ONLINE_ABOVE -> XLimitsMessages.reasonPlayers to readings.players.toDouble()
                Trigger.TPS_BELOW -> XLimitsMessages.reasonTps to readings.tps
                Trigger.MSPT_ABOVE -> XLimitsMessages.reasonMspt to readings.mspt
            }
            staff { reader ->
                val because = texts.render(reader, reason, "value" to value, "on" to p.on) ?: Component.text("")
                texts.render(reader, XLimitsMessages.alertProfileOn, "profile" to toName, "where" to (where?.render(reader) ?: EMPTY), "reason" to because)
            }
        } else {
            staff { reader -> texts.render(reader, XLimitsMessages.alertProfileOff, "profile" to fromName, "where" to (where?.render(reader) ?: EMPTY), "now" to toName) }
        }
    }

    private inner class WherePart(private val chunk: ChunkRef) {
        fun render(reader: PlayerRef?): Component =
            texts.render(reader, XLimitsMessages.profileWhere, "world" to chunk.world.name, "x" to (chunk.x shl 4).toString(), "z" to (chunk.z shl 4).toString()) ?: EMPTY
    }

    // ---- refusing ----

    /**
     * What happens at a limit: holders of the bypass permission go on, then [LimitReachedEvent] may
     * allow it; otherwise [player] is told [message] and staff are alerted. Returns whether it's refused.
     */
    private fun refuse(
        kind: LimitKind, name: String, location: Location, player: PlayerRef?, route: SpawnRoute?, count: Int, limit: Int,
        entry: ChunkEntry?, message: MessageKey?, r: Rules, vararg extra: Pair<String, Any>,
    ): Boolean {
        if (player != null && server.hasPermission(player, XLimits.BYPASS_PERMISSION)) return false
        if (cancelled(LimitReachedEvent.TYPE) { LimitReachedEvent(kind, name, location, player, route, count, limit) }) return false
        if (player != null && message != null) texts.send(player, message, "limit" to name, "count" to count, "max" to limit, *extra)
        if (entry != null) alert(entry, name, count, limit, location, r)
        return true
    }

    // Staff are told the first time [entry]'s chunk reaches a limit, at most once per alerts.cooldown.
    private fun alert(entry: ChunkEntry, name: String, count: Int, limit: Int, location: Location, r: Rules) {
        if (!r.alerts) return
        val now = now()
        if (now - entry.alertedAt < r.alertCooldown) return
        entry.alertedAt = now
        val at = Place(location)
        staff { reader ->
            texts.render(reader, XLimitsMessages.alertReached, "limit" to name, "count" to count, "max" to limit, *at.placeholders(reader))
        }
    }

    // A place in an alert: its world and block, and the xRewind button for it.
    private inner class Place(private val location: Location) {
        fun placeholders(reader: PlayerRef?): Array<Pair<String, Any>> = arrayOf(
            "world" to location.world.name, "x" to location.blockX.toString(), "y" to location.blockY.toString(), "z" to location.blockZ.toString(),
            "who_built" to whoBuilt(reader, location),
        )
    }

    private fun whoBuilt(reader: PlayerRef?, location: Location): Component {
        val template = config[XLimitsConfig.alerts.whoBuilt].trim().removePrefix("/")
        if (template.isEmpty() || !server.hasPlugin("xRewind")) return EMPTY
        val command = template.replace("<world>", location.world.name).replace("<x>", location.blockX.toString())
            .replace("<y>", location.blockY.toString()).replace("<z>", location.blockZ.toString())
        return texts.render(reader, XLimitsMessages.alertWhoBuilt, "command" to command) ?: EMPTY
    }

    /** Sends each staff member with `xlimits.alerts` (and the console) what [render] draws for them. */
    internal inline fun staff(render: (PlayerRef?) -> Component?) {
        for (player in server.online()) {
            if (server.hasPermission(player, ALERTS)) render(player)?.let { server.send(player, it) }
        }
        render(null)?.let(server::console)
    }

    // ---- the checks the listeners ask ----

    override fun spawning(kind: EntityKind, location: Location, route: SpawnRoute, player: PlayerRef?): Boolean {
        val r = rules
        if (!running || location.world.name in r.exemptWorlds) return true
        val m = r.matched(kind)
        if (m.entity.isEmpty() && m.farm.isEmpty()) return true
        val entry = counted(ChunkRef.of(location), r)
        if (r.refuses(route)) {
            val world = location.world.name
            for (i in m.entity) {
                val rule = r.entities[i]
                val count = entry.entities[i]
                // Cheap first: most spawns are far under their cap, and need no profile or provider.
                if (providers.isEmpty() && r.profiles.isEmpty() && count < r.base(world, rule.name, rule.cap)) continue
                val limit = limitAt(entry, location, rule.name, r.base(world, rule.name, rule.cap), r)
                if (count >= limit && refuse(LimitKind.ENTITY, rule.name, location, player, route, count, limit, entry, XLimitsMessages.entityReached, r)) return false
            }
        }
        for (i in m.entity) entry.entities[i]++
        for (i in m.farm) entry.farm[i]++
        return true
    }

    override fun chunkLoaded(chunk: ChunkRef) {
        val r = rules
        if (!running || (r.farms.isEmpty() && r.pets < 0) || chunk.world.name in r.exemptWorlds) return
        // After /xlimits restore, a chunk that loads later is restored too, so none is missed.
        if (farmsPaused) server.slow(chunk, Anything, emptySet(), villagers = true, slow = false)
        counted(chunk, r, force = true)
    }

    private object Anything : (EntityKind) -> Boolean {
        override fun invoke(kind: EntityKind): Boolean = true
    }

    override fun chunkUnloaded(chunk: ChunkRef) {
        chunks.forget(chunk)
    }

    override fun placing(block: String, location: Location, player: PlayerRef?): Boolean {
        val r = rules
        val rulesHere = r.blockIndex[block] ?: return true
        if (!running || location.world.name in r.exemptWorlds) return true
        val chunk = ChunkRef.of(location)
        val entry = chunks.entry(chunk)
        entry.resize(r)
        var counts = entry.blocks
        if (counts == null) {
            val delta = entry.blockDelta
            if (delta != null) {
                // Being counted off the server's threads: allowed, and remembered for when it's done.
                for (i in rulesHere) delta[i]++
                return true
            }
            countBlocks(entry, r, placing = rulesHere)
            counts = entry.blocks
            if (counts == null) {
                // Counted later, off the server's threads: allowed now. Where the server hasn't put
                // the block there yet, the count won't have it, so it's remembered.
                if (!server.features.placedBeforeCheck) entry.blockDelta?.let { delta -> for (i in rulesHere) delta[i]++ }
                return true
            }
        }
        val world = location.world.name
        for (i in rulesHere) {
            val rule = r.blocks[i]
            val limit = limitAt(entry, location, rule.name, r.base(world, rule.name, rule.cap), r)
            if (counts[i] >= limit && refuse(LimitKind.BLOCK, rule.name, location, player, null, counts[i], limit, entry, XLimitsMessages.blockReached, r)) return false
        }
        for (i in rulesHere) counts[i]++
        return true
    }

    /**
     * Counts [entry]'s blocks for [r]'s block rules. While the count is made, what's placed and
     * broken goes in [ChunkEntry.blockDelta]. [placing] are the rules of a block whose placing is
     * being checked: left out of the count if the server already put it there, so the check
     * counts it once.
     */
    internal fun countBlocks(entry: ChunkEntry, r: Rules, placing: IntArray? = null, then: (() -> Unit)? = null) {
        entry.blockDelta = IntArray(r.blocks.size)
        val generation = r.generation
        var answering = true
        server.countBlocks(entry.chunk, r.blockIds) { found ->
            val delta = entry.blockDelta
            if (entry.generation == generation && delta != null) {
                entry.blockDelta = null
                if (found != null) {
                    val counts = IntArray(r.blocks.size) { i -> r.blocks[i].ids.sumOf { found[it] ?: 0 } + delta[i] }
                    // Answered at once, the block being placed is in the count but not yet allowed:
                    // left out, so the check counts it once. Answered later, it was allowed meanwhile.
                    if (answering && placing != null && server.features.placedBeforeCheck) for (i in placing) counts[i]--
                    for (i in counts.indices) if (counts[i] < 0) counts[i] = 0
                    entry.blocks = counts
                }
            }
            then?.invoke()
        }
        answering = false
    }

    override fun removed(block: String, location: Location) {
        changed(block, location, -1)
    }

    override fun added(block: String, location: Location) {
        changed(block, location, 1)
    }

    // A block counted in or out without a check, where the chunk's blocks are known or being counted.
    private fun changed(block: String, location: Location, by: Int) {
        val r = rules
        val rulesHere = r.blockIndex[block] ?: return
        val entry = chunks.find(ChunkRef.of(location)) ?: return
        if (entry.generation != r.generation) return
        val counts = entry.blocks ?: entry.blockDelta ?: return
        for (i in rulesHere) counts[i] = if (entry.blocks != null) maxOf(0, counts[i] + by) else counts[i] + by
    }

    override fun redstone(location: Location): Boolean {
        val r = rules
        val max = r.redstoneChanges
        if (max < 0 || !running || location.world.name in r.exemptWorlds) return true
        val entry = chunks.entry(ChunkRef.of(location))
        entry.resize(r)
        val map = entry.redstone ?: HashMap<Int, LongArray>().also { entry.redstone = it }
        val now = now()
        if (map.size > 256) map.values.removeAll { now - it[0] > r.redstoneWindow }
        val key = ChunkEntry.redstoneKey(location.blockX, location.blockY, location.blockZ)
        val clock = map[key] ?: longArrayOf(now, 0).also { map[key] = it }
        if (now - clock[0] > r.redstoneWindow) {
            clock[0] = now
            clock[1] = 0
        }
        clock[1]++
        val changes = clock[1].toInt()
        if (changes > entry.redstoneScore) entry.redstoneScore = changes
        if (changes <= max) return true
        if (refuse(LimitKind.REDSTONE, "redstone", location, null, null, changes, max, null, null, r)) {
            map.remove(key)
            server.breakBlock(location)
            if (r.alerts) {
                val at = Place(location)
                val window = config[XLimitsConfig.redstone.window]
                staff { reader -> texts.render(reader, XLimitsMessages.alertRedstone, "count" to changes, "window" to window, *at.placeholders(reader)) }
            }
            return false
        }
        return true
    }

    override fun priming(location: Location, player: PlayerRef?): Boolean = burst(location, player, tnt = true)

    override fun falling(location: Location): Boolean = burst(location, null, tnt = false)

    // Primed TNT or falling blocks against their cap per chunk.
    private fun burst(location: Location, player: PlayerRef?, tnt: Boolean): Boolean {
        val r = rules
        val cap = if (tnt) r.tnt else r.falling
        if (cap < 0 || !running || location.world.name in r.exemptWorlds) return true
        val entry = counted(ChunkRef.of(location), r)
        val name = if (tnt) "tnt" else "falling_blocks"
        val count = if (tnt) entry.tnt else entry.falling
        val limit = limitAt(entry, location, name, r.base(location.world.name, name, cap), r)
        if (count >= limit) {
            val kind = if (tnt) LimitKind.TNT else LimitKind.FALLING_BLOCK
            if (refuse(kind, name, location, player, null, count, limit, entry, if (tnt) XLimitsMessages.tntReached else null, r)) return false
        }
        if (tnt) entry.tnt++ else entry.falling++
        return true
    }

    override fun portal(kind: EntityKind, location: Location): Boolean {
        val r = rules
        if (r.portalEntities < 0 || !running || kind.isIn(Category.PLAYER) || location.world.name in r.exemptWorlds) return true
        val entry = chunks.entry(ChunkRef.of(location))
        entry.resize(r)
        val now = now()
        if (now - entry.portalStart >= r.portalWindow) {
            entry.portalStart = now
            entry.portalCount = 0
        }
        val name = "portal_items"
        val limit = limitAt(entry, location, name, r.base(location.world.name, name, r.portalEntities), r)
        if (entry.portalCount >= limit && refuse(LimitKind.PORTAL, name, location, null, null, entry.portalCount, limit, entry, null, r)) return false
        entry.portalCount++
        return true
    }

    override fun taming(owner: PlayerRef, kind: EntityKind, location: Location, pet: String): Boolean {
        val r = rules
        if (r.pets < 0 || !running || location.world.name in r.exemptWorlds) return true
        val entry = chunks.entry(ChunkRef.of(location))
        entry.resize(r)
        val id = owner.toString()
        val total = pets.count(id)
        val limit = limitAt(entry, location, "pets", r.base(location.world.name, "pets", r.pets), r)
        if (total >= limit && refuse(LimitKind.PET, "pets", location, owner, null, total, limit, null, XLimitsMessages.petsReached, r, "type" to "")) return false
        val perType = r.petsPerType[kind.id]
        if (perType != null) {
            val ofType = pets.count(id, kind.id)
            if (ofType >= perType) {
                val kindText = texts.render(owner, XLimitsMessages.petsOfType) ?: EMPTY
                if (refuse(LimitKind.PET, kind.id, location, owner, null, ofType, perType, null, XLimitsMessages.petsReached, r, "type" to kindText)) return false
            }
        }
        pets.seen(id, pet, kind.id)
        return true
    }

    override fun petGone(pet: String) {
        pets.gone(pet)
    }

    // ---- the API ----

    override fun count(chunk: ChunkRef, group: String): Int {
        val r = rules
        val name = co.xenastudios.xlimits.config.LimitTypes.id(group)
        val entry = counted(chunk, r)
        if (entry.countedAt == ChunkEntry.NEVER) return -1
        r.entities.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let { return entry.entities[it] }
        r.blocks.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let { i ->
            if (entry.blocks == null && entry.blockDelta == null) countBlocks(entry, r)
            return entry.blocks?.get(i) ?: -1
        }
        return when (name) {
            "tnt" -> entry.tnt
            "falling_blocks" -> entry.falling
            "pets" -> -1
            else -> -1
        }
    }

    override fun limit(chunk: ChunkRef, group: String): Int {
        val r = rules
        val name = co.xenastudios.xlimits.config.LimitTypes.id(group)
        val base = r.entities.firstOrNull { it.name == name }?.cap ?: r.blocks.firstOrNull { it.name == name }?.cap ?: when (name) {
            "tnt" -> r.tnt
            "falling_blocks" -> r.falling
            "portal_items" -> r.portalEntities
            "pets" -> r.pets
            else -> -1
        }
        if (base < 0) return XLimits.NO_LIMIT
        val entry = chunks.entry(chunk)
        entry.resize(r)
        return limitAt(entry, chunk.center(), name, r.base(chunk.world.name, name, base), r)
    }

    override fun limits(): List<String> {
        val r = rules
        val out = ArrayList<String>()
        r.entities.mapTo(out) { it.name }
        r.blocks.mapTo(out) { it.name }
        if (r.tnt >= 0) out += listOf("tnt", "falling_blocks")
        if (r.portalEntities >= 0) out += "portal_items"
        if (r.pets >= 0) out += "pets"
        return out
    }

    override fun activeProfile(chunk: ChunkRef): String {
        val r = rules
        val state = if (server.features.perRegion) chunks.find(chunk)?.profile ?: return NORMAL else serverProfile
        return profileName(r, state.active)
    }

    override fun register(provider: LimitProvider): Subscription {
        co.xenastudios.core.common.locked(this) { providers = providers + provider }
        return object : Subscription {
            override val isActive: Boolean get() = provider in providers

            override fun cancel() {
                co.xenastudios.core.common.locked(this@XLimitsPlugin) { providers = providers - provider }
            }
        }
    }

    // ---- for the commands ----

    /** An online player called [name] whom [viewer] can see. */
    internal fun find(name: String, viewer: co.xenastudios.core.common.command.CommandSource): PlayerRef? = players.find(name, viewer)

    /** The names [source] might type. */
    internal fun names(source: co.xenastudios.core.common.command.CommandSource): List<String> = players.names(source)

    public companion object {
        public const val PLUGIN_ID: String = "xlimits"
        public const val NAME: String = "xLimits"

        /** xLimits' version. The adapters' builds check theirs is the same. */
        public const val VERSION: String = "1.0.0"

        /** Staff alerts. */
        public const val ALERTS: String = "xlimits.alerts"

        /** The profile's name when none is on. */
        public const val NORMAL: String = "normal"

        /** How long a chunk's count of entities is kept: a busy spawner costs one count a second. */
        internal const val COUNT_EVERY: Long = 1_000

        internal val EMPTY: Component = Component.text("")
    }
}
