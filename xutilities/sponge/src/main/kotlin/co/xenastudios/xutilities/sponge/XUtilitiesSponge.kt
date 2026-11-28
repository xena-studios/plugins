package co.xenastudios.xutilities.sponge

import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.sponge.ServerStoppingEvent
import co.xenastudios.core.sponge.SpongeBootstrap
import co.xenastudios.core.sponge.SpongePlatform
import co.xenastudios.xutilities.JvmFiles
import co.xenastudios.xutilities.XUtilitiesPlugin
import co.xenastudios.xutilities.config.XUtilitiesConfig
import com.google.inject.Inject
import org.spongepowered.plugin.PluginContainer
import java.nio.charset.StandardCharsets

/**
 * xUtilities as a Sponge plugin: the shared plugin on platform-sponge, made by Sponge as it
 * constructs plugins. As on Fabric, `config.yml` (in `config/xutilities/`) is read here, so the
 * commands are there when Sponge asks for them as the server starts; the family's settings and
 * `lang/` are read off the server thread after, and apply once the server ticks.
 */
public class XUtilitiesSponge @Inject constructor(container: PluginContainer) : SpongeBootstrap(container) {
    init {
        val platform = SpongePlatform(this, XUtilitiesPlugin.NAME, XUtilitiesPlugin.VERSION)
        val family = platform.family
        val files = JvmFiles(platform.storage.root, family.family, shipped(platform), platform.log)
        val xutilities = XUtilitiesPlugin(
            platform, SpongeXServer(platform), platform.players, files, family.players,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            version = platform.version,
        )
        platform.commands.feedback = xutilities.texts.feedback

        val config = files.config()
        // The update checker; bStats needs a Sponge service of its own first (platform-sponge).
        xutilities.updates = platform.checkUpdates(XUtilitiesPlugin.PLUGIN_ID, xutilities.admin.updates, XUtilitiesConfig.updates.settings(config.config)) {
            xutilities.texts.translations
        }
        platform.scheduler.now(Where.Global) { xutilities.start(config) }
        platform.scheduler.run(Where.Async) {
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { xutilities.apply(loaded) }
        }
        platform.events.subscribe(ServerStoppingEvent.TYPE) { xutilities.stop() }
        platform.log.info("xUtilities ${xutilities.version} on ${platform.name}")
    }

    // The jar's translations, from this plugin's own jar: every Sponge plugin shares one class
    // loader, so a resource found by name could be another plugin's lang/ file.
    private fun shipped(platform: SpongePlatform): Map<LanguageTag, String> {
        val out = LinkedHashMap<LanguageTag, String>()
        val bundle = platform.resource(LangFolder.BUNDLE)?.let { LangFolder.bundle(String(it, StandardCharsets.UTF_8)) }.orEmpty()
        for (tag in JvmFiles.LANGUAGES) {
            val file = platform.resource("lang/$tag.yml")
            if (file != null) out[tag] = String(file, StandardCharsets.UTF_8) else bundle[tag.toString()]?.let { out[tag] = it }
        }
        return out
    }
}
