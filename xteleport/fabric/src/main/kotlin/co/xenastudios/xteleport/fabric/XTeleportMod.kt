package co.xenastudios.xteleport.fabric

import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.xteleport.JvmDatabase
import co.xenastudios.xteleport.JvmFiles
import co.xenastudios.xteleport.XTeleportPlugin
import co.xenastudios.xteleport.config.XTeleportConfig
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * xTeleport as a server-side Fabric mod: the shared plugin on platform-fabric. As on Bukkit,
 * `config.yml` (in `config/xteleport/`) is read as the mod starts, so its commands are registered
 * when Minecraft builds its command tree; the database is opened (its driver downloaded the first
 * time: Fabric servers have none) and the warps read off the server thread, long before anyone
 * can join. Costs go through xMoney when it's installed. EssentialsX is a Bukkit plugin, so there's
 * nothing to import here.
 */
public object XTeleportMod : ModInitializer {
    override fun onInitialize() {
        val platform = FabricPlatform(XTeleportPlugin.PLUGIN_ID, XTeleportPlugin.NAME)
        val family = platform.family
        val folder = platform.storage.root
        val files = JvmFiles(folder, family.family, shipped(), platform.log)
        val version = FabricLoader.getInstance().getModContainer(XTeleportPlugin.PLUGIN_ID).map { it.metadata.version.friendlyString }
            .orElse(XTeleportPlugin.VERSION).substringBefore('+')
        val server = FabricTeleportServer(platform)
        val xteleport = XTeleportPlugin(
            platform, server, files, family.players, platform.players, family.players,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            version = version,
        )
        platform.commands.feedback = xteleport.texts.feedback

        val config = files.config()
        xteleport.start(config)
        val where = JvmDatabase.where(config.config, family.family, folder, FabricLoader.getInstance().configDir.resolve("xena"))
        val drivers = Drivers(XTeleportMod::class.java.classLoader, platform.drivers(XTeleportMod::class.java.classLoader))
        platform.scheduler.run(Where.Async) {
            xteleport.open(JvmDatabase.open(where, drivers, xteleport.log))
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { xteleport.apply(loaded) }
        }
        // The update checker; bStats has no Fabric platform, so there's no metrics here.
        xteleport.updates = platform.checkUpdates(XTeleportPlugin.PLUGIN_ID, xteleport.admin.updates, XTeleportConfig.updates.settings(config.config)) {
            xteleport.texts.translations
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            try {
                xteleport.stop().get(15, TimeUnit.SECONDS)
            } catch (e: Exception) {
                platform.log.warn("Stopping, xTeleport's database didn't finish saving in time: $e")
            }
        }
        platform.log.info("xTeleport ${xteleport.version} on ${platform.name}")
    }

    // The jar's translations, from this mod's own jar: every Fabric mod shares one class loader,
    // so a resource found through it could be another mod's lang/ file.
    private fun shipped(): Map<LanguageTag, String> {
        val mod = FabricLoader.getInstance().getModContainer(XTeleportPlugin.PLUGIN_ID).orElse(null) ?: return emptyMap()
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
