@file:Suppress("DEPRECATION")

package co.xenastudios.xbans.bukkit

import co.xenastudios.core.bukkit.ref
import co.xenastudios.xbans.XBansPlugin
import co.xenastudios.xbans.config.XBansConfig
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerChatEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerEditBookEvent
import org.bukkit.event.player.PlayerEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerLoginEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import java.util.concurrent.TimeUnit
import kotlin.uuid.toKotlinUuid

/**
 * Keeps banned players out, from memory alone: no database query on the way in
 * (decisions/2026-11-18-xbans.md, "Three checks"). At pre-login, off the server's threads: the
 * main check, which waits for the bans to load if the server has only just started. As the
 * connection finishes (Paper's `PlayerConnectionValidateLoginEvent`, or Bukkit's login event), and
 * again at join, for a ban made while the player was connecting.
 */
internal class Gate(private val xbans: XBansPlugin, private val server: BukkitBansServer, private val validates: Boolean) : Listener {
    // Highest, so a plugin that allows logins later can't let a banned player back in.
    @EventHandler(priority = EventPriority.HIGHEST)
    fun preLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        val player = event.uniqueId.toKotlinUuid().let(co.xenastudios.core.api.PlayerRef::of)
        if (!waitForBans()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, server.legacy(xbans.denied(player, event.name, null)))
            return
        }
        val ban = xbans.banOnJoin(player, event.address?.hostAddress) ?: return
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, server.legacy(xbans.denied(player, event.name, ban)))
    }

    // Where Paper has no PlayerConnectionValidateLoginEvent: the connection's last check before the world.
    @EventHandler(priority = EventPriority.HIGHEST)
    fun login(event: PlayerLoginEvent) {
        if (validates || event.result != PlayerLoginEvent.Result.ALLOWED) return
        val player = event.player.ref
        if (xbans.backend == null) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, server.legacy(xbans.denied(player, event.player.name, null)))
            return
        }
        val ban = xbans.banOnJoin(player, event.address?.hostAddress) ?: return
        event.disallow(PlayerLoginEvent.Result.KICK_BANNED, server.legacy(xbans.denied(player, event.player.name, ban)))
    }

    @Suppress("DEPRECATION")
    @EventHandler(priority = EventPriority.LOWEST)
    fun join(event: PlayerJoinEvent) {
        val player = event.player
        val ref = player.ref
        val ban = xbans.joined(ref, player.name, player.address?.address?.hostAddress)
        if (xbans.backend == null || ban != null) {
            event.joinMessage = null
            xbans.sessions.quietQuit(ref)
            player.kickPlayer(server.legacy(xbans.denied(ref, player.name, ban)))
        }
    }

    @Suppress("DEPRECATION")
    @EventHandler(priority = EventPriority.LOWEST)
    fun quit(event: PlayerQuitEvent) {
        if (xbans.quit(event.player.ref)) event.quitMessage = null
    }

    private fun waitForBans(): Boolean {
        if (xbans.backend != null) return true
        return try {
            xbans.ready.get(XBansPlugin.WAIT_SECONDS, TimeUnit.SECONDS) == true && xbans.backend != null
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Keeps muted players quiet: chat, the configured commands (by any alias or namespace), and, while
 * they're on, signs and books (anvils in [Anvils]). Each check is a map lookup for the player and
 * their IP; only a muted player's command is looked up further.
 */
internal class MuteGuard(private val xbans: XBansPlugin, private val server: BukkitBansServer) : Listener, co.xenastudios.xbans.bukkit.modern.Tell {
    // What Paper's listeners ask of the adapter: the screen a player turned away sees.
    override fun screen(player: co.xenastudios.core.api.PlayerRef, name: String, ban: co.xenastudios.xbans.model.Entry?): String = server.legacy(xbans.denied(player, name, ban))

    override fun tell(player: Player) {
        val mute = xbans.muteOf(player.ref) ?: return
        xbans.mutedMessage(player.ref, mute)?.let { server.send(player.ref, it) }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun command(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        if (xbans.muteOf(player.ref) == null) return
        if (xbans.blocksCommand(event.message)) {
            event.isCancelled = true
            tell(player)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun sign(event: SignChangeEvent) {
        if (!xbans.config[XBansConfig.mutes.blockSigns]) return
        if (event.lines.none { it != null && ChatColor.stripColor(it)?.isNotBlank() == true }) return
        if (xbans.muteOf(event.player.ref) != null) {
            event.isCancelled = true
            tell(event.player)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun book(event: PlayerEditBookEvent) {
        if (!xbans.config[XBansConfig.mutes.blockBooks]) return
        if (xbans.muteOf(event.player.ref) != null) {
            event.isCancelled = true
            tell(event.player)
        }
    }
}

/**
 * Chat on Bukkit's chat events, `AsyncPlayerChatEvent` and `PlayerChatEvent`, cancelled at the
 * lowest priority and again at the highest, so a plugin that un-cancels chat in between can't let
 * a muted player through (decisions/2026-11-18-xbans.md, "Mutes"). The asynchronous one is always
 * guarded: it costs one event per message, off the server's threads. The synchronous one can't
 * be (a listener on it moves every message onto the main thread), so it's guarded only while
 * another plugin listens to it, which each message's asynchronous event checks first. Paper's
 * `AsyncChatEvent` has guards of its own (`PaperChat`).
 */
@Suppress("DEPRECATION")
internal class LegacyChat(private val xbans: XBansPlugin, private val plugin: Plugin, private val mutes: MuteGuard) {
    private val async = Guard(AsyncPlayerChatEvent::class.java, true)
    private val sync = Guard(PlayerChatEvent::class.java, false)

    fun on() {
        async.on()
    }

    fun off() {
        async.off()
        sync.off()
    }

    private inner class Guard(private val type: Class<out PlayerEvent>, private val checks: Boolean) : Listener {
        @Volatile
        private var on = false

        private val first = Stop(true)
        private val last = Stop(false)

        @Synchronized
        fun refresh() {
            val others = handlers().registeredListeners.any { it.plugin !== plugin }
            if (others) on() else off()
        }

        @Synchronized
        fun on() {
            if (on) return
            plugin.server.pluginManager.registerEvent(type, this, EventPriority.LOWEST, first, plugin, false)
            plugin.server.pluginManager.registerEvent(type, this, EventPriority.HIGHEST, last, plugin, false)
            on = true
        }

        @Synchronized
        fun off() {
            if (!on) return
            handlers().unregister(this)
            on = false
        }

        @Suppress("DEPRECATION")
        private fun handlers(): HandlerList = if (type == AsyncPlayerChatEvent::class.java) AsyncPlayerChatEvent.getHandlerList() else PlayerChatEvent.getHandlerList()

        // Each priority's executor, as a class rather than a lambda.
        private inner class Stop(private val first: Boolean) : EventExecutor {
            override fun execute(listener: Listener, event: Event) {
                if (!type.isInstance(event) || event !is Cancellable) return
                if (first && checks) sync.refresh()
                val player = (event as PlayerEvent).player
                if (first || !event.isCancelled) {
                    if (xbans.muteOf(player.ref) != null) {
                        if (first && !event.isCancelled) mutes.tell(player)
                        event.isCancelled = true
                    }
                }
            }
        }
    }
}
