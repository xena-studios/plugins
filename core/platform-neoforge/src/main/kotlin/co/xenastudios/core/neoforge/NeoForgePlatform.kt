package co.xenastudios.core.neoforge

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.api.event.Subscription
import co.xenastudios.core.common.chat.ChatBridge
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
import co.xenastudios.core.jvm.platform.Slf4jLog
import co.xenastudios.core.jvm.update.JvmHttp
import co.xenastudios.core.loader.OnDemandLibraries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.EventPriority as NeoPriority
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import net.neoforged.neoforge.event.server.ServerStartedEvent as NeoServerStarted
import net.neoforged.neoforge.event.server.ServerStoppingEvent as NeoServerStopping

/**
 * The core's [Platform] on a NeoForge server, made once by each plugin as its mod is made (its
 * `@Mod` class's constructor), before there's a server: everything it needs from the server it
 * reaches through [server] once the server is starting, and work scheduled before then waits for
 * the first tick. The twin of platform-fabric's `FabricPlatform`, member for member, so a
 * plugin's NeoForge entry reads as its Fabric one (decisions/2026-11-28-neoforge-and-forge.md).
 *
 * Everything here is NeoForge's events on its game bus and Minecraft's own classes, with no
 * mixins and no coremods, so it can't clash with another mod's.
 *
 * - **Scheduler:** [TickScheduler], driven by the start of every server tick, and a small async
 *   pool. NeoForge has one server thread, so global, place and entity work all run there.
 * - **Events:** players joining and leaving and the server starting and stopping, from
 *   NeoForge's events, on the core's bus [events].
 * - **Commands and permissions:** [commands] on Brigadier, [permissions] through NeoForge's
 *   permission API with ops as the fallback.
 * - **Files:** `config/<modId>/`, NeoForge's place for a mod's settings, as core-jvm's [FolderStorage].
 * - **Console:** SLF4J, under the plugin's [displayName].
 *
 * One server per process, as a dedicated server is: once the server has stopped, the scheduler
 * is closed for good.
 */
public class NeoForgePlatform(
    /** The mod's id, as in `neoforge.mods.toml`: its folder is `config/<modId>/`. */
    public val modId: String,
    /** The plugin's name, for its console lines and thread names: `xSample`. */
    public val displayName: String,
) : Platform {

    @Volatile
    private var running: MinecraftServer? = null

    // Before the server starts, the thread that made the platform (one of NeoForge's mod-loading
    // threads) is the one that may touch the plugin's state, as the server thread is later.
    private val startupThread = Thread.currentThread()

    // A dedicated server's log shows the logger's name, shortened; each line starts with the plugin's.
    override val log: Log = Slf4jLog(org.slf4j.LoggerFactory.getLogger(displayName), "[$displayName] ")

    // The YAML parser and the other libraries the mod downloads rather than carries, before
    // anything here reads a file with them.
    init {
        NeoForgeLibraries.load(log)
    }

    override val name: String = "NeoForge ${version("minecraft")}"

    override val threading: Threading get() = Threading.MAIN_THREAD

    // A plugin's database through JDBC, and its API called directly by other mods.
    override val capabilities: Set<Capability> = setOf(Capability.SQL, Capability.SHARED_API)

    override val clock: Clock get() = SystemClock

    override val storage: FolderStorage = FolderStorage(FMLPaths.CONFIGDIR.get().resolve(modId))

    // Under ModDevGradle's runServer, or with -Dxena.development=true on a real server.
    override val development: Boolean = !production() || java.lang.Boolean.getBoolean("xena.development")

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
    public val permissions: NeoForgePermissions = NeoForgePermissions()

    /** The plugin's commands: its [co.xenastudios.core.common.command.CommandRegistrar]. */
    public val commands: NeoForgeCommands = NeoForgeCommands({ running }, permissions)

    /** The players online, for player arguments. */
    public val players: NeoForgeOnlinePlayers = NeoForgeOnlinePlayers { running }

    /** The server, once it's starting; `null` before and after. */
    public val server: MinecraftServer? get() = running

    /**
     * The family: `config/xena/`, the board the Xena mods share and the player cache, which
     * records joins, quits and languages. Made on first use, which is as the mod starts.
     */
    public val family: NeoForgeFamily by lazy { NeoForgeFamily(this) }

    @Volatile
    private var madeMenus: NeoForgeMenus? = null

    /**
     * The plugin's chest menus ([co.xenastudios.core.common.menu.Menus]), made the first time
     * they're asked for: a mod that never opens one never loads them. Every one still open is
     * closed as the server stops, a deposit's items handed to its handler first.
     */
    public val menus: NeoForgeMenus
        get() = madeMenus ?: synchronized(this) { madeMenus ?: NeoForgeMenus({ running }, log).also { madeMenus = it } }

    /**
     * Starts hearing chat for the plugin at [priority] among other mods, through NeoForge's
     * `ServerChatEvent` (DECISIONS.md, "The chat bridge"); nothing listens until this is called.
     * [ChatBridge.close] stops it, and it stops for good with the server.
     */
    @JvmOverloads
    public fun chat(priority: EventPriority = EventPriority.NORMAL): ChatBridge = NeoForgeChat({ running }, priority, log)

    /** Sends drawn messages to players by reference, and to the console. */
    public val delivery: NeoForgeDelivery = NeoForgeDelivery { running }

    /**
     * The client locale a command source reads in, for the admin subcommands' replies
     * ([co.xenastudios.core.common.admin.AdminText.of]): the player's, as the family's cache has
     * it, or `null` for the console.
     */
    public fun locale(source: CommandSource): String? = source.player?.let { family.players.clientLocale(it) }

    /**
     * What `/<plugin> debug`'s report says about the server: Java, NeoForge and Minecraft, and
     * the mods installed (not the libraries mods carry inside them).
     */
    public fun about(): Map<String, String> {
        val mods = ModList.get().mods
            .filter { it.modId != "minecraft" && it.modId != "neoforge" }
            .map { "${it.modId} ${it.version}" }
            .sorted()
        return linkedMapOf(
            "Java" to "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})",
            "Server" to "$name, NeoForge ${version("neoforge")}",
            "Mods" to "${mods.size}: ${mods.joinToString(", ")}",
        )
    }

    /**
     * Fetches a JDBC driver the server doesn't have, for [co.xenastudios.core.jvm.db.Drivers]:
     * the drivers [modJar]'s build pinned (`META-INF/xena/on-demand-libraries.txt`), downloaded
     * into `config/xena/libraries/` and checked against the pin each time, as on Fabric. NeoForge
     * has no JDBC driver of its own, so every database needs one; `null` when the jar pins none.
     */
    public fun drivers(modJar: ClassLoader): DriverFetcher? {
        val cache = NeoForgeLibraries.cache()
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
     * Starts the update checker for the mod's Modrinth [project] (ROADMAP.md, Updates), as on
     * Fabric: once now and then once a day, off the server thread, for the newest release on
     * [settings]' channel for this Minecraft line and NeoForge; said in the console (once the
     * server runs) and to players with [permission] as they join, each in their language from
     * [translations]. The running version is the mod's own, from its `neoforge.mods.toml`. Call
     * the checker's `apply` on every reload; it stops with the server.
     *
     * bStats has no NeoForge platform (bstats.org lists Bukkit, BungeeCord, Sponge and Velocity),
     * so NeoForge has no `metrics`.
     */
    public fun checkUpdates(
        project: String,
        permission: Permission,
        settings: UpdateSettings,
        translations: () -> Translations,
    ): UpdateChecker {
        val current = version(modId).takeIf { it != "?" } ?: "0"
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
        val releases = ModrinthReleases(project, "neoforge", version("minecraft"), current, JvmHttp())
        val checker = UpdateChecker(this, displayName, current, releases, notices)
        val joins = bus.subscribe(PlayerJoinEvent.TYPE) { event -> checker.joined(event.player) }
        updates?.let { (old, subscription) -> subscription.cancel(); old.close() }
        updates = checker to joins
        checker.apply(settings)
        return checker
    }

    init {
        val game = NeoForge.EVENT_BUS
        game.addListener(NeoPriority.HIGHEST, false, ServerAboutToStartEvent::class.java) { event -> running = event.server }
        game.addListener(NeoPriority.NORMAL, false, NeoServerStarted::class.java) { _ -> fire(ServerStartedEvent.TYPE) { ServerStartedEvent() } }
        game.addListener(NeoPriority.NORMAL, false, NeoServerStopping::class.java) { _ ->
            madeMenus?.closeAll()
            fire(ServerStoppingEvent.TYPE) { ServerStoppingEvent() }
        }
        game.addListener(NeoPriority.LOWEST, false, ServerStoppedEvent::class.java) { _ ->
            updates?.let { (checker, subscription) -> subscription.cancel(); checker.close() }
            updates = null
            // After every plugin's own stopping listener, so a plugin's last saves still run.
            scheduler.close(wait = 10.seconds)
            running = null
        }
        game.addListener(NeoPriority.NORMAL, false, ServerTickEvent.Pre::class.java) { _ -> scheduler.tick() }
        game.addListener(NeoPriority.NORMAL, false, PlayerEvent.PlayerLoggedInEvent::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            fire(PlayerJoinEvent.TYPE) { PlayerJoinEvent(player.ref, player.scoreboardName) }
        }
        game.addListener(NeoPriority.NORMAL, false, PlayerEvent.PlayerLoggedOutEvent::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
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

    private companion object {
        /** A loaded mod's version, `minecraft` and `neoforge` among them; `?` for none. */
        fun version(mod: String): String =
            ModList.get().getModContainerById(mod).map { it.modInfo.version.toString() }.orElse("?")

        /** Whether this is a real server, not ModDevGradle's run. */
        fun production(): Boolean =
            //? if fml_environment_methods {
            net.neoforged.fml.loading.FMLEnvironment.isProduction()
            //?} else
            //net.neoforged.fml.loading.FMLEnvironment.production
    }
}
