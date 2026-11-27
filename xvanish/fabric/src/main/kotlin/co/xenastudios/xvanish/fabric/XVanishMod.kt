package co.xenastudios.xvanish.fabric

import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.xvanish.JvmFiles
import co.xenastudios.xvanish.JvmNetwork
import co.xenastudios.xvanish.XVanishPlugin
import co.xenastudios.xvanish.ZChatHidden
import co.xenastudios.xvanish.config.XVanishConfig
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * xVanish as a server-side Fabric mod: the shared plugin on platform-fabric. `config.yml` (in
 * `config/xvanish/`) and `vanished.txt` are read as the mod starts, long before anyone can join;
 * the lang files, and network mode's database if it's on, off the server thread. zChat is told who's
 * hidden once the server has started, as Fabric has no `canSee` for it to ask. SuperVanish and
 * PremiumVanish are Bukkit plugins, so there's nothing to import here.
 */
public object XVanishMod : ModInitializer {
    override fun onInitialize() {
        val platform = FabricPlatform(XVanishPlugin.PLUGIN_ID, XVanishPlugin.NAME)
        val family = platform.family
        val folder = platform.storage.root
        val files = JvmFiles(folder, family.family, shipped(), platform.log)
        val version = FabricLoader.getInstance().getModContainer(XVanishPlugin.PLUGIN_ID).map { it.metadata.version.friendlyString }
            .orElse(XVanishPlugin.VERSION).substringBefore('+')
        val server = FabricVanishServer(platform)
        val xvanish = XVanishPlugin(
            platform, server, files, family.players, platform.players, family.players,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            version = version,
        )
        platform.commands.feedback = xvanish.texts.feedback

        val config = files.config()
        xvanish.start(config)
        platform.scheduler.run(Where.Async) {
            if (JvmNetwork.wanted(config.config)) {
                val drivers = Drivers(XVanishMod::class.java.classLoader, platform.drivers(XVanishMod::class.java.classLoader))
                xvanish.openNetwork(JvmNetwork.open(config.config, family.family, folder, FabricLoader.getInstance().configDir.resolve("xena"), drivers, xvanish.log))
            }
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { xvanish.apply(loaded) }
        }
        // The update checker; bStats has no Fabric platform, and xVanish no bStats id yet.
        xvanish.updates = platform.checkUpdates(XVanishPlugin.PLUGIN_ID, xvanish.admin.updates, XVanishConfig.updates.settings(config.config)) {
            xvanish.texts.translations
        }
        ServerLifecycleEvents.SERVER_STARTED.register { _ ->
            ZChatHidden.fabricApi()?.let { ZChatHidden.register(it, xvanish, xvanish.log) }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            try {
                xvanish.stop().get(15, TimeUnit.SECONDS)
            } catch (e: Exception) {
                platform.log.warn("Stopping, xVanish didn't finish saving in time: $e")
            }
        }
        platform.log.info("xVanish ${xvanish.version} on ${platform.name}")
    }

    // The jar's translations, from this mod's own jar: every Fabric mod shares one class loader.
    private fun shipped(): Map<LanguageTag, String> {
        val mod = FabricLoader.getInstance().getModContainer(XVanishPlugin.PLUGIN_ID).orElse(null) ?: return emptyMap()
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
