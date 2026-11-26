package co.xenastudios.xmoney.bukkit

import co.xenastudios.core.api.onSettled
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.xmoney.CmiImport
import co.xenastudios.xmoney.EssentialsImport
import co.xenastudios.xmoney.JvmDatabase
import co.xenastudios.xmoney.JvmFiles
import co.xenastudios.xmoney.XMoneyPlugin
import co.xenastudios.xmoney.ZChatPlaceholders
import co.xenastudios.xmoney.api.XMoneyApi
import co.xenastudios.xmoney.bukkit.vault.VaultHolder
import co.xenastudios.xmoney.bukkit.vault.VaultServices
import co.xenastudios.xmoney.config.XMoneyConfig
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.TimeUnit

/**
 * xMoney on Paper, Folia, Spigot and CraftBukkit, 1.8.8 on: the shared plugin on platform-bukkit,
 * loaded by the core's Java shim once the stdlib is there. It wires the shared code to the server:
 *
 * - `config.yml` is read as the plugin enables, so its commands are the server's from the start;
 *   the database is opened and every balance read on a thread of its own, never the server's.
 *   A player can't join before the balances are read (their pre-login waits for it), and a Vault
 *   call made in the moments before waits for them too.
 * - Vault 1 and VaultUnlocked 2, registered as the plugin enables, so plugins enabling after find
 *   them; a Permission provider for servers with neither a permissions plugin nor real Vault.
 * - PlaceholderAPI's `%xmoney_...%` while it's installed, and zChat's `<balance>` tags.
 * - The API's events fired as Bukkit events too, and the API in the services manager.
 */
public class XMoneyBukkit(private val plugin: JavaPlugin) : PluginEntry, Listener {

    private var platform: BukkitPlatform? = null
    private var xmoney: XMoneyPlugin? = null
    private var vault: VaultServices? = null
    private var papi: Any? = null

    override fun enable() {
        val started = System.nanoTime()
        val platform = BukkitPlatform.create(plugin, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XMoneyPlugin.PLUGIN_ID)
        platform.record(cache)
        val server = BukkitMoneyServer(platform, cache)
        val folder = plugin.dataFolder.toPath()
        val files = JvmFiles(folder, family, LangFolder.shipped(plugin.javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val xmoney = XMoneyPlugin(
            platform, server, files, cache,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            players = cache,
            about = platform::about,
            // -Dxena.debug.paste=false writes debug reports to the plugin's folder instead (boot tests do).
            paste = if (System.getProperty("xena.debug.paste") == "false") null else Mclogs,
            version = plugin.description.version,
        )
        this.platform = platform
        this.xmoney = xmoney
        platform.commands.feedback = xmoney.texts.feedback
        xmoney.beforeFirstChange = EventBridge(plugin.server, xmoney.bus)

        // Vault now, so plugins enabling after xMoney find the economy as they do.
        val vault = VaultServices(plugin, platform, VaultHolder(plugin, xmoney, server))
        vault.register()
        this.vault = vault
        val plugins = folder.parent
        xmoney.sources = Importers(folder.parent, platform, vault)
        plugin.server.servicesManager.register(XMoneyApi::class.java, xmoney, plugin, ServicePriority.Normal)
        plugin.server.pluginManager.registerEvents(this, plugin)

        // The config now, so the commands are the server's from the start; the database off the thread.
        val config = files.config()
        xmoney.start(config)
        val where = JvmDatabase.where(config.config, family, folder, folder.resolveSibling("Xena"))
        val drivers = Drivers(plugin.javaClass.classLoader, platform.driverFetcher())
        val steps = Steps(platform, xmoney, cache, files, config, where, drivers, vault)
        platform.scheduler.run(Where.Async, action = steps)
        xmoney.ready.onSettled(steps)
        xmoney.updates = platform.checkUpdates(XMoneyPlugin.PLUGIN_ID, xmoney.admin.updates, XMoneyConfig.updates.settings(config.config)) {
            xmoney.texts.translations
        }
        platform.metrics(BSTATS)
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xMoney ${xmoney.version} on ${platform.name}: ${platform.threading} threading, text as ${platform.text}, enabled in $millis ms")
    }

    override fun disable() {
        val platform = platform ?: return
        HandlerList.unregisterAll(this as Listener)
        vault?.unregister()
        papi?.let(PapiHook::unregister)
        plugin.server.servicesManager.unregisterAll(plugin)
        // What's queued is saved before the server stops: the database closes once it's written.
        try {
            xmoney?.stop()?.get(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            platform.log.warn("Stopping, xMoney's database didn't finish saving in time: $e")
        }
        platform.close()
        this.platform = null
        xmoney = null
        vault = null
        papi = null
    }

    // A player can't join until every balance is read: their pre-login (async) waits for it.
    @EventHandler(priority = EventPriority.LOWEST)
    public fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        val xmoney = xmoney ?: return
        if (xmoney.ledger != null || xmoney.failure != null) return
        try {
            xmoney.ready.get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Let them in: their account is made when the database answers.
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onJoin(event: PlayerJoinEvent) {
        xmoney?.joined(event.player.ref, event.player.name)
    }

    @EventHandler
    public fun onPluginEnable(event: PluginEnableEvent) {
        val xmoney = xmoney?.takeIf { it.ledger != null } ?: return
        when (event.plugin.name) {
            "PlaceholderAPI" -> hookPlaceholders(xmoney)
            "zChat" -> platform?.scheduler?.run(Where.Global) { hookZChat(xmoney) }
        }
    }

    private fun hookPlaceholders(xmoney: XMoneyPlugin) {
        if (papi != null || !plugin.server.pluginManager.isPluginEnabled("PlaceholderAPI")) return
        papi = PapiHook.register(xmoney)
    }

    // zChat's API is in the services manager while it's enabled: its <balance> tags, through its own classes.
    private fun hookZChat(xmoney: XMoneyPlugin) {
        val services = plugin.server.servicesManager
        val type = services.knownServices.firstOrNull { it.name == "co.xenastudios.zchat.api.ZChatApi" } ?: return
        val api = services.getRegistration(type)?.provider ?: return
        ZChatPlaceholders.register(api, xmoney.placeholders, xmoney.log)
    }

    // EssentialsX's files, CMI's SQLite and any economy registered with Vault, for /xmoney import.
    private inner class Importers(private val plugins: java.nio.file.Path, private val platform: BukkitPlatform, private val vault: VaultServices) :
        co.xenastudios.xmoney.BalanceSources {
        override val ids: List<String> get() = listOf("essentials", "cmi", "vault")

        override fun make(id: String): co.xenastudios.xmoney.BalanceSource? = when (id) {
            "essentials" -> EssentialsImport(plugins, platform)
            "cmi" -> CmiImport(plugins, platform, plugin.javaClass.classLoader)
            "vault" -> vault.importer()
            else -> null
        }
    }

    // mclo.gs, made with the first /xmoney debug upload: its class isn't loaded before.
    private object Mclogs : co.xenastudios.core.common.admin.PasteService {
        override val name: String get() = "mclo.gs"

        override fun upload(text: String) = MclogsPaste().upload(text)
    }

    /**
     * What happens after enable, as one class rather than a lambda each (each would be a class
     * of its own, made as the server starts: decisions/2026-11-25-a-lighter-core.md). Run on an
     * async task first: the database opened, every balance read, the lang files. Then, once
     * [XMoneyPlugin.ready] settles, on the global thread: anyone already online, and the hooks.
     */
    private inner class Steps(
        private val platform: BukkitPlatform,
        private val xmoney: XMoneyPlugin,
        private val cache: PlayerCache,
        private val files: JvmFiles,
        private val config: co.xenastudios.xmoney.Loaded,
        private val where: JvmDatabase.Where,
        private val drivers: Drivers,
        private val vault: VaultServices,
    ) : (co.xenastudios.core.common.scheduler.Task) -> Unit, (Boolean?, Throwable?) -> Unit {
        private var step = OPEN
        private var loaded: co.xenastudios.xmoney.Loaded? = null

        override fun invoke(task: co.xenastudios.core.common.scheduler.Task) {
            when (step) {
                OPEN -> open()
                APPLY -> xmoney.apply(loaded!!)
                else -> {
                    // Anyone already online, such as after the plugin is loaded on a running server.
                    for (player in Bukkit.getOnlinePlayers()) xmoney.joined(player.ref, player.name)
                    hookPlaceholders(xmoney)
                    hookZChat(xmoney)
                }
            }
        }

        private fun open() {
            val opened = when (val result = JvmDatabase.open(where, drivers, xmoney.log)) {
                is Outcome.Ok -> Outcome.Ok(result.value)
                is Outcome.Err -> Outcome.Err("Couldn't open ${if (where.settings.type == co.xenastudios.core.common.db.DatabaseSettings.Type.MYSQL) "MySQL" else "the SQLite database ${where.sqliteFile.fileName}"}: ${result.error.detail}.")
            }
            xmoney.open(opened, where.network)
            cache.load()
            loaded = files.translations(config.config, config.problems)
            step = APPLY
            platform.scheduler.run(Where.Global, action = this)
            vault.checkJars()
        }

        // XMoneyPlugin.ready settled; after the translations are applied, as both run on the global thread in order.
        override fun invoke(ok: Boolean?, error: Throwable?) {
            if (ok != true) return
            platform.scheduler.run(Where.Global, action = Steps(platform, xmoney, cache, files, config, where, drivers, vault).also { it.step = READY })
        }
    }

    private companion object {
        const val OPEN = 0
        const val APPLY = 1
        const val READY = 2

        // bstats.org/plugin/bukkit/xMoney/35421, 1.x's.
        const val BSTATS = 35421
    }
}
