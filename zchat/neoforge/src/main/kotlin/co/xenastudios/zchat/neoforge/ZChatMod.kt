package co.xenastudios.zchat.neoforge

import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.neoforge.NeoForgePlatform
import co.xenastudios.core.neoforge.PlayerJoinEvent
import co.xenastudios.core.neoforge.PlayerQuitEvent
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.zchat.JvmFiles
import co.xenastudios.zchat.ZChatPlugin
import co.xenastudios.zchat.config.ZChatConfig
import java.nio.charset.StandardCharsets
import net.neoforged.bus.api.EventPriority
import net.neoforged.fml.ModList
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppingEvent

/**
 * zChat as a server-side NeoForge mod: the shared plugin on platform-neoforge, as its Fabric mod
 * is on platform-fabric. As on Bukkit, `config.yml` (in `config/zchat/`) is read as the mod
 * starts, so its commands are registered when
 * Minecraft builds its command tree; the family's settings, `lang/` and the remembered choices
 * are read off the server thread after, and apply once the server ticks. Chat comes through the
 * platform's chat bridge (NeoForge's `ServerChatEvent`).
 */
@Mod(ZChatPlugin.PLUGIN_ID)
public class ZChatMod {
    // NeoForge makes every mod at once, on several threads, before Minecraft is set up: the
    // plugin starts at common setup instead, on the main thread (NeoForgePlatform.whenSetUp).
    init {
        NeoForgePlatform.whenSetUp(ZChatPlugin.PLUGIN_ID) { start() }
    }

    private fun start() {
        val platform = NeoForgePlatform(ZChatPlugin.PLUGIN_ID, ZChatPlugin.NAME)
        val family = platform.family
        val files = JvmFiles(platform.storage.root, family.family, shipped(), platform.log)
        val version = ModList.get().getModContainerById(ZChatPlugin.PLUGIN_ID).map { it.modInfo.version.toString() }
            .orElse(ZChatPlugin.VERSION).substringBefore('+')
        val zchat = ZChatPlugin(
            platform, NeoForgeChatServer(platform), files, family.players, { priority -> platform.chat(priority) },
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            lookup = family.players.asLookup(),
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            version = version,
        )
        platform.commands.feedback = zchat.texts.feedback
        platform.events.subscribe(PlayerJoinEvent.TYPE) { zchat.joined(it.player, it.name) }
        platform.events.subscribe(PlayerQuitEvent.TYPE) { zchat.quit(it.player) }

        val config = files.config()
        if (config.config[ZChatConfig.network.enabled]) {
            platform.log.warn("network.enabled is on, but network chat isn't on NeoForge yet (the proxy layer has no NeoForge end): this server's chat stays here.")
        }
        // The update checker; bStats has no NeoForge platform, so there's no metrics here.
        zchat.updates = platform.checkUpdates(ZChatPlugin.PLUGIN_ID, zchat.admin.updates, ZChatConfig.updates.settings(config.config)) {
            zchat.texts.translations
        }
        platform.scheduler.now(Where.Global) { zchat.start(config) }
        platform.scheduler.run(Where.Async) {
            zchat.settings.open()?.let(platform.log::warn)
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { zchat.apply(loaded) }
        }
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppingEvent::class.java) { _ -> zchat.stop() }
        platform.log.info("zChat ${zchat.version} on ${platform.name}")
    }

    // The jar's translations, from this mod's own module: NeoForge's game loader looks a
    // resource outside a package up in every mod, so lang/ could be another mod's.
    private fun shipped(): Map<LanguageTag, String> {
        val module = ZChatMod::class.java.module
        fun read(path: String): String? = module.getResourceAsStream(path)?.use { String(it.readBytes(), StandardCharsets.UTF_8) }
        val out = LinkedHashMap<LanguageTag, String>()
        // The shrunk jar has them in one entry (LangFolder.BUNDLE); a dev build, one file each.
        val bundle = read(LangFolder.BUNDLE)?.let { LangFolder.bundle(it) }.orEmpty()
        for (tag in JvmFiles.LANGUAGES) {
            val text = read("lang/$tag.yml") ?: bundle[tag.toString()] ?: continue
            out[tag] = text
        }
        return out
    }
}
