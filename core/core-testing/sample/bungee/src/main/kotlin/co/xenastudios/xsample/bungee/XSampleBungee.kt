package co.xenastudios.xsample.bungee

import co.xenastudios.core.common.admin.AdminText
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.family.JvmFamilyBoard
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.core.loader.bungee.BungeeBootstrap
import co.xenastudios.core.sample.ProxyCheck
import co.xenastudios.core.sample.SampleConfig
import co.xenastudios.core.sample.SampleMessages
import co.xenastudios.core.sample.SampleNetwork
import co.xenastudios.core.sample.SamplePermissions
import co.xenastudios.core.sample.XSample
import co.xenastudios.core.bungee.BungeePlatform
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile

/**
 * xSample on BungeeCord and Waterfall: the shared plugin on [BungeePlatform], loaded by the core's
 * Java shim once the stdlib is there. Its commands are the proxy's here, so a player behind the
 * proxy runs this copy's; the family's player cache records the proxy's joins, quits and
 * languages; and its network side ([SampleNetwork.Proxy]) answers each server's hello on
 * `xena:xsample`. Then it runs [ProxyCheck], which the boot tests read.
 */
public class XSampleBungee(private val bootstrap: BungeeBootstrap) : PluginEntry {

    private var platform: BungeePlatform? = null
    private var sample: XSample? = null

    @Volatile
    private var adminText: AdminText = AdminText.ENGLISH

    @Volatile
    private var translations: Translations = Translations.of(SampleMessages.catalog, emptyMap())

    override fun enable() {
        val started = System.nanoTime()
        val platform = BungeePlatform.create(bootstrap, development = true)
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, "xsample")
        platform.record(cache)
        val sample = XSample(
            platform, platform.players,
            lookup = cache.asLookup(),
            registrar = platform.commands,
            about = platform::about,
            dependencies = { listOf(platform.dependency("LuckPerms")) },
            adminText = AdminText { source, message, placeholders -> adminText.render(source, message, placeholders) },
        )
        this.platform = platform
        this.sample = sample
        platform.permissions.sync(SamplePermissions.permissions + sample.admin.permissions.permissions)
        // Modules and commands on the plugin's own thread, where its state lives.
        platform.scheduler.run(Where.Global) { sample.enable() }
        platform.scheduler.run(Where.Async) {
            family.load().error?.let { platform.log.warn(it.message) }
            cache.load()
            val shipped = LangFolder.shipped(javaClass.classLoader, listOf(LanguageTag.of("de")))
            val look = family.look
            val translations = LangFolder(bootstrap.dataFolder.toPath(), SampleMessages.catalog, shipped).load(look.languages, look.theme).translations
            this.translations = translations
            adminText = AdminText.of(translations, platform::locale)
        }
        SampleNetwork.Proxy(platform.proxyChannel(), platform)
        platform.checkUpdates("xsample", sample.admin.updates, SampleConfig.updates.settings(SampleConfig.defaults)) { translations }
        Integer.getInteger("xsample.bstats")?.let(platform::metrics)
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xSample on ${platform.name}: ${platform.threading} threading, text as ${platform.text}, enabled in $millis ms")
        ProxyCheck(platform, platform.events, family, cache) { bootstrap.get() === (family.board as JvmFamilyBoard).root }.start()
    }

    override fun disable() {
        val platform = platform ?: return
        // Modules turn off on the plugin's thread, which the platform then stops.
        sample?.let { sample ->
            val done = CountDownLatch(1)
            platform.scheduler.run(Where.Global) {
                try {
                    sample.modules.clear()
                } finally {
                    done.countDown()
                }
            }
            done.await(5, TimeUnit.SECONDS)
        }
        platform.close()
        this.platform = null
        sample = null
        platform.log.info("xSample stopped")
    }
}
