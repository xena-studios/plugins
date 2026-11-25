package co.xenastudios.core.bukkit

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.bukkit.modern.AdventureText
import co.xenastudios.core.common.i18n.Delivery
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.LegacyRenderer
import co.xenastudios.core.common.text.TextRenderer
import co.xenastudios.core.jvm.text.BungeeRenderer
import net.md_5.bungee.api.chat.BaseComponent
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.function.BiConsumer

/**
 * How messages reach players and the console on this server, picked once at startup
 * (DECISIONS.md, M0): a [renderer] that draws our components to the server's own text and a
 * [delivery] that sends what it drew to a player, by [PlayerRef], or the console. A
 * [co.xenastudios.core.common.i18n.MessageDrawer] built on the two keeps each message drawn
 * per language, so a send is the server's own send.
 *
 * - **Adventure** on Paper and Folia from 1.16.5 (and PandaSpigot): the server's own components.
 * - **BaseComponent** on Spigot and on Paper before 1.16.5: Spigot's `BaseComponent[]` for
 *   players, which costs almost nothing to send on 1.8.8. From 1.21, where Spigot turns them
 *   into text on the server thread (12.7 µs a send against 4.6 µs for a `§` string, M0), a
 *   message with no click or hover goes as a string. The console always gets the string.
 * - **legacy text** on CraftBukkit, which has no component API: `§` strings, without clicks
 *   and hovers.
 *
 * What [renderer] draws is shared by every send: don't change it.
 */
public class BukkitText internal constructor(
    /** Which way it is: `Adventure`, `BaseComponent` or `legacy text`, for logs and `/<plugin> debug`. */
    public val name: String,
    /** Draws a component to what [delivery] sends. */
    public val renderer: TextRenderer<Any>,
    private val server: Server,
    // A Java type, so the Adventure side, compiled apart, can be one.
    private val toSender: BiConsumer<CommandSender, Any>,
    /** The same text as `§` codes, for what takes only a string: Bukkit's chat format, a console line. */
    public val legacy: LegacyRenderer,
) {
    /** Sends what [renderer] drew to the player [viewer] names, if they're online, or to the console when it's `null`. */
    public val delivery: Delivery<Any> = Delivery { viewer, text ->
        val target = if (viewer == null) server.consoleSender else viewer.online(server)
        if (target != null) toSender.accept(target, text)
    }

    /** Sends what [renderer] drew to [target]. */
    public fun send(target: CommandSender, drawn: Any) {
        toSender.accept(target, drawn)
    }

    /** Draws [component] and sends it to [target]: for one-off messages. Kept messages go through a `MessageDrawer`. */
    public fun send(target: CommandSender, component: Component) {
        toSender.accept(target, renderer.draw(component))
    }

    override fun toString(): String = name

    internal companion object {
        /** The way [server] takes text. */
        fun pick(server: BukkitServer, bukkit: Server): BukkitText = when {
            server.adventure -> adventure(server, bukkit)
            server.bungeeChat -> spigot(server, bukkit)
            else -> legacy(server, bukkit)
        }

        // Its own function, so only a server with Adventure loads the class that calls it.
        private fun adventure(server: BukkitServer, bukkit: Server): BukkitText =
            BukkitText("Adventure", AdventureText.renderer, bukkit, AdventureText.send, LegacyRenderer(hex = server.atLeast(1, 16)))

        fun spigot(server: BukkitServer, bukkit: Server): BukkitText {
            val legacy = LegacyRenderer(hex = server.atLeast(1, 16))
            val renderer = SpigotRenderer(
                components = BungeeRenderer(hex = server.atLeast(1, 16), copyToClipboard = server.atLeast(1, 15)),
                legacy = legacy,
                plainAsString = server.atLeast(1, 21),
            )
            return BukkitText("BaseComponent", renderer, bukkit, BiConsumer { target, text -> SpigotRenderer.send(target, text as SpigotMessage) }, legacy)
        }

        fun legacy(server: BukkitServer, bukkit: Server): BukkitText {
            val renderer = LegacyRenderer(hex = server.atLeast(1, 16))
            return BukkitText("legacy text", renderer, bukkit, BiConsumer { target, text -> target.sendMessage(text as String) }, renderer)
        }
    }
}

/**
 * A message drawn for Spigot: the components players get, or `null` when the string does as
 * well (no click or hover, on a server where strings send faster), and the `§` string the
 * console gets.
 */
internal class SpigotMessage(val components: Array<BaseComponent>?, val legacy: String)

/** Draws both of a [SpigotMessage]'s forms: once per language, so the second costs nothing per send. */
internal class SpigotRenderer(
    private val components: BungeeRenderer,
    private val legacy: LegacyRenderer,
    private val plainAsString: Boolean,
) : TextRenderer<SpigotMessage> {

    override fun draw(component: Component): SpigotMessage {
        val asComponents = !plainAsString || interactive(component)
        return SpigotMessage(if (asComponents) components.draw(component) else null, legacy.draw(component))
    }

    private fun interactive(component: Component): Boolean =
        component.style.click != null || component.style.hover != null || component.children.any { interactive(it) }

    companion object {
        fun send(target: CommandSender, message: SpigotMessage) {
            val components = message.components
            // 1.8.8's CommandSender has no spigot(), and a console shows no clicks anyway.
            if (components == null || target !is Player) {
                target.sendMessage(message.legacy)
            } else if (components.size == 1) {
                // Spreading an array into a vararg copies it; the renderer draws one root.
                target.spigot().sendMessage(components[0])
            } else {
                target.spigot().sendMessage(*components)
            }
        }
    }
}
