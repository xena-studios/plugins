package co.xenastudios.xteleport

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.pendingOf
import java.lang.reflect.Method
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.CompletableFuture

/**
 * Teleport costs through xMoney 2.0's API, as `xTeleport` with the teleport as the reason, so
 * `/eco history` says what each was. xMoney has its own copy of the core, relocated into its own
 * package, so its `PlayerRef` and `Amount` aren't ours: the API is called by name, through the
 * class loader that loaded xMoney, its methods looked up once ([find]).
 */
public class XMoneyEconomy private constructor(
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

    private fun ref(player: PlayerRef): Any? = parseRef.invoke(null, player.toString())

    // Amounts are units of 1/10000; a cost is rounded up to them, as xMoney rounds a withdrawal.
    private fun amount(value: Double): Any = ofUnits.invoke(null, BigDecimal.valueOf(value).movePointRight(4).setScale(0, RoundingMode.CEILING).toLong())

    override fun has(player: PlayerRef, amount: Double): Boolean = try {
        has.invoke(api, ref(player), amount(amount)) as Boolean
    } catch (e: Exception) {
        false
    }

    override fun withdraw(player: PlayerRef, amount: Double, reason: String): Pending<Boolean> = change(withdraw, player, amount, reason)

    override fun deposit(player: PlayerRef, amount: Double, reason: String): Pending<Boolean> = change(deposit, player, amount, reason)

    private fun change(method: Method, player: PlayerRef, amount: Double, reason: String): Pending<Boolean> = try {
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(api, XTeleportPlugin.NAME, ref(player), amount(amount), reason) as CompletableFuture<Any?>
        result.handle { done, error -> error == null && done != null && isSuccess.invoke(done) == true }
    } catch (e: Exception) {
        pendingOf(false)
    }

    override fun format(amount: Double): String? = try {
        format.invoke(api, amount(amount)) as String
    } catch (e: Exception) {
        null
    }

    public companion object {
        private const val API = "co.xenastudios.xmoney.api.XMoney"

        /**
         * xMoney's API, if xMoney is enabled where [loader] (the loader of xMoney's own classes, or
         * the mods' shared one on Fabric) can see it; `null` otherwise.
         */
        @JvmStatic
        public fun find(loader: ClassLoader): XMoneyEconomy? = try {
            val entry = Class.forName(API, true, loader)
            if (entry.getMethod("isEnabled").invoke(null) != true) {
                null
            } else {
                val api = entry.getMethod("api").invoke(null)
                val type = Class.forName("co.xenastudios.xmoney.api.XMoneyApi", false, loader)
                val withdraw = type.methods.first { it.name == "withdraw" && it.parameterCount == 4 }
                val deposit = type.methods.first { it.name == "deposit" && it.parameterCount == 4 }
                val refType = withdraw.parameterTypes[1]
                val amountType = withdraw.parameterTypes[2]
                val has = type.getMethod("has", refType, amountType)
                val format = type.getMethod("format", amountType)
                val result = withdraw.returnType.let { Class.forName("co.xenastudios.xmoney.api.TransactionResult", false, loader) }
                XMoneyEconomy(
                    api, has, withdraw, deposit, format,
                    refType.getMethod("parseOrNull", String::class.java), amountType.getMethod("ofUnits", Long::class.javaPrimitiveType),
                    result.getMethod("isSuccess"),
                )
            }
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }
    }
}
