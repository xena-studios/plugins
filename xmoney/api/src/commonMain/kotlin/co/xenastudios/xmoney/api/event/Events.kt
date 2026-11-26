package co.xenastudios.xmoney.api.event

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.xmoney.api.Amount
import co.xenastudios.xmoney.api.TransactionKind
import kotlin.jvm.JvmField

/**
 * A balance is about to change: for a tax, an anti-cheat or a limit plugin to refuse it.
 * Cancelling it refuses the change, and the caller is told
 * [co.xenastudios.xmoney.api.TransactionResult.Status.CANCELLED].
 *
 * Fired on whichever thread made the change (a shop's async task, a command's thread), and
 * only when something listens. Keep listeners short: a shop is waiting. Not fired for imports.
 * On Bukkit it's also `co.xenastudios.xmoney.api.bukkit.BukkitPreTransactionEvent`.
 *
 * @since 1.0.0
 */
public class PreTransactionEvent(
    /** Whose balance is about to change: the payer, for a payment or transfer. */
    public val player: PlayerRef,
    /** How much it changes by: negative when money leaves it. */
    public val change: Amount,
    public val kind: TransactionKind,
    /** Who or what is moving it: a plugin's name, a player's, or `Console`. */
    public val source: String,
    /** The few words given with it, or empty. */
    public val reason: String,
    /** The other player in a payment or transfer, or `null`. */
    public val other: PlayerRef?,
) : CancellableEvent() {
    public companion object {
        /** Subscribe to it on [co.xenastudios.xmoney.api.XMoneyApi.events]. */
        @JvmField
        public val TYPE: EventType<PreTransactionEvent> = EventType.of("xmoney:pre_transaction")
    }
}

/**
 * A balance changed, and the change is saved. One for each account in a payment: the payer's
 * and the payee's.
 *
 * Fired off the server's threads, after the database has the change, in the order the changes
 * were made, and only when something listens. On a network, only on the server that made it.
 * On Bukkit it's also `co.xenastudios.xmoney.api.bukkit.BukkitBalanceChangedEvent`.
 *
 * @since 1.0.0
 */
public class BalanceChangedEvent(
    public val player: PlayerRef,
    /** How much it changed by: negative when money left. */
    public val change: Amount,
    /** The balance after. */
    public val balance: Amount,
    public val kind: TransactionKind,
    public val source: String,
    public val reason: String,
    /** The other player in a payment or transfer, or `null`. */
    public val other: PlayerRef?,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<BalanceChangedEvent> = EventType.of("xmoney:balance_changed")
    }
}
