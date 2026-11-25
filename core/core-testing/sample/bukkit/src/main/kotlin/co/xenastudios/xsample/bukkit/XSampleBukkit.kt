package co.xenastudios.xsample.bukkit

import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.common.admin.AdminText
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.sample.SampleMessages
import co.xenastudios.core.sample.SampleNetwork
import co.xenastudios.core.sample.SampleConfig
import co.xenastudios.core.sample.SamplePermissions
import co.xenastudios.core.sample.XSample
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.java.JavaPlugin
import kotlin.concurrent.Volatile

/**
 * xSample on Bukkit: the shared plugin on [BukkitPlatform], loaded by the core's Java shim once
 * the stdlib is visible. Everything it does is the shared code's; this only wires it to the
 * server, as each plugin's `bukkit/` adapter will: the family's player cache (joins, quits and
 * languages recorded by the platform, and `/xsample forget` finding players by name), the
 * admin replies in each reader's language, the debug report's server lines and dependencies, and
 * its network side behind a proxy ([SampleNetwork.Server]).
 * Then it runs [PlatformCheck], which the boot tests read.
 */
public class XSampleBukkit(private val plugin: JavaPlugin) : PluginEntry {

    private var platform: BukkitPlatform? = null
    private var sample: XSample? = null

    // English until the lang files are read, off the server thread.
    @Volatile
    private var adminText: AdminText = AdminText.ENGLISH

    // The same, for the update notice.
    @Volatile
    private var translations: Translations = Translations.of(SampleMessages.catalog, emptyMap())

    override fun enable() {
        val started = System.nanoTime()
        // A development build, so code on the wrong thread throws here as it would break on Folia.
        val platform = BukkitPlatform.create(plugin, development = true)
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, "xsample")
        platform.record(cache)
        val sample = XSample(
            platform, platform.players,
            lookup = cache.asLookup(),
            registrar = platform.commands,
            about = platform::about,
            // Plugins a real one would hook into, to show the check: neither is needed.
            dependencies = { listOf(platform.dependency("Vault"), platform.dependency("PlaceholderAPI")) },
            adminText = AdminText { source, message, placeholders -> adminText.render(source, message, placeholders) },
            // The chat format: Paper's AsyncChatEvent, or Bukkit's AsyncPlayerChatEvent, picked as it starts.
            chat = { platform.chat() },
        )
        this.platform = platform
        this.sample = sample
        platform.permissions.sync(SamplePermissions.permissions + sample.admin.permissions.permissions)
        // Modules turn on on the global thread: this one on Bukkit, the next global tick on Folia.
        platform.scheduler.now(Where.Global) { sample.enable() }
        // Files are read off the server's threads: the family's settings, the saved players, the
        // lang files in the family's languages and theme.
        platform.scheduler.run(Where.Async) {
            family.load().error?.let { platform.log.warn(it.message) }
            cache.load()
            val shipped = LangFolder.shipped(plugin.javaClass.classLoader, listOf(LanguageTag.of("de")))
            val look = family.look
            val translations = LangFolder(plugin.dataFolder.toPath(), SampleMessages.catalog, shipped).load(look.languages, look.theme).translations
            this.translations = translations
            adminText = AdminText.of(translations, platform::locale)
        }
        // The update checker, as every plugin has it: xSample isn't on Modrinth, so it finds
        // nothing (GitHub has no xsample tags either), but it asks on every real server it boots
        // on, Java 8 and Spigot 1.8.8 among them. And bStats, carried relocated in the jar, only
        // when -Dxsample.bstats names an id: boot.sh does, with plugins/bStats/config.yml
        // turning bStats off, so it starts and nothing is sent.
        platform.checkUpdates("xsample", sample.admin.updates, SampleConfig.updates.settings(SampleConfig.defaults)) { translations }
        Integer.getInteger("xsample.bstats")?.let(platform::metrics)
        // Its network side: a hello to the proxy, on xena:xsample, as each player joins. Behind no
        // proxy there's no secret and no one to tell, so it does nothing.
        val network = SampleNetwork.Server(platform.proxyChannel(), platform)
        plugin.server.pluginManager.registerEvent(PlayerJoinEvent::class.java, object : Listener {}, EventPriority.MONITOR, EventExecutor { _, event ->
            if (event is PlayerJoinEvent) network.joined(event.player.uniqueId.toPlayerRef(), event.player.name)
        }, plugin)
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xSample on ${platform.name}: ${platform.threading} threading, text as ${platform.text}, chat through ${platform.chatPath}, enabled in $millis ms")
        PlatformCheck(plugin, platform, cache).start()
    }

    override fun disable() {
        val platform = platform ?: return
        sample?.modules?.clear()
        platform.close()
        this.platform = null
        sample = null
        platform.log.info("xSample stopped")
    }
}
