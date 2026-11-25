package co.xenastudios.zchat

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Markup
import co.xenastudios.core.common.text.Placeholder

/** Finds players named in a message, highlights them, and says who to ping. */
public object Mentions {
    /** A name as a word on its own, with or without @: Minecraft names are 3 to 16 of these. */
    private val NAME = Regex("(?<![A-Za-z0-9_@])@?[A-Za-z0-9_]{3,16}(?![A-Za-z0-9_])")

    /** The message with mentions highlighted: [pinged] by name, and [everyone] if `@everyone` was used by someone allowed to. */
    public class Applied(public val message: Component, public val pinged: List<PlayerRef>, public val everyone: Boolean)

    /**
     * [message] with each name of an online player ([find]) that [sender] may see ([visible])
     * drawn in [format], and `@everyone` too when [mayPingEveryone].
     */
    public fun apply(
        message: Component,
        sender: PlayerRef,
        format: Markup,
        mayPingEveryone: Boolean,
        find: (String) -> Pair<PlayerRef, String>?,
        visible: (PlayerRef) -> Boolean,
    ): Applied {
        val pinged = ArrayList<PlayerRef>(2)
        var everyone = false
        val highlighted = ChatText.replace(message, NAME) { match ->
            val raw = match.value
            val at = raw[0] == '@'
            val name = if (at) raw.substring(1) else raw
            if (at && mayPingEveryone && name.equals("everyone", ignoreCase = true)) {
                everyone = true
                return@replace format.render("name" to Placeholder.text("everyone"))
            }
            val (target, shown) = find(name) ?: return@replace null
            if (target == sender || !visible(target)) return@replace null
            if (target !in pinged) pinged += target
            format.render("name" to Placeholder.text(shown))
        }
        return Applied(highlighted, pinged, everyone)
    }
}
