package co.xenastudios.xmoney.fabric

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.fabric.PlayerJoinEvent
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.xmoney.JvmDatabase
import co.xenastudios.xmoney.JvmFiles
import co.xenastudios.xmoney.XMoneyPlugin
import co.xenastudios.xmoney.ZChatPlaceholders
import co.xenastudios.xmoney.config.XMoneyConfig
import eu.pb4.common.economy.api.CommonEconomy
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * xMoney as a server-side Fabric mod: the shared plugin on platform-fabric. As on Bukkit,
 * `config.yml` (in `config/xmoney/`) is read as the mod starts, so its commands are registered when
 * Minecraft builds its command tree; the database is opened (its driver downloaded the first time:
 * Fabric servers have none) and every balance read off the server thread, long before anyone can
 * join. Other mods find the economy through the Common Economy API, and zChat's formats get
 * `<balance>` once the server has started.
 */
public object XMoneyMod : ModInitializer {
    override fun onInitialize() {
        val platform = FabricPlatform(XMoneyPlugin.PLUGIN_ID, XMoneyPlugin.NAME)
        val family = platform.family
        val folder = platform.storage.root
        val files = JvmFiles(folder, family.family, shipped(), platform.log)
        val version = FabricLoader.getInstance().getModContainer(XMoneyPlugin.PLUGIN_ID).map { it.metadata.version.friendlyString }
            .orElse(XMoneyPlugin.VERSION).substringBefore('+')
        val server = FabricMoneyServer(platform, family.players)
        val xmoney = XMoneyPlugin(
            platform, server, files, family.players,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            players = family.players,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            version = version,
        )
        platform.commands.feedback = xmoney.texts.feedback
        platform.events.subscribe(PlayerJoinEvent.TYPE) { xmoney.joined(it.player, it.name) }
        CommonEconomy.register(XMoneyPlugin.PLUGIN_ID, CommonEconomyProvider(xmoney))

        val config = files.config()
        xmoney.start(config)
        val where = JvmDatabase.where(config.config, family.family, folder, FabricLoader.getInstance().configDir.resolve("xena"))
        val drivers = Drivers(XMoneyMod::class.java.classLoader, platform.drivers(XMoneyMod::class.java.classLoader))
        platform.scheduler.run(Where.Async) {
            val opened = when (val result = JvmDatabase.open(where, drivers, xmoney.log)) {
                is Outcome.Ok -> Outcome.Ok(result.value)
                is Outcome.Err -> Outcome.Err("Couldn't open the database: ${result.error.detail}.")
            }
            xmoney.open(opened, where.network)
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { xmoney.apply(loaded) }
        }
        xmoney.ready.onSettled { ok, _ ->
            if (ok != true) return@onSettled
            platform.scheduler.run(Where.Global) {
                for (player in platform.server?.playerList?.players.orEmpty()) {
                    xmoney.joined(PlayerRef.parseOrNull(player.uuid.toString())!!, player.scoreboardName)
                }
            }
        }
        // zChat's API once every mod has started; zChat's <balance> tags.
        ServerLifecycleEvents.SERVER_STARTED.register { _ ->
            xmoney.ready.onSettled { ok, _ ->
                if (ok == true) ZChatPlaceholders.fabricApi()?.let { ZChatPlaceholders.register(it, xmoney.placeholders, xmoney.log) }
            }
        }
        // The update checker; bStats has no Fabric platform, so there's no metrics here.
        xmoney.updates = platform.checkUpdates(XMoneyPlugin.PLUGIN_ID, xmoney.admin.updates, XMoneyConfig.updates.settings(config.config)) {
            xmoney.texts.translations
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            try {
                xmoney.stop().get(15, TimeUnit.SECONDS)
            } catch (e: Exception) {
                platform.log.warn("Stopping, xMoney's database didn't finish saving in time: $e")
            }
        }
        platform.log.info("xMoney ${xmoney.version} on ${platform.name}")
    }

    // The jar's translations, from this mod's own jar: every Fabric mod shares one class loader,
    // so a resource found through it could be another mod's lang/ file.
    private fun shipped(): Map<LanguageTag, String> {
        val mod = FabricLoader.getInstance().getModContainer(XMoneyPlugin.PLUGIN_ID).orElse(null) ?: return emptyMap()
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
