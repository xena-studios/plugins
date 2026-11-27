package co.xenastudios.xmarket.fabric

import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.xmarket.JvmDatabase
import co.xenastudios.xmarket.JvmFiles
import co.xenastudios.xmarket.XMarketPlugin
import co.xenastudios.xmarket.config.XMarketConfig
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * xMarket as a server-side Fabric mod: the shared plugin on platform-fabric. `config.yml` (in
 * `config/xmarket/`) is read as the mod starts, so its commands are registered when Minecraft
 * builds its command tree; the price list, the lang files and the database (its driver downloaded
 * the first time) are read off the server thread, long before anyone can join. Money goes through
 * xMoney, or another economy mod's Common Economy API. EconomyShopGUI and ShopGUI+ are Bukkit
 * plugins, so there's nothing to import here.
 */
public object XMarketMod : ModInitializer {
    override fun onInitialize() {
        val platform = FabricPlatform(XMarketPlugin.PLUGIN_ID, XMarketPlugin.NAME)
        val family = platform.family
        val folder = platform.storage.root
        val files = JvmFiles(folder, family.family, shipped(), platform.log, ::resource)
        val version = FabricLoader.getInstance().getModContainer(XMarketPlugin.PLUGIN_ID).map { it.metadata.version.friendlyString }
            .orElse(XMarketPlugin.VERSION).substringBefore('+')
        val server = FabricMarketServer(platform)
        val xmarket = XMarketPlugin(
            platform, server, files, family.players, platform.players, family.players,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            version = version,
        )
        server.xmarket = xmarket
        platform.commands.feedback = xmarket.texts.feedback

        val config = files.config()
        xmarket.start(config)
        val where = JvmDatabase.where(config.config, family.family, folder, FabricLoader.getInstance().configDir.resolve("xena"))
        val drivers = Drivers(XMarketMod::class.java.classLoader, platform.drivers(XMarketMod::class.java.classLoader))
        platform.scheduler.run(Where.Async) {
            xmarket.loadPrices()
            xmarket.open(JvmDatabase.open(where, drivers, xmarket.log))
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { xmarket.apply(loaded) }
        }
        // The update checker; bStats has no Fabric platform, so there's no metrics here.
        xmarket.updates = platform.checkUpdates(XMarketPlugin.PLUGIN_ID, xmarket.admin.updates, XMarketConfig.updates.settings(config.config)) {
            xmarket.texts.translations
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            try {
                xmarket.stop().get(15, TimeUnit.SECONDS)
            } catch (e: Exception) {
                platform.log.warn("Stopping, xMarket's database didn't finish saving in time: $e")
            }
        }
        platform.log.info("xMarket ${xmarket.version} on ${platform.name}")
    }

    // A file from this mod's own jar: every Fabric mod shares one class loader.
    private fun resource(name: String): String? {
        val mod = FabricLoader.getInstance().getModContainer(XMarketPlugin.PLUGIN_ID).orElse(null) ?: return null
        val path = mod.findPath(name).orElse(null) ?: return null
        return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    }

    private fun shipped(): Map<LanguageTag, String> {
        val mod = FabricLoader.getInstance().getModContainer(XMarketPlugin.PLUGIN_ID).orElse(null) ?: return emptyMap()
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
