package co.xenastudios.zchat.fabric

import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.fabric.PlayerJoinEvent
import co.xenastudios.core.fabric.PlayerQuitEvent
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.zchat.JvmFiles
import co.xenastudios.zchat.ZChatPlugin
import co.xenastudios.zchat.config.ZChatConfig
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * zChat as a server-side Fabric mod: the shared plugin on platform-fabric. As on Bukkit,
 * `config.yml` (in `config/zchat/`) is read as the mod starts, so its commands are registered when
 * Minecraft builds its command tree; the family's settings, `lang/` and the remembered choices
 * are read off the server thread after, and apply once the server ticks. Chat comes through the
 * platform's chat bridge (Fabric API's message events).
 */
public object ZChatMod : ModInitializer {
    override fun onInitialize() {
        val platform = FabricPlatform(ZChatPlugin.PLUGIN_ID, ZChatPlugin.NAME)
        val family = platform.family
        val files = JvmFiles(platform.storage.root, family.family, shipped(), platform.log)
        val version = FabricLoader.getInstance().getModContainer(ZChatPlugin.PLUGIN_ID).map { it.metadata.version.friendlyString }
            .orElse(ZChatPlugin.VERSION).substringBefore('+')
        val zchat = ZChatPlugin(
            platform, FabricChatServer(platform), files, family.players, { priority -> platform.chat(priority) },
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
        // The update checker; bStats has no Fabric platform, so there's no metrics here.
        zchat.updates = platform.checkUpdates(ZChatPlugin.PLUGIN_ID, zchat.admin.updates, ZChatConfig.updates.settings(config.config)) {
            zchat.texts.translations
        }
        platform.scheduler.now(Where.Global) { zchat.start(config) }
        platform.scheduler.run(Where.Async) {
            zchat.settings.open()?.let(platform.log::warn)
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { zchat.apply(loaded) }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ -> zchat.stop() }
        platform.log.info("zChat ${zchat.version} on ${platform.name}")
    }

    // The jar's translations, from this mod's own jar: every Fabric mod shares one class loader,
    // so a resource found through it could be another mod's lang/ file.
    private fun shipped(): Map<LanguageTag, String> {
        val mod = FabricLoader.getInstance().getModContainer(ZChatPlugin.PLUGIN_ID).orElse(null) ?: return emptyMap()
        val out = LinkedHashMap<LanguageTag, String>()
        // The shrunk jar has them in one entry (LangFolder.BUNDLE); a dev build, one file each.
        val bundle = mod.findPath(LangFolder.BUNDLE).orElse(null)?.let { LangFolder.bundle(String(Files.readAllBytes(it), StandardCharsets.UTF_8)) }.orEmpty()
        for (tag in JvmFiles.LANGUAGES) {
            val path = mod.findPath("lang/$tag.yml").orElse(null)
            if (path == null) {
                bundle[tag.toString()]?.let { out[tag] = it }
                continue
            }
            out[tag] = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        }
        return out
    }
}
