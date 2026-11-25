package co.xenastudios.core.common.chat

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import kotlin.jvm.JvmField

/**
 * A player sent a chat message, and it hasn't gone out yet: what a chat plugin screens,
 * changes and formats, on every platform the same (DECISIONS.md, "The chat bridge"). Heard
 * through a [ChatBridge] ([ChatBridge.listen]), on the thread the platform hands chat over on:
 * a chat thread on Paper, Folia and Spigot (or the sender's own, for chat a plugin makes them
 * say), the server thread on Fabric. Keep what a listener reads safe from any thread, as
 * zChat's state is.
 *
 * - [isCancelled] stops it: nobody sees it, and the platform logs nothing.
 * - [message] is what the player typed, as plain text; a filter changes it here.
 * - [recipients] are the players who'll see it, from the platform's own set: remove a player
 *   who hid chat, add a spy.
 * - [rendering] draws the line players see, once per group of viewers (one for everyone, one
 *   per language, or one per viewer if a plugin asks for it); `null` leaves the platform's own
 *   format, `<name> message`.
 *
 * Changing nothing leaves the message exactly as the platform would send it, signed where the
 * server signs chat; a [rendering] or a changed [message] may change how signing applies, per
 * platform (DECISIONS.md, "The chat bridge").
 */
public class ChatEvent(
    /** Who sent it. */
    public val sender: PlayerRef,
    /** Their name, as the server knows it (not a display name). */
    public val senderName: String,
    message: String,
    /** Who'll see it, the console aside: it always logs the line. */
    public val recipients: ChatRecipients,
    /** Whether it's being heard off the server's threads, as chat usually is on Bukkit. */
    public val async: Boolean,
) : CancellableEvent() {

    /** The text as the player sent it, before any plugin changed it. */
    public val originalMessage: String = message

    /**
     * The text that will be sent: plain, never read as markup (a renderer puts it in a line with
     * `Component.text`). By the time [rendering] runs it holds the platform's final text, any
     * later plugin's changes included.
     */
    public var message: String = message

    /** Draws the line each viewer sees; `null` for the platform's own format. See [ChatRendering]. */
    public var rendering: ChatRendering? = null

    /** Whether a plugin changed what goes out: the [message], who sees it, or how it's drawn. */
    public val changed: Boolean get() = message != originalMessage || rendering != null || recipients.changed

    public companion object {
        @JvmField
        public val TYPE: EventType<ChatEvent> = EventType.of("core:chat")
    }
}

/**
 * A chat message went out, after every plugin on the server had its say (Bukkit's `MONITOR`):
 * for what counts only once a message is really sent, such as a cooldown, anti-repeat or a
 * mention's sound. [chat] is the event as it ended; change nothing in it, and don't keep its
 * [ChatEvent.recipients], which are the platform's own set, valid while this is heard.
 */
public class ChatSentEvent(
    /** The message as it was sent. */
    public val chat: ChatEvent,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<ChatSentEvent> = EventType.of("core:chat_sent")
    }
}

/**
 * The players who'll see a chat message: a view of the platform's own set (Paper's viewers,
 * Bukkit's recipients), not a copy, so nothing is made per recipient. Players are
 * [PlayerRef]s, which the platform keeps one of per online player. Not safe to share between
 * threads: it's the event's.
 */
public interface ChatRecipients {
    /** How many players see it. */
    public val size: Int

    /** Whether [player] sees it. */
    public operator fun contains(player: PlayerRef): Boolean

    /** Lets [player] see it too, if they're online. Returns whether that changed anything. */
    public fun add(player: PlayerRef): Boolean

    /** Hides it from [player]. Returns whether they were seeing it. */
    public fun remove(player: PlayerRef): Boolean

    /** Hides it from every player [filter] says, and returns how many. */
    public fun removeIf(filter: RecipientFilter): Int

    /** Calls [action] with each player who sees it. */
    public fun forEach(action: RecipientAction)

    /** Whether a plugin added or removed a player, through this view or the platform's own. */
    public val changed: Boolean
}

/** Which recipients to drop: see [ChatRecipients.removeIf]. */
public fun interface RecipientFilter {
    public fun test(player: PlayerRef): Boolean
}

/** Something done for each recipient: see [ChatRecipients.forEach]. */
public fun interface RecipientAction {
    public fun accept(player: PlayerRef)
}

/**
 * Recipients kept in a list of our own, for a platform that has no set of its own to view
 * (Fabric, whose chat goes to every player) and for tests. [online] says whether a player
 * may be added. Made per message from the players online, in one copy of references the
 * platform already holds.
 */
public class RecipientList(
    players: Collection<PlayerRef>,
    private val online: (PlayerRef) -> Boolean = { true },
) : ChatRecipients {
    private val players = ArrayList(players)

    override var changed: Boolean = false
        private set

    override val size: Int get() = players.size

    override fun contains(player: PlayerRef): Boolean = player in players

    override fun add(player: PlayerRef): Boolean {
        if (player in players || !online(player)) return false
        players += player
        changed = true
        return true
    }

    override fun remove(player: PlayerRef): Boolean = players.remove(player).also { if (it) changed = true }

    override fun removeIf(filter: RecipientFilter): Int {
        val before = players.size
        players.removeAll { filter.test(it) }
        val removed = before - players.size
        if (removed > 0) changed = true
        return removed
    }

    override fun forEach(action: RecipientAction) {
        for (i in players.indices) action.accept(players[i])
    }

    /** The players, in order: for the platform sending it. */
    public operator fun get(index: Int): PlayerRef = players[index]
}
