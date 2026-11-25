package co.xenastudios.core.fabric

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.common.chat.ChatBridge
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.Subscription
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.common.update.ModrinthReleases
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateNotices
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.core.jvm.update.JvmHttp
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.platform.Capability
import co.xenastudios.core.common.platform.Clock
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.SystemClock
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.jvm.db.DriverFetcher
import co.xenastudios.core.jvm.db.OnDemandDriverFetcher
import co.xenastudios.core.jvm.platform.FolderStorage
import co.xenastudios.core.loader.OnDemandLibraries
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * The core's [Platform] on a Fabric server, made once by each plugin as its mod starts
 * (`onInitialize`), before there's a server: everything it needs from the server it reaches
 * through [server] once the server is starting, and work scheduled before then waits for the
 * first tick.
 *
 * Everything here is Fabric API's callbacks and Minecraft's own classes: no mixins, so it can't
 * clash with another mod's and needs nothing changed per Minecraft version but a few names
 * (ROADMAP.md, Fast, robust, light).
 *
 * - **Scheduler:** [TickScheduler], driven by the start of every server tick, and a small async
 *   pool. Fabric has one server thread, so global, place and entity work all run there.
 * - **Events:** players joining and leaving and the server starting and stopping, from Fabric
 *   API, on the core's bus [events].
 * - **Commands and permissions:** [commands] on Brigadier, [permissions] through
 *   fabric-permissions-api with ops as the fallback.
 * - **Files:** `config/<modId>/`, Fabric's place for a mod's settings, as core-jvm's [FolderStorage].
 * - **Console:** SLF4J, under the plugin's [displayName].
 *
 * One server per process, as a dedicated server is: once the server has stopped, the scheduler
 * is closed for good. A singleplayer world opened twice in one game isn't supported.
 */
public class FabricPlatform(
    /** The mod's id, as in `fabric.mod.json`: its folder is `config/<modId>/`. */
    public val modId: String,
    /** The plugin's name, for its console lines and thread names: `xSample`. */
    public val displayName: String,
) : Platform {

    @Volatile
    private var running: MinecraftServer? = null

    // Before the server starts, the thread that made the platform (Fabric's main thread, while
    // mods load) is the one that may touch the plugin's state, as the server thread is later.
    private val startupThread = Thread.currentThread()

    override val log: Log = Slf4jLog(displayName)

    // The YAML parser and the other libraries the mod downloads rather than carries, before
    // anything here reads a file with them.
    init {
        FabricLibraries.load(modId, log)
    }

    override val name: String = run {
        val loader = if (FabricLoader.getInstance().isModLoaded("quilt_loader")) "Quilt" else "Fabric"
        val minecraft = FabricLoader.getInstance().getModContainer("minecraft").map { it.metadata.version.friendlyString }.orElse("?")
        "$loader $minecraft"
    }

    override val threading: Threading get() = Threading.MAIN_THREAD

    // A plugin's database through JDBC, and its API called directly by other mods.
    override val capabilities: Set<Capability> = setOf(Capability.SQL, Capability.SHARED_API)

    override val clock: Clock get() = SystemClock

    override val storage: FolderStorage = FolderStorage(FabricLoader.getInstance().configDir.resolve(modId))

    // Under Loom's runServer, or with -Dxena.development=true on a real server.
    override val development: Boolean =
        FabricLoader.getInstance().isDevelopmentEnvironment || java.lang.Boolean.getBoolean("xena.development")

    override val scheduler: TickScheduler = TickScheduler(
        log,
        isServerThread = { running?.isSameThread ?: (Thread.currentThread() === startupThread) },
        entityPresent = ::entityPresent,
        asyncName = "$displayName async",
    )

    private val bus = SimpleEventBus(log)

    /** The server's events, for the plugin to listen to: see [PlayerJoinEvent] and the others beside it. */
    public val events: EventBus get() = bus

    /** The plugin's permissions: its [co.xenastudios.core.common.permission.PermissionRegistrar], and every check. */
    public val permissions: FabricPermissions = FabricPermissions()

    /** The plugin's commands: its [co.xenastudios.core.common.command.CommandRegistrar]. */
    public val commands: FabricCommands = FabricCommands({ running }, permissions)

    /** The players online, for player arguments. */
    public val players: FabricOnlinePlayers = FabricOnlinePlayers { running }

    /** The server, once it's starting; `null` before and after. */
    public val server: MinecraftServer? get() = running

    /**
     * The family: `config/xena/`, the board the Xena mods share and the player cache, which
     * records joins, quits and languages. Made on first use, which is as the mod starts.
     */
    public val family: FabricFamily by lazy { FabricFamily(this) }

    /**
     * Starts hearing chat for the plugin at [priority] among other mods, through Fabric API's
     * message events (DECISIONS.md, "The chat bridge"); nothing listens until this is called.
     * [ChatBridge.close] stops it, and it stops for good with the server.
     */
    @JvmOverloads
    public fun chat(priority: EventPriority = EventPriority.NORMAL): ChatBridge = FabricChat({ running }, priority, log)

    /** Sends drawn messages to players by reference, and to the console. */
    public val delivery: FabricDelivery = FabricDelivery { running }

    /**
     * The client locale a command source reads in, for the admin subcommands' replies
     * ([co.xenastudios.core.common.admin.AdminText.of]): the player's, as the family's cache has
     * it, or `null` for the console.
     */
    public fun locale(source: CommandSource): String? = source.player?.let { family.players.clientLocale(it) }

    /**
     * What `/<plugin> debug`'s report says about the server: Java, the loader and Minecraft, and
     * the mods installed (not the parts of Fabric API, or the libraries mods carry inside them).
     */
    public fun about(): Map<String, String> {
        val loader = FabricLoader.getInstance()
        val mods = loader.allMods
            .filter { !it.containingMod.isPresent && it.metadata.type != "builtin" }
            .map { "${it.metadata.id} ${it.metadata.version.friendlyString}" }
            .sorted()
        val loaderVersion = loader.getModContainer(if (loader.isModLoaded("quilt_loader")) "quilt_loader" else "fabricloader")
            .map { it.metadata.version.friendlyString }.orElse("?")
        return linkedMapOf(
            "Java" to "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})",
            "Server" to "$name, loader $loaderVersion",
            "Mods" to "${mods.size}: ${mods.joinToString(", ")}",
        )
    }

    /**
     * Fetches a JDBC driver the server doesn't have, for [co.xenastudios.core.jvm.db.Drivers]:
     * the drivers [modJar]'s build pinned (`META-INF/xena/on-demand-libraries.txt`), downloaded
     * into `config/xena/libraries/` and checked against the pin each time, as the Bukkit loader
     * does in `plugins/Xena/libraries/`. Fabric has no JDBC driver of its own, so every database
     * needs one; `null` when the jar pins none.
     */
    public fun drivers(modJar: ClassLoader): DriverFetcher? {
        // -Dxena.libraries.mirror, as on Bukkit (FabricLibraries); a file for it (Bukkit's
        // libraries.yml) can come when an owner needs one, since a mod's server can usually take a JVM flag.
        val cache = FabricLibraries.cache()
        val libraries = try {
            OnDemandLibraries.read(modJar, cache)
        } catch (e: java.io.IOException) {
            log.warn("Couldn't read ${OnDemandLibraries.RESOURCE} from the mod's jar, so no database driver can be downloaded", e)
            return null
        }
        return if (libraries.pinned().isEmpty()) null else OnDemandDriverFetcher(libraries)
    }

    @Volatile
    private var updates: Pair<UpdateChecker, Subscription>? = null

    /**
     * Starts the update checker for the mod's Modrinth [project] (ROADMAP.md, Updates), as
     * platform-bukkit's `checkUpdates` does: once now and then once a day, off the server
     * thread, for the newest release on [settings]' channel for this Minecraft line; said in the
     * console (once the server runs) and to players with [permission] as they join, each in
     * their language from [translations]. The running version is the mod's own, from its
     * `fabric.mod.json`. Call the checker's `apply` on every reload; it stops with the server.
     *
     * bStats has no Fabric platform (bstats.org lists Bukkit, BungeeCord, Sponge and Velocity,
     * and its base library needs a platform id the site knows), so Fabric has no `metrics`.
     */
    public fun checkUpdates(
        project: String,
        permission: Permission,
        settings: UpdateSettings,
        translations: () -> Translations,
    ): UpdateChecker {
        val loader = FabricLoader.getInstance()
        val current = loader.getModContainer(modId).map { it.metadata.version.friendlyString }.orElse("0")
        val minecraft = loader.getModContainer("minecraft").map { it.metadata.version.friendlyString }.orElse("?")
        val node = permission.node
        val notices = object : UpdateNotices {
            override fun send(viewer: PlayerRef?, message: MessageKey, placeholders: Map<String, Placeholder>) {
                // Minecraft sends from the server thread; a check that finishes before the server
                // starts waits for its first tick.
                scheduler.run(Where.Global) {
                    val messages = translations()
                    val language = messages.languageFor(viewer?.let { family.players.clientLocale(it) })
                    messages.render(message, language, placeholders)?.let { delivery.deliver(viewer, MinecraftRenderer.draw(it)) }
                }
            }

            override fun mayKnow(player: PlayerRef): Boolean =
                running?.player(player)?.let { permissions.has(it, node) } == true
        }
        val checker = UpdateChecker(this, displayName, current, ModrinthReleases(project, "fabric", minecraft, current, JvmHttp()), notices)
        val joins = bus.subscribe(PlayerJoinEvent.TYPE) { event -> checker.joined(event.player) }
        updates?.let { (old, subscription) -> subscription.cancel(); old.close() }
        updates = checker to joins
        checker.apply(settings)
        return checker
    }

    init {
        ServerLifecycleEvents.SERVER_STARTING.register { server -> running = server }
        ServerLifecycleEvents.SERVER_STARTED.register { _ -> fire(ServerStartedEvent.TYPE) { ServerStartedEvent() } }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ -> fire(ServerStoppingEvent.TYPE) { ServerStoppingEvent() } }
        ServerLifecycleEvents.SERVER_STOPPED.register { _ ->
            updates?.let { (checker, subscription) -> subscription.cancel(); checker.close() }
            updates = null
            // After every plugin's own SERVER_STOPPING, so a plugin's last saves still run.
            scheduler.close(wait = 10.seconds)
            running = null
        }
        ServerTickEvents.START_SERVER_TICK.register { _ -> scheduler.tick() }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            fire(PlayerJoinEvent.TYPE) { PlayerJoinEvent(player.ref, player.scoreboardName) }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val player = handler.player
            fire(PlayerQuitEvent.TYPE) { PlayerQuitEvent(player.ref, player.scoreboardName) }
        }
    }

    // An event no one listens to isn't even made.
    private inline fun <E : Event> fire(type: EventType<E>, make: () -> E) {
        if (bus.hasListeners(type)) bus.fire(type, make())
    }

    private fun entityPresent(entity: Uuid): Boolean {
        val server = running ?: return false
        val uuid = entity.toJavaUuid()
        if (server.playerList.getPlayer(uuid) != null) return true
        return server.allLevels.any { it.getEntity(uuid) != null }
    }
}
