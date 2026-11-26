package co.xenastudios.xmoney.api.bukkit

import co.xenastudios.xmoney.api.event.BalanceChangedEvent
import co.xenastudios.xmoney.api.event.PreTransactionEvent
import java.math.BigDecimal
import java.util.UUID
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * [PreTransactionEvent] as a Bukkit event, for plugins and Skript scripts listening the Bukkit
 * way: a balance is about to change. Cancelling it refuses the change, as cancelling xMoney's own
 * event does. Fired on whichever thread made the change, so often async.
 *
 * @since 2.0.0
 */
public class BukkitPreTransactionEvent(async: Boolean, private val event: PreTransactionEvent) : Event(async), Cancellable {
    /** Whose balance changes (the payer, for a transfer). */
    public fun getPlayer(): UUID = uuid(event.player.toString())

    /** How much it changes by: negative when money leaves it. */
    public fun getChange(): BigDecimal = BigDecimal.valueOf(event.change.units, 4).stripTrailingZeros()

    public fun getKind(): String = event.kind.name

    public fun getSource(): String = event.source

    public fun getReason(): String = event.reason

    /** The other player in a payment or transfer, or `null`. */
    public fun getOther(): UUID? = event.other?.let { uuid(it.toString()) }

    /** xMoney's own event, the same on every platform. */
    public fun getEvent(): PreTransactionEvent = event

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

/**
 * [BalanceChangedEvent] as a Bukkit event: a balance changed and the change is saved. Always async.
 *
 * @since 2.0.0
 */
public class BukkitBalanceChangedEvent(private val event: BalanceChangedEvent) : Event(true) {
    public fun getPlayer(): UUID = uuid(event.player.toString())

    /** How much it changed by: negative when money left. */
    public fun getChange(): BigDecimal = BigDecimal.valueOf(event.change.units, 4).stripTrailingZeros()

    /** The balance after. */
    public fun getBalance(): BigDecimal = BigDecimal.valueOf(event.balance.units, 4).stripTrailingZeros()

    public fun getKind(): String = event.kind.name

    public fun getSource(): String = event.source

    public fun getReason(): String = event.reason

    public fun getOther(): UUID? = event.other?.let { uuid(it.toString()) }

    /** xMoney's own event, the same on every platform. */
    public fun getEvent(): BalanceChangedEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

private fun uuid(text: String): UUID = UUID.fromString(text)
