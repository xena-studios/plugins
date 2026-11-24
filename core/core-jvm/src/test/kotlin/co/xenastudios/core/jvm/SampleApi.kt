package co.xenastudios.core.jvm

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.platform.Log

/**
 * A plugin API written the way the rewrite's are, in Kotlin, for [JavaApiTest] to call from
 * Java: what a Java plugin sees of core-api.
 */
class SampleApi {
    val bus = SimpleEventBus(object : Log {
        override fun info(message: String) {}
        override fun warn(message: String, cause: Throwable?) {}
        override fun error(message: String, cause: Throwable?) {}
    })

    fun events(): EventBus = bus

    fun balance(player: PlayerRef): Pending<Long> = pendingOf(if (player.uuid.toString().startsWith("0")) 1000L else 0L)

    fun withdraw(player: PlayerRef, amount: Long): Boolean = !bus.fire(Withdraw.TYPE, Withdraw(player, amount)).isCancelled

    class Withdraw(val player: PlayerRef, val amount: Long) : CancellableEvent() {
        companion object {
            @JvmField
            val TYPE: EventType<Withdraw> = EventType.of("sample:withdraw")
        }
    }
}
