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

/** The inspector, at LOWEST, its listeners registered only while someone has it on: an object of its own, loaded then. */
internal object Inspection {
    fun start(plugin: Plugin, inspecting: Inspecting): AutoCloseable {
        val listener = Inspector(inspecting)
        plugin.server.pluginManager.registerEvents(listener, plugin)
        return AutoCloseable { HandlerList.unregisterAll(listener) }
    }

    /**
     * First, so nothing else acts on a click that's only a question, and it isn't logged:
     * clicking a block shows its history, placing one shows what was there, and nothing breaks.
     */
    private class Inspector(private val inspecting: Inspecting) : Listener {
        private fun on(player: Player) = inspecting.isInspecting(player.ref)

        @EventHandler(priority = EventPriority.LOWEST)
        fun onInteract(event: PlayerInteractEvent) {
            val clicked = event.clickedBlock ?: return
            if (event.hand != EquipmentSlot.HAND || !on(event.player)) return
            when (event.action) {
                Action.LEFT_CLICK_BLOCK -> Unit
                // Right-clicking with a block places it: that's handled as a place.
                Action.RIGHT_CLICK_BLOCK -> if (event.item?.type?.isBlock == true) return
                else -> return
            }
            event.isCancelled = true
            inspecting.inspect(event.player.ref, Spot(clicked.world.name, clicked.x, clicked.y, clicked.z))
        }

        @EventHandler(priority = EventPriority.LOWEST)
        fun onPlace(event: BlockPlaceEvent) {
            if (!on(event.player)) return
            event.isCancelled = true
            inspecting.inspect(event.player.ref, Spot(event.block.world.name, event.block.x, event.block.y, event.block.z))
        }

        @EventHandler(priority = EventPriority.LOWEST)
        fun onBreak(event: BlockBreakEvent) {
            if (on(event.player)) event.isCancelled = true
        }
    }
}
