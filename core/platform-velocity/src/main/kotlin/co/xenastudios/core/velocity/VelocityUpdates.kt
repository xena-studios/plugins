package co.xenastudios.core.velocity

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
import com.velocitypowered.api.event.EventHandler
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.proxy.ProxyServer
import java.nio.file.Path

/** The update checker and bStats on Velocity, behind [VelocityPlatform.checkUpdates] and [VelocityPlatform.metrics]. */
internal object VelocityUpdates {

    /**
     * The checker for the plugin's Modrinth [project], for Velocity's loader (`velocity`) and every
     * Minecraft version, since one proxy jar runs in front of servers of any; its notices drawn by
     * [translations] in each reader's language, told to players with [permission] as they join.
     */
    fun start(
        platform: VelocityPlatform,
        project: String,
        permission: String,
        settings: UpdateSettings,
        translations: () -> Translations,
    ): Pair<UpdateChecker, AutoCloseable> {
        val server = platform.server
        val current = platform.container.description.version.orElse("0")
        val notices = object : UpdateNotices {
            override fun send(viewer: PlayerRef?, message: MessageKey, placeholders: Map<String, Placeholder>) {
                val target = if (viewer == null) server.consoleCommandSource else server.getPlayer(viewer.javaUuid).orElse(null) ?: return
                val messages = translations()
                messages.render(message, messages.languageFor(viewer?.let(platform::locale)), placeholders)?.let { platform.text.send(target, it) }
            }

            override fun mayKnow(player: PlayerRef): Boolean =
                server.getPlayer(player.javaUuid).orElse(null)?.let { platform.permissions.has(it, permission) } == true
        }
        val checker = UpdateChecker(platform, platform.displayName, current, ModrinthReleases(project, "velocity", null, current, JvmHttp()), notices)
        val joins = EventHandler<PostLoginEvent> { event -> checker.joined(event.player.uniqueId.toPlayerRef()) }
        server.eventManager.register(platform.bootstrap, PostLoginEvent::class.java, Priority.LAST, joins)
        checker.apply(settings)
        return checker to AutoCloseable {
            server.eventManager.unregister(platform.bootstrap, joins)
            checker.close()
        }
    }

    /**
     * bStats for the plugin under [serviceId]: bstats-velocity's `Metrics`, which the plugin's jar
     * carries relocated and which reads `plugins/bStats/config.txt`, the owner's switch for every
     * plugin. Its factory is made for Guice to fill in, so it's made here the way Guice would.
     * `null` with `-Dxena.offline=true`, or when the jar doesn't carry bStats.
     */
    fun metrics(platform: VelocityPlatform, serviceId: Int): AutoCloseable? {
        if (java.lang.Boolean.getBoolean("xena.offline")) return null
        return try {
            BStats(platform, serviceId)
        } catch (e: LinkageError) {
            platform.log.warn("bStats isn't in this plugin's jar, so it doesn't report: shade org.bstats:bstats-velocity and relocate it", e)
            null
        } catch (e: ReflectiveOperationException) {
            platform.log.warn("bStats didn't start: $e", e)
            null
        } catch (e: IllegalStateException) {
            platform.log.warn("bStats didn't start: ${e.message}", e)
            null
        }
    }

    // In a class of its own, so a jar without bStats fails here, where it's caught.
    private class BStats(platform: VelocityPlatform, serviceId: Int) : AutoCloseable {
        private val metrics: org.bstats.velocity.Metrics

        init {
            val factory = org.bstats.velocity.Metrics.Factory::class.java
                .getDeclaredConstructor(ProxyServer::class.java, org.slf4j.Logger::class.java, Path::class.java)
                .apply { isAccessible = true }
                .newInstance(platform.server, platform.bootstrap.logger(), platform.bootstrap.dataFolder())
            metrics = factory.make(platform.bootstrap, serviceId)
        }

        override fun close() {
            metrics.shutdown()
        }
    }
}
