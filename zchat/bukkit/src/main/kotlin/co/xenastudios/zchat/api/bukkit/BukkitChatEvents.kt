package co.xenastudios.zchat.api.bukkit

import co.xenastudios.zchat.api.MessageKind
import co.xenastudios.zchat.api.event.ChatMessageEvent
import co.xenastudios.zchat.api.event.ChatMessageSentEvent
import co.xenastudios.zchat.api.event.FilterMatchEvent
import co.xenastudios.zchat.api.event.MentionEvent
import co.xenastudios.zchat.api.event.PrivateMessageEvent
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * [ChatMessageEvent] as a Bukkit event, for plugins and Skript scripts listening the Bukkit way:
 * a chat message passed zChat's checks and is about to be drawn. Cancelling it, or adding a tag
 * with [placeholder], changes zChat's own event. Usually async (chat arrives on its own threads).
 *
 * @since 4.0.0
 */
public class BukkitChatMessageEvent(async: Boolean, private val player: Player, private val event: ChatMessageEvent) : Event(async), Cancellable {
    public fun getPlayer(): Player = player

    /** The message as it'll appear, after the filter, as plain text. */
    public fun getMessage(): String = event.message

    /** The format it'll be drawn in: a group's name under `formatting.groups`, or `default`. */
    public fun getFormatName(): String = event.formatName

    /** Gives the format a `<name>` tag with [value] (zChat's markup, styles only), for this message only. */
    public fun placeholder(name: String, value: String) {
        event.placeholder(name, value)
    }

    /** zChat's own event, the same on every platform. */
    public fun getEvent(): ChatMessageEvent = event

    override fun isCancelled(): Boolean = event.isCancelled
    override fun setCancelled(cancel: Boolean) {
        event.isCancelled = cancel
    }
    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * [ChatMessageSentEvent] as a Bukkit event: a chat message zChat handled went out. For logging,
 * stats and chat bridges. Usually async.
 *
 * @since 4.0.0
 */
public class BukkitChatMessageSentEvent(async: Boolean, private val player: Player, private val event: ChatMessageSentEvent) : Event(async) {
    public fun getPlayer(): Player = player

    /** The message as sent, before it's drawn in its format: plain text. */
    public fun getMessage(): String = event.message

    /** zChat's own event, with who saw it. */
    public fun getEvent(): ChatMessageSentEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * [MentionEvent] as a Bukkit event: a player was mentioned and is about to hear the mention
 * sound. Cancel it to keep it quiet. On the mentioned player's thread.
 *
 * @since 4.0.0
 */
public class BukkitMentionEvent(private val mentioned: Player, private val sender: Player?, private val event: MentionEvent) : Event(false), Cancellable {
    /** The player about to be pinged. */
    public fun getPlayer(): Player = mentioned

    /** Who mentioned them, if they're on this server. */
    public fun getSender(): Player? = sender

    /** Whether it's `@everyone` rather than their name. */
    public fun isEveryone(): Boolean = event.everyone

    public fun getEvent(): MentionEvent = event

    override fun isCancelled(): Boolean = event.isCancelled
    override fun setCancelled(cancel: Boolean) {
        event.isCancelled = cancel
    }
    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * [PrivateMessageEvent] as a Bukkit event: a private message is about to be delivered. Cancel it,
 * or change it with [setMessage]. On the thread the command runs on.
 *
 * @since 4.0.0
 */
public class BukkitPrivateMessageEvent(
    async: Boolean,
    private val sender: CommandSender,
    private val recipient: CommandSender?,
    private val event: PrivateMessageEvent,
) : Event(async), Cancellable {
    /** Who sent it: a player or the console. */
    public fun getSender(): CommandSender = sender

    /** Who it's for: a player or the console here, or `null` for a player on another server of the network. */
    public fun getRecipient(): CommandSender? = recipient

    public fun getRecipientName(): String = event.recipientName
    public fun isReply(): Boolean = event.reply
    public fun getMessage(): String = event.message
    public fun setMessage(message: String) {
        event.message = message
    }
    public fun getEvent(): PrivateMessageEvent = event

    override fun isCancelled(): Boolean = event.isCancelled
    override fun setCancelled(cancel: Boolean) {
        event.isCancelled = cancel
    }
    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * [FilterMatchEvent] as a Bukkit event: the filter caught something, for logging and staff
 * alerts. The message is already blocked or censored.
 *
 * @since 4.0.0
 */
public class BukkitFilterMatchEvent(async: Boolean, private val player: Player, private val event: FilterMatchEvent) : Event(async) {
    public fun getPlayer(): Player = player
    public fun getKind(): MessageKind = event.kind
    public fun getMessage(): String = event.message
    public fun getMatches(): List<String> = event.matches
    public fun isBlocked(): Boolean = event.blocked
    public fun getEvent(): FilterMatchEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}
