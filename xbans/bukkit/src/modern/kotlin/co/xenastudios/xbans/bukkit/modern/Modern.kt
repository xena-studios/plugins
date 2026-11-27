package co.xenastudios.xbans.bukkit.modern

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.xbans.XBansPlugin
import co.xenastudios.xbans.config.XBansConfig
import io.papermc.paper.connection.PlayerConfigurationConnection
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.inventory.AnvilInventory
import java.net.InetSocketAddress
import kotlin.uuid.toKotlinUuid

// What only newer servers have, compiled against Paper's API. Each is registered only once a check
// at startup has found what it uses (XBansBukkit), so an old server never loads this file's classes.

/** Tells a muted player they're muted: the adapter's, which this file can't name. */
internal interface Tell {
    fun tell(player: Player)

    /** What [player] is shown when they're turned away, by [ban] or while the bans aren't read: a `§` string. */
    fun screen(player: PlayerRef, name: String, ban: co.xenastudios.xbans.model.Entry?): String
}

/** Paper's `AsyncChatEvent`, cancelled first and again last, as Bukkit's chat events are (LegacyChat). */
internal class PaperChat(private val xbans: XBansPlugin, private val tell: Tell) : Listener {
    // Lowest, so the message is stopped before any chat plugin formats or sends it. Already
    // cancelled means the older chat events' guard stopped it and told them.
    @EventHandler(priority = EventPriority.LOWEST)
    fun chat(event: AsyncChatEvent) {
        if (xbans.muteOf(PlayerRef.of(event.player.uniqueId.toKotlinUuid())) == null) return
        if (!event.isCancelled) tell.tell(event.player)
        event.isCancelled = true
    }

    // And again last, so a plugin that un-cancels chat in between can't let it through.
    @EventHandler(priority = EventPriority.HIGHEST)
    fun chatAgain(event: AsyncChatEvent) {
        if (!event.isCancelled && xbans.muteOf(PlayerRef.of(event.player.uniqueId.toKotlinUuid())) != null) event.isCancelled = true
    }
}

/**
 * Paper's last check of a connection, as it finishes configuring (1.21.7 on): a ban made while the
 * player was connecting turns them away before they're in the world.
 */
internal class PaperValidate(private val xbans: XBansPlugin, private val tell: Tell) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    fun validate(event: PlayerConnectionValidateLoginEvent) {
        if (!event.isAllowed) return
        val connection = event.connection as? PlayerConfigurationConnection ?: return
        val profile = connection.profile
        val id = profile.id ?: return
        val player = PlayerRef.of(id.toKotlinUuid())
        val name = profile.name ?: id.toString()
        if (xbans.backend == null) {
            event.kickMessage(LegacyComponentSerializer.legacySection().deserialize(tell.screen(player, name, null)))
            return
        }
        val address = (connection.address as? InetSocketAddress)?.address?.hostAddress
        val ban = xbans.banOnJoin(player, address) ?: return
        event.kickMessage(LegacyComponentSerializer.legacySection().deserialize(tell.screen(player, name, ban)))
    }
}

/** Renaming items on an anvil, while `mutes.block-anvils` is on (1.11 on): a name is something others read. */
internal class Anvils(private val xbans: XBansPlugin) : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    fun rename(event: PrepareAnvilEvent) {
        if (!xbans.config[XBansConfig.mutes.blockAnvils]) return
        val inventory: AnvilInventory = event.inventory
        @Suppress("DEPRECATION")
        val text = inventory.renameText
        if (text.isNullOrBlank()) return
        val player = event.view.player as? Player ?: return
        val item = inventory.getItem(0)
        @Suppress("DEPRECATION")
        if (item != null && item.hasItemMeta() && item.itemMeta?.hasDisplayName() == true && text == org.bukkit.ChatColor.stripColor(item.itemMeta?.displayName)) return
        if (xbans.muteOf(PlayerRef.of(player.uniqueId.toKotlinUuid())) != null) event.result = null
    }
}
