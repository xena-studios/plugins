package co.xenastudios.core.velocity

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.common.i18n.Delivery
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.TextRenderer
import co.xenastudios.core.jvm.text.AdventureRenderer
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component as AdventureComponent

/**
 * How messages reach players and the console on Velocity: Adventure, Velocity's own (4.x on
 * Velocity 3, 5.x on Velocity 4, which the renderer reads alike), drawn once per language by a
 * `MessageDrawer` on [renderer] and sent with [delivery].
 */
public class VelocityText internal constructor(private val server: ProxyServer) {
    /** Draws our components to Adventure's. */
    public val renderer: TextRenderer<AdventureComponent> = AdventureRenderer

    /** Sends what [renderer] drew to the player [PlayerRef] names, if they're on the proxy, or the console when it's `null`. */
    public val delivery: Delivery<AdventureComponent> = Delivery { viewer, text ->
        val target: CommandSource? = if (viewer == null) server.consoleCommandSource else server.getPlayer(viewer.javaUuid).orElse(null)
        target?.sendMessage(text)
    }

    /** Draws [component] and sends it to [target]: for one-off messages. */
    public fun send(target: CommandSource, component: Component) {
        target.sendMessage(renderer.draw(component))
    }

    override fun toString(): String = "Adventure"
}
