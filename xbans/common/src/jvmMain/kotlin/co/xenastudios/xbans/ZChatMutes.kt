package co.xenastudios.xbans

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.platform.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * xBans' mutes in zChat 4.0, through zChat's own seam (`ZChatApi.registerMuteProvider`): zChat asks
 * before every chat line and private message, on this server and before a line goes out to the
 * network, so a muted player can't chat or `/msg` anywhere zChat carries their words. From memory.
 *
 * By reflection, on zChat's own classes: each Xena plugin relocates its own core, so zChat's
 * `MuteProvider` takes zChat's `PlayerRef`, which xBans can't name; and on Spigot each plugin has
 * its own Kotlin. A proxy of zChat's interface, made in zChat's class loader, reads the player's
 * uuid from the `PlayerRef`'s text and answers with a JDK string, as xMoney's tags do. Found once,
 * as zChat enables; asked once per message.
 */
public object ZChatMutes {
    private const val API = "co.xenastudios.zchat.api.ZChatApi"
    private const val PROVIDER = "co.xenastudios.zchat.api.MuteProvider"

    /** Gives [api] (zChat's API object) xBans' mutes. `true` if it took them; `false`, with a line in [log], if this zChat can't. */
    @JvmStatic
    public fun register(api: Any, plugin: XBansPlugin, log: Log): Boolean {
        val type = interfaces(api.javaClass).firstOrNull { it.name == API } ?: return false
        return try {
            val provider = Class.forName(PROVIDER, false, type.classLoader)
            val register = type.getMethod("registerMuteProvider", String::class.java, provider)
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "muteReason" -> args?.firstOrNull()?.toString()?.let(PlayerRef::parseOrNull)?.let { reason(plugin, it) }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "xBans' mutes"
                    else -> null
                }
            }
            register.invoke(api, XBansPlugin.NAME, Proxy.newProxyInstance(type.classLoader, arrayOf(provider), handler))
            log.info("zChat asks xBans who's muted before each message.")
            true
        } catch (e: ReflectiveOperationException) {
            log.warn("Couldn't give zChat xBans' mutes: this zChat's API is different ($e).")
            false
        } catch (e: LinkageError) {
            log.warn("Couldn't give zChat xBans' mutes: $e")
            false
        }
    }

    /** What zChat tells a muted player, in its markup: xBans' "You're muted" for them, as plain words. */
    internal fun reason(plugin: XBansPlugin, player: PlayerRef): String? {
        val mute = plugin.muteOf(player) ?: return null
        // zChat draws the markup itself; xBans' own drawing, as plain text, is the reason.
        val shown = plugin.mutedMessage(player, mute)?.plain() ?: mute.punishment.reason
        return shown.replace("<", "‹")
    }

    /** zChat's API on Fabric, where every mod shares one class loader: `ZChat.api()`, or `null` if it isn't there or enabled. */
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
