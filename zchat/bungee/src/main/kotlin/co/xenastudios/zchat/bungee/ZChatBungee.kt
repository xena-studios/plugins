package co.xenastudios.zchat.bungee

import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.core.loader.bungee.BungeeBootstrap
import co.xenastudios.core.bungee.BungeePlatform
import co.xenastudios.zchat.JvmFiles
import co.xenastudios.zchat.ZChatMessages
import co.xenastudios.zchat.ZChatPlugin
import co.xenastudios.zchat.network.ZChatProxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * zChat on BungeeCord: the relay that carries chat and private messages between the network's servers
 * ([ZChatProxy]), on platform-bungee, loaded by the core's Java shim once the stdlib is there. The
 * family's player cache records the proxy's joins, quits and languages, which `/zchatproxy`'s
 * replies are drawn in.
 */
public class ZChatBungee(private val bootstrap: BungeeBootstrap) : PluginEntry {
    private var platform: BungeePlatform? = null
    private var proxy: ZChatProxy? = null

    override fun enable() {
        val started = System.nanoTime()
        val platform = BungeePlatform.create(bootstrap, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, ZChatPlugin.PLUGIN_ID)
        platform.record(cache)
        val shipped = LangFolder.shipped(javaClass.classLoader, JvmFiles.LANGUAGES)
        val load = {
            family.load().error?.let { platform.log.warn(it.message) }
            val look = family.look
            LangFolder(bootstrap.dataFolder.toPath(), ZChatMessages.catalog, shipped).load(look.languages, look.theme).translations
        }
        val proxy = ZChatProxy(
            platform, platform.proxyChannel(ZChatPlugin.PLUGIN_ID), platform.events, cache,
            translations = load,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            version = bootstrap.description.version,
        )
        this.platform = platform
        this.proxy = proxy
        // The command, its feedback and permissions from the plugin's own thread, after enable.
        platform.scheduler.run(Where.Global) {
            platform.commands.feedback = proxy.texts.feedback
            proxy.start()
        }
        platform.scheduler.run(Where.Async) {
            cache.load()
            proxy.apply(load())
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("zChat ${proxy.version} on ${platform.name}: carrying chat between servers on xena:zchat, enabled in $millis ms")
    }

    override fun disable() {
        val platform = platform ?: return
        proxy?.let { proxy ->
            val done = CountDownLatch(1)
            platform.scheduler.run(Where.Global) {
                try {
                    proxy.stop()
                } finally {
                    done.countDown()
                }
            }
            done.await(5, TimeUnit.SECONDS)
        }
        platform.close()
        this.platform = null
        proxy = null
    }
}
