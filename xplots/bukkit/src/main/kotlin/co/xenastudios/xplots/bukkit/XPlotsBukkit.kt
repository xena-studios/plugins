package co.xenastudios.xplots.bukkit

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.xplots.Economy
import co.xenastudios.xplots.Hooks
import co.xenastudios.xplots.JvmDatabase
import co.xenastudios.xplots.JvmDeflate
import co.xenastudios.xplots.JvmFiles
import co.xenastudios.xplots.Loaded
import co.xenastudios.xplots.PlotHome
import co.xenastudios.xplots.Rewind
import co.xenastudios.xplots.XMoneyBridge
import co.xenastudios.xplots.XPlotsPlugin
import co.xenastudios.xplots.XRewindBridge
import co.xenastudios.xplots.XTeleportHomes
import co.xenastudios.xplots.XVanishBridge
import co.xenastudios.xplots.api.XPlotsApi
import co.xenastudios.xplots.config.XPlotsConfig
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * xPlots on Paper, Folia, Spigot and CraftBukkit, 1.13 on: the shared plugin on platform-bukkit,
 * loaded by the core's Java shim once the stdlib is there.
 *
 * - `config.yml` is read as the plugin enables, so its commands are the server's from the start,
 *   and protection listens at once (refusing changes until the plots are in); the database, the
 *   player cache and the lang files are read on an async task, never the server thread.
 * - The API's events are fired as Bukkit events too, and the API is in the services manager.
 */
public class XPlotsBukkit(private val plugin: JavaPlugin) : PluginEntry {
    private var platform: BukkitPlatform? = null
    private var xplots: XPlotsPlugin? = null
    private var listeners: AutoCloseable? = null
    private var papi: Any? = null

    override fun enable() {
        val started = System.nanoTime()
        val platform = BukkitPlatform.create(plugin, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XPlotsPlugin.PLUGIN_ID)
        platform.record(cache)
        val server = BukkitPlotServer(plugin, platform, cache)
        val folder = plugin.dataFolder.toPath()
        val files = JvmFiles(folder, family, LangFolder.shipped(plugin.javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val drivers = Drivers(plugin.javaClass.classLoader, platform.driverFetcher())
        val opener = JvmDatabase(family, folder, folder.resolveSibling("Xena"), drivers, platform.log)
        val xplots = XPlotsPlugin(
            platform, server, files, cache, platform.players, cache, opener, BukkitHooks(plugin.server), JvmDeflate,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else Mclogs,
            version = plugin.description.version,
        )
        this.platform = platform
        this.xplots = xplots
        platform.commands.feedback = xplots.texts.feedback
        xplots.beforeFirstEvent = EventBridge(plugin.server, xplots.bus)
        plugin.server.servicesManager.register(XPlotsApi::class.java, xplots, plugin, ServicePriority.Normal)

        val config = files.config()
        xplots.start(config)
        listeners = Listeners.register(plugin, xplots, server)
        xplots.open()
        platform.scheduler.run(Where.Async, action = Steps(platform, xplots, cache, files, config))
        xplots.updates = platform.checkUpdates(XPlotsPlugin.PLUGIN_ID, xplots.admin.updates, XPlotsConfig.updates.settings(config.config)) {
            xplots.texts.translations
        }
        if (plugin.server.pluginManager.isPluginEnabled("PlaceholderAPI")) papi = PapiHook.register(xplots)
        // bStats 35419, xPlots 1.x's.
        platform.metrics(BSTATS)
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xPlots ${xplots.version} on ${platform.name}: ${platform.threading} threading, text as ${platform.text}, enabled in $millis ms")
    }

    override fun disable() {
        val platform = platform ?: return
        plugin.server.servicesManager.unregisterAll(plugin)
        papi?.let(PapiHook::unregister)
        papi = null
        listeners?.close()
        listeners = null
        try {
            xplots?.stop()?.get(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            platform.log.warn("Stopping, xPlots' database didn't finish saving in time: $e")
        }
        platform.close()
        this.platform = null
        xplots = null
    }

    private object Mclogs : co.xenastudios.core.common.admin.PasteService {
        override val name: String get() = "mclo.gs"

        override fun upload(text: String) = MclogsPaste().upload(text)
    }

    /** After enable, on an async task: the player cache and the lang files; then on the global thread, the lang files applied. */
    private class Steps(
        private val platform: BukkitPlatform,
        private val xplots: XPlotsPlugin,
        private val cache: PlayerCache,
        private val files: JvmFiles,
        private val config: Loaded,
    ) : (Task) -> Unit {
        private var loaded: Loaded? = null

        override fun invoke(task: Task) {
            val read = loaded
            if (read == null) {
                cache.load()
                loaded = files.translations(config.config, config.problems)
                platform.scheduler.run(Where.Global, action = this)
            } else {
                xplots.apply(read)
            }
        }
    }

    private companion object {
        const val BSTATS = 35419
    }
}

/**
 * The family's plugins on Bukkit, each found through its own class loader when it's first needed
 * and kept once found: xMoney (else Vault's economy), xRewind, xVanish and xTeleport. An economy
 * that isn't there yet is looked for again at most every five seconds, as economy plugins often
 * register after xPlots starts.
 */
internal class BukkitHooks(private val server: Server) : Hooks {
    @Volatile
    private var economy: Economy? = null

    @Volatile
    private var lookedAt = 0L

    @Volatile
    private var rewind: Rewind? = null

    @Volatile
    private var vanish: XVanishBridge? = null

    @Volatile
    private var vanishLookedAt = 0L

    private fun loader(name: String): ClassLoader? = server.pluginManager.getPlugin(name)?.takeIf { it.isEnabled }?.javaClass?.classLoader

    override fun economy(): Economy? {
        economy?.let { return it }
        val now = System.currentTimeMillis()
        if (now - lookedAt < 5_000) return null
        lookedAt = now
        val found = loader("xMoney")?.let(XMoneyBridge::find) ?: VaultMoney.find(server)
        economy = found
        return found
    }

    override fun rewind(): Rewind? = rewind ?: loader("xRewind")?.let(XRewindBridge::find)?.also { rewind = it }

    override fun vanished(viewer: PlayerRef, target: PlayerRef): Boolean {
        var bridge = vanish
        if (bridge == null) {
            val now = System.currentTimeMillis()
            if (now - vanishLookedAt < 5_000) return false
            vanishLookedAt = now
            bridge = loader("xVanish")?.let(XVanishBridge::find) ?: return false
            vanish = bridge
        }
        return bridge.hidden(viewer, target)
    }

    override fun registerHomes(homes: (PlayerRef) -> List<PlotHome>): AutoCloseable? = loader("xTeleport")?.let { XTeleportHomes.register(it, homes) }
}

/**
 * Money through whatever economy is registered with Vault, for servers without xMoney: EssentialsX's,
 * CMI's and the rest. Called by name through the class Vault registered it under, so xPlots needs
 * no Vault on its class path. Vault keeps no ledger xPlots can ask, so a renewal a restart cut
 * short is taken as paid ([charged] is `null`): never charged twice.
 */
internal class VaultMoney private constructor(
    private val provider: Any,
    private val hasMethod: Method,
    private val withdrawMethod: Method,
    private val depositMethod: Method,
    private val formatMethod: Method,
    private val success: Method,
    private val server: Server,
    providerName: String,
) : Economy {
    override val name: String = "Vault ($providerName)"

    private fun player(ref: PlayerRef): OfflinePlayer = server.getOfflinePlayer(ref.javaUuid)

    override fun has(player: PlayerRef, amount: Double): Boolean = try {
        hasMethod.invoke(provider, player(player), amount) == true
    } catch (e: Exception) {
        false
    }

    override fun withdraw(player: PlayerRef, amount: Double, reason: String): Pending<Boolean> = call(withdrawMethod, player, amount)

    override fun deposit(player: PlayerRef, amount: Double, reason: String): Pending<Boolean> = call(depositMethod, player, amount)

    private fun call(method: Method, player: PlayerRef, amount: Double): Pending<Boolean> = try {
        pendingOf(success.invoke(method.invoke(provider, player(player), amount)) == true)
    } catch (e: Exception) {
        pendingOf(false)
    }

    override fun format(amount: Double): String? = try {
        formatMethod.invoke(provider, amount) as String?
    } catch (e: Exception) {
        null
    }

    override fun charged(player: PlayerRef, marker: String): Pending<Boolean?> = pendingOf(null)

    companion object {
        /** The economy registered with Vault, or `null` without one. */
        fun find(server: Server): VaultMoney? = try {
            val services = server.servicesManager
            val type = services.knownServices.firstOrNull { it.name == "net.milkbowl.vault.economy.Economy" }
            val registration = type?.let { services.getRegistration(it) }
            val provider = registration?.provider
            if (provider == null) {
                null
            } else {
                val offline = OfflinePlayer::class.java
                val double = Double::class.javaPrimitiveType
                val withdraw = type.getMethod("withdrawPlayer", offline, double)
                VaultMoney(
                    provider, type.getMethod("has", offline, double), withdraw, type.getMethod("depositPlayer", offline, double),
                    type.getMethod("format", double), withdraw.returnType.getMethod("transactionSuccess"), server,
                    (type.getMethod("getName").invoke(provider) as? String) ?: registration.plugin.name,
                )
            }
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }
    }
}
