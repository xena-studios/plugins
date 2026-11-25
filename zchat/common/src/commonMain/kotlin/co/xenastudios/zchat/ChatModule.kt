package co.xenastudios.zchat

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.common.chat.ChatBridge
import co.xenastudios.core.common.chat.ChatEvent
import co.xenastudios.core.common.chat.ChatRendering
import co.xenastudios.core.common.chat.ChatSentEvent
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleContext
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Markup
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.zchat.api.MessageKind
import co.xenastudios.zchat.api.event.ChatMessageEvent
import co.xenastudios.zchat.api.event.ChatMessageSentEvent
import co.xenastudios.zchat.api.event.FilterMatchEvent
import co.xenastudios.zchat.api.event.MentionEvent
import co.xenastudios.zchat.config.ZChatConfig
import kotlin.math.ceil

/**
 * Makes the platform's chat bridge at the priority zChat's chat sits at: `LOW`, as 3.x's
 * listener did, so plugins that change a message first (an `[item]` tag) come before, and
 * plugins that only watch come after. Each adapter gives its platform's.
 */
public fun interface ChatBridges {
    public fun open(priority: EventPriority): ChatBridge
}

/**
 * Every chat message comes through here, while chat handling is on. The cheapest checks run
 * first (the mute, mute providers, cooldown, filter, anti-repeat), so a message that's stopped
 * costs almost nothing; one that gets through is drawn once, and that line goes to everyone
 * ([ChatRendering.same]).
 */
internal class ChatModule(private val plugin: ZChatPlugin, private val bridges: ChatBridges) : Module {

    private var bridge: ChatBridge? = null

    /**
     * What a message's checks worked out, for when it's sent: only a message that really goes out
     * counts toward the cooldown and anti-repeat, and pings anyone. One per sender (their latest),
     * so a message another plugin cancels leaves nothing behind for long.
     */
    private class Pending(val chat: ChatEvent, val recent: ChatState.Recent, val mentions: Mentions.Applied?, val line: (() -> Component)?)

    private val pending = concurrentMap<PlayerRef, Pending>()

    override fun enable(context: ModuleContext) {
        val bridge = bridges.open(EventPriority.LOW)
        this.bridge = bridge
        bridge.listen(EventPriority.NORMAL, ignoreCancelled = true) { chat -> onChat(chat) }
        bridge.onSent { sent -> onSent(sent, context) }
        context.onDisable { close() }
    }

    override fun disable() {
        close()
    }

    private fun close() {
        bridge?.close()
        bridge = null
        pending.clear()
    }

    /** Forgets what's waiting for [player], who left. */
    fun quit(player: PlayerRef) {
        pending.remove(player)
    }

    private fun onChat(chat: ChatEvent) {
        val compiled = plugin.compiled
        val config = compiled.config
        val server = plugin.server
        val state = plugin.state
        val sender = chat.sender
        val now = plugin.millis()

        if (state.muted && config[ZChatConfig.mutechat.enabled] && !server.has(sender, ZChatPermissions.BYPASS_MUTE)) {
            return refuse(chat, ZChatMessages.mutechatBlocked)
        }
        // Other plugins' mutes, through the API: one read when there are none.
        if (plugin.hooks.hasMutes) {
            plugin.hooks.muteReason(sender, MessageKind.CHAT)?.let { reason ->
                chat.isCancelled = true
                server.send(sender, Markup.parse(reason, compiled.theme.tags).render())
                return
            }
        }

        val recent = state.recent(sender)
        val cooldown = ZChatConfig.cooldown
        val seconds = config[cooldown.seconds]
        if (config[cooldown.enabled] && seconds > 0 && recent != null && !server.has(sender, ZChatPermissions.BYPASS_COOLDOWN)) {
            val left = recent.at + (seconds * 1000).toLong() - now
            if (left > 0) return refuse(chat, ZChatMessages.cooldownWait, "seconds" to ceil(left / 1000.0).toLong())
        }

        val screen = plugin.screen(sender, chat.message, MessageKind.CHAT)
        if (screen.blocked) return refuse(chat, ZChatMessages.filterBlocked)

        val antiRepeat = ZChatConfig.antiRepeat
        var key: String? = null
        if (config[antiRepeat.enabled] && !server.has(sender, ZChatPermissions.BYPASS_ANTI_REPEAT)) {
            key = co.xenastudios.zchat.filter.Repeats.key(screen.plain)
            if (key.length < co.xenastudios.zchat.filter.Repeats.SHORTEST) {
                key = null
            } else if (recent?.key != null && now - recent.keyAt < config[antiRepeat.seconds] * 1000L &&
                co.xenastudios.zchat.filter.Repeats.similarity(key, recent.key) >= config[antiRepeat.similarity]
            ) {
                return refuse(chat, ZChatMessages.antiRepeatBlocked)
            }
        }
        val sent = if (key != null) ChatState.Recent(now, key, now) else ChatState.Recent(now, recent?.key, recent?.keyAt ?: 0)

        var message = screen.message
        var mentions: Mentions.Applied? = null
        val mentionSettings = ZChatConfig.mentions
        if (config[mentionSettings.enabled]) {
            val everyone = config[mentionSettings.everyonePermission]
            mentions = Mentions.apply(
                message, sender, compiled.formats.mention,
                mayPingEveryone = everyone.isNotEmpty() && server.has(sender, everyone),
                find = { name -> state.online(name)?.let { ref -> state.name(ref)?.let { ref to it } } ?: plugin.network?.find(name)?.let { it.player to it.name } },
                visible = { target -> plugin.visible(sender, target) },
            )
            message = mentions.message
        }
        // The text other plugins, the console and the server's log see: censored, if it was.
        chat.message = screen.plain

        // Players who hid chat don't see others' messages, but still see their own.
        if (config[ZChatConfig.togglechat.enabled]) chat.recipients.removeIf { it != sender && state.hidesChat(it) }

        val format = compiled.formats.of(sender, server)
        // For other plugins, through the API; made only when something listens.
        var added: Map<String, String> = emptyMap()
        if (plugin.bus.hasListeners(ChatMessageEvent.TYPE)) {
            val event = plugin.bus.fire(ChatMessageEvent.TYPE, ChatMessageEvent(sender, chat.senderName, screen.plain, format.name, chat.async))
            if (event.isCancelled) {
                chat.isCancelled = true
                return
            }
            added = event.placeholders
        }

        var line: (() -> Component)? = null
        if (compiled.formats.enabled) {
            val name = chat.senderName
            val shown = message
            val said = screen.plain
            val tags = added
            // Drawn once, after every plugin's say, and the same line sent to every viewer.
            chat.rendering = ChatRendering.same { final ->
                // A later plugin changed the words: show its text, as it left it.
                val body = if (final.message == said) shown else Component.text(final.message)
                plugin.line(format, sender, name, body, tags)
            }
            line = { plugin.line(format, sender, name, if (chat.message == said) shown else Component.text(chat.message), tags) }
        }
        pending[sender] = Pending(chat, sent, mentions, line)
    }

    private fun onSent(sent: ChatSentEvent, context: ModuleContext) {
        val chat = sent.chat
        val sender = chat.sender
        val done = pending[sender]?.takeIf { it.chat === chat } ?: return
        pending.remove(sender)
        plugin.state.recent(sender, done.recent)
        if (plugin.bus.hasListeners(ChatMessageSentEvent.TYPE)) {
            val recipients = HashSet<PlayerRef>(chat.recipients.size * 2)
            chat.recipients.forEach { recipients += it }
            plugin.bus.fire(ChatMessageSentEvent.TYPE, ChatMessageSentEvent(sender, chat.senderName, chat.message, recipients, network = false))
        }
        // To the network's other servers: the same line, drawn here, where the sender's group and placeholders are known.
        val network = plugin.network
        if (network != null && plugin.compiled.config[ZChatConfig.network.chat]) {
            val drawn = done.line?.invoke() ?: Component.text("<${chat.senderName}> ${chat.message}")
            network.chat(sender, chat.senderName, drawn, chat.message, done.mentions?.pinged.orEmpty(), done.mentions?.everyone == true)
        }
        val mentions = done.mentions ?: return
        if (!mentions.everyone && mentions.pinged.isEmpty()) return
        val sound = plugin.compiled.config[ZChatConfig.mentions.sound]
        if (sound.isNone && !plugin.bus.hasListeners(MentionEvent.TYPE)) return
        chat.recipients.forEach { viewer ->
            if (viewer == sender || !(mentions.everyone || viewer in mentions.pinged)) return@forEach
            plugin.ping(sender, viewer, mentions.everyone, context)
        }
    }

    private fun refuse(chat: ChatEvent, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        chat.isCancelled = true
        plugin.tell(chat.sender, message, *placeholders)
    }
}

/**
 * What a player's text came to through the filter, for chat and private messages alike: whether
 * it was [blocked], the words as the filter and anti-repeat see them ([plain], censored if it
 * was), and how it shows ([message]).
 */
public class Screen(public val blocked: Boolean, public val plain: String, public val message: Component)

/** Fires [FilterMatchEvent] for a match, if something listens. */
internal fun ZChatPlugin.filterMatched(player: PlayerRef, kind: MessageKind, checked: String, matches: List<String>, blocked: Boolean) {
    if (bus.hasListeners(FilterMatchEvent.TYPE)) bus.fire(FilterMatchEvent.TYPE, FilterMatchEvent(player, kind, checked, matches, blocked))
}

/** A mention's ping for [viewer], on their own thread, where it asks again whether [sender] can see them. */
internal fun ZChatPlugin.ping(sender: PlayerRef, viewer: PlayerRef, everyone: Boolean, context: ModuleContext) {
    context.scheduler.run(Where.on(viewer)) {
        if (!everyone && !visible(sender, viewer)) return@run
        if (bus.hasListeners(MentionEvent.TYPE) && bus.fire(MentionEvent.TYPE, MentionEvent(viewer, sender, everyone)).isCancelled) return@run
        server.playSound(viewer, compiled.config[ZChatConfig.mentions.sound])
    }
}

/** The values every chat format has. */
internal fun chatValues(name: String, displayName: Component, message: Component): Map<String, Placeholder> = mapOf(
    "player" to Placeholder.text(name),
    "displayname" to Placeholder.component(ChatText.inert(displayName)),
    "message" to Placeholder.component(message),
)
