package co.xenastudios.core.bukkit.modern

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.common.chat.ChatBridge
import co.xenastudios.core.common.chat.ChatDrawing
import co.xenastudios.core.common.chat.ChatEvent
import co.xenastudios.core.common.chat.ChatRendering
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.jvm.text.AdventureRenderer
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority as BukkitPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap

/**
 * Chat through Paper's `AsyncChatEvent` (Paper 1.17 and later, and Folia): the plugin's
 * listeners at its priority, then a `ChatRenderer` that Paper calls once every plugin has had
 * its say, on the chat thread. A line that's the same for everyone is a viewer-unaware
 * renderer, which Paper calls once and sends to every viewer; any other is called per viewer,
 * and draws once per group ([ChatDrawing]).
 *
 * **Signed chat** is Paper's to keep: a message nobody changed goes out as the client signed
 * it. With a renderer, Paper still sends the player's signed message, with the drawn line as
 * its unsigned content under a chat type that shows it as it is; a changed message is that
 * unsigned content too. So chat reports still carry what the player signed, and a client
 * may mark such a line as changed by the server (DECISIONS.md, "The chat bridge").
 */
internal class PaperChat(
    plugin: Plugin,
    private val refs: ChatRefs,
    priority: EventPriority,
    log: Log,
) : ChatBridge(priority, log), Listener {

    // What each event became at the bridge's priority, until MONITOR. By identity.
    private val pending = ConcurrentHashMap<AsyncChatEvent, ChatEvent>()

    init {
        val manager = plugin.server.pluginManager
        manager.registerEvent(AsyncChatEvent::class.java, this, bukkitPriority(priority), EventExecutor { _, event ->
            if (event is AsyncChatEvent) heard(event)
        }, plugin, false)
        manager.registerEvent(AsyncChatEvent::class.java, this, BukkitPriority.MONITOR, EventExecutor { _, event ->
            if (event is AsyncChatEvent) sent(event)
        }, plugin, false)
    }

    private fun heard(event: AsyncChatEvent) {
        if (!heard()) return
        val player = event.player
        val text = plain(event.message())
        val chat = ChatEvent(refs.of(player), player.name, text, BukkitRecipients(event.viewers(), player.server, refs), event.isAsynchronous)
        chat.isCancelled = event.isCancelled
        fire(chat)
        event.isCancelled = chat.isCancelled
        if (chat.isCancelled) return
        // Unchanged, the message stays the component Paper has, with whatever another plugin put in it.
        if (chat.message != text) event.message(Component.text(chat.message))
        chat.rendering?.let { event.renderer(renderer(chat, it)) }
        pending[event] = chat
    }

    private fun sent(event: AsyncChatEvent) {
        val chat = pending.remove(event) ?: return
        if (!event.isCancelled) fireSent(chat)
    }

    private fun renderer(chat: ChatEvent, rendering: ChatRendering): ChatRenderer =
        if (rendering.sameForEveryone) {
            ChatRenderer.viewerUnaware { source, name, message ->
                try {
                    sync(chat, message)
                    AdventureRenderer.draw(rendering.render(chat, null as PlayerRef?))
                } catch (e: Throwable) {
                    renderingFailed(e)
                    ChatRenderer.defaultRenderer().render(source, name, message, source)
                }
            }
        } else {
            Grouped(chat, rendering)
        }

    /** A line per group of viewers, each drawn once however many viewers it has. */
    private inner class Grouped(private val chat: ChatEvent, rendering: ChatRendering) : ChatRenderer {
        private val drawing = ChatDrawing(chat, rendering, AdventureRenderer)
        private var synced = false

        override fun render(source: Player, sourceDisplayName: Component, message: Component, viewer: Audience): Component = try {
            if (!synced) {
                sync(chat, message)
                synced = true
            }
            drawing.of(if (viewer is Player) refs.of(viewer) else null)
        } catch (e: Throwable) {
            renderingFailed(e)
            ChatRenderer.defaultRenderer().render(source, sourceDisplayName, message, viewer)
        }
    }

    // The final message, after every plugin, as the rendering sees it.
    private fun sync(chat: ChatEvent, message: Component) {
        val text = plain(message)
        if (text != chat.message) chat.message = text
    }

    override fun unregister() {
        HandlerList.unregisterAll(this)
        pending.clear()
        refs.close()
    }

    companion object {
        /**
         * [message] as plain text: its text parts in order. Adventure 4.7.0 (Paper 1.16.5's)
         * has no plain serializer under today's name, so this walks the tree itself.
         */
        fun plain(message: Component): String {
            if (message is TextComponent && message.children().isEmpty()) return message.content()
            return buildString { append(message) }
        }

        private fun StringBuilder.append(component: Component) {
            if (component is TextComponent) append(component.content())
            for (child in component.children()) append(child)
        }
    }
}
