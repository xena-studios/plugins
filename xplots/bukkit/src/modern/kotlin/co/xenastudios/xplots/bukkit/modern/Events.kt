package co.xenastudios.xplots.bukkit.modern

import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.TNTPrimeEvent
import org.bukkit.event.player.PlayerTakeLecternBookEvent

/** What the listeners here ask xPlots: its protection's decisions, from the main source set. */
internal interface Guard {
    fun mayBuild(player: Player, block: Block): Boolean
    fun crosses(from: Block, to: Block): Boolean
    fun tell(player: Player)
}

/** 1.14 on: taking a lectern's book is using what's in the plot. */
internal class Lecterns(private val guard: Guard) : Listener {
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onLectern(event: PlayerTakeLecternBookEvent) {
        if (!guard.mayBuild(event.player, event.lectern.block)) {
            event.isCancelled = true
            guard.tell(event.player)
        }
    }
}

/** 1.19.4 on: TNT primed by a player, or by fire or redstone from another plot. */
internal class Priming(private val guard: Guard) : Listener {
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPrime(event: TNTPrimeEvent) {
        val player = event.primingEntity as? Player
        if (player != null) {
            if (!guard.mayBuild(player, event.block)) {
                event.isCancelled = true
                guard.tell(player)
            }
            return
        }
        val from = event.primingBlock ?: return
        if (guard.crosses(from, event.block)) event.isCancelled = true
    }
}
