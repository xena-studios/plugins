package co.xenastudios.xutilities.fabric

import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.xutilities.JvmFiles
import co.xenastudios.xutilities.XUtilitiesPlugin
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * xUtilities as a server-side Fabric mod: the shared plugin on platform-fabric. As on Bukkit,
 * `config.yml` (in `config/xutilities/`) is read as the mod starts, so its commands are
 * registered when Minecraft builds its command tree; the family's settings and `lang/` are read
 * off the server thread after, and apply once the server ticks.
 */
public object XUtilitiesMod : ModInitializer {
    override fun onInitialize() {
        val platform = FabricPlatform(XUtilitiesPlugin.PLUGIN_ID, XUtilitiesPlugin.NAME)
        val family = platform.family
        val files = JvmFiles(platform.storage.root, family.family, shipped(), platform.log)
        val version = FabricLoader.getInstance().getModContainer(XUtilitiesPlugin.PLUGIN_ID).map { it.metadata.version.friendlyString }
            .orElse(XUtilitiesPlugin.VERSION).substringBefore('+')
        val xutilities = XUtilitiesPlugin(
            platform, FabricXServer(platform), platform.players, files, family.players,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            version = version,
        )
        platform.commands.feedback = xutilities.texts.feedback

        val config = files.config()
        platform.scheduler.now(Where.Global) { xutilities.start(config) }
        platform.scheduler.run(Where.Async) {
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { xutilities.apply(loaded) }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ -> xutilities.stop() }
        platform.log.info("xUtilities ${xutilities.version} on ${platform.name}")
    }

    // The jar's translations, from this mod's own jar: every Fabric mod shares one class loader,
    // so a resource found through it could be another mod's lang/ file.
    private fun shipped(): Map<LanguageTag, String> {
        val mod = FabricLoader.getInstance().getModContainer(XUtilitiesPlugin.PLUGIN_ID).orElse(null) ?: return emptyMap()
        val out = LinkedHashMap<LanguageTag, String>()
        for (tag in JvmFiles.LANGUAGES) {
            val path = mod.findPath("lang/$tag.yml").orElse(null) ?: continue
            out[tag] = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        }
        return out
    }
}
