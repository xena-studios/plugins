package co.xenastudios.xrewind.api.bukkit

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.xrewind.api.Actor
import co.xenastudios.xrewind.api.Query
import co.xenastudios.xrewind.api.XRewind
import co.xenastudios.xrewind.api.event.LookupEvent
import co.xenastudios.xrewind.api.event.RollbackEvent
import co.xenastudios.xrewind.api.event.RollbackFinishedEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.block.data.BlockData
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.inventory.ItemStack

/**
 * xRewind's API with Bukkit's own types: a [Location], [BlockData], an [ItemStack] with its name
 * and enchantments, an [OfflinePlayer]. Everything here calls [XRewind.api].
 *
 * @since 2.0.0
 */
public object BukkitRewind {
    /** [player] as an actor. */
    @JvmStatic
    public fun actor(player: OfflinePlayer): Actor = Actor.player(PlayerRef.of(kotlin.uuid.Uuid.fromLongs(player.uniqueId.mostSignificantBits, player.uniqueId.leastSignificantBits)),
        player.name ?: throw IllegalArgumentException("That player has never joined, so has no name"))

    private fun world(location: Location): WorldRef = WorldRef.of(location.world?.name ?: throw IllegalArgumentException("The location has no world"))

    /** Logs [placed] put down at [location]. Any thread. */
    @JvmStatic
    public fun logPlacement(actor: Actor, location: Location, placed: BlockData) {
        XRewind.api().logPlacement(actor, world(location), location.blockX, location.blockY, location.blockZ, placed.asString)
    }

    /** Logs [removed] taken away at [location]. Any thread. */
    @JvmStatic
    public fun logRemoval(actor: Actor, location: Location, removed: BlockData) {
        XRewind.api().logRemoval(actor, world(location), location.blockX, location.blockY, location.blockZ, removed.asString)
    }

    /** Logs the block at [location] changed from [before] to [after]. Any thread. */
    @JvmStatic
    public fun logChange(actor: Actor, location: Location, before: BlockData, after: BlockData) {
        XRewind.api().logChange(actor, world(location), location.blockX, location.blockY, location.blockZ, before.asString, after.asString)
    }

    /**
     * Logs [amount] of [item] put into the container at [location] (negative: taken out), with
     * its name and enchantments, so a rollback puts the same item back. Any thread.
     */
    @JvmStatic
    public fun logContainerChange(actor: Actor, location: Location, item: ItemStack, amount: Int) {
        co.xenastudios.xrewind.bukkit.ApiItems.log(actor, location, item, amount)
    }

    /** A query for [blocks] around [center]. */
    @JvmStatic
    public fun radius(builder: Query.Builder, center: Location, blocks: Int): Query.Builder =
        builder.radius(world(center), center.blockX, center.blockY, center.blockZ, blocks)
}

private fun async(): Boolean = !Bukkit.isPrimaryThread()

/** [LookupEvent] as a Bukkit event, for plugins and Skript scripts listening the Bukkit way. @since 2.0.0 */
public class BukkitLookupEvent(private val event: LookupEvent) : Event(async()) {
    public fun getPlayer(): PlayerRef? = event.player
    public fun getName(): String? = event.name
    public fun getQuery(): Query = event.query
    public fun getPage(): Int = event.page
    public fun getEvent(): LookupEvent = event
    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [RollbackEvent] as a Bukkit event: cancelling it stops the rollback. On the global thread. @since 2.0.0 */
public class BukkitRollbackEvent(private val event: RollbackEvent) : Event(async()), Cancellable {
    public fun getPlan(): co.xenastudios.xrewind.api.Plan = event.plan
    public fun getPlayer(): PlayerRef? = event.player
    public fun getName(): String? = event.name
    public fun getEvent(): RollbackEvent = event
    override fun isCancelled(): Boolean = event.isCancelled
    override fun setCancelled(cancel: Boolean) {
        event.isCancelled = cancel
    }
    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [RollbackFinishedEvent] as a Bukkit event. On the global thread. @since 2.0.0 */
public class BukkitRollbackFinishedEvent(private val event: RollbackFinishedEvent) : Event(async()) {
    public fun getPlan(): co.xenastudios.xrewind.api.Plan = event.plan
    public fun getResult(): co.xenastudios.xrewind.api.RollbackResult = event.result
    public fun getPlayer(): PlayerRef? = event.player
    public fun getName(): String? = event.name
    public fun getEvent(): RollbackFinishedEvent = event
    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}
