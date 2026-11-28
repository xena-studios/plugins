package co.xenastudios.core.neoforge

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.common.chat.ChatBridge
import co.xenastudios.core.common.chat.ChatDrawing
import co.xenastudios.core.common.chat.ChatEvent
import co.xenastudios.core.common.chat.ChatRecipients
import co.xenastudios.core.common.chat.RecipientAction
import co.xenastudios.core.common.chat.RecipientFilter
import co.xenastudios.core.common.platform.Log
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.OutgoingChatMessage
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.chat.Component as McComponent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.ServerChatEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import net.neoforged.bus.api.EventPriority as NeoPriority

/**
 * Chat on NeoForge, through its `ServerChatEvent`, no mixins (DECISIONS.md, "The chat bridge";
 * decisions/2026-11-28-neoforge-and-forge.md): it's fired on the server thread for each message,
 * before Minecraft broadcasts it to every player, and can be cancelled or given a new text.
 *
 * - **Nothing changed:** Minecraft broadcasts it as the client signed it, to everyone; the
 *   bridge's sent event fires after every other listener at NeoForge's lowest priority, unless
 *   one of them cancelled it.
 * - **Stopped:** the event is cancelled; nothing is sent or logged.
 * - **A new message, no rendering, everyone still sees it:** the event gets the new text, which
 *   NeoForge sends as the signed message's unsigned content, in Minecraft's own chat type: still
 *   reportable, as Paper does.
 * - **Other recipients, no rendering:** the event can't name recipients, so it's cancelled and the
 *   bridge sends the message itself, in Minecraft's chat type with the sender's name: unsigned,
 *   since the event doesn't hand over the signed message.
 * - **A rendering:** as on Fabric, the line goes to each recipient as a system message, drawn once
 *   per group: unsigned, so it can't be reported, as a Spigot server's formatted chat is.
 *
 * Where it sits among other mods is NeoForge's priority for [priority], turned over (ours run
 * LOWEST first, NeoForge's HIGHEST), with MONITOR at its last, never seeing a cancelled message. A listener on NeoForge's bus can't be unregistered here, so
 * [close] turns ours into a check that returns at once.
 */
public class NeoForgeChat internal constructor(
    private val server: () -> MinecraftServer?,
    priority: EventPriority,
    log: Log,
) : ChatBridge(priority, log) {

    // The message heard and let through unchanged, for the lowest listener's sent event.
    private var pending: ChatEvent? = null

    private val refs = NeoForgeChatRefs()

    init {
        NeoForge.EVENT_BUS.addListener(NeoPriority.NORMAL, false, PlayerEvent.PlayerLoggedOutEvent::class.java) { event ->
            (event.entity as? ServerPlayer)?.let { refs.forget(it) }
        }
        NeoForge.EVENT_BUS.addListener(neoPriority(priority), false, ServerChatEvent::class.java, ::heard)
        NeoForge.EVENT_BUS.addListener(NeoPriority.LOWEST, true, ServerChatEvent::class.java) { event ->
            val chat = pending
            pending = null
            if (chat != null && !event.isCanceled) fireSent(chat)
        }
    }

    private fun heard(event: ServerChatEvent) {
        pending = null
        if (!heard()) return
        val running = server() ?: return
        val sender = event.player
        val recipients = NeoForgeRecipients(running, refs)
        val chat = fire(ChatEvent(refs.of(sender), sender.scoreboardName, event.message.string, recipients, async = false))
        if (chat.isCancelled) {
            event.isCanceled = true
            return
        }
        if (!chat.changed) {
            pending = chat
            return
        }
        if (chat.rendering == null && !recipients.changed) {
            // Everyone still sees it: Minecraft's own broadcast, with the new text.
            event.message = McComponent.literal(chat.message)
            pending = chat
            return
        }
        event.isCanceled = true
        deliver(running, sender, chat, recipients)
        fireSent(chat)
    }

    private fun deliver(running: MinecraftServer, sender: ServerPlayer, chat: ChatEvent, recipients: NeoForgeRecipients) {
        val players = recipients.players
        val rendering = chat.rendering
        if (rendering != null) {
            try {
                val drawing = ChatDrawing(chat, rendering, MinecraftRenderer)
                for (player in players) player.sendSystemMessage(drawing.of(refs.of(player)))
                // The console's line, which Minecraft logs.
                running.sendSystemMessage(drawing.of(null))
                return
            } catch (e: Throwable) {
                renderingFailed(e)
            }
        }
        // Minecraft's own format, unsigned: the event doesn't hand over the signed message.
        val outgoing = OutgoingChatMessage.create(PlayerChatMessage.unsigned(sender.uuid, chat.message))
        val params = ChatType.bind(ChatType.CHAT, sender)
        for (player in players) player.sendChatMessage(outgoing, false, params)
        running.sendSystemMessage(McComponent.literal("<${sender.scoreboardName}> ${chat.message}"))
    }

    override fun unregister() {
        pending = null
        refs.clear()
    }

    private companion object {
        // Ours run LOWEST first and MONITOR last, as Bukkit's do; NeoForge's run HIGHEST first. So
        // the order turns over, and MONITOR, which never changes anything, shares NeoForge's last.
        fun neoPriority(priority: EventPriority): NeoPriority = when (priority) {
            EventPriority.LOWEST -> NeoPriority.HIGHEST
            EventPriority.LOW -> NeoPriority.HIGH
            EventPriority.NORMAL -> NeoPriority.NORMAL
            EventPriority.HIGH -> NeoPriority.LOW
            EventPriority.HIGHEST, EventPriority.MONITOR -> NeoPriority.LOWEST
        }
    }
}

/** One [PlayerRef] per online player, so chat names recipients without making one each message. */
internal class NeoForgeChatRefs {
    private val refs = ConcurrentHashMap<UUID, PlayerRef>()

    fun of(player: ServerPlayer): PlayerRef = refs.computeIfAbsent(player.uuid, MAKE)

    fun forget(player: ServerPlayer) {
        refs.remove(player.uuid)
    }

    fun clear() {
        refs.clear()
    }

    private companion object {
        val MAKE: java.util.function.Function<UUID, PlayerRef> = java.util.function.Function { PlayerRef.of(it.toKotlinUuid()) }
    }
}

/**
 * Who sees a chat message on NeoForge: every player online, as Minecraft broadcasts it, in a list
 * of the bridge's own (one copy of the player list per message, nothing per player).
 */
internal class NeoForgeRecipients(private val server: MinecraftServer, private val refs: NeoForgeChatRefs) : ChatRecipients {
    val players: ArrayList<ServerPlayer> = ArrayList(server.playerList.players)

    override var changed: Boolean = false
        private set

    override val size: Int get() = players.size

    override fun contains(player: PlayerRef): Boolean = index(player) >= 0

    override fun add(player: PlayerRef): Boolean {
        if (contains(player)) return false
        val online = server.playerList.getPlayer(player.uuid.toJavaUuid()) ?: return false
        players += online
        changed = true
        return true
    }

    override fun remove(player: PlayerRef): Boolean {
        val at = index(player)
        if (at < 0) return false
        players.removeAt(at)
        changed = true
        return true
    }

    override fun removeIf(filter: RecipientFilter): Int {
        val before = players.size
        players.removeAll { filter.test(refs.of(it)) }
        val removed = before - players.size
        if (removed > 0) changed = true
        return removed
    }

    override fun forEach(action: RecipientAction) {
        for (player in players) action.accept(refs.of(player))
    }

    private fun index(player: PlayerRef): Int {
        for (i in players.indices) if (refs.of(players[i]) == player) return i
        return -1
    }
}
