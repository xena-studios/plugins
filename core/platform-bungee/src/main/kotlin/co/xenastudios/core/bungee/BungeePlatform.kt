package co.xenastudios.core.bungee

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.common.admin.Dependency
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.platform.Capability
import co.xenastudios.core.common.platform.Clock
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.SystemClock
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.proxy.ProxyChannel
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.db.DriverFetcher
import co.xenastudios.core.jvm.db.OnDemandDriverFetcher
import co.xenastudios.core.jvm.platform.FolderStorage
import co.xenastudios.core.jvm.platform.JulLog
import co.xenastudios.core.jvm.scheduler.ProxyScheduler
import co.xenastudios.core.loader.bungee.BungeeBootstrap
import net.md_5.bungee.api.ProxyServer
import kotlin.concurrent.Volatile
import kotlin.uuid.toJavaUuid

/**
 * The core's [Platform] on BungeeCord and Waterfall, a plugin's proxy side: made once in the
 * plugin's `enable` from the loader's [BungeeBootstrap]. Java 8 bytecode, as the core's, though
 * BungeeCord itself now needs Java 17.
 *
 * - **Threads** ([Threading.PROXY]): BungeeCord runs events and commands on its network threads
 *   and tasks on a pool, so the plugin gets one thread of its own for global and player work and
 *   a pool for async ([ProxyScheduler]); commands run on the plugin's thread too.
 * - **Commands** as BungeeCord commands with tab completion ([commands]); **permissions** through
 *   BungeeCord's, which LuckPerms answers, with the plugin's declared defaults applied first
 *   ([BungeePermissions]).
 * - **Text** as BungeeCord's `BaseComponent`s ([text]); each player's language from their
 *   client's settings, in the family's player cache ([record]).
 * - **Files** in `plugins/<Plugin>/`, the family's in `plugins/Xena/`, the family's board held by
 *   every Xena plugin's main object ([BungeeFamily]).
 * - **The network**: [proxyChannel] for the plugin's `xena:<plugin>` channel to its servers, and
 *   server switches, joins and quits on [events].
 */
public class BungeePlatform private constructor(
    /** The plugin's main object. */
    public val plugin: BungeeBootstrap,
    override val development: Boolean,
) : Platform {

    /** The proxy. */
    public val server: ProxyServer = plugin.proxy

    /** The plugin's name, `xSample`. */
    public val displayName: String = plugin.description.name

    /** The plugin's id, its name in lower case: its channel is `xena:<id>`. */
    public val id: String = displayName.lowercase()

    /** `BungeeCord 26.1-R0.1-SNAPSHOT:...`, or `Waterfall ...`. */
    override val name: String = "${server.name} ${server.version}"

    override val threading: Threading get() = Threading.PROXY

    override val capabilities: Set<Capability> = setOf(Capability.SQL, Capability.SHARED_API)

    override val log: Log = JulLog(plugin.logger)

    override val clock: Clock get() = SystemClock

    /** `plugins/<Plugin>/`. */
    override val storage: FolderStorage = FolderStorage(plugin.dataFolder.toPath())

    override val scheduler: ProxyScheduler = ProxyScheduler(displayName, log) { uuid -> server.getPlayer(uuid.toJavaUuid()) != null }

    private val bus = SimpleEventBus(log)

    /** The proxy's events: [co.xenastudios.core.common.proxy.ProxyJoinEvent] and the others beside it. */
    public val events: EventBus get() = bus

    /** How messages reach players and the console: BungeeCord's components. */
    public val text: BungeeText = BungeeText(server)

    /** The plugin's permissions: its declared defaults, through BungeeCord's permission checks. */
    public val permissions: BungeePermissions = BungeePermissions(this)

    /** The plugin's commands, with tab completion. */
    public val commands: BungeeCommands = BungeeCommands(this)

    /** The players on the proxy, for the `player` argument. */
    public val players: OnlinePlayers = BungeePlayers(server)

    private val listeners = BungeeListeners(this, bus)

    /** The family: `plugins/Xena/` and the board the Xena plugins on this proxy share. */
    public val family: Family = Family(FolderStorage(familyPath), BungeeFamily.board(this), JvmYaml)

    @Volatile
    private var recording: AutoCloseable? = null

    @Volatile
    private var updates: AutoCloseable? = null

    @Volatile
    private var bStats: AutoCloseable? = null

    private val channels = ArrayList<ProxyChannel>()

    /** Records each join, quit and client language change in [cache] from now until [close], and the players on the proxy now. */
    public fun record(cache: PlayerCache) {
        recording?.close()
        recording = BungeeFamily.record(this, cache)
    }

    /**
     * The plugin's channel to its servers, `xena:<id>`, signed with the network's secret,
     * `plugins/Xena/proxy.secret`, made on the first start for the owner to copy to each server:
     * BungeeCord's forwarding has no secret to share. Messages players send on it are dropped,
     * and so are the servers' on their way to a player. Closed by [close].
     */
    @JvmOverloads
    public fun proxyChannel(id: String = this.id): ProxyChannel =
        BungeeChannel.open(this, ProxyChannel.nameOf(id)).also { synchronized(channels) { channels += it } }

    /** Starts the update checker for the plugin's Modrinth [project], for BungeeCord's loader, as the other platforms do. */
    public fun checkUpdates(project: String, permission: Permission, settings: UpdateSettings, translations: () -> Translations): UpdateChecker {
        val (checker, stop) = BungeeUpdates.start(this, project, permission.node, settings, translations)
        updates?.close()
        updates = stop
        return checker
    }

    /** Turns bStats on under [serviceId], with bstats-bungeecord carried relocated in the plugin's jar. Never with `-Dxena.offline=true`. */
    public fun metrics(serviceId: Int) {
        bStats?.close()
        bStats = BungeeUpdates.metrics(this, serviceId)
    }

    /** The locale [player]'s client sent (`de_de`), once it has. */
    public fun locale(player: PlayerRef): String? = server.getPlayer(player.javaUuid)?.locale?.let(BungeeFamily::localeTag)

    /** The locale of whoever ran a command: a player's client's, `null` for the console. */
    public fun locale(source: CommandSource): String? = source.player?.let(::locale)

    /** Whether the plugin called [name] is installed, for `/<plugin> debug`. */
    @JvmOverloads
    public fun dependency(name: String, required: Boolean = false): Dependency = Dependency(name, server.pluginManager.getPlugin(name) != null, required)

    /** The debug report's lines about the proxy. */
    public fun about(): Map<String, String> = linkedMapOf(
        "Java" to "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})",
        "Proxy" to "${server.name} ${server.version}",
        "Servers" to server.servers.values.joinToString(", ") { "${it.name} (${it.players.size} on)" },
        "Plugins" to server.pluginManager.plugins.joinToString(", ") { "${it.description.name} ${it.description.version}" },
    )

    /** Fetches the JDBC drivers the plugin's jar pins into the family's library cache, for `Drivers`. */
    public fun driverFetcher(): DriverFetcher = OnDemandDriverFetcher(plugin.onDemandLibraries())

    /** Undoes what was registered here, as the plugin is disabled, then stops its threads. */
    public fun close() {
        updates?.close()
        updates = null
        bStats?.close()
        bStats = null
        synchronized(channels) {
            channels.forEach { it.close() }
            channels.clear()
        }
        commands.clear()
        recording?.close()
        recording = null
        listeners.close()
        permissions.close()
        scheduler.close()
    }

    internal val familyPath get() = plugin.dataFolder.toPath().toAbsolutePath().parent.resolve(FAMILY_FOLDER)

    init {
        listeners.start()
        permissions.start()
    }

    public companion object {
        private const val FAMILY_FOLDER = "Xena"

        /** The platform for [plugin]. A [development] build checks threads (`checkThread`). */
        @JvmStatic
        @JvmOverloads
        public fun create(plugin: BungeeBootstrap, development: Boolean = false): BungeePlatform = BungeePlatform(plugin, development)
    }
}
