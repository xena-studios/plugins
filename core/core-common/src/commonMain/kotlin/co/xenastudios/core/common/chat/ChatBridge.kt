package co.xenastudios.core.common.chat

import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.event.EventListener
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.event.Subscription
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.platform.Log
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmOverloads

/**
 * A platform's chat, for one plugin: every chat message, as a [ChatEvent] before it goes out
 * and a [ChatSentEvent] once it has. Each platform's adapter makes one on the plugin's asking
 * (`BukkitPlatform.chat`, `FabricPlatform.chat`, `FakeChat` in tests), picking its way of
 * hearing chat once, at that moment; until a plugin asks, nothing listens to chat at all.
 * [close] stops it, as the module that asked turns off.
 *
 * **Among other chat plugins**, the bridge's [priority] is where the platform puts this
 * plugin's listeners: Bukkit's event priority of the same name, a phase of Fabric API's event.
 * Every [listen]er runs inside that one slot, in their own priorities' order; [onSent] hears
 * after every plugin on the server (Bukkit's `MONITOR`). A [ChatEvent.rendering] is drawn last
 * of all, so it sees the final message and recipients whatever the priority.
 *
 * **Cost.** A message nobody listens to costs the platform's own event and one check. One that
 * is heard costs the event and its listeners, then a render and a draw per group of viewers
 * ([ChatRendering]): nothing is made per recipient, unless a plugin asks for a line per viewer.
 */
public abstract class ChatBridge(
    /** Where this plugin's chat listeners sit among other plugins'. */
    public val priority: EventPriority,
    /** Where a listener that throws is logged. */
    protected val log: Log,
) : AutoCloseable {

    private val bus = SimpleEventBus(log)

    @Volatile
    private var closed = false

    /** The chat events, for listeners that want the bus itself. */
    public val events: EventBus get() = bus

    /**
     * [listener] hears every chat message before it goes out, at [order] among this plugin's
     * chat listeners; if [ignoreCancelled], not one a plugin already stopped. Until the
     * subscription is cancelled or the bridge closed.
     */
    @JvmOverloads
    public fun listen(order: EventPriority = EventPriority.NORMAL, ignoreCancelled: Boolean = true, listener: EventListener<ChatEvent>): Subscription =
        bus.subscribe(ChatEvent.TYPE, order, ignoreCancelled, listener)

    /** [listener] hears every chat message once it's been sent, after every plugin's say. */
    public fun onSent(listener: EventListener<ChatSentEvent>): Subscription = bus.subscribe(ChatSentEvent.TYPE, listener)

    /** Whether the bridge is still on. */
    public val isOpen: Boolean get() = !closed

    /** For the platform: whether anything listens, so a message no one hears isn't even made into an event. */
    protected fun heard(): Boolean = !closed && bus.hasListeners(ChatEvent.TYPE)

    /** For the platform: whether anything waits for sent messages. */
    protected fun heardSent(): Boolean = !closed && bus.hasListeners(ChatSentEvent.TYPE)

    /** For the platform: tells the listeners about [event], and returns it. */
    protected fun fire(event: ChatEvent): ChatEvent = bus.fire(ChatEvent.TYPE, event)

    /** For the platform: tells the sent-listeners [event] went out. */
    protected fun fireSent(event: ChatEvent) {
        if (heardSent()) bus.fire(ChatSentEvent.TYPE, ChatSentEvent(event))
    }

    /**
     * For the platform, when a rendering threw [error]: logs it, and the platform sends the
     * message in its own format. Errors the JVM can't recover from are thrown again.
     */
    protected fun renderingFailed(error: Throwable) {
        if (isFatal(error)) throw error
        log.error("A chat rendering failed, so the message went out in the server's own format", error)
    }

    /** Stops hearing chat: the platform unregisters its listeners. Calling it again does nothing. */
    override fun close() {
        if (closed) return
        closed = true
        unregister()
    }

    /** For the platform: undo what it registered to hear chat. */
    protected abstract fun unregister()
}
