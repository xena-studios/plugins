package co.xenastudios.core.bungee

import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.common.i18n.Delivery
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.TextRenderer
import co.xenastudios.core.jvm.text.BungeeRenderer
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.connection.ProxiedPlayer

/**
 * How messages reach players and the console on BungeeCord: its own `BaseComponent`s, drawn once
 * per language by a `MessageDrawer` on [renderer]. Players get RGB colours and copy-to-clipboard
 * clicks, which BungeeCord turns into what each client can show; the console, whose colours are
 * the sixteen, gets the nearest of those, so each message is drawn both ways at once.
 */
public class BungeeText internal constructor(private val server: ProxyServer) {
    /** Draws our components to BungeeCord's, for players and for the console. */
    public val renderer: TextRenderer<BungeeMessage> = TextRenderer { component ->
        BungeeMessage(players.draw(component), console.draw(component))
    }

    /** Sends what [renderer] drew to the player a reference names, if they're on the proxy, or the console when it's `null`. */
    public val delivery: Delivery<BungeeMessage> = Delivery { viewer, text ->
        val target: CommandSender? = if (viewer == null) server.console else server.getPlayer(viewer.javaUuid)
        target?.let { send(it, text) }
    }

    /** Draws [component] and sends it to [target]: for one-off messages. */
    public fun send(target: CommandSender, component: Component) {
        send(target, (if (target is ProxiedPlayer) players else console).draw(component))
    }

    private fun send(target: CommandSender, message: BungeeMessage) {
        send(target, if (target is ProxiedPlayer) message.players else message.console)
    }

    private fun send(target: CommandSender, drawn: Array<BaseComponent>) {
        // The renderer draws one root; spreading an array into the vararg would copy it.
        if (drawn.size == 1) target.sendMessage(drawn[0]) else target.sendMessage(*drawn)
    }

    override fun toString(): String = "BaseComponent"

    private companion object {
        val players = BungeeRenderer(hex = true, copyToClipboard = true)
        val console = BungeeRenderer(hex = false, copyToClipboard = true)
    }
}

/** A message drawn for BungeeCord: the [players]' components, in RGB, and the [console]'s, in its sixteen colours. Shared by every send: don't change them. */
public class BungeeMessage internal constructor(
    public val players: Array<BaseComponent>,
    public val console: Array<BaseComponent>,
)
