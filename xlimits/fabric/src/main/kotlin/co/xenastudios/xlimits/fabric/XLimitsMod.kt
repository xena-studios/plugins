package co.xenastudios.xlimits.fabric

import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.xlimits.JvmFiles
import co.xenastudios.xlimits.XLimitsPlugin
import co.xenastudios.xlimits.config.XLimitsConfig
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * xLimits as a server-side Fabric mod: the shared plugin on platform-fabric. `config.yml` (in
 * `config/xlimits/`) is read as the mod starts, long before anyone can join; the lang files off
 * the server thread. FarmControl and Insights are Bukkit plugins, so there's nothing to import here.
 */
public object XLimitsMod : ModInitializer {
    override fun onInitialize() {
        val platform = FabricPlatform(XLimitsPlugin.PLUGIN_ID, XLimitsPlugin.NAME)
        val family = platform.family
        val folder = platform.storage.root
        val files = JvmFiles(folder, family.family, shipped(), platform.log)
        val version = FabricLoader.getInstance().getModContainer(XLimitsPlugin.PLUGIN_ID).map { it.metadata.version.friendlyString }
            .orElse(XLimitsPlugin.VERSION).substringBefore('+')
        val server = FabricLimitsServer(platform, platform.world(EventPriority.HIGH))
        val xlimits = XLimitsPlugin(
            platform, server, files, family.players, platform.players,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            version = version,
        )
        platform.commands.feedback = xlimits.texts.feedback

        val config = files.config()
        xlimits.start(config)
        platform.scheduler.run(Where.Async) {
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { xlimits.apply(loaded) }
        }
        // The update checker; bStats has no Fabric platform, and xLimits no bStats id yet.
        xlimits.updates = platform.checkUpdates(XLimitsPlugin.PLUGIN_ID, xlimits.admin.updates, XLimitsConfig.updates.settings(config.config)) {
            xlimits.texts.translations
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ -> xlimits.stop() }
        platform.log.info("xLimits ${xlimits.version} on ${platform.name}")
    }

    // The jar's translations, from this mod's own jar: every Fabric mod shares one class loader.
    private fun shipped(): Map<LanguageTag, String> {
        val mod = FabricLoader.getInstance().getModContainer(XLimitsPlugin.PLUGIN_ID).orElse(null) ?: return emptyMap()
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
