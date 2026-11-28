package co.xenastudios.xmoney.api

import co.xenastudios.core.api.event.EventBus

// xMoney's API as xStats finds it, by name (xmoney/api): its holder, bus, amounts and kinds.

interface XMoneyApi {
    val events: EventBus
}

object XMoney {
    @Volatile
    private var running: XMoneyApi? = null

    @JvmStatic
    fun api(): XMoneyApi = running ?: throw IllegalStateException("xMoney isn't enabled")

    @JvmStatic
    fun isEnabled(): Boolean = running != null

    @JvmStatic
    fun register(api: XMoneyApi?) {
        running = api
    }
}

/** An amount in ten-thousandths, as xMoney's. */
class Amount private constructor(val units: Long) {
    companion object {
        fun of(whole: Long): Amount = Amount(whole * 10_000)
    }
}

enum class TransactionKind { START, PAY, GIVE, TAKE, SET, PLUGIN, IMPORT }
