package co.xenastudios.xrewind.fabric

import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.xrewind.JvmDatabase
import co.xenastudios.xrewind.JvmFiles
import co.xenastudios.xrewind.XRewindPlugin
import co.xenastudios.xrewind.config.XRewindConfig
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * xRewind as a server-side Fabric mod: the shared plugin on platform-fabric, with the core's world
 * hooks (their mixins in this jar, `xrewind-world.mixins.json`). `config.yml` (in
 * `config/xrewind/`) is read as the mod starts, so its commands are registered when Minecraft
 * builds its command tree; the lang files and the database (its driver downloaded the first time)
 * are read off the server thread, and changes wait in memory until it's open. CoreProtect is a
 * Bukkit plugin, so there's nothing to import here.
 */
public object XRewindMod : ModInitializer {
    override fun onInitialize() {
        val platform = FabricPlatform(XRewindPlugin.PLUGIN_ID, XRewindPlugin.NAME)
        val family = platform.family
        val folder = platform.storage.root
        val files = JvmFiles(folder, family.family, shipped(), platform.log)
        val version = FabricLoader.getInstance().getModContainer(XRewindPlugin.PLUGIN_ID).map { it.metadata.version.friendlyString }
            .orElse(XRewindPlugin.VERSION).substringBefore('+')
        val server = FabricRewindServer(platform)
        val drivers = Drivers(XRewindMod::class.java.classLoader, platform.drivers(XRewindMod::class.java.classLoader))
        val opener = JvmDatabase(family.family, folder, FabricLoader.getInstance().configDir.resolve("xena"), drivers, platform.log)
        val xrewind = XRewindPlugin(
            platform, server, files, family.players, platform.players, family.players, opener,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            version = version,
        )
        platform.commands.feedback = xrewind.texts.feedback

        val config = files.config()
        xrewind.start(config)
        xrewind.open()
        platform.scheduler.run(Where.Async) {
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { xrewind.apply(loaded) }
        }
        // The update checker; bStats has no Fabric platform, so there's no metrics here.
        xrewind.updates = platform.checkUpdates(XRewindPlugin.PLUGIN_ID, xrewind.admin.updates, XRewindConfig.updates.settings(config.config)) {
            xrewind.texts.translations
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            try {
                xrewind.stop().get(15, TimeUnit.SECONDS)
            } catch (e: Exception) {
                platform.log.warn("Stopping, xRewind's database didn't finish saving in time: $e")
            }
        }
        platform.log.info("xRewind ${xrewind.version} on ${platform.name}")
    }

    private fun shipped(): Map<LanguageTag, String> {
        val mod = FabricLoader.getInstance().getModContainer(XRewindPlugin.PLUGIN_ID).orElse(null) ?: return emptyMap()
        val out = LinkedHashMap<LanguageTag, String>()
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
