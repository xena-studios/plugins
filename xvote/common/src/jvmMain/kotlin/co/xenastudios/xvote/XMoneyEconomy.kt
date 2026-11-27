package co.xenastudios.xvote

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import java.lang.reflect.Method
import java.util.concurrent.CompletionStage

/**
 * Money rewards through xMoney 2.0's API, the family's economy: each reward is a deposit recorded
 * under xVote with the reason `vote reward`, so `/eco history` says why the money came.
 *
 * By reflection, on xMoney's own classes: each Xena plugin relocates its own core, so xMoney's
 * `deposit` takes xMoney's `PlayerRef`, which xVote can't name; and on Spigot each plugin has its own
 * Kotlin. Its `PlayerRef.parseOrNull` makes one from the uuid's text, and `Amount.ofDouble` the
 * amount, rounded down as a deposit is. Found once, as xMoney enables; a reward is one call.
 */
public class XMoneyEconomy private constructor(
    private val api: Any,
    private val deposit: Method,
    private val parsePlayer: Method,
    private val amount: Method,
    private val down: Any,
) : Economy {
    override val name: String get() = "xMoney"

    override val ready: Boolean get() = true

    override fun give(player: PlayerRef, amount: Double): Pending<String?> {
        return try {
            val ref = parsePlayer.invoke(null, player.toString()) ?: return pendingOf("xMoney couldn't read the uuid $player")
            val units = this.amount.invoke(null, amount, down) ?: return pendingOf("$amount isn't an amount xMoney takes")
            val result = deposit.invoke(api, XVotePlugin.NAME, ref, units, REASON) as? CompletionStage<*> ?: return pendingOf("xMoney didn't answer")
            val done = newPending<String?>()
            result.whenComplete { value, error ->
                done.resolve(when {
                    error != null -> error.toString()
                    value == null -> "xMoney didn't answer"
                    else -> status(value).takeUnless { it == "SUCCESS" }
                })
            }
            done
        } catch (e: ReflectiveOperationException) {
            pendingOf("this xMoney's API is different ($e)")
        } catch (e: RuntimeException) {
            pendingOf(e.toString())
        }
    }

    private fun status(result: Any): String = try {
        result.javaClass.getMethod("getStatus").invoke(result).toString()
    } catch (e: ReflectiveOperationException) {
        "SUCCESS"
    }

    public companion object {
        private const val API = "co.xenastudios.xmoney.api.XMoneyApi"
        private const val REASON = "vote reward"

        /** xMoney's API object [api] (on Bukkit from the services manager), as an [Economy]; `null`, with why, if this xMoney's API can't be used. */
        @JvmStatic
        public fun of(api: Any): Pair<Economy?, String?> {
            val type = interfaces(api.javaClass).firstOrNull { it.name == API } ?: return null to "it isn't xMoney's API"
            return try {
                val deposit = type.methods.first { it.name == "deposit" && it.parameterTypes.size == 4 }
                val refType = deposit.parameterTypes[1]
                val amountType = deposit.parameterTypes[2]
                val parse = refType.getMethod("parseOrNull", String::class.java)
                val ofDouble = amountType.methods.first { it.name == "ofDouble" && it.parameterTypes.size == 2 && it.parameterTypes[0] == Double::class.javaPrimitiveType }
                val rounding = ofDouble.parameterTypes[1]
                val down = rounding.enumConstants.first { (it as Enum<*>).name == "DOWN" }
                XMoneyEconomy(api, deposit, parse, ofDouble, down) to null
            } catch (e: NoSuchElementException) {
                null to "this xMoney's API is different ($e)"
            } catch (e: ReflectiveOperationException) {
                null to "this xMoney's API is different ($e)"
            } catch (e: LinkageError) {
                null to e.toString()
            }
        }

        /** xMoney's API where every mod shares one class loader (Fabric): `XMoney.api()`, or `null` if it isn't there or enabled. */
        @JvmStatic
        public fun shared(): Any? = try {
            val holder = Class.forName("co.xenastudios.xmoney.api.XMoney")
            if (holder.getMethod("isEnabled").invoke(null) == true) holder.getMethod("api").invoke(null) else null
        } catch (e: ReflectiveOperationException) {
            null
        } catch (e: LinkageError) {
            null
        }

        private fun interfaces(type: Class<*>): Sequence<Class<*>> = sequence {
            var current: Class<*>? = type
            while (current != null) {
                for (each in current.interfaces) {
                    yield(each)
                    yieldAll(interfaces(each))
                }
                current = current.superclass
            }
        }
    }
}
