package co.xenastudios.xutilities.bukkit

import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.bukkit.toBukkit
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.xutilities.JvmFiles
import co.xenastudios.xutilities.XUtilitiesPlugin
import co.xenastudios.xutilities.api.XUtilitiesApi
import co.xenastudios.xutilities.config.XUtilitiesConfig
import co.xenastudios.xutilities.api.bukkit.BukkitBackRecordEvent
import co.xenastudios.xutilities.api.bukkit.BukkitBackTeleportEvent
import co.xenastudios.xutilities.api.event.BackRecordEvent
import co.xenastudios.xutilities.api.event.BackTeleportEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/**
 * xUtilities on Paper, Folia, Spigot and CraftBukkit, 1.8.8 on: the shared plugin on
 * platform-bukkit, loaded by the core's Java shim once the stdlib is there. It wires the shared
 * code to the server and nothing more:
 *
 * - `config.yml` is read here, as the plugin enables, so its commands are registered with the
 *   server's own; the family's settings and `lang/` are read off the server thread after.
 * - Joins, quits and each client's language go into the family's player cache, which messages
 *   read a player's language from.
 * - `/back`'s events are fired as Bukkit events too, and the API is in the services manager.
 */
public class XUtilitiesBukkit(private val plugin: JavaPlugin) : PluginEntry {

    private var platform: BukkitPlatform? = null
    private var xutilities: XUtilitiesPlugin? = null

    override fun enable() {
        val started = System.nanoTime()
        val platform = BukkitPlatform.create(plugin, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XUtilitiesPlugin.PLUGIN_ID)
        platform.record(cache)
        val server = BukkitXServer(plugin, platform)
        val files = JvmFiles(plugin.dataFolder.toPath(), family, LangFolder.shipped(plugin.javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val xutilities = XUtilitiesPlugin(
            platform, server, platform.players, files, cache,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            // -Dxena.debug.paste=false writes debug reports to the plugin's folder instead (boot tests do).
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            version = plugin.description.version,
        )
        this.platform = platform
        this.xutilities = xutilities
        platform.commands.feedback = xutilities.texts.feedback
        bridge(xutilities)
        plugin.server.servicesManager.register(XUtilitiesApi::class.java, xutilities, plugin, ServicePriority.Normal)

        // The config now, so the commands are the server's from the start; the rest off the thread.
        val config = files.config()
        // The update checker (once a day, off the threads) and bStats, as 2.x had them.
        xutilities.updates = platform.checkUpdates(XUtilitiesPlugin.PLUGIN_ID, xutilities.admin.updates, XUtilitiesConfig.updates.settings(config.config)) {
            xutilities.texts.translations
        }
        platform.metrics(BSTATS)
        platform.scheduler.now(Where.Global) {
            xutilities.start(config)
            platform.scheduler.run(Where.Async) {
                cache.load()
                val loaded = files.translations(config.config, config.problems)
                platform.scheduler.run(Where.Global) { xutilities.apply(loaded) }
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xUtilities ${xutilities.version} on ${platform.name}: ${platform.threading} threading, text as ${platform.text}, enabled in $millis ms")
    }

    override fun disable() {
        val platform = platform ?: return
        xutilities?.stop()
        plugin.server.servicesManager.unregisterAll(plugin)
        platform.close()
        this.platform = null
        xutilities = null
    }

    private companion object {
        // bstats.org/plugin/bukkit/XenaUtilities/34866, 2.x's.
        const val BSTATS = 34866
    }

    // /back's events as Bukkit events, made only when a Bukkit plugin listens.
    private fun bridge(xutilities: XUtilitiesPlugin) {
        val bukkit = plugin.server
        xutilities.bus.bridge(BackRecordEvent.TYPE) { event ->
            if (BukkitBackRecordEvent.getHandlerList().registeredListeners.isEmpty()) return@bridge
            val player = event.player.online(bukkit) ?: return@bridge
            val location = event.location.toBukkit(bukkit) ?: return@bridge
            bukkit.pluginManager.callEvent(BukkitBackRecordEvent(player, location, event))
        }
        xutilities.bus.bridge(BackTeleportEvent.TYPE) { event ->
            if (BukkitBackTeleportEvent.getHandlerList().registeredListeners.isEmpty()) return@bridge
            val player = event.player.online(bukkit) ?: return@bridge
            val location = event.destination.toBukkit(bukkit) ?: return@bridge
            bukkit.pluginManager.callEvent(BukkitBackTeleportEvent(player, location, event))
        }
    }
}
