package co.xenastudios.xstats.fabric

import co.xenastudios.core.fabric.ref
import co.xenastudios.xstats.Placeholders
import co.xenastudios.xstats.XStatsPlugin
import net.minecraft.server.level.ServerPlayer
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * xStats' placeholders in Patbox's Text Placeholder API, when it's installed: `%xstats:stat <params>%`,
 * where `<params>` is what follows `xstats_` in PlaceholderAPI's (`%xstats:stat kills_daily%`,
 * `%xstats:stat top_kills_1_name%`), and `%xstats:kd%`. Answered from memory, as on Bukkit.
 *
 * Through its API by name: Text Placeholder API 2.x (Minecraft 1.20.1 to 1.21.11) registers with
 * `Placeholders.register` and a `PlaceholderHandler`, 3.x (26.1 on) with `registerServer` and a
 * `Placeholder.Handler`, so neither is compiled against; the handler is a [Proxy] of whichever this
 * server has.
 */
internal object TextPlaceholders {
    fun register(plugin: XStatsPlugin, stat: Any, kd: Any) {
        val api = Class.forName("eu.pb4.placeholders.api.Placeholders")
        val register = api.methods.firstOrNull { it.name == "registerServer" && it.parameterTypes.size == 2 }
            ?: api.methods.first { it.name == "register" && it.parameterTypes.size == 2 }
        val handler = register.parameterTypes[1]
        val value = Class.forName("eu.pb4.placeholders.api.PlaceholderResult").getMethod("value", String::class.java)
        val invalid = Class.forName("eu.pb4.placeholders.api.PlaceholderResult").getMethod("invalid", String::class.java)
        register.invoke(null, stat, Proxy.newProxyInstance(handler.classLoader, arrayOf(handler), Handler(plugin, false, value, invalid)))
        register.invoke(null, kd, Proxy.newProxyInstance(handler.classLoader, arrayOf(handler), Handler(plugin, true, value, invalid)))
    }

    /** One placeholder's answers: the stat one, given its params as its argument, or `kd`. */
    private class Handler(private val plugin: XStatsPlugin, private val kd: Boolean, private val value: Method, private val invalid: Method) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "onPlaceholderRequest" -> answer(args!![0]!!, args.getOrNull(1) as? String)
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> "xStats' placeholder"
            else -> null
        }

        private fun answer(context: Any, argument: String?): Any? {
            val player = player(context)
            val params = if (kd) "kd" else argument?.trim()?.lowercase().orEmpty()
            if (params.isEmpty()) return invalid.invoke(null, "a stat, such as %xstats:stat kills%")
            val text = Placeholders.resolve(plugin, player?.ref, params) ?: return invalid.invoke(null, "no such xStats placeholder")
            return value.invoke(null, text)
        }

        // 3.x's context has serverPlayer(), 2.x's player(): either, from a public type that declares it.
        private fun player(context: Any): ServerPlayer? {
            for (name in listOf("serverPlayer", "player")) {
                val method = public(context.javaClass, name) ?: continue
                return try {
                    method.invoke(context) as? ServerPlayer
                } catch (e: Exception) {
                    null
                }
            }
            return null
        }

        private fun public(type: Class<*>?, name: String): Method? {
            if (type == null) return null
            if (Modifier.isPublic(type.modifiers)) {
                try {
                    return type.getMethod(name)
                } catch (e: NoSuchMethodException) {
                    // Maybe an interface has it.
                }
            }
            for (each in type.interfaces) public(each, name)?.let { return it }
            return public(type.superclass, name)
        }
    }
}
