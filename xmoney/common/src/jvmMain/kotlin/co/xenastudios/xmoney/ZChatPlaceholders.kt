package co.xenastudios.xmoney

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.platform.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Balances in zChat's chat formats: `<balance>`, `<balance_raw>` and `<balance_short>`, through
 * zChat's own placeholder seam (`ZChatApi.registerPlaceholder`), filled in per sender from memory.
 *
 * By reflection, on zChat's own classes: each Xena plugin relocates its own core, so zChat's
 * `ChatPlaceholder` takes zChat's `PlayerRef` (`co.xenastudios.zchat.core.api.PlayerRef`), which
 * xMoney can't name; and on Spigot each plugin has its own Kotlin, so not even a Kotlin type is
 * shared. A proxy of zChat's interface, made in zChat's class loader, reads the player's uuid from
 * the `PlayerRef`'s text and answers with plain JDK strings. Found once, as zChat enables; asked
 * once per chat message.
 */
public object ZChatPlaceholders {
    private const val API = "co.xenastudios.zchat.api.ZChatApi"
    private const val PLACEHOLDER = "co.xenastudios.zchat.api.ChatPlaceholder"

    /** The tags xMoney gives zChat's formats, and the placeholder each reads. */
    public val TAGS: List<String> = listOf("balance", "balance_raw", "balance_short")

    /**
     * Gives [api] (zChat's API object, as zChat registered it) xMoney's tags. `true` if it took
     * them; `false`, with a line in [log], if it isn't a zChat this works with.
     */
    @JvmStatic
    public fun register(api: Any, placeholders: Placeholders, log: Log): Boolean {
        val type = interfaces(api.javaClass).firstOrNull { it.name == API } ?: return false
        return try {
            val placeholder = Class.forName(PLACEHOLDER, false, type.classLoader)
            val register = type.getMethod("registerPlaceholder", String::class.java, placeholder)
            for (tag in TAGS) {
                val handler = InvocationHandler { proxy, method, args ->
                    when (method.name) {
                        "value" -> args?.firstOrNull()?.toString()?.let(PlayerRef::parseOrNull)?.let { placeholders.resolve(tag, it) }
                        "equals" -> proxy === args?.firstOrNull()
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "xMoney's <$tag>"
                        else -> null
                    }
                }
                register.invoke(api, tag, Proxy.newProxyInstance(type.classLoader, arrayOf(placeholder), handler))
            }
            log.info("Gave zChat's chat formats ${TAGS.joinToString(", ") { "<$it>" }}.")
            true
        } catch (e: ReflectiveOperationException) {
            log.warn("Couldn't give zChat's formats xMoney's balance tags: this zChat's API is different ($e).")
            false
        } catch (e: LinkageError) {
            log.warn("Couldn't give zChat's formats xMoney's balance tags: $e")
            false
        }
    }

    /**
     * zChat's API on Fabric, where every mod shares one class loader: `ZChat.api()`, or `null` if
     * zChat isn't there or isn't enabled.
     */
    @JvmStatic
    public fun fabricApi(): Any? = try {
        val holder = Class.forName("co.xenastudios.zchat.api.ZChat")
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
