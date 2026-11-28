package co.xenastudios.xstats.velocity

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.core.loader.velocity.VelocityBootstrap
import co.xenastudios.core.velocity.VelocityPlatform
import co.xenastudios.xstats.JvmDatabase
import co.xenastudios.xstats.JvmExtras
import co.xenastudios.xstats.JvmFiles
import co.xenastudios.xstats.JvmZones
import co.xenastudios.xstats.StatsServer
import co.xenastudios.xstats.XStatsPlugin
import co.xenastudios.xstats.config.XStatsConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * xStats on Velocity: the network-wide view (decisions/2026-11-28-xstats-2.md). It reads every
 * server's stats from the database they share, read again as each server saves (the change feed's
 * `saved`), and answers network leaderboards, player lookups (`/networkstats`, `/networkleaderboard`)
 * and the website from memory, wherever a player is, even on a server without xStats. It counts
 * nothing itself: the servers do. Its own command is `/xstatsproxy` (`/xsp`), so a player's `/xstats`
 * still reaches their server. The plugin is made on its own thread right after enable, as xBans'
 * is (decisions/2026-11-27-xbans-on-a-proxy-starts-on-its-own-thread.md).
 */
public class XStatsVelocity(private val bootstrap: VelocityBootstrap) : PluginEntry {
    private var platform: VelocityPlatform? = null

    @Volatile
    private var xstats: XStatsPlugin? = null

    override fun enable() {
        val started = System.nanoTime()
        val platform = VelocityPlatform.create(bootstrap, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XStatsPlugin.PLUGIN_ID)
        platform.record(cache)
        this.platform = platform
        platform.scheduler.run(Where.Global, action = Make(platform, cache))
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xStats ${platform.container.description.version.orElse(XStatsPlugin.VERSION)} on ${platform.name}: the network's stats, enabled in $millis ms")
    }

    // A class, not a lambda (decisions/2026-11-25-a-lighter-core.md).
    private inner class Make(private val platform: VelocityPlatform, private val cache: PlayerCache) : (Task) -> Unit {
        override fun invoke(task: Task) {
            xstats = try {
                make(platform, cache)
            } catch (e: Throwable) {
                platform.log.error("xStats couldn't start.", e)
                return
            }
        }
    }

    private fun make(platform: VelocityPlatform, cache: PlayerCache): XStatsPlugin {
        val family = platform.family
        val folder = bootstrap.dataFolder()
        val files = JvmFiles(folder, family, LangFolder.shipped(javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val config = files.config()
        val drivers = Drivers(javaClass.classLoader, platform.driverFetcher())
        val plugins = folder.toAbsolutePath().parent
        val xstats = XStatsPlugin(
            platform, VelocityStatsServer(platform), files, cache, platform.players, cache,
            opener = JvmDatabase(family, folder, plugins.resolve("Xena"), drivers, platform.log),
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            // The website can run here, for the whole network; there's no world to import from.
            extras = JvmExtras(folder, NoWorld, folder, NoNames, javaClass.classLoader),
            zones = JvmZones,
            version = platform.container.description.version.orElse(XStatsPlugin.VERSION),
        )
        platform.commands.feedback = xstats.texts.feedback
        xstats.start(config)
        xstats.open()
        platform.scheduler.run(Where.Async) { _ ->
            cache.load()
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { _ -> xstats.apply(loaded) }
        }
        xstats.updates = platform.checkUpdates(XStatsPlugin.PLUGIN_ID, xstats.admin.updates, XStatsConfig.updates.settings(config.config)) { xstats.texts.translations }
        return xstats
    }

    private object NoWorld : () -> java.nio.file.Path? {
        override fun invoke(): java.nio.file.Path? = null
    }

    private object NoNames : (PlayerRef) -> String? {
        override fun invoke(player: PlayerRef): String? = null
    }

    override fun disable() {
        val platform = platform ?: return
        val done = CountDownLatch(1)
        // On the plugin's thread, so after Make: whatever it made is stopped.
        platform.scheduler.run(Where.Global) { _ ->
            try {
                xstats?.stop()?.get(10, TimeUnit.SECONDS)
            } catch (e: Exception) {
                platform.log.warn("Stopping, xStats' database didn't finish in time: $e")
            } finally {
                done.countDown()
            }
        }
        done.await(15, TimeUnit.SECONDS)
        platform.close()
        this.platform = null
        xstats = null
    }
}

/** xStats' [StatsServer] on Velocity: the network's players. It reads no stats: the servers do. */
internal class VelocityStatsServer(private val platform: VelocityPlatform) : StatsServer {
    private val network = platform.server

    override val proxy: Boolean get() = true

    override fun online(): List<PlayerRef> = network.allPlayers.map { it.uniqueId.toPlayerRef() }

    override fun isOnline(player: PlayerRef): Boolean = network.getPlayer(player.javaUuid).isPresent

    override fun send(player: PlayerRef, message: Component) {
        network.getPlayer(player.javaUuid).ifPresent { it.sendMessage(platform.text.renderer.draw(message)) }
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean =
        network.getPlayer(player.javaUuid).map { platform.permissions.has(it, node) }.orElse(false)
}
