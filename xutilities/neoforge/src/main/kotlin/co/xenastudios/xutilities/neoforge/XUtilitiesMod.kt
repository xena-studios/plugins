package co.xenastudios.xutilities.neoforge

import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.neoforge.NeoForgePlatform
import co.xenastudios.xutilities.JvmFiles
import co.xenastudios.xutilities.XUtilitiesPlugin
import co.xenastudios.xutilities.config.XUtilitiesConfig
import net.neoforged.bus.api.EventPriority
import net.neoforged.fml.ModList
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import java.nio.charset.StandardCharsets

/**
 * xUtilities as a server-side NeoForge mod: the shared plugin on platform-neoforge, as its
 * Fabric mod is on platform-fabric. `config.yml` (in `config/xutilities/`) is read as the mod is
 * made, so its commands are registered when Minecraft builds its command tree; the family's
 * settings and `lang/` are read off the server thread after, and apply once the server ticks.
 */
@Mod(XUtilitiesPlugin.PLUGIN_ID)
public class XUtilitiesMod {
    init {
        val platform = NeoForgePlatform(XUtilitiesPlugin.PLUGIN_ID, XUtilitiesPlugin.NAME)
        val family = platform.family
        val files = JvmFiles(platform.storage.root, family.family, shipped(), platform.log)
        val version = ModList.get().getModContainerById(XUtilitiesPlugin.PLUGIN_ID).map { it.modInfo.version.toString() }
            .orElse(XUtilitiesPlugin.VERSION).substringBefore('+')
        val xutilities = XUtilitiesPlugin(
            platform, NeoForgeXServer(platform), platform.players, files, family.players,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            version = version,
        )
        platform.commands.feedback = xutilities.texts.feedback

        val config = files.config()
        // The update checker; bStats has no NeoForge platform, so there's no metrics here.
        xutilities.updates = platform.checkUpdates(XUtilitiesPlugin.PLUGIN_ID, xutilities.admin.updates, XUtilitiesConfig.updates.settings(config.config)) {
            xutilities.texts.translations
        }
        platform.scheduler.now(Where.Global) { xutilities.start(config) }
        platform.scheduler.run(Where.Async) {
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { xutilities.apply(loaded) }
        }
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppingEvent::class.java) { _ -> xutilities.stop() }
        platform.log.info("xUtilities ${xutilities.version} on ${platform.name}")
    }

    // The jar's translations, from this mod's own module: NeoForge's game loader looks a
    // resource outside a package up in every mod, so lang/ could be another mod's.
    private fun shipped(): Map<LanguageTag, String> {
        val module = XUtilitiesMod::class.java.module
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
