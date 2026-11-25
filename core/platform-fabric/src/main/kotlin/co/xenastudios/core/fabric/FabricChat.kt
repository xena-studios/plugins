package co.xenastudios.core.fabric

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.common.chat.ChatBridge
import co.xenastudios.core.common.chat.ChatDrawing
import co.xenastudios.core.common.chat.ChatEvent
import co.xenastudios.core.common.chat.ChatRecipients
import co.xenastudios.core.common.chat.RecipientAction
import co.xenastudios.core.common.chat.RecipientFilter
import co.xenastudios.core.common.platform.Log
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.OutgoingChatMessage
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.chat.Component as McComponent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
//? if identifier {
import net.minecraft.resources.Identifier
//?} else
//import net.minecraft.resources.ResourceLocation

/**
 * Chat on Fabric, through Fabric API's message events, no mixins (DECISIONS.md, "The chat
 * bridge"): `ALLOW_CHAT_MESSAGE` hears each message on the server thread, before Minecraft
 * broadcasts it to every player, and `CHAT_MESSAGE` once it has.
 *
 * - **Nothing changed:** Minecraft broadcasts it as the client signed it, to everyone.
 * - **Stopped:** the broadcast is refused; nothing is sent or logged.
 * - **A new message or other recipients, no rendering:** the broadcast is refused and the
 *   bridge sends the player's signed message itself, to the recipients, with the new text as
 *   its unsigned content in Minecraft's own chat type: still reportable, as Paper does.
 * - **A rendering:** Minecraft can show a signed message only inside a chat type, which wraps
 *   it as `<name> message`; one that shows a line as it is would be a registry entry every
 *   client is sent, which Fabric API has no way to add without a data pack. So the line goes to
 *   each recipient as a system message, drawn once per group: unsigned, so it can't be
 *   reported, as a Spigot server's formatted chat is.
 *
 * Where it sits among other mods is a phase of the event per [priority], around Fabric's
 * default phase ([EventPriority.NORMAL]). Fabric API can't unregister a callback, so [close]
 * turns ours into a check that returns at once.
 */
public class FabricChat internal constructor(
    private val server: () -> MinecraftServer?,
    priority: EventPriority,
    log: Log,
) : ChatBridge(priority, log) {

    // What the last message heard became, for CHAT_MESSAGE: Minecraft broadcasts from the server
    // thread, one message at a time, right after ALLOW_CHAT_MESSAGE.
    private var pending: ChatEvent? = null

    private val refs = FabricChatRefs()

    init {
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> refs.forget(handler.player) }
        val phase = phase(priority)
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(phase) { message, sender, params -> allow(message, sender, params) }
        ServerMessageEvents.CHAT_MESSAGE.register(phase) { _, _, _ -> sent() }
    }

    private fun allow(message: PlayerChatMessage, sender: ServerPlayer, params: ChatType.Bound): Boolean {
        pending = null
        if (!heard()) return true
        val running = server() ?: return true
        val recipients = FabricRecipients(running, refs)
        val text = message.decoratedContent().string
        val chat = fire(ChatEvent(refs.of(sender), sender.scoreboardName, text, recipients, async = false))
        if (chat.isCancelled) return false
        if (!chat.changed) {
            pending = chat
            return true
        }
        deliver(running, message, sender, params, chat, recipients)
        fireSent(chat)
        return false
    }

    private fun sent() {
        val chat = pending ?: return
        pending = null
        fireSent(chat)
    }

    private fun deliver(running: MinecraftServer, message: PlayerChatMessage, sender: ServerPlayer, params: ChatType.Bound, chat: ChatEvent, recipients: FabricRecipients) {
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
        // Minecraft's own format, with the new text as the signed message's unsigned content.
        val outgoing = OutgoingChatMessage.create(if (chat.message == chat.originalMessage) message else message.withUnsignedContent(McComponent.literal(chat.message)))
        for (player in players) player.sendChatMessage(outgoing, false, params)
        running.sendSystemMessage(McComponent.literal("<${sender.scoreboardName}> ${chat.message}"))
    }

    override fun unregister() {
        pending = null
        refs.clear()
    }

    private companion object {
        private val ORDER = listOf(
            EventPriority.LOWEST to "lowest", EventPriority.LOW to "low", EventPriority.NORMAL to null,
            EventPriority.HIGH to "high", EventPriority.HIGHEST to "highest", EventPriority.MONITOR to "monitor",
        )

        // One phase per priority, ordered around Fabric's default, which is NORMAL's. Every Xena
        // mod adds the same orderings, which Fabric keeps once.
        private val PHASES = ORDER.associate { (priority, name) -> priority to (name?.let { id("xena", "chat_$it") } ?: Event.DEFAULT_PHASE) }

        init {
            val phases = ORDER.map { PHASES.getValue(it.first) }
            for (i in 0 until phases.size - 1) {
                ServerMessageEvents.ALLOW_CHAT_MESSAGE.addPhaseOrdering(phases[i], phases[i + 1])
                ServerMessageEvents.CHAT_MESSAGE.addPhaseOrdering(phases[i], phases[i + 1])
            }
        }

        fun phase(priority: EventPriority) = PHASES.getValue(priority)

        //? if identifier {
        private fun id(namespace: String, path: String): Identifier = Identifier.fromNamespaceAndPath(namespace, path)
        //?} else
        //private fun id(namespace: String, path: String): ResourceLocation = ResourceLocation(namespace, path)
    }
}

/** One [PlayerRef] per online player, so chat names recipients without making one each message. */
internal class FabricChatRefs {
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
 * Who sees a chat message on Fabric: every player online, as Minecraft broadcasts it, in a list
 * of the bridge's own (one copy of the player list per message, nothing per player).
 */
internal class FabricRecipients(private val server: MinecraftServer, private val refs: FabricChatRefs) : ChatRecipients {
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
