package co.xenastudios.core.bukkit

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateNotices
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.core.common.update.ModrinthReleases
import co.xenastudios.core.jvm.update.JvmHttp
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin

/**
 * The update checker and bStats on Bukkit, behind [BukkitPlatform.checkUpdates] and
 * [BukkitPlatform.metrics].
 */
internal object BukkitUpdates {

    /**
     * The checker for [plugin]'s Modrinth [project], its notices drawn by [translations] in each
     * reader's language and sent through [text], told to players with [permission] as they join.
     * Returns it and what unregisters its join listener.
     */
    fun start(
        platform: BukkitPlatform,
        project: String,
        permission: String,
        settings: UpdateSettings,
        translations: () -> Translations,
        loader: String,
    ): Pair<UpdateChecker, AutoCloseable> {
        val plugin = platform.plugin
        val server = plugin.server
        val current = plugin.description.version
        val notices = object : UpdateNotices {
            override fun send(viewer: PlayerRef?, message: MessageKey, placeholders: Map<String, Placeholder>) {
                val target = if (viewer == null) server.consoleSender else viewer.online(server) ?: return
                val messages = translations()
                val language = messages.languageFor(viewer?.let(platform::locale))
                messages.render(message, language, placeholders)?.let { platform.text.send(target, it) }
            }

            override fun mayKnow(player: PlayerRef): Boolean = player.online(server)?.hasPermission(permission) == true
        }
        val source = ModrinthReleases(project, loader, platform.server.version.toString(), current, JvmHttp())
        val checker = UpdateChecker(platform, plugin.name, current, source, notices)
        val listener = joins(plugin, checker)
        checker.apply(settings)
        return checker to AutoCloseable {
            HandlerList.unregisterAll(listener)
            checker.close()
        }
    }

    // Told as they join, on their own thread (Folia's region, Bukkit's main), after the join message.
    private fun joins(plugin: Plugin, checker: UpdateChecker): Listener {
        val listener = object : Listener {}
        plugin.server.pluginManager.registerEvent(PlayerJoinEvent::class.java, listener, EventPriority.MONITOR, EventExecutor { _, event ->
            if (event is PlayerJoinEvent) checker.joined(event.player.uniqueId.toPlayerRef())
        }, plugin)
        return listener
    }

    /**
     * bStats for [plugin] under [serviceId]: bstats-bukkit's own `Metrics`, which the plugin's
     * jar carries relocated (bStats refuses to start under its own package, so two plugins never
     * share it), and which reads `plugins/bStats/config.yml`, the owner's switch for every
     * plugin at once, itself. `null` when the tests' `-Dxena.offline=true` is set, or when the
     * jar doesn't carry bStats (a warning says so).
     */
    fun metrics(platform: BukkitPlatform, serviceId: Int): AutoCloseable? {
        if (java.lang.Boolean.getBoolean("xena.offline")) return null
        return try {
            BStats(platform.plugin, serviceId)
        } catch (e: LinkageError) {
            platform.log.warn("bStats isn't in this plugin's jar, so it doesn't report: shade org.bstats:bstats-bukkit and relocate it", e)
            null
        } catch (e: IllegalStateException) {
            // Its own check that it was relocated.
            platform.log.warn("bStats didn't start: ${e.message}", e)
            null
        }
    }

    // In a class of its own, so a jar without bStats fails here, where it's caught, and not
    // when BukkitUpdates is loaded.
    private class BStats(plugin: Plugin, serviceId: Int) : AutoCloseable {
        private val metrics = org.bstats.bukkit.Metrics(plugin, serviceId)

        override fun close() {
            metrics.shutdown()
        }
    }
}
