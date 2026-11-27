package co.xenastudios.xmarket

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.xmarket.api.Money
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture

/**
 * Money through xMoney 2.0's API, exactly: xMoney counts in 1/10,000ths as xMarket does, so an
 * amount crosses as its units, never a double. Every purchase and sale is in xMoney's ledger as
 * `xMarket`, with the item named (`/eco history` shows "xMarket: bought 16 diamond"). xMoney has
 * its own copy of the core, so its types aren't ours: its API is called by name, through the
 * class loader that loaded it, its methods looked up once ([find]).
 */
public class XMoneyBridge private constructor(
    private val api: Any,
    private val has: Method,
    private val withdraw: Method,
    private val deposit: Method,
    private val format: Method,
    private val parseRef: Method,
    private val ofUnits: Method,
    private val isSuccess: Method,
) : Economy {
    override val name: String get() = "xMoney"

    private fun amount(money: Money): Any = ofUnits.invoke(null, money.units)

    override fun has(player: PlayerRef, amount: Money): Boolean = try {
        has.invoke(api, parseRef.invoke(null, player.toString()), amount(amount)) == true
    } catch (e: Exception) {
        false
    }

    override fun withdraw(player: PlayerRef, amount: Money, reason: String): Pending<Boolean> = call(withdraw, player, amount, reason)

    override fun deposit(player: PlayerRef, amount: Money, reason: String): Pending<Boolean> = call(deposit, player, amount, reason)

    private fun call(method: Method, player: PlayerRef, amount: Money, reason: String): Pending<Boolean> = try {
        @Suppress("UNCHECKED_CAST")
        val future = method.invoke(api, XMarketPlugin.NAME, parseRef.invoke(null, player.toString()), amount(amount), reason.removePrefix("xMarket: ")) as CompletableFuture<Any?>
        future.handle { result, error -> error == null && result != null && isSuccess.invoke(result) == true }
    } catch (e: Exception) {
        pendingOf(false)
    }

    override fun format(amount: Money): String? = try {
        format.invoke(api, amount(amount)) as String
    } catch (e: Exception) {
        null
    }

    public companion object {
        /** xMoney's API, if xMoney is enabled where [loader] can see it; `null` otherwise. */
        @JvmStatic
        public fun find(loader: ClassLoader): XMoneyBridge? = try {
            val entry = Class.forName("co.xenastudios.xmoney.api.XMoney", true, loader)
            if (entry.getMethod("isEnabled").invoke(null) != true) return null
            val api = entry.getMethod("api").invoke(null)
            val type = Class.forName("co.xenastudios.xmoney.api.XMoneyApi", false, loader)
            val withdraw = type.methods.first { it.name == "withdraw" && it.parameterCount == 4 }
            val deposit = type.methods.first { it.name == "deposit" && it.parameterCount == 4 }
            val ref = withdraw.parameterTypes[1]
            val money = withdraw.parameterTypes[2]
            val result = Class.forName("co.xenastudios.xmoney.api.TransactionResult", false, loader)
            XMoneyBridge(
                api, type.getMethod("has", ref, money), withdraw, deposit, type.getMethod("format", money),
                ref.getMethod("parseOrNull", String::class.java), money.getMethod("ofUnits", Long::class.javaPrimitiveType), result.getMethod("isSuccess"),
            )
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }
    }
}
