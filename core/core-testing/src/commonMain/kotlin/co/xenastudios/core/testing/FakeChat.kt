package co.xenastudios.core.testing

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.common.chat.ChatBridge
import co.xenastudios.core.common.chat.ChatDrawing
import co.xenastudios.core.common.chat.ChatEvent
import co.xenastudios.core.common.chat.RecipientList
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.TextRenderer

/**
 * Chat on the fake platform, for a plugin's tests: players [join], then [say] something, and
 * the result is what each of them (and the console) saw. It runs the message the way Paper
 * does: the plugin's listeners, then the rendering drawn once per group of viewers, then the
 * sent-listeners, all on the test's thread.
 *
 * ```kotlin
 * val chat = FakeChat(platform.log)
 * chat.join(alex, "Alex"); chat.join(sam, "Sam")
 * plugin.enable(chat)
 * val said = chat.say(alex, "hello")
 * assertEquals("[Member] Alex: hello", said.seenBy(sam))
 * ```
 */
public class FakeChat(log: Log, priority: EventPriority = EventPriority.NORMAL) : ChatBridge(priority, log) {

    private val online = LinkedHashMap<PlayerRef, String>()

    /** [player] is online as [name], and sees chat. */
    public fun join(player: PlayerRef, name: String) {
        online[player] = name
    }

    /** [player] left. */
    public fun quit(player: PlayerRef) {
        online.remove(player)
    }

    /** Whether the bridge's listeners were told to stop ([close]). */
    public val closed: Boolean get() = !isOpen

    /**
     * [sender] (online) says [text]: every listener hears it, and the result has the line each
     * recipient got. [async] is what the event says about its thread.
     */
    public fun say(sender: PlayerRef, text: String, async: Boolean = true): Said {
        val name = requireNotNull(online[sender]) { "$sender isn't online: join them first" }
        if (!heard()) return Said(null, default(name, text), online.keys.associateWith { default(name, text) }, 0)
        val event = fire(ChatEvent(sender, name, text, RecipientList(online.keys) { it in online }, async))
        if (event.isCancelled) return Said(event, null, emptyMap(), 0)
        val rendering = event.rendering
        val lines = LinkedHashMap<PlayerRef, Component>()
        var draws = 0
        var console: Component? = null
        if (rendering != null) {
            // A rendering that throws leaves the server's own format, as on every platform.
            try {
                val drawing = ChatDrawing(event, rendering, TextRenderer { it })
                event.recipients.forEach { lines[it] = drawing.of(it) }
                console = drawing.of(null)
                draws = drawing.draws
            } catch (e: Throwable) {
                renderingFailed(e)
                lines.clear()
            }
        }
        if (console == null) {
            val line = default(name, event.message)
            event.recipients.forEach { lines[it] = line }
            console = line
        }
        fireSent(event)
        return Said(event, console, lines, draws)
    }

    override fun unregister() {}

    /** What a message came to. */
    public class Said(
        /** The event as the listeners left it; `null` when no one listened. */
        public val event: ChatEvent?,
        /** The console's line; `null` if the message was stopped. */
        public val console: Component?,
        /** Each recipient's line. */
        public val lines: Map<PlayerRef, Component>,
        /** How many times a rendering was drawn: at most one per group of viewers. */
        public val draws: Int,
    ) {
        /** Whether a listener stopped it. */
        public val cancelled: Boolean get() = event?.isCancelled == true

        /** The plain text [player] saw, or `null` if they didn't see it. */
        public fun seenBy(player: PlayerRef): String? = lines[player]?.plain()
    }

    private companion object {
        // Minecraft's own chat format.
        fun default(name: String, text: String): Component = Component.text("<$name> $text")
    }
}
