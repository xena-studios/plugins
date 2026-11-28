package co.xenastudios.xrewind.bukkit

import co.xenastudios.core.api.event.EventPriority as CorePriority
import co.xenastudios.core.api.event.Subscription
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.bukkit.ref
import co.xenastudios.xrewind.Heard
import co.xenastudios.xrewind.Inspecting
import co.xenastudios.xrewind.ServerEvents
import co.xenastudios.xrewind.Spot
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.data.Openable
import org.bukkit.block.data.Powerable
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin

/**
 * What the world hooks don't cover, heard the Bukkit way at MONITOR, each kind registered only
 * while it's logged: joins and quits, signs, commands and clicks; chat through the core's chat
 * bridge. The inspector's are [Inspection]'s.
 */
internal object Listeners {
    private fun spot(at: Location): Spot = Spot(at.world!!.name, at.blockX, at.blockY, at.blockZ)

    private fun spot(block: Block): Spot = Spot(block.world.name, block.x, block.y, block.z)

    fun listen(plugin: Plugin, platform: BukkitPlatform, kinds: Set<Heard>, events: ServerEvents): AutoCloseable {
        val listeners = ArrayList<Listener>()
        if (Heard.SESSIONS in kinds) listeners += Sessions(events)
        if (Heard.SIGNS in kinds) listeners += Signs(events)
        if (Heard.COMMANDS in kinds) listeners += Commands(events)
        if (Heard.CLICKS in kinds) listeners += Clicks(events)
        listeners.forEach { plugin.server.pluginManager.registerEvents(it, plugin) }
        var chat: Subscription? = null
        var bridge: co.xenastudios.core.common.chat.ChatBridge? = null
        if (Heard.CHAT in kinds) {
            val made = platform.chat(CorePriority.MONITOR)
            bridge = made
            chat = made.listen(CorePriority.MONITOR, true) { event ->
                // Off the server's threads: only the player's world, not where they are.
                val world = event.sender.online(plugin.server)?.world?.name ?: return@listen
                events.chat(event.sender, event.senderName, world, event.originalMessage)
            }
        }
        return AutoCloseable {
            listeners.forEach(HandlerList::unregisterAll)
            chat?.cancel()
            bridge?.close()
        }
    }

    private class Sessions(private val events: ServerEvents) : Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        fun onJoin(event: PlayerJoinEvent) {
            val p = event.player
            events.joined(p.ref, p.name, spot(p.location))
        }

        @EventHandler(priority = EventPriority.MONITOR)
        fun onQuit(event: PlayerQuitEvent) {
            val p = event.player
            events.quit(p.ref, p.name, spot(p.location))
        }
    }

    private class Signs(private val events: ServerEvents) : Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onSign(event: SignChangeEvent) {
            @Suppress("DEPRECATION")
            val lines = event.lines.mapNotNull { line -> line?.let(org.bukkit.ChatColor::stripColor)?.trim()?.takeIf { it.isNotEmpty() } }
            if (lines.isEmpty()) return
            events.sign(event.player.ref, event.player.name, spot(event.block), lines.joinToString(" | "))
        }
    }

    private class Commands(private val events: ServerEvents) : Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onCommand(event: PlayerCommandPreprocessEvent) {
            val p = event.player
            events.command(p.ref, p.name, spot(p.location), event.message)
        }
    }

    // Doors, trapdoors, gates, levers and buttons players use: "who opened my door".
    private class Clicks(private val events: ServerEvents) : Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        fun onClick(event: PlayerInteractEvent) {
            val block = event.clickedBlock ?: return
            if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND || event.useInteractedBlock() == Event.Result.DENY) return
            val data = block.blockData
            if (data is Openable || data is Powerable) events.click(event.player.ref, event.player.name, spot(block), data.asString)
        }
    }
}
