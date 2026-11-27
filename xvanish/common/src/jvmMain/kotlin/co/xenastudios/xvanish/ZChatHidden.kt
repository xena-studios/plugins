package co.xenastudios.xvanish

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.platform.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Who's hidden from whom, for zChat 4.0, through its own seam (`ZChatApi.registerHiddenPlayers`):
 * zChat asks before it completes, mentions or private-messages a player, so a vanished player
 * isn't found there by anyone who can't see them. On Bukkit zChat asks the server's `canSee`
 * too, which xVanish's hiding already answers; on Fabric, which has no such thing, this is how
 * zChat knows. From memory.
 *
 * By reflection, on zChat's own classes, as xBans' mutes reach it: each Xena plugin relocates its
 * own core, so zChat's `HiddenPlayers` takes zChat's `PlayerRef`, read here by its uuid text.
 */
public object ZChatHidden {
    private const val API = "co.xenastudios.zchat.api.ZChatApi"
    private const val HOOK = "co.xenastudios.zchat.api.HiddenPlayers"

    /** Gives [api] (zChat's API object) xVanish's hiding. `true` if it took it. */
    @JvmStatic
    public fun register(api: Any, plugin: XVanishPlugin, log: Log): Boolean {
        return try {
            // zChat's API interface, from the class loader that made its object.
            val type = Class.forName(API, false, api.javaClass.classLoader)
            if (!type.isInstance(api)) return false
            val hook = Class.forName(HOOK, false, type.classLoader)
            val register = type.getMethod("registerHiddenPlayers", String::class.java, hook)
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "isHidden" -> {
                        val viewer = args?.getOrNull(0)?.toString()?.let(PlayerRef::parseOrNull)
                        val target = args?.getOrNull(1)?.toString()?.let(PlayerRef::parseOrNull)
                        viewer != null && target != null && !plugin.canSee(viewer, target)
                    }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "xVanish's vanished players"
                    else -> null
                }
            }
            register.invoke(api, XVanishPlugin.NAME, Proxy.newProxyInstance(type.classLoader, arrayOf(hook), handler))
            log.info("zChat asks xVanish who's hidden before it names a player.")
            true
        } catch (e: ReflectiveOperationException) {
            log.warn("Couldn't tell zChat who's vanished: this zChat's API is different ($e).")
            false
        } catch (e: LinkageError) {
            log.warn("Couldn't tell zChat who's vanished: $e")
            false
        }
    }

    /** zChat's API on Fabric, where every mod shares one class loader: `ZChat.api()` once it's enabled, else `null`. */
    @JvmStatic
    public fun fabricApi(): Any? = try {
        val holder = Class.forName("co.xenastudios.zchat.api.ZChat")
        holder.getMethod("isEnabled").invoke(null).takeIf { it == true }?.let { holder.getMethod("api").invoke(null) }
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: LinkageError) {
        null
    }
}
