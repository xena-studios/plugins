package co.xenastudios.xvote.bukkit

import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.xvote.FileImports
import co.xenastudios.xvote.Json
import co.xenastudios.xvote.JvmDatabase
import co.xenastudios.xvote.JvmFiles
import co.xenastudios.xvote.JvmZones
import co.xenastudios.xvote.Loaded
import co.xenastudios.xvote.XMoneyEconomy
import co.xenastudios.xvote.XVotePlugin
import co.xenastudios.xvote.api.VoteSource
import co.xenastudios.xvote.api.XVoteApi
import co.xenastudios.xvote.config.XVoteConfig
import co.xenastudios.xvote.receiver.ReceiverHost
import co.xenastudios.xvote.vote.IncomingVote
import java.util.concurrent.TimeUnit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener

/**
 * xVote on Paper, Folia, Spigot and CraftBukkit, 1.8.8 on: the shared plugin on platform-bukkit,
 * loaded by the core's Java shim once the stdlib is there. It wires the shared code to the server:
 *
 * - `config.yml` is read as the plugin enables, so its commands are the server's from the start;
 *   the database is opened, migrated and every voter read on an async task, never the server
 *   thread, then the receiver listens on its own thread.
 * - Joins pay what's waiting for a player, wherever the vote arrived; quits are kept.
 * - Money through xMoney's API, or Vault's economy; PlaceholderAPI's placeholders while it's on.
 * - Behind a proxy with network mode on, the proxy layer's channel brings the proxy's votes at once;
 *   with `receiver.nuvotifier-channel`, NuVotifier's forwarded votes.
 */
public class XVoteBukkit(private val plugin: JavaPlugin) : PluginEntry, Listener {

    private var platform: BukkitPlatform? = null
    private var xvote: XVotePlugin? = null
    private var placeholders: Any? = null
    private var nuvotifier: String? = null

    override fun enable() {
        val started = System.nanoTime()
        val platform = BukkitPlatform.create(plugin, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XVotePlugin.PLUGIN_ID)
        platform.record(cache)
        val server = BukkitVoteServer(platform, cache)
        val folder = plugin.dataFolder.toPath()
        val files = JvmFiles(folder, family, LangFolder.shipped(plugin.javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val config = files.config()
        val drivers = Drivers(plugin.javaClass.classLoader, platform.driverFetcher())
        val plugins = folder.toAbsolutePath().parent
        // The receiver hands votes to the plugin, made just below.
        var made: XVotePlugin? = null
        val receiver = ReceiverHost(folder, { vote -> made?.accept(vote) }, platform.log, "plugins/${plugin.name}/rsa/public.key", plugins, files::useReceiver)
        val xvote = XVotePlugin(
            platform, server, files, cache, platform.players, cache,
            opener = JvmDatabase(family, folder, plugins.resolve("Xena"), drivers, platform.log),
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            // -Dxena.debug.paste=false writes debug reports to the plugin's folder instead (boot tests do).
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            receiver = receiver,
            imports = FileImports(plugins, folder, drivers, platform.log, files::useReceiver) { ref ->
                plugin.server.getOfflinePlayer(java.util.UUID.fromString(ref.toString())).name
            },
            // Behind a proxy, read at startup: the channel opens once.
            channel = if (config.config[XVoteConfig.network.enabled]) platform.proxyChannel(XVotePlugin.PLUGIN_ID) else null,
            zones = JvmZones,
            version = plugin.description.version,
        )
        made = xvote
        this.platform = platform
        this.xvote = xvote
        platform.commands.feedback = xvote.texts.feedback
        xvote.beforeFirstEvent = EventBridge(plugin.server, xvote.bus)
        plugin.server.servicesManager.register(XVoteApi::class.java, xvote, plugin, ServicePriority.Normal)
        val pm = plugin.server.pluginManager
        pm.registerEvents(this, plugin)
        xvote.start(config)
        listenToNuVotifier(xvote, config.config[XVoteConfig.receiver.nuvotifierChannel].trim())
        xvote.open()
        platform.scheduler.run(Where.Async, action = Steps(platform, xvote, cache, files, config))
        xvote.updates = platform.checkUpdates(XVotePlugin.PLUGIN_ID, xvote.admin.updates, XVoteConfig.updates.settings(config.config)) { xvote.texts.translations }
        platform.metrics(BSTATS)
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xVote ${xvote.version} on ${platform.name}: ${platform.threading} threading, text as ${platform.text}, enabled in $millis ms")
    }

    override fun disable() {
        val platform = platform ?: return
        HandlerList.unregisterAll(this as Listener)
        nuvotifier?.let { plugin.server.messenger.unregisterIncomingPluginChannel(plugin, it) }
        unhookPlaceholders()
        plugin.server.servicesManager.unregisterAll(plugin)
        // Votes taken but not paid are given back before the database closes.
        try {
            xvote?.stop()?.get(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            platform.log.warn("Stopping, xVote's database didn't finish in time: $e")
        }
        platform.close()
        this.platform = null
        xvote = null
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        xvote?.joined(player.ref, player.name)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onQuit(event: PlayerQuitEvent) {
        val player: Player = event.player
        xvote?.quit(player.ref, player.name)
    }

    @EventHandler
    public fun onPluginEnable(event: PluginEnableEvent) {
        val xvote = xvote ?: return
        when (event.plugin.name) {
            "xMoney", "Vault" -> findEconomy(xvote)
            "PlaceholderAPI" -> hookPlaceholders(xvote)
        }
    }

    @EventHandler
    public fun onPluginDisable(event: PluginDisableEvent) {
        val xvote = xvote ?: return
        when (event.plugin.name) {
            "xMoney", "Vault" -> platform?.scheduler?.run(Where.Global) { _ -> findEconomy(xvote) }
            "PlaceholderAPI" -> placeholders = null
        }
    }

    /** xMoney's API first (it says why money moved), then Vault's economy, as 1.x paid. */
    private fun findEconomy(xvote: XVotePlugin) {
        val services = plugin.server.servicesManager
        val type = services.knownServices.firstOrNull { it.name == "co.xenastudios.xmoney.api.XMoneyApi" }
        val api = type?.let { services.getRegistration(it)?.provider }
        if (api != null) {
            val (economy, why) = XMoneyEconomy.of(api)
            if (economy != null) {
                if (xvote.economy?.name != economy.name) xvote.log.info("Money rewards are paid through xMoney.")
                xvote.economy = economy
                return
            }
            xvote.log.warn("Couldn't pay money rewards through xMoney: $why")
        }
        xvote.economy = VaultEconomy.find(platform ?: return)
    }

    private fun hookPlaceholders(xvote: XVotePlugin) {
        if (placeholders != null) return
        placeholders = try {
            PlaceholderHook.register(xvote, plugin.description.version)
        } catch (e: LinkageError) {
            xvote.log.warn("Couldn't add xVote's placeholders to PlaceholderAPI: $e")
            null
        }
    }

    private fun unhookPlaceholders() {
        val expansion = placeholders ?: return
        try {
            PlaceholderHook.unregister(expansion)
        } catch (e: LinkageError) {
            // PlaceholderAPI went first.
        }
        placeholders = null
    }

    // NuVotifier on the proxy, forwarding by plugin message: its JSON votes, back to back.
    private fun listenToNuVotifier(xvote: XVotePlugin, channel: String) {
        if (channel.isEmpty()) return
        try {
            plugin.server.messenger.registerIncomingPluginChannel(plugin, channel, NuVotifierListener(xvote))
            nuvotifier = channel
            xvote.log.info("Taking votes NuVotifier forwards on $channel. A player's client can send on it too: only keep this on while the proxy drops players' messages there.")
        } catch (e: IllegalArgumentException) {
            xvote.log.warn("receiver.nuvotifier-channel (\"$channel\") isn't a channel this server takes: ${e.message}")
        }
    }

    private class NuVotifierListener(private val xvote: XVotePlugin) : PluginMessageListener {
        override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
            val now = System.currentTimeMillis()
            val votes = try {
                Json.parseAll(String(message, Charsets.UTF_8))
            } catch (e: Json.Malformed) {
                return xvote.log.warn("A message on $channel wasn't NuVotifier's votes: ${e.message}")
            }
            for (vote in votes) {
                val fields = vote as? Map<*, *> ?: continue
                xvote.accept(
                    IncomingVote(
                        fields["serviceName"]?.toString(), fields["username"]?.toString(), fields["address"]?.toString(),
                        fields["timeStamp"]?.toString() ?: fields["timestamp"]?.toString(), VoteSource.FORWARDED, now,
                    ),
                )
            }
        }
    }

    /**
     * What happens after enable, as one class rather than a lambda each. On an async task first:
     * the player cache and the lang files read. Then on the global thread: the lang files applied,
     * the economy and the placeholders found.
     */
    private inner class Steps(
        private val platform: BukkitPlatform,
        private val xvote: XVotePlugin,
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
                xvote.apply(read)
                findEconomy(xvote)
                if (plugin.server.pluginManager.isPluginEnabled("PlaceholderAPI")) hookPlaceholders(xvote)
            }
        }
    }

    private companion object {
        // bstats.org/plugin/bukkit/xVote/35422, 1.x's.
        const val BSTATS = 35422
    }
}
