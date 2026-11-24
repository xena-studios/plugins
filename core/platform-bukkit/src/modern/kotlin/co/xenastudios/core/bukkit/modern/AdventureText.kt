package co.xenastudios.core.bukkit.modern

import co.xenastudios.core.common.text.TextRenderer
import co.xenastudios.core.jvm.text.AdventureRenderer
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import java.util.function.BiConsumer

/**
 * Text on Paper and Folia from 1.16.5, and PandaSpigot: our components drawn to the server's own
 * Adventure, and sent to players and the console alike, which are audiences there. Only
 * [Audience.sendMessage] with a component is called, which Adventure 4.7.0 (Paper 1.16.5's)
 * already has.
 */
internal object AdventureText {
    val renderer: TextRenderer<Any> = AdventureRenderer

    val send: BiConsumer<CommandSender, Any> = BiConsumer { target, text -> (target as Audience).sendMessage(text as Component) }
}
