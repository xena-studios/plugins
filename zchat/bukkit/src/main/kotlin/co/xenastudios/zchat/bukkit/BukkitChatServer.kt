package co.xenastudios.zchat.bukkit

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.config.Sound
import co.xenastudios.core.common.text.Component
import co.xenastudios.zchat.ChatServer
import co.xenastudios.zchat.ChatText
import co.xenastudios.zchat.bukkit.modern.select
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.Plugin
import kotlin.concurrent.Volatile

/**
 * What zChat does to players on a Bukkit-family server, 1.8.8 on. Chat arrives on its own threads
 * (async on Paper, Folia and Spigot), and Bukkit's player lookup, permission checks and messages
 * are what 3.x called there too. Picked once at startup: selectors where the server has them for
 * plugins (1.12.2 on, not Folia), PlaceholderAPI while it's enabled.
 */
internal class BukkitChatServer(private val plugin: Plugin, private val platform: BukkitPlatform) : ChatServer, Listener {
    private val bukkit = plugin.server

    private val selectors = platform.threading != co.xenastudios.core.common.platform.Threading.REGIONS && platform.server.atLeast(1, 12, 2)

    // Read on every chat line, off the main thread, so a flag rather than a plugin-manager lookup.
    @Volatile
    private var papi = bukkit.pluginManager.isPluginEnabled(PAPI)

    private fun player(ref: PlayerRef): Player? = ref.online(bukkit)

    override fun name(player: PlayerRef): String? = player(player)?.name

    @Suppress("DEPRECATION")
    override fun displayName(player: PlayerRef): Component {
        val p = player(player) ?: return Component.EMPTY
        // The legacy display name every version has; a nickname plugin's colours come as § codes.
        return ChatText.legacy(p.displayName)
    }

    override fun has(player: PlayerRef, node: String): Boolean = player(player)?.hasPermission(node) == true

    override fun canSee(viewer: PlayerRef, target: PlayerRef): Boolean {
        val v = player(viewer) ?: return true
        val t = player(target) ?: return true
        return v.canSee(t)
    }

    override fun send(player: PlayerRef, message: Component) {
        player(player)?.let { platform.text.send(it, message) }
    }

    override fun console(message: Component) {
        platform.text.send(bukkit.consoleSender, message)
    }

    override fun playSound(player: PlayerRef, sound: Sound) {
        if (sound.isNone) return
        val p = player(player) ?: return
        p.playSound(p.location, sound.name, sound.volume, sound.pitch)
    }

    override val placeholders: Boolean get() = papi

    override fun placeholder(player: PlayerRef, identifier: String): String? {
        if (!papi) return null
        val p = player(player) ?: return null
        return Papi.value(p, identifier)
    }

    override fun select(source: CommandSource, selector: String): List<PlayerRef>? {
        if (!selectors) return null
        val sender = source.player?.let(::player) ?: bukkit.consoleSender
        return select(sender, selector).map { it.ref }
    }

    @EventHandler
    fun onEnable(event: PluginEnableEvent) {
        if (event.plugin.name == PAPI) papi = true
    }

    @EventHandler
    fun onDisable(event: PluginDisableEvent) {
        if (event.plugin.name == PAPI) papi = false
    }

    private companion object {
        const val PAPI = "PlaceholderAPI"
    }
}
