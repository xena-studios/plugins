package co.xenastudios.core.common.chat

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.i18n.Language
import co.xenastudios.core.common.i18n.MessageDrawer
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.TextRenderer
import kotlin.jvm.JvmStatic

/**
 * How a chat message is drawn: a line of our [Component]s, made once per group of viewers who
 * see the same thing, as messages are drawn once per language (DECISIONS.md, "Translations"),
 * then drawn once to the server's own text and that one object sent to every viewer in the
 * group. Set one on [ChatEvent.rendering].
 *
 * - [same]: one line for everyone, the console too. The cheapest, and what a format with no
 *   words of its own (a prefix, the name, the message) needs. Paper sends it as a viewer-unaware
 *   renderer; Spigot as the event's format when it has no hover or click.
 * - [grouped]: one line per group, such as per language ([byLanguage]): a format with words,
 *   or a hover in the reader's language.
 * - [perViewer]: one line per viewer, for a line that differs for each (a mention highlighted
 *   only for the player mentioned). It costs a render and a draw per recipient: ask for it only
 *   when the line really differs.
 *
 * A line is rendered on the platform's chat thread, after every plugin's listeners, with the
 * event's final [ChatEvent.message]. It must not throw; if it does, the message goes out in
 * the platform's own format and the error is logged.
 */
public class ChatRendering private constructor(
    internal val kind: Kind,
    private val group: ViewerGroup<Any>?,
    private val line: ChatLine<Any?>,
) {
    internal enum class Kind { SAME, GROUPED, PER_VIEWER }

    /** Whether every viewer sees one line, so the platform can draw it once and send it as its own format. */
    public val sameForEveryone: Boolean get() = kind == Kind.SAME

    /** The group [viewer] (a player, or `null` for the console) is in: `Unit` for [same], the viewer for [perViewer]. */
    internal fun groupOf(viewer: PlayerRef?): Any? = when (kind) {
        Kind.SAME -> Unit
        Kind.GROUPED -> group!!.of(viewer)
        Kind.PER_VIEWER -> viewer
    }

    internal fun render(chat: ChatEvent, group: Any?): Component = line.render(chat, group)

    /** The line for one viewer: for tests, and platforms that draw nothing ahead. */
    public fun render(chat: ChatEvent, viewer: PlayerRef?): Component = render(chat, groupOf(viewer))

    public companion object {
        /** One [format] for every viewer. */
        @JvmStatic
        public fun same(format: ChatFormat): ChatRendering = ChatRendering(Kind.SAME, null, ChatLine { chat, _ -> format.render(chat) })

        /**
         * One [line] per group [group] puts viewers in: viewers whose group is equal share one
         * line. Keep groups few (a language, a rank): a group is a render and a draw per message.
         */
        @JvmStatic
        public fun <K : Any> grouped(group: ViewerGroup<K>, line: ChatLine<K>): ChatRendering {
            @Suppress("UNCHECKED_CAST")
            return ChatRendering(Kind.GROUPED, group, line as ChatLine<Any?>)
        }

        /** One [line] per language viewers read in, as [drawer] works it out: the console in `default-language`. */
        @JvmStatic
        public fun byLanguage(drawer: MessageDrawer<*>, line: ChatLine<Language>): ChatRendering =
            grouped(ViewerGroup { viewer -> drawer.languageOf(viewer) }, line)

        /** One [line] for each viewer: [line] gets the viewer, `null` for the console. */
        @JvmStatic
        public fun perViewer(line: ChatLine<PlayerRef?>): ChatRendering {
            @Suppress("UNCHECKED_CAST")
            return ChatRendering(Kind.PER_VIEWER, null, line as ChatLine<Any?>)
        }
    }
}

/** A chat line that's the same for everyone: see [ChatRendering.same]. */
public fun interface ChatFormat {
    /** The line for [chat]. Put [ChatEvent.message] in with `Component.text`, so it's never read as markup. */
    public fun render(chat: ChatEvent): Component
}

/** A chat line for one group of viewers, [group]: see [ChatRendering.grouped]. */
public fun interface ChatLine<in K> {
    public fun render(chat: ChatEvent, group: K): Component
}

/** The group a viewer (a player, or `null` for the console) is drawn for: see [ChatRendering.grouped]. */
public fun interface ViewerGroup<out K : Any> {
    /** Called once per recipient: keep it a lookup, such as `MessageDrawer.languageOf`. */
    public fun of(viewer: PlayerRef?): K
}

/**
 * One message's lines drawn to the server's text [T], kept per group so each group is
 * rendered and drawn once, however many viewers it has: what each platform sends through.
 * Made per message, and used on one thread (the one sending it). Finding a viewer's line is
 * [ViewerGroup.of] and a look through the groups drawn so far; nothing is made per recipient.
 */
public class ChatDrawing<T : Any>(
    private val chat: ChatEvent,
    private val rendering: ChatRendering,
    private val renderer: TextRenderer<T>,
) {
    private var groups: Array<Any?> = arrayOfNulls(if (rendering.kind == ChatRendering.Kind.SAME) 1 else 4)
    private var drawn: Array<Any?> = arrayOfNulls(groups.size)
    private var count = 0

    /** How many times a line was rendered and drawn: at most the number of groups. */
    public var draws: Int = 0
        private set

    /** The line [viewer] (a player, or `null` for the console) sees, drawn for their group. */
    public fun of(viewer: PlayerRef?): T {
        if (rendering.kind == ChatRendering.Kind.PER_VIEWER) return draw(viewer)
        val group = rendering.groupOf(viewer)
        for (i in 0 until count) {
            if (groups[i] == group) {
                @Suppress("UNCHECKED_CAST")
                return drawn[i] as T
            }
        }
        val text = draw(group)
        if (count == groups.size) {
            groups = groups.copyOf(count * 2)
            drawn = drawn.copyOf(count * 2)
        }
        groups[count] = group
        drawn[count] = text
        count++
        return text
    }

    /** The line rendered as our components for [viewer], before it's drawn: for a platform that needs both. */
    public fun component(viewer: PlayerRef?): Component = rendering.render(chat, viewer)

    private fun draw(group: Any?): T {
        draws++
        return renderer.draw(rendering.render(chat, group))
    }
}
