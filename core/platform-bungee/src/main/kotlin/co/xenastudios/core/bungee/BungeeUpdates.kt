package co.xenastudios.core.bungee

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.common.update.ModrinthReleases
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateNotices
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.core.jvm.update.JvmHttp
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.event.EventHandler
import net.md_5.bungee.event.EventPriority

/** The update checker and bStats on BungeeCord, behind [BungeePlatform.checkUpdates] and [BungeePlatform.metrics]. */
internal object BungeeUpdates {

    /** The checker for the plugin's Modrinth [project], for BungeeCord's loader (`bungeecord`) and every Minecraft version. */
    fun start(
        platform: BungeePlatform,
        project: String,
        permission: String,
        settings: UpdateSettings,
        translations: () -> Translations,
    ): Pair<UpdateChecker, AutoCloseable> {
        val server = platform.server
        val current = platform.plugin.description.version
        val notices = object : UpdateNotices {
            override fun send(viewer: PlayerRef?, message: MessageKey, placeholders: Map<String, Placeholder>) {
                val target = if (viewer == null) server.console else server.getPlayer(viewer.javaUuid) ?: return
                val messages = translations()
                messages.render(message, messages.languageFor(viewer?.let(platform::locale)), placeholders)?.let { platform.text.send(target, it) }
            }

            override fun mayKnow(player: PlayerRef): Boolean = server.getPlayer(player.javaUuid)?.hasPermission(permission) == true
        }
        val checker = UpdateChecker(platform, platform.displayName, current, ModrinthReleases(project, "bungeecord", null, current, JvmHttp()), notices)
        val joins = Joins(checker)
        server.pluginManager.registerListener(platform.plugin, joins)
        checker.apply(settings)
        return checker to AutoCloseable {
            server.pluginManager.unregisterListener(joins)
            checker.close()
        }
    }

    class Joins(private val checker: UpdateChecker) : Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        fun onJoin(event: PostLoginEvent) {
            checker.joined(event.player.uniqueId.toPlayerRef())
        }
    }

    /**
     * bStats under [serviceId]: bstats-bungeecord's `Metrics`, carried relocated in the plugin's
     * jar, which reads `plugins/bStats/config.yml`, the owner's switch for every plugin.
     */
    fun metrics(platform: BungeePlatform, serviceId: Int): AutoCloseable? {
        if (java.lang.Boolean.getBoolean("xena.offline")) return null
        return try {
            BStats(platform, serviceId)
        } catch (e: LinkageError) {
            platform.log.warn("bStats isn't in this plugin's jar, so it doesn't report: shade org.bstats:bstats-bungeecord and relocate it", e)
            null
        } catch (e: IllegalStateException) {
            platform.log.warn("bStats didn't start: ${e.message}", e)
            null
        }
    }

    private class BStats(platform: BungeePlatform, serviceId: Int) : AutoCloseable {
        private val metrics = org.bstats.bungeecord.Metrics(platform.plugin, serviceId)

        override fun close() {
            metrics.shutdown()
        }
    }
}
