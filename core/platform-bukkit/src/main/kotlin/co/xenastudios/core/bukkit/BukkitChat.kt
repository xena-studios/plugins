package co.xenastudios.core.bukkit

import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.bukkit.modern.BukkitRecipients
import co.xenastudios.core.bukkit.modern.ChatRefs
import co.xenastudios.core.bukkit.modern.PaperChat
import co.xenastudios.core.bukkit.modern.bukkitPriority
import co.xenastudios.core.common.chat.ChatBridge
import co.xenastudios.core.common.chat.ChatDrawing
import co.xenastudios.core.common.chat.ChatEvent
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.api.PlayerRef
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority as BukkitPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap

/**
 * Chat on Bukkit, picked once as a plugin asks for it ([BukkitPlatform.chat]): Paper's
 * `AsyncChatEvent` and a `ChatRenderer` where Paper has both (1.17 and later, Folia), Bukkit's
 * `AsyncPlayerChatEvent` everywhere else, Spigot and CraftBukkit from 1.8.8 and Paper before
 * 1.17. The sync `PlayerChatEvent` isn't needed: Bukkit fires the async event for chat a
 * plugin makes a player say too, on that thread (DECISIONS.md, "The chat bridge").
 */
internal object BukkitChat {
    private const val PAPER_EVENT = "io.papermc.paper.event.player.AsyncChatEvent"
    private const val PAPER_RENDERER = "io.papermc.paper.chat.ChatRenderer"

    fun create(plugin: Plugin, text: BukkitText, priority: EventPriority, log: Log): ChatBridge {
        val refs = ChatRefs(plugin)
        return if (paperChat(plugin.server)) paper(plugin, refs, priority, log) else SpigotChat(plugin, text, refs, priority, log)
    }

    /** Whether the server has Paper's chat event with viewers and a renderer. */
    fun paperChat(server: Server): Boolean {
        val loader = server.javaClass.classLoader
        return try {
            val event = Class.forName(PAPER_EVENT, false, loader)
            val renderer = Class.forName(PAPER_RENDERER, false, loader)
            event.getMethod("viewers")
            event.getMethod("renderer", renderer)
            renderer.getMethod("viewerUnaware", Class.forName("$PAPER_RENDERER\$ViewerUnaware", false, loader))
            true
        } catch (e: ReflectiveOperationException) {
            false
        } catch (e: LinkageError) {
            false
        }
    }

    // Its own function, so only a server with Paper's chat API loads the class that calls it.
    private fun paper(plugin: Plugin, refs: ChatRefs, priority: EventPriority, log: Log): ChatBridge = PaperChat(plugin, refs, priority, log)

    /** Whether a line has a click or a hover, which only components carry. */
    fun interactive(component: Component): Boolean =
        component.style.click != null || component.style.hover != null || component.children.any(::interactive)
}

/**
 * Chat through `AsyncPlayerChatEvent`, from Spigot 1.8.8 on (and Paper before 1.17): the
 * plugin's listeners at its priority, then, at `MONITOR`, the rendering, once every plugin has
 * had its say. A line that's the same for everyone and has no hover or click becomes the
 * event's format, as `§` text, so the server sends it and logs it as its own. Anything else
 * (a hover, a click, a line per language or per viewer) is drawn once per group and sent to
 * each recipient as the server's text (`BaseComponent[]` on Spigot), and the event keeps the
 * console's line as its format with no recipients left, so the server logs it and every
 * `MONITOR` listener still sees a message that went out.
 *
 * Spigot has no signed chat to keep: before 1.19 there's none, and from 1.19 Spigot sends
 * chat a plugin formatted as a system message, unsigned (DECISIONS.md, "The chat bridge").
 */
internal class SpigotChat(
    private val plugin: Plugin,
    private val text: BukkitText,
    private val refs: ChatRefs,
    priority: EventPriority,
    log: Log,
) : ChatBridge(priority, log), Listener {

    private val server = plugin.server

    // What each event became at the bridge's priority, for its MONITOR step. Events don't
    // override equals, so this is by identity.
    private val pending = ConcurrentHashMap<AsyncPlayerChatEvent, ChatEvent>()

    init {
        val manager = server.pluginManager
        manager.registerEvent(AsyncPlayerChatEvent::class.java, this, bukkitPriority(priority), EventExecutor { _, event ->
            if (event is AsyncPlayerChatEvent) heard(event)
        }, plugin, false)
        manager.registerEvent(AsyncPlayerChatEvent::class.java, this, BukkitPriority.MONITOR, EventExecutor { _, event ->
            if (event is AsyncPlayerChatEvent) sent(event)
        }, plugin, false)
    }

    private fun heard(event: AsyncPlayerChatEvent) {
        if (!heard()) return
        val player = event.player
        val chat = ChatEvent(refs.of(player), player.name, event.message, BukkitRecipients(event.recipients, server, refs), event.isAsynchronous)
        chat.isCancelled = event.isCancelled
        fire(chat)
        event.isCancelled = chat.isCancelled
        if (chat.isCancelled) return
        if (chat.message != event.message) event.message = chat.message
        pending[event] = chat
    }

    private fun sent(event: AsyncPlayerChatEvent) {
        val chat = pending.remove(event) ?: return
        if (event.isCancelled) return
        // A later plugin's change is the message that goes out.
        chat.message = event.message
        val rendering = chat.rendering
        if (rendering != null) {
            try {
                val same = if (rendering.sameForEveryone) rendering.render(chat, null as PlayerRef?) else null
                if (same != null && !BukkitChat.interactive(same)) {
                    event.format = format(same)
                } else {
                    sendEach(event, chat, rendering, same)
                }
            } catch (e: Throwable) {
                renderingFailed(e)
            }
        }
        fireSent(chat)
    }

    private fun sendEach(event: AsyncPlayerChatEvent, chat: ChatEvent, rendering: co.xenastudios.core.common.chat.ChatRendering, same: Component?) {
        val console = same ?: rendering.render(chat, null as PlayerRef?)
        val drawing = ChatDrawing(chat, rendering, text.renderer)
        val recipients = event.recipients
        for (player in recipients) text.send(player, drawing.of(refs.of(player)))
        val format = format(console)
        try {
            recipients.clear()
            event.format = format
        } catch (e: UnsupportedOperationException) {
            // A set another plugin made fixed: stop the server's own send, and log the line ourselves.
            event.isCancelled = true
            server.consoleSender.sendMessage(text.legacy.draw(console))
        }
    }

    // The whole line as the format: % doubled, since the server formats it with the name and message.
    private fun format(line: Component): String = text.legacy.draw(line).replace("%", "%%")

    override fun unregister() {
        HandlerList.unregisterAll(this)
        pending.clear()
        refs.close()
    }
}
