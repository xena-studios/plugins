package co.xenastudios.core.velocity

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
import co.xenastudios.core.jvm.platform.Slf4jLog
import co.xenastudios.core.jvm.scheduler.ProxyScheduler
import co.xenastudios.core.loader.velocity.VelocityBootstrap
import com.velocitypowered.api.plugin.PluginContainer
import com.velocitypowered.api.proxy.ProxyServer
import kotlin.concurrent.Volatile
import kotlin.uuid.toJavaUuid

/**
 * The core's [Platform] on Velocity (3.4 on, and 4.x), a plugin's proxy side: made once as the
 * plugin's entry point starts, from the loader's [VelocityBootstrap], which Velocity made and
 * filled in. Velocity is one kind of server, so nothing needs picking at startup but its version.
 *
 * - **Threads** ([Threading.PROXY]): Velocity has no main thread, and runs events and commands
 *   on its own pools, so the plugin gets one thread of its own for global and player work and a
 *   pool for async ([ProxyScheduler]); commands run on the plugin's thread too.
 * - **Commands** on Velocity's Brigadier, with our suggestions and only what a player may use
 *   ([commands]); **permissions** through Velocity's permission API, which LuckPerms answers,
 *   with the plugin's declared defaults where it says nothing ([permissions]).
 * - **Text** through Adventure, Velocity's own ([text]); each player's language from their
 *   client's settings, in the family's player cache ([record]).
 * - **Files** in `plugins/<plugin id>/`, the family's in `plugins/Xena/`, and the family's board
 *   held by every Xena plugin's main object ([VelocityFamily]).
 * - **The network**: [proxyChannel] for each plugin's `xena:<plugin>` channel to its servers,
 *   and the server switches, joins and quits on [events].
 */
public class VelocityPlatform private constructor(
    /** The plugin's main object, Velocity's view of it. */
    public val bootstrap: VelocityBootstrap,
    override val development: Boolean,
) : Platform {

    /** The proxy. */
    public val server: ProxyServer = bootstrap.server()

    /** The plugin, as Velocity knows it: its id, name and version. */
    public val container: PluginContainer = server.pluginManager.fromInstance(bootstrap)
        .orElseThrow { IllegalStateException("Velocity doesn't know ${bootstrap.javaClass.name} as a plugin") }

    /** The plugin's id, `xsample`: its folder's name, and its channel's. */
    public val id: String = container.description.id

    /** The plugin's name, `xSample`. */
    public val displayName: String = container.description.name.orElse(id)

    override val name: String = server.version.let { "${it.name} ${it.version}" }

    override val threading: Threading get() = Threading.PROXY

    // JDBC for a plugin's database, and its API called directly by other plugins.
    override val capabilities: Set<Capability> = setOf(Capability.SQL, Capability.SHARED_API)

    override val log: Log = Slf4jLog(bootstrap.logger())

    override val clock: Clock get() = SystemClock

    /** `plugins/<plugin id>/`. */
    override val storage: FolderStorage = FolderStorage(bootstrap.dataFolder())

    override val scheduler: ProxyScheduler = ProxyScheduler(displayName, log) { uuid -> server.getPlayer(uuid.toJavaUuid()).isPresent }

    private val bus = SimpleEventBus(log)

    /** The proxy's events, for the plugin to listen to: [co.xenastudios.core.common.proxy.ProxyJoinEvent] and the others beside it. */
    public val events: EventBus get() = bus

    /** How messages reach players and the console: Adventure, Velocity's own. */
    public val text: VelocityText = VelocityText(server)

    /** The plugin's permissions: declared defaults around Velocity's permission API. */
    public val permissions: VelocityPermissions = VelocityPermissions()

    /** The plugin's commands, on Velocity's Brigadier. */
    public val commands: VelocityCommands = VelocityCommands(bootstrap, server, text, permissions, scheduler, log, id)

    /** The players on the proxy, for the `player` argument. */
    public val players: OnlinePlayers = VelocityPlayers(server)

    private val listeners = VelocityListeners(this, bus)

    /**
     * The family: `plugins/Xena/` and the board the Xena plugins on this proxy share. Load it off
     * the proxy's threads ([Family.load]).
     */
    public val family: Family = Family(FolderStorage(familyFolder()), VelocityFamily.board(this), JvmYaml)

    @Volatile
    private var recording: AutoCloseable? = null

    @Volatile
    private var updates: AutoCloseable? = null

    @Volatile
    private var bStats: AutoCloseable? = null

    private val channels = ArrayList<ProxyChannel>()

    /**
     * Records each join, quit and client language change in [cache], the family's player cache,
     * from now until [close], and the players on the proxy now.
     */
    public fun record(cache: PlayerCache) {
        recording?.close()
        recording = VelocityFamily.record(this, cache)
    }

    /**
     * The plugin's channel to its servers, `xena:<id>` (`xena:zchat`), signed with the network's
     * secret ([co.xenastudios.core.jvm.proxy.ProxySecret]): `plugins/Xena/proxy.secret`, else
     * Velocity's modern forwarding secret, else a new one written to `plugins/Xena/proxy.secret`
     * for the owner to copy to each server. Messages players send on it are dropped, and so are
     * the servers' on their way to a player. Closed by [close].
     */
    @JvmOverloads
    public fun proxyChannel(id: String = this.id): ProxyChannel =
        VelocityChannel.open(this, ProxyChannel.nameOf(id)).also { synchronized(channels) { channels += it } }

    /**
     * Starts the update checker for the plugin's Modrinth [project] (ROADMAP.md, Updates), as the
     * other platforms' `checkUpdates` do: asked now and then once a day, off the proxy's threads,
     * for the newest release on [settings]' channel for Velocity; said in the console and to
     * players with [permission] as they join, each in their language. Call the checker's `apply`
     * on every reload; [close] stops it.
     */
    public fun checkUpdates(project: String, permission: Permission, settings: UpdateSettings, translations: () -> Translations): UpdateChecker {
        val (checker, stop) = VelocityUpdates.start(this, project, permission.node, settings, translations)
        updates?.close()
        updates = stop
        return checker
    }

    /**
     * Turns bStats on under the plugin's bStats [serviceId], with bstats-velocity, which the
     * plugin's jar carries relocated; `plugins/bStats/config.txt` is the owner's switch for every
     * plugin. Never with `-Dxena.offline=true`. [close] shuts it down.
     */
    public fun metrics(serviceId: Int) {
        bStats?.close()
        bStats = VelocityUpdates.metrics(this, serviceId)
    }

    /** The locale [player]'s client sent (`de_de`), if they're on the proxy and it has said. */
    public fun locale(player: PlayerRef): String? =
        server.getPlayer(player.javaUuid).orElse(null)?.effectiveLocale?.let(VelocityFamily::localeTag)

    /** The locale of whoever ran a command: a player's client's, `null` for the console. For `AdminText.of`. */
    public fun locale(source: CommandSource): String? = source.player?.let(::locale)

    /** Whether the plugin with this Velocity id is installed, for `/<plugin> debug`'s dependencies check. */
    @JvmOverloads
    public fun dependency(id: String, required: Boolean = false): Dependency = Dependency(id, server.pluginManager.isLoaded(id), required)

    /** The debug report's lines about the proxy: Java, Velocity's version, its servers and its plugins. */
    public fun about(): Map<String, String> = linkedMapOf(
        "Java" to "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})",
        "Proxy" to "${server.version.name} ${server.version.version} (${server.version.vendor})",
        "Servers" to server.allServers.joinToString(", ") { "${it.serverInfo.name} (${it.playersConnected.size} on)" },
        "Plugins" to server.pluginManager.plugins.joinToString(", ") { "${it.description.id} ${it.description.version.orElse("?")}" },
    )

    /**
     * Fetches the JDBC drivers the plugin's jar pins into the family's library cache, for
     * `Drivers`: Velocity ships none. Reads the loader's settings, so call it at startup or off
     * the proxy's threads.
     */
    public fun driverFetcher(): DriverFetcher = OnDemandDriverFetcher(bootstrap.onDemandLibraries())

    /**
     * Undoes what was registered here, as the proxy stops: the plugin's channels, commands,
     * listeners and update checker, then its threads, which get five seconds to finish. Disable
     * the plugin's modules first, which may still schedule work.
     */
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
        scheduler.close()
    }

    private fun familyFolder() = bootstrap.dataFolder().toAbsolutePath().parent.resolve(FAMILY_FOLDER)

    internal val familyPath get() = familyFolder()

    init {
        listeners.start()
    }

    public companion object {
        // The family's folder, beside each plugin's own.
        private const val FAMILY_FOLDER = "Xena"

        /**
         * The platform for the plugin [bootstrap] is the main object of. A [development] build
         * checks threads (`checkThread`) and throws where code is on the wrong one; release builds
         * don't.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(bootstrap: VelocityBootstrap, development: Boolean = false): VelocityPlatform = VelocityPlatform(bootstrap, development)
    }
}
