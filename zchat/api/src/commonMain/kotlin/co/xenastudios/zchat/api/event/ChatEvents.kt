package co.xenastudios.zchat.api.event

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.zchat.api.MessageKind
import kotlin.jvm.JvmField

/**
 * A chat message passed zChat's checks (the mute, cooldown, filter and anti-repeat) and is about
 * to be drawn in its format. Cancel it to stop the message, or add tags for the format to use.
 *
 * A pre-event: it fires at zChat's place among chat plugins, so another plugin may still stop
 * the message afterwards. To act only on messages that really go out, listen for
 * [ChatMessageSentEvent].
 *
 * Fired on the thread chat arrived on (a chat thread on Paper, Folia and Spigot, usually async;
 * the server thread on Fabric), many at once, and only when something listens.
 *
 * @since 4.0.0
 */
public class ChatMessageEvent(
    /** Who sent it. */
    public val player: PlayerRef,
    /** Their name. */
    public val playerName: String,
    /** The message as it'll appear, after the filter, as plain text. */
    public val message: String,
    /** The format it'll be drawn in: a group's name under `formatting.groups`, or `default`. */
    public val formatName: String,
    /** Whether it's being heard off the server's threads. */
    public val async: Boolean,
) : CancellableEvent() {
    private val added = LinkedHashMap<String, String>()

    /** The tags [placeholder] added, by name. */
    public val placeholders: Map<String, String> get() = added

    /**
     * Gives the format a `<name>` tag with [value] (zChat's markup, styles only), for this
     * message only, such as `<clan>`. zChat's own tags (`player`, `displayname`, `message`) win.
     *
     * @throws IllegalArgumentException if [name] isn't lower-case letters, digits, `_` and `-`
     */
    public fun placeholder(name: String, value: String) {
        require(NAME.matches(name)) { "a tag's name is lower-case letters, digits, _ and -: \"$name\"" }
        added[name] = value
    }

    public companion object {
        /** Subscribe to it on [co.xenastudios.zchat.api.ZChatApi.events]. */
        @JvmField
        public val TYPE: EventType<ChatMessageEvent> = EventType.of("zchat:chat_message")

        private val NAME = Regex("[a-z0-9_-]+")
    }
}

/**
 * A chat message zChat handled went out: every plugin had its say and none cancelled it. For
 * logging, stats and chat bridges, which want only what players see. It can't be changed.
 *
 * Fired once every plugin has seen the message, on the thread it arrived on, only when
 * something listens. Not fired for a message zChat or another plugin stopped.
 *
 * @since 4.0.0
 */
public class ChatMessageSentEvent(
    /** Who sent it. */
    public val player: PlayerRef,
    /** Their name. */
    public val playerName: String,
    /** The message as sent, before it's drawn in its format: plain text. */
    public val message: String,
    /** Who saw it, the console aside: a copy. */
    public val recipients: Set<PlayerRef>,
    /** Whether it went to the other servers of a network too. */
    public val network: Boolean,
) : Event {
    public companion object {
        /** Subscribe to it on [co.xenastudios.zchat.api.ZChatApi.events]. */
        @JvmField
        public val TYPE: EventType<ChatMessageSentEvent> = EventType.of("zchat:chat_message_sent")
    }
}

/**
 * A player was mentioned in chat and is about to hear the mention sound. Cancel it to keep it
 * quiet; their name is still highlighted.
 *
 * Fired on the mentioned player's thread (the main thread, or their region's on Folia), after
 * the message was sent, only when something listens.
 *
 * @since 4.0.0
 */
public class MentionEvent(
    /** The player about to be pinged. */
    public val mentioned: PlayerRef,
    /** Who mentioned them. On a network, maybe a player on another server. */
    public val sender: PlayerRef,
    /** Whether it's `@everyone` rather than their name. */
    public val everyone: Boolean,
) : CancellableEvent() {
    public companion object {
        /** Subscribe to it on [co.xenastudios.zchat.api.ZChatApi.events]. */
        @JvmField
        public val TYPE: EventType<MentionEvent> = EventType.of("zchat:mention")
    }
}

/**
 * A private message (`/msg` or `/reply`) passed zChat's checks (mute providers and the filter)
 * and is about to be delivered. Cancel it to stop it, or change [message].
 *
 * Fired on the sender's server, on the thread the command runs on, only when something listens.
 *
 * @since 4.0.0
 */
public class PrivateMessageEvent(
    /** Who sent it; `null` for the console. */
    public val sender: PlayerRef?,
    /** The sender's name (`CONSOLE` for the console). */
    public val senderName: String,
    /** Who it's for; `null` for the console. */
    public val recipient: PlayerRef?,
    /** The recipient's name. */
    public val recipientName: String,
    message: String,
    /** Whether it was sent with `/reply`. */
    public val reply: Boolean,
    /** Whether the recipient is on another server of the network. */
    public val network: Boolean,
) : CancellableEvent() {
    /** The message, after the filter, as plain text: change it to change what's delivered, to both sides and to spies. */
    public var message: String = message

    public companion object {
        /** Subscribe to it on [co.xenastudios.zchat.api.ZChatApi.events]. */
        @JvmField
        public val TYPE: EventType<PrivateMessageEvent> = EventType.of("zchat:private_message")
    }
}

/**
 * zChat's filter caught something in a chat message or private message, for logging and staff
 * alerts. By the time it fires the message has been blocked or censored, as the filter's `mode`
 * says.
 *
 * Fired on the thread the message was sent from, only when something listens.
 *
 * @since 4.0.0
 */
public class FilterMatchEvent(
    /** Who sent it. */
    public val player: PlayerRef,
    /** A chat message or a private message. */
    public val kind: MessageKind,
    /** What the filter checked, as typed (with the styles a player may use taken out). */
    public val message: String,
    /** The parts that matched, as typed, in order: never empty. */
    public val matches: List<String>,
    /** Whether the message was blocked (`mode: block`) rather than censored. */
    public val blocked: Boolean,
) : Event {
    public companion object {
        /** Subscribe to it on [co.xenastudios.zchat.api.ZChatApi.events]. */
        @JvmField
        public val TYPE: EventType<FilterMatchEvent> = EventType.of("zchat:filter_match")
    }
}
