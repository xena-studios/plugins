package co.xenastudios.xteleport

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.map
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.reject
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.PasteService
import co.xenastudios.core.common.admin.PlayerDataRegistry
import co.xenastudios.core.common.admin.RecentErrors
import co.xenastudios.core.common.command.AdminCommands
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.ModuleReloader
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleContext
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.permission.Permissible
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.xteleport.api.DestinationProvider
import co.xenastudios.xteleport.api.Home
import co.xenastudios.xteleport.api.HomeProvider
import co.xenastudios.xteleport.api.Names
import co.xenastudios.xteleport.api.RtpSettings
import co.xenastudios.xteleport.api.TeleportOptions
import co.xenastudios.xteleport.api.TeleportResult
import co.xenastudios.xteleport.api.TeleportType
import co.xenastudios.xteleport.api.VisibilityHook
import co.xenastudios.xteleport.api.Warp
import co.xenastudios.xteleport.api.XTeleport
import co.xenastudios.xteleport.api.XTeleportApi
import co.xenastudios.xteleport.config.XTeleportConfig
import co.xenastudios.xteleport.rtp.Rtp
import co.xenastudios.xteleport.store.Homes
import co.xenastudios.xteleport.store.Places
import co.xenastudios.xteleport.store.Store
import co.xenastudios.xteleport.store.Tables
import co.xenastudios.xteleport.teleport.Teleporter
import co.xenastudios.xteleport.teleport.Trip
import co.xenastudios.xteleport.tpa.Requests
import kotlin.concurrent.Volatile

/** What the plugin's files said when they were last read: the config, the lang files, and the problems found. */
public class Loaded(
    public val config: Config,
    public val translations: Translations,
    /** Each problem, in English, already logged. */
    public val problems: List<String> = emptyList(),
)

/** xTeleport's files: on the JVM, `JvmFiles`. [load] reads `config.yml` and `lang/`. */
public fun interface Files {
    public fun load(): Loaded
}

/**
 * xTeleport, the same on every platform: each adapter makes one with its [Platform] and its
 * [TeleportServer], reads the files ([Files]) and calls [start] as the plugin enables, then
 * [open]s the database off the server's threads. Until it's open, commands that need it say so.
 * Everything the config says applies on `/xteleport reload`, but `storage`, which is read once.
 */
public class XTeleportPlugin(
    public val platform: Platform,
    /** What the adapter does to players and worlds. */
    public val server: TeleportServer,
    private val files: Files,
    languages: LanguageSource,
    /** Who's online, by name, as each command source can see them. */
    private val players: OnlinePlayers,
    /** The family's player cache: names of players who aren't online, and who's vanished. */
    private val cache: PlayerCache? = null,
    private val registrar: CommandRegistrar? = null,
    private val permissionRegistrar: PermissionRegistrar? = null,
    /** What `/xteleport debug`'s report says about the server. */
    about: () -> Map<String, String> = { emptyMap() },
    /** Where `/xteleport debug` uploads its report; `null` to write a file. */
    paste: PasteService? = null,
    /** EssentialsX's files, for `/xteleport import`; `null` where there's no such folder (Fabric). */
    public val essentials: Essentials? = null,
    override val version: String = VERSION,
) : XTeleportApi {

    /** Where the plugin's console lines go; the last 50 warnings and errors are kept for `/xteleport debug`. */
    public val log: RecentErrors = RecentErrors(platform.log, platform.clock)

    /** The messages, in each reader's language. */
    public val texts: Texts = Texts(languages) { server }

    /** The API's events. On Bukkit the adapter bridges them to Bukkit events. */
    public val bus: SimpleEventBus = SimpleEventBus(log)

    override val events: EventBus get() = bus

    /** The modules: the players' listener, and the spawn's while a spawn setting is on. */
    public val modules: ModuleSet = ModuleSet(platform)

    @Volatile
    public var config: Config = XTeleportConfig.defaults
        private set

    @Volatile
    internal var problems: List<String> = emptyList()
        private set

    /** The database, once open; `null` before, or if it couldn't be. */
    @Volatile
    public var store: Store? = null
        private set

    /** Why there's no database, once opening it failed. */
    @Volatile
    public var failure: String? = null
        private set

    /** Settled `true` once the database is open and the warps read, `false` if it couldn't be opened. */
    public val opened: Pending<Boolean> = newPending()

    /** Whether the plugin is running: started and not stopped. */
    @Volatile
    public var running: Boolean = false
        private set

    public val homes: Homes = Homes({ store }, server::isOnline, ::now)
    public val places: Places = Places({ store }, ::now)
    public val requests: Requests = Requests(::now)
    public val homeLimits: HomeLimits = HomeLimits(this)

    // Made on first use: a server that's just started hasn't teleported anyone.
    @Volatile
    private var madeTeleporter: Teleporter? = null
    public val teleporter: Teleporter get() = madeTeleporter ?: co.xenastudios.core.common.locked(this) { madeTeleporter ?: Teleporter(this).also { madeTeleporter = it } }

    @Volatile
    private var madeRtp: Rtp? = null
    public val rtp: Rtp get() = madeRtp ?: co.xenastudios.core.common.locked(this) { madeRtp ?: Rtp(server, platform.scheduler) { running }.also { it.configure(config); madeRtp = it } }

    /** `/xteleport reload`, `debug` and `forget`. */
    public val admin: AdminCommands = AdminCommands(
        PLUGIN_ID, NAME, platform,
        ModuleReloader(modules, reloadFiles = ::reload, wanted = ::wanted),
        // Objects, not lambdas, so neither class loads until the command runs (AdminCommands).
        debug = object : () -> Debug {
            override fun invoke() = AdminParts.debug(this@XTeleportPlugin, about, paste)
        },
        playerData = object : () -> PlayerDataRegistry {
            override fun invoke() = AdminParts.playerData(this@XTeleportPlugin, cache)
        },
        text = texts.admin,
    )

    private val commands = Commands(this)

    /** What runs the commands; built again on reload, from the new config. */
    @Volatile
    public var dispatcher: CommandDispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        private set

    /** The permissions the config declares. */
    @Volatile
    public var permissions: XTeleportPermissions = commands.permissions
        private set

    /** The platform's update checker, which the adapter starts; every reload gives it the config's settings again. */
    @Volatile
    public var updates: UpdateChecker? = null

    public val updateSettings: UpdateSettings get() = XTeleportConfig.updates.settings(config)

    /** The placeholders, which read from memory. */
    public val placeholders: Placeholders by lazy { Placeholders(this) }

    // The add-ons' seams, by owner.
    private val destinations = concurrentMap<String, Pair<String, DestinationProvider>>()
    private val homeProviders = concurrentMap<HomeProvider, String>()
    private val visibility = concurrentMap<VisibilityHook, String>()

    /** Whether homes and warps can be used: the database is open and the warps read. */
    public val ready: Boolean get() = store != null && places.loaded

    internal fun now(): Long = platform.clock.now().toEpochMilliseconds()

    /** Turns the plugin on with what [Files] read: permissions, commands and the players' listener. The database comes with [open]. */
    public fun start(loaded: Loaded) {
        running = true
        apply(loaded)
    }

    /**
     * The database the adapter opened (off the server's threads), or why it couldn't: its tables
     * brought up to date, the warps and the spawn read, every online player's homes, then the API
     * registered and [opened] settled. On the database's threads.
     */
    public fun open(result: Outcome<Database, String>) {
        val db = when (result) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> return fail(result.error)
        }
        db.migrate(Tables.MIGRATIONS).onSettled(Opening(db))
    }

    // Opening, one class for its steps rather than a lambda each.
    private inner class Opening(private val db: Database) : (Any?, Throwable?) -> Unit {
        private var store: Store? = null

        override fun invoke(value: Any?, error: Throwable?) {
            val opened = store
            if (opened == null) migrated(value, error) else read(opened, value, error)
        }

        private fun migrated(value: Any?, error: Throwable?) {
            if (value !is Outcome.Ok<*>) return fail("couldn't bring ${db.description}'s tables up to date: ${(value as? Outcome.Err<*>)?.error ?: error}")
            val store = Store(db)
            this.store = store
            store.loadServer().onSettled(this)
        }

        private fun read(store: Store, value: Any?, error: Throwable?) {
            if (value !is Outcome.Ok<*>) return fail("couldn't read the warps in ${db.description}: ${(value as? Outcome.Err<*>)?.error ?: error}")
            places.load(value.value as co.xenastudios.xteleport.store.ServerData)
            this@XTeleportPlugin.store = store
            val warps = places.warps().size
            log.info("Loaded $warps ${if (warps == 1) "warp" else "warps"}${if (places.setSpawn == null) "" else " and the spawn"} from ${db.description}.")
            // Players who joined while it opened (a reload with players online).
            for (player in server.online()) homes.of(player)
            XTeleport.register(this@XTeleportPlugin)
            opened.resolve(true)
        }
    }

    private fun fail(reason: String) {
        failure = reason
        log.error("$reason $NAME can't keep homes or warps without its database: fix storage in config.yml and restart.")
        opened.resolve(false)
    }

    /** Turns the plugin off, as the server stops: the API, the modules, the commands, every teleport, then the database once what's queued is saved. */
    public fun stop(): Pending<Unit> {
        running = false
        XTeleport.register(null)
        updates?.close()
        updates = null
        modules.clear()
        madeTeleporter?.clear()
        requests.clear()
        registrar?.clear()
        permissionRegistrar?.sync(emptyList())
        val store = store ?: return pendingOf(Unit)
        this.store = null
        return store.close()
    }

    // /xteleport reload: the files again, then everything built from them.
    private fun reload(): List<String> = apply(files.load())

    /** Uses what [Files] read: the commands rebuilt, the limits forgotten. On the global thread. */
    public fun apply(loaded: Loaded): List<String> {
        config = loaded.config
        texts.translations = loaded.translations
        madeRtp?.configure(config)
        homeLimits.clear()
        dispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        permissions = commands.permissions
        permissionRegistrar?.sync(permissions.permissions + admin.permissions.permissions)
        val warnings = ArrayList<String>()
        warnings += registrar?.sync(dispatcher).orEmpty() + commands.warnings
        warnings.forEach(log::warn)
        problems = loaded.problems + warnings
        updates?.apply(updateSettings)
        modules.sync(wanted())
        return problems
    }

    private fun wanted(): Map<String, () -> Module> {
        val s = XTeleportConfig.spawn
        val spawn = config[s.onFirstJoin] || config[s.onEveryJoin] || config[s.onRespawn]
        return if (spawn) mapOf("players" to PLAYERS, "spawn" to SPAWN) else mapOf("players" to PLAYERS)
    }

    // The two modules' makers, as one class told apart by kind, rather than a lambda each.
    private inner class Make(private val kind: Int) : () -> Module {
        override fun invoke(): Module = Listen(kind)
    }

    private val PLAYERS = Make(0)
    private val SPAWN = Make(1)

    /** The players' listener (homes read as they join, everything forgotten as they leave), or the spawn's. */
    private inner class Listen(private val kind: Int) : Module, PlayerEvents, SpawnEvents {
        private var handle: AutoCloseable? = null

        override fun enable(context: ModuleContext) {
            handle = if (kind == 0) server.watchPlayers(this) else server.watchSpawn(this)
        }

        override fun disable() {
            handle?.close()
            handle = null
        }

        override fun joined(player: PlayerRef, name: String) {
            // Read off the server's threads; until it's back, /home says "not available".
            if (store != null) homes.of(player)
        }

        override fun quit(player: PlayerRef) {
            homes.forget(player)
            homeLimits.forget(player)
            requests.forget(player)
            madeTeleporter?.cooldowns?.prune()
        }

        // Off the server's threads, before the player is in the world: reads only memory.
        override fun spawnOnJoin(player: PlayerRef, firstJoin: Boolean): Location? {
            val s = XTeleportConfig.spawn
            if (!(config[s.onEveryJoin] || (config[s.onFirstJoin] && firstJoin))) return null
            return places.setSpawn?.location
        }

        // Deaths only: leaving the End goes where the game sends it (the adapter calls this only for those).
        override fun respawn(player: PlayerRef): Location? = if (config[XTeleportConfig.spawn.onRespawn]) places.setSpawn?.location else null
    }

    // ---- for the commands ----

    /** An online player called [name] whom [viewer] can see, as the server and every visibility hook say. */
    internal fun findVisible(name: String, viewer: CommandSource): PlayerRef? {
        val found = players.find(name, viewer) ?: return null
        val seer = viewer.player ?: return found
        return if (hidden(seer, found)) null else found
    }

    /** Whether [target] is hidden from [viewer]: vanished in the family's player cache, or by an add-on's hook. */
    internal fun hidden(viewer: PlayerRef, target: PlayerRef): Boolean {
        if (viewer == target) return false
        if (cache?.isVanished(target) == true) return true
        for (hook in visibility.keys) {
            try {
                if (hook.hidden(viewer, target)) return true
            } catch (e: Exception) {
                log.warn("${visibility[hook]}'s visibility hook failed: $e")
            }
        }
        return false
    }

    /** An online player called exactly [name] (any case), seen or not. */
    internal fun findOnline(name: String): PlayerRef? = server.online().firstOrNull { server.name(it).equals(name, ignoreCase = true) }

    /** A player called [name], online or not, with their name as last seen; `null` if no one by that name has played here. */
    internal fun findPlayer(name: String): Pending<Pair<PlayerRef, String>?> {
        findOnline(name)?.let { return pendingOf(it to (server.name(it) ?: name)) }
        val cache = cache ?: return pendingOf(null)
        return cache.lookup(name).map { found -> (found as? Outcome.Ok)?.value?.let { it.player to it.name } }
    }

    /** Whether [who] may use the warp called [warp]: always, unless per-warp permissions are on. */
    internal fun mayUse(who: Permissible, warp: String): Boolean =
        !config[XTeleportConfig.warps.perWarpPermission] || who.hasPermission(WARP_PERMISSION + warp.lowercase())

    /** Homes add-ons give [player] (plot homes). */
    internal fun extraHomes(player: PlayerRef): List<Home> {
        if (homeProviders.isEmpty()) return emptyList()
        val out = ArrayList<Home>()
        for ((provider, owner) in homeProviders.entries.toList()) {
            try {
                out += provider.homes(player)
            } catch (e: Exception) {
                log.warn("$owner's home provider failed: $e")
            }
        }
        return out
    }

    /** The economy costs go through, as the adapter finds it. */
    internal fun economy(): Economy? = server.economy()

    /** Where `/spawn` goes: the spawn set with `/setspawn` if its world is loaded, otherwise the main world's spawn point. */
    internal fun spawnPoint(): Location? {
        places.setSpawn?.location?.let { if (server.world(it.world.name) != null) return it }
        val main = server.worlds().firstOrNull() ?: return null
        return server.world(main.name)?.spawn
    }

    /** What [source] might type, for [Words]. */
    internal fun suggestions(source: CommandSource, kind: Int): List<String> {
        val player = source.player
        return when (kind) {
            Words.PLAYERS -> players.names(source).filter { name -> player == null || findOnline(name)?.let { !hidden(player, it) } != false }
            Words.HOMES, Words.HOMES_AND_BED -> {
                if (player == null) return emptyList()
                val names = homes.cached(player)?.list?.map { it.name }.orEmpty().toMutableList()
                for (home in extraHomes(player)) names += home.name
                if (kind == Words.HOMES_AND_BED && config[XTeleportConfig.homes.bedHome]) names += config[XTeleportConfig.homes.bedName]
                names
            }
            Words.WARPS -> places.warps().filter { mayUse(source, it.name) }.map { it.name }
            Words.REQUESTS -> if (player == null) emptyList() else requests.pending(player, config[XTeleportConfig.tpa.expireAfter] * 1000L).map { it.fromName }
            Words.WORLDS -> server.worlds().map { it.name }
            else -> server.worlds().filter { ref -> server.world(ref.name)?.let { rtp.allowed(source, it) } != null }.map { it.name }
        }
    }

    // ---- the API ----

    private fun options(options: TeleportOptions): Trip =
        Trip(TeleportType.PLUGIN, options.cooldownKey, options.cooldownSeconds, options.cost, options.warmupSeconds, options.checkSafe)

    override fun teleport(player: PlayerRef, destination: Location, options: TeleportOptions): Pending<TeleportResult> =
        request(player, options) { pendingOf(destination) }

    override fun teleport(player: PlayerRef, destination: String, options: TeleportOptions): Pending<TeleportResult> {
        val provider = destinations[destination.lowercase()]?.second ?: return pendingOf(TeleportResult.FAILED)
        return request(player, options) { provider.destination(it) }
    }

    private fun request(player: PlayerRef, options: TeleportOptions, destination: (PlayerRef) -> Pending<Location?>): Pending<TeleportResult> {
        if (!server.isOnline(player)) return pendingOf(TeleportResult.FAILED)
        val result = newPending<TeleportResult>()
        teleporter.onPlayer(player, { result.resolve(TeleportResult.FAILED) }) {
            teleporter.teleport(player, options(options), destination).onSettled { done, _ -> result.resolve(done ?: TeleportResult.FAILED) }
        }
        return result
    }

    override fun cooldownMillis(player: PlayerRef, key: String): Long = madeTeleporter?.cooldowns?.remaining(player, key) ?: 0

    override fun cachedHomes(player: PlayerRef): List<Home>? = homes.cached(player)?.list

    override fun homes(player: PlayerRef): Pending<List<Home>> = homes.of(player).map { it.list }

    override fun setHome(player: PlayerRef, name: String, location: Location): Pending<Boolean> =
        if (!Names.valid(name)) pendingOf(false) else homes.set(Home(player, name, location))

    override fun deleteHome(player: PlayerRef, name: String): Pending<Boolean> {
        val result = newPending<Boolean>()
        homes.of(player).onSettled { read, _ ->
            if (read != null && read.home(name) == null) {
                result.resolve(false)
                return@onSettled
            }
            homes.delete(player, name).onSettled { ok, error -> if (error != null) result.reject(error) else result.resolve(ok == true) }
        }
        return result
    }

    override fun homeLimit(player: PlayerRef): Int =
        if (server.isOnline(player)) homeLimits.cached(player) else config[XTeleportConfig.homes.defaultLimit]

    override fun warps(): List<Warp> = places.warps()

    override fun warp(name: String): Warp? = places.warp(name)

    override fun setWarp(name: String, location: Location): Pending<Boolean> =
        if (!Names.valid(name)) pendingOf(false) else places.setWarp(Warp(name, location))

    override fun deleteWarp(name: String): Pending<Boolean> =
        if (places.loaded && places.warp(name) == null) pendingOf(false) else places.deleteWarp(name)

    override fun spawn(): Location? = spawnPoint()

    override fun findSafeLocation(world: WorldRef, settings: RtpSettings): Pending<Location?> {
        val info = server.world(world.name) ?: return pendingOf(null)
        if (rtp.begin(null) != co.xenastudios.xteleport.rtp.Search.Start.STARTED) return pendingOf(null)
        val result = newPending<Location?>()
        val search = try {
            rtp.find(info, settings)
        } catch (e: Exception) {
            rtp.end(null)
            return co.xenastudios.core.api.pendingFailure(e)
        }
        search.onSettled { found, _ ->
            rtp.end(null)
            result.resolve(found?.location)
        }
        return result
    }

    override fun registerDestination(owner: String, name: String, provider: DestinationProvider) {
        destinations[name.lowercase()] = owner to provider
    }

    override fun registerHomes(owner: String, provider: HomeProvider) {
        homeProviders[provider] = owner
    }

    override fun registerVisibility(owner: String, hook: VisibilityHook) {
        visibility[hook] = owner
    }

    override fun unregisterAll(owner: String) {
        for ((name, entry) in destinations.entries.toList()) if (entry.first == owner) destinations.remove(name)
        for ((provider, of) in homeProviders.entries.toList()) if (of == owner) homeProviders.remove(provider)
        for ((hook, of) in visibility.entries.toList()) if (of == owner) visibility.remove(hook)
    }

    public companion object {
        public const val PLUGIN_ID: String = "xteleport"
        public const val NAME: String = "xTeleport"

        /** xTeleport's version. The adapters' builds check theirs is the same. */
        public const val VERSION: String = "2.0.0"

        /** With per-warp permissions on, `xteleport.warp.<name>` is needed for each warp. */
        public const val WARP_PERMISSION: String = "xteleport.warp."
    }
}

/**
 * How many homes a player may have: the highest of the default, the config's limits and
 * `xteleport.homes.<n>`. Working it out reads the player's permissions, so what's asked often (a
 * placeholder on a scoreboard) is answered from what was last worked out, for up to 30 seconds:
 * there's no event for a player's permissions changing, so that short time lets a rank change
 * show. Setting a home and `/homes` always work it out again. Forgotten as players leave, and all
 * of it on reload.
 */
public class HomeLimits(private val plugin: XTeleportPlugin) {
    private class Known(val limit: Int, val at: Long)

    private val known = concurrentMap<PlayerRef, Known>()

    /** Works out [player]'s limit now, and keeps it for [cached]. */
    public fun of(player: PlayerRef): Int {
        val limit = workOut(player)
        known[player] = Known(limit, plugin.now())
        return limit
    }

    /** The limit as last worked out, if that was recent; otherwise worked out. */
    public fun cached(player: PlayerRef): Int {
        val last = known[player]
        if (last != null && plugin.now() - last.at < FRESH_MILLIS) return last.limit
        return of(player)
    }

    public fun forget(player: PlayerRef) {
        known.remove(player)
    }

    public fun clear() {
        known.clear()
    }

    private fun workOut(player: PlayerRef): Int {
        val server = plugin.server
        if (server.hasPermission(player, plugin.permissions.unlimitedHomes.node)) return Int.MAX_VALUE
        val config = plugin.config
        var best = config[XTeleportConfig.homes.defaultLimit]
        for ((name, limit) in config[XTeleportConfig.homes.limits]) {
            val permission = limit.permission.ifBlank { "group.$name" }
            if (limit.homes > best && server.hasPermission(player, permission)) best = limit.homes
        }
        return maxOf(best, server.homeNumbers(player))
    }

    internal companion object {
        const val FRESH_MILLIS = 30_000L
    }
}
