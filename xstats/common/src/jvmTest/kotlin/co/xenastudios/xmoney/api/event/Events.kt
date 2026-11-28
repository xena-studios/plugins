package co.xenastudios.xmoney.api.event

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.xmoney.api.Amount
import co.xenastudios.xmoney.api.TransactionKind

/** xMoney's BalanceChangedEvent, as its API has it (xmoney/api). */
class BalanceChangedEvent(
    val player: PlayerRef,
    val change: Amount,
    val balance: Amount,
    val kind: TransactionKind,
    val source: String,
    val reason: String,
    val other: PlayerRef?,
) : Event {
    companion object {
        @JvmField
        val TYPE: EventType<BalanceChangedEvent> = EventType.of("xmoney:balance_changed")
    }
}
