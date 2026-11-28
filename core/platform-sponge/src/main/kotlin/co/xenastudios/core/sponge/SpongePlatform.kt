package co.xenastudios.core.sponge

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.api.event.Subscription
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.platform.Capability
import co.xenastudios.core.common.platform.Clock
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.SystemClock
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.common.update.ModrinthReleases
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateNotices
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.core.jvm.db.DriverFetcher
import co.xenastudios.core.jvm.db.OnDemandDriverFetcher
import co.xenastudios.core.jvm.platform.FolderStorage
import co.xenastudios.core.jvm.scheduler.TickScheduler
import co.xenastudios.core.jvm.text.AdventureRenderer
import co.xenastudios.core.jvm.update.JvmHttp
import co.xenastudios.core.loader.LibraryCache
import co.xenastudios.core.loader.OnDemandLibraries
import io.leangen.geantyref.TypeToken
import org.spongepowered.api.Server
import org.spongepowered.api.Sponge
import org.spongepowered.api.command.Command
import org.spongepowered.api.event.EventListener
import org.spongepowered.api.event.EventListenerRegistration
import org.spongepowered.api.event.Order
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent
import org.spongepowered.api.event.lifecycle.StartedEngineEvent
import org.spongepowered.api.event.lifecycle.StartingEngineEvent
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent
import org.spongepowered.api.event.network.ServerSideConnectionEvent
import org.spongepowered.api.scheduler.ScheduledTask
import org.spongepowered.api.scheduler.Task
import org.spongepowered.api.util.Ticks
import java.nio.file.Path
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import org.spongepowered.api.Platform as SpongeApiPlatform
import org.spongepowered.api.event.Event as SpongeEvent

/**
 * The core's [Platform] on a Sponge server, API 8 (SpongeVanilla 1.16.5) on, made once by each
 * plugin as Sponge constructs its main class ([SpongeBootstrap]), before the server exists: what
 * it needs from the server it asks for once the server is starting, and work scheduled before
 * then waits for the first tick.
 *
 * One jar for every API line: compiled against API 8, calling only what the newest API still
 * has (each plugin's build checks its shrunk jar against both), and registering its listeners
 * in code rather than by annotation, so nothing depends on how Sponge reads a class.
 *
 * - **Scheduler:** core-jvm's [TickScheduler], ticked by a Sponge task that runs every server
 *   tick, and a small async pool. Sponge has one server thread, so global, place and entity work
 *   all run there.
 * - **Events:** players joining and leaving, the server starting and stopping, on [events].
 * - **Commands and permissions:** [commands], our own bridge to Sponge's raw commands (Cloud has
 *   no Sponge 8+ module, M0), and [permissions] through Sponge's permission service.
 * - **Text:** Sponge's own Adventure (core-jvm's [AdventureRenderer]), sent to players and the
 *   console alike.
 * - **Files:** `config/<id>/`, Sponge's place for a plugin's settings, as core-jvm's [FolderStorage].
 * - **Console:** the plugin's Log4j logger, which the console shows by the plugin's id.
 *
 * The Kotlin stdlib, the YAML parser and the core are inside each plugin's jar, relocated to its
 * own package and shrunk (xena.sponge-plugin): Sponge has no library loader and no Kotlin
 * language provider, and every plugin shares one class loader.
 */
public class SpongePlatform(
    /** The plugin's main object, which Sponge made. */
    public val bootstrap: SpongeBootstrap,
    /** The plugin's name, for its thread names and `/<plugin> debug`: `xSample`. */
    public val displayName: String,
    /** The plugin's version, as its jar was built: `1.0.0`. */
    public val version: String,
) : Platform {

    private val container = bootstrap.container

    /** The plugin's id, as in `META-INF/sponge_plugins.json`: its folder is `config/<id>/`. */
    public val id: String = container.metadata().id()

    // Before the server starts, the thread that constructs plugins (the game's main thread) is the
    // one that may touch the plugin's state, as the server thread is later.
    private val startupThread = Thread.currentThread()

    override val log: Log = Log4jLog(container.logger())

    override val name: String = run {
        val implementation = Sponge.platform().container(SpongeApiPlatform.Component.IMPLEMENTATION).metadata()
        val api = Sponge.platform().container(SpongeApiPlatform.Component.API).metadata().version()
        val brand = if (implementation.id() == "spongevanilla") "SpongeVanilla" else if (implementation.id() == "spongeforge") "SpongeForge" else implementation.id()
        "$brand ${Sponge.platform().minecraftVersion().name()} (API $api)"
    }

    override val threading: Threading get() = Threading.MAIN_THREAD

    // A plugin's database through JDBC, and its API called directly by other plugins.
    override val capabilities: Set<Capability> = setOf(Capability.SQL, Capability.SHARED_API)

    override val clock: Clock get() = SystemClock

    /** Sponge's `config/` folder, which holds each plugin's and the family's. */
    public val configRoot: Path = Sponge.configManager().pluginConfig(container).directory().toAbsolutePath().parent

    override val storage: FolderStorage = FolderStorage(configRoot.resolve(id))

    // Only with -Dxena.development=true: Sponge has no development flag of its own.
    override val development: Boolean = java.lang.Boolean.getBoolean("xena.development")

    @Volatile
    private var server: Server? = null

    override val scheduler: TickScheduler = TickScheduler(
        log,
        isServerThread = { server?.onMainThread() ?: (Thread.currentThread() === startupThread) },
        entityPresent = ::entityPresent,
        asyncName = "$displayName async",
    )

    private val bus = SimpleEventBus(log)

    /** The server's events, for the plugin to listen to: see [PlayerJoinEvent] and the others beside it. */
    public val events: EventBus get() = bus

    /** The plugin's permissions: its [co.xenastudios.core.common.permission.PermissionRegistrar], and every check. */
    public val permissions: SpongePermissions = SpongePermissions()

    /** The plugin's commands: its [co.xenastudios.core.common.command.CommandRegistrar]. */
    public val commands: SpongeCommands = SpongeCommands(container, permissions, log)

    /** The players online, for player arguments. */
    public val players: SpongeOnlinePlayers = SpongeOnlinePlayers()

    /** Sends drawn messages to players by reference, and to the console. */
    public val delivery: SpongeDelivery = SpongeDelivery()

    /**
     * The family: `config/xena/`, the board the Xena plugins share and the player cache, which
     * records joins, quits and languages. Made on first use, which is as the plugin starts.
     */
    public val family: SpongeFamily by lazy { SpongeFamily(this) }

    @Volatile
    private var ticks: ScheduledTask? = null

    init {
        listen(object : TypeToken<RegisterCommandEvent<Command.Raw>>() {}) { event -> commands.register(event) }
        listen(object : TypeToken<StartingEngineEvent<Server>>() {}) { event ->
            val engine = event.engine()
            server = engine
            // The scheduler's tick: every server tick, from the first.
            ticks = engine.scheduler().submit(Task.builder().plugin(container).interval(Ticks.of(1)).execute(Runnable { scheduler.tick() }).build())
        }
        listen(object : TypeToken<StartedEngineEvent<Server>>() {}) { _ -> fire(ServerStartedEvent.TYPE) { ServerStartedEvent() } }
        listen(object : TypeToken<StoppingEngineEvent<Server>>() {}) { _ -> fire(ServerStoppingEvent.TYPE) { ServerStoppingEvent() } }
        // After every plugin's own stopping listeners, so a plugin's last saves still run.
        listen(object : TypeToken<StoppingEngineEvent<Server>>() {}, Order.POST) { _ ->
            updates?.let { (checker, subscription) -> subscription.cancel(); checker.close() }
            updates = null
            ticks?.cancel()
            ticks = null
            scheduler.close(wait = 10.seconds)
            server = null
        }
        listen(ServerSideConnectionEvent.Join::class.java) { event ->
            val player = event.player()
            fire(PlayerJoinEvent.TYPE) { PlayerJoinEvent(player.ref, player.name()) }
        }
        SpongeQuits.listen(this) { player -> fire(PlayerQuitEvent.TYPE) { PlayerQuitEvent(player.ref, player.name()) } }
    }

    /**
     * Listens to Sponge's [type] for the plugin, at [order]: registered in code, as every listener
     * of the core's is, so no class needs Sponge's annotations.
     */
    @JvmOverloads
    public fun <E : SpongeEvent> listen(type: Class<E>, order: Order = Order.DEFAULT, listener: (E) -> Unit) {
        Sponge.eventManager().registerListener(
            EventListenerRegistration.builder(type).plugin(container).order(order).listener(EventListener { listener(it) }).build(),
        )
    }

    /**
     * Listens to a generic Sponge event by its full [type], such as `StoppingEngineEvent<Server>`:
     * Sponge refuses a generic event's bare class.
     */
    @JvmOverloads
    public fun <E : SpongeEvent> listen(type: TypeToken<E>, order: Order = Order.DEFAULT, listener: (E) -> Unit) {
        Sponge.eventManager().registerListener(
            EventListenerRegistration.builder(type).plugin(container).order(order).listener(EventListener { listener(it) }).build(),
        )
    }

    /**
     * The client locale a command source reads in, for the admin subcommands' replies
     * ([co.xenastudios.core.common.admin.AdminText.of]): the player's, as the family's cache has
     * it, or `null` for the console.
     */
    public fun locale(source: CommandSource): String? = source.player?.let { family.players.clientLocale(it) }

    /**
     * A file in this plugin's own jar, such as `lang/en.yml`: every Sponge plugin shares one class
     * loader, so a resource found through it by name could be another plugin's. `null` when the
     * jar hasn't it.
     */
    public fun resource(path: String): ByteArray? {
        val type = bootstrap.javaClass
        val loader = type.classLoader
        val classFile = type.name.replace('.', '/') + ".class"
        val own = loader.getResource(classFile)?.toString() ?: return null
        val base = own.removeSuffix(classFile)
        val url = loader.getResources(path).toList().firstOrNull { it.toString() == base + path } ?: return null
        return url.openStream().use { it.readBytes() }
    }

    /**
     * What `/<plugin> debug`'s report says about the server: Java, Sponge and Minecraft, and the
     * plugins installed (not Sponge's own).
     */
    public fun about(): Map<String, String> {
        val own = setOf("minecraft", "spongeapi", "sponge", "spongevanilla", "spongeforge")
        val plugins = Sponge.pluginManager().plugins()
            .map { it.metadata() }
            .filter { it.id() !in own }
            .map { "${it.id()} ${it.version()}" }
            .sorted()
        return linkedMapOf(
            "Java" to "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})",
            "Server" to name,
            "Plugins" to "${plugins.size}: ${plugins.joinToString(", ")}",
        )
    }

    /**
     * Fetches a JDBC driver the server doesn't have, for [co.xenastudios.core.jvm.db.Drivers]:
     * the drivers this plugin's build pinned (`META-INF/xena/on-demand-libraries.txt`), downloaded
     * into `config/xena/libraries/` and checked against the pin each time, as on Fabric. `null`
     * when the jar pins none.
     */
    public fun drivers(): DriverFetcher? {
        val mirror = System.getProperty("xena.libraries.mirror")?.takeIf { it.isNotBlank() }
        val cache = LibraryCache(
            configRoot.resolve(SpongeFamily.FOLDER).resolve("libraries"),
            mirror?.split(',')?.map { it.trim() } ?: LibraryCache.DEFAULT_MIRRORS,
            "Set a Maven mirror with -Dxena.libraries.mirror=<url>, or put the jar in config/xena/libraries/ by hand.",
        )
        val libraries = try {
            OnDemandLibraries.read(bootstrap.javaClass.classLoader, cache)
        } catch (e: java.io.IOException) {
            log.warn("Couldn't read ${OnDemandLibraries.RESOURCE} from the plugin's jar, so no database driver can be downloaded", e)
            return null
        }
        return if (libraries.pinned().isEmpty()) null else OnDemandDriverFetcher(libraries)
    }

    @Volatile
    private var updates: Pair<UpdateChecker, Subscription>? = null

    /**
     * Starts the update checker for the plugin's Modrinth [project] (ROADMAP.md, Updates), as on
     * Fabric: once now and then once a day, off the server thread, for the newest release on
     * [settings]' channel for Sponge and this Minecraft version; said in the console and to players
     * with [permission] as they join, each in their language from [translations]. Call the
     * checker's `apply` on every reload; it stops with the server.
     *
     * bStats has a Sponge platform, but each plugin needs a service of its own for it on
     * bstats.org, which only the owner can make, so Sponge has no `metrics` yet.
     */
    public fun checkUpdates(
        project: String,
        permission: Permission,
        settings: UpdateSettings,
        translations: () -> Translations,
    ): UpdateChecker {
        val minecraft = Sponge.platform().minecraftVersion().name()
        val node = permission.node
        val notices = object : UpdateNotices {
            override fun send(viewer: PlayerRef?, message: MessageKey, placeholders: Map<String, Placeholder>) {
                scheduler.run(Where.Global) {
                    val messages = translations()
                    val language = messages.languageFor(viewer?.let { family.players.clientLocale(it) })
                    messages.render(message, language, placeholders)?.let { delivery.deliver(viewer, AdventureRenderer.draw(it)) }
                }
            }

            override fun mayKnow(player: PlayerRef): Boolean =
                runningServer()?.player(player)?.let { permissions.has(it, node) } == true
        }
        val checker = UpdateChecker(this, displayName, version, ModrinthReleases(project, "sponge", minecraft, version, JvmHttp()), notices)
        val joins = bus.subscribe(PlayerJoinEvent.TYPE) { event -> checker.joined(event.player) }
        updates?.let { (old, subscription) -> subscription.cancel(); old.close() }
        updates = checker to joins
        checker.apply(settings)
        return checker
    }

    // An event no one listens to isn't even made.
    private inline fun <E : Event> fire(type: EventType<E>, make: () -> E) {
        if (bus.hasListeners(type)) bus.fire(type, make())
    }

    private fun entityPresent(entity: Uuid): Boolean {
        val running = server ?: return false
        val uuid = entity.toJavaUuid()
        if (running.player(uuid).isPresent) return true
        return running.worldManager().worlds().any { it.entity(uuid).isPresent }
    }
}
