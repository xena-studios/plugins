package co.xenastudios.zchat

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.ArgumentError
import co.xenastudios.core.common.command.ArgumentType
import co.xenastudios.core.common.command.ArgumentTypes
import co.xenastudios.core.common.command.CommandBuilder
import co.xenastudios.core.common.command.CommandContext
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.CommandTree
import co.xenastudios.core.common.command.CommandTreeBuilder
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.common.text.Component
import co.xenastudios.zchat.api.MessageKind
import co.xenastudios.zchat.config.CommandSettings
import co.xenastudios.zchat.config.ZChatConfig

/**
 * zChat's permissions: the fixed bypasses, and every node the config names (each command's, the
 * colour and `@everyone` permissions, each group's), declared with its default. Built again on
 * every reload; two settings naming the same node share one.
 */
public class ZChatPermissions internal constructor(private val admin: PermissionSet) : PermissionSet(ZChatPlugin.PLUGIN_ID) {
    init {
        permission("bypass.mute", "Talk while chat is muted with /mutechat.")
        permission("bypass.cooldown", "Skip the gap between chat messages.")
        permission("bypass.filter", "Skip the chat filter, in chat and private messages.")
        permission("bypass.anti-repeat", "Say the same thing twice in a row.")
        permission("bypass.clearchat", "Keep your chat when /clearchat clears everyone's.")
    }

    internal fun of(node: String, description: String, default: PermissionDefault = PermissionDefault.OP): Permission? {
        if (node.isEmpty()) return null
        admin[node]?.let { return it }
        return this[node] ?: node(node, description, default)
    }

    public companion object {
        public const val BYPASS_MUTE: String = "zchat.bypass.mute"
        public const val BYPASS_COOLDOWN: String = "zchat.bypass.cooldown"
        public const val BYPASS_FILTER: String = "zchat.bypass.filter"
        public const val BYPASS_ANTI_REPEAT: String = "zchat.bypass.anti-repeat"
        public const val BYPASS_CLEARCHAT: String = "zchat.bypass.clearchat"

        /** Minecraft's own permission for target selectors; operators have it by default. */
        public const val SELECTORS: String = "minecraft.command.selector"
    }
}

/** Builds the command tree from the config, and runs each command. */
internal class Commands(private val plugin: ZChatPlugin) {
    private val server get() = plugin.server
    private val state get() = plugin.state

    /** The permissions the last tree checks. */
    lateinit var permissions: ZChatPermissions
        private set

    /** What building the last tree found wrong: aliases that clash. */
    var warnings: List<String> = emptyList()
        private set

    fun tree(config: Config): CommandTree {
        val perms = ZChatPermissions(plugin.admin.permissions)
        val problems = ArrayList<String>()
        val f = ZChatConfig.formatting
        perms.of(config[f.colorPermission], "Use colour and style tags in your own chat messages.")
        perms.of(config[ZChatConfig.mentions.everyonePermission], "Mention everyone with @everyone.")
        // Groups are given out on purpose, so operators don't all get the admin format.
        for ((name, group) in config[f.groups]) perms.of(group.permission(name), "Chat in the $name format.", PermissionDefault.NOBODY)

        fun permission(section: CommandSettings, default: PermissionDefault = PermissionDefault.OP): Permission? =
            perms.of(config[section.permission], "Use /${section.command}.", default)

        val taken = HashSet<String>()
        fun CommandTreeBuilder.add(section: CommandSettings, description: String, default: PermissionDefault, block: CommandBuilder.() -> Unit) {
            if (!config[section.enabled]) return
            val name = section.command
            if (name in taken) {
                problems += "/$name is also another of zChat's commands' alias, so it's skipped. Remove that alias."
                return
            }
            val aliases = config[section.aliases].filter { it != name }.distinct().filter { alias ->
                (alias !in taken).also { free -> if (!free) problems += "${section.path}.aliases: /$alias is already another zChat command's, so it's left out." }
            }
            taken += name
            taken += aliases
            root(name, description, permission(section, default), aliases, block)
        }

        val pm = ZChatConfig.privateMessages
        val tree = commands {
            taken += listOf(ZChatPlugin.PLUGIN_ID, "zc")
            root(ZChatPlugin.PLUGIN_ID, "Shows zChat's version, and holds its admin subcommands.", aliases = listOf("zc")) {
                executes { ctx -> info(ctx) }
                literal("info", "Shows zChat's version.") { executes { ctx -> info(ctx) } }
                mount(plugin.admin)
            }

            add(ZChatConfig.clearchat, "Clear chat for everyone.", PermissionDefault.OP) {
                executes { ctx -> plugin.clearChat(ctx.source) }
            }
            add(ZChatConfig.mutechat, "Mute or unmute chat for everyone.", PermissionDefault.OP) {
                executes { ctx ->
                    state.muted = !state.muted
                    plugin.broadcast(if (state.muted) ZChatMessages.mutechatMuted else ZChatMessages.mutechatUnmuted, "player" to ctx.source.name)
                }
            }
            add(ZChatConfig.togglechat, "Hide or show other players' chat.", PermissionDefault.EVERYONE) {
                playerOnly()
                executes { ctx ->
                    val player = ctx.player
                    val hide = !state.hidesChat(player)
                    state.hideChat(player, hide)
                    if (config[ZChatConfig.togglechat.remember]) plugin.remember(player)
                    plugin.texts.reply(ctx.source, if (hide) ZChatMessages.togglechatOff else ZChatMessages.togglechatOn)
                }
            }
            add(pm.msg, "Send a player a private message.", PermissionDefault.EVERYONE) {
                val target = argument("player", Recipient(plugin), "Who to message: an online player.")
                val text = argument("message", ArgumentTypes.text, "What to say.")
                executes { ctx -> plugin.messages.msg(ctx, ctx[target], ctx[text]) }
            }
            add(pm.reply, "Answer your last private message.", PermissionDefault.EVERYONE) {
                val text = argument("message", ArgumentTypes.text, "What to say.")
                executes { ctx -> plugin.messages.reply(ctx, ctx[text]) }
            }
            add(pm.socialspy, "See every private message.", PermissionDefault.OP) {
                playerOnly()
                executes { ctx ->
                    val player = ctx.player
                    val on = !state.spies(player)
                    state.spy(player, on)
                    if (config[pm.socialspy.remember]) plugin.remember(player)
                    plugin.texts.reply(ctx.source, if (on) ZChatMessages.socialspyOn else ZChatMessages.socialspyOff)
                }
            }
            add(ZChatConfig.filter.command, "Add, remove and list chat filter words, or test a message.", PermissionDefault.OP) {
                literal("add", "Add a word to the filter.") {
                    val word = argument("word", ArgumentTypes.text, "One word, with * at either end to catch longer words.")
                    executes { ctx -> changeWords(ctx, ctx[word], add = true) }
                }
                literal("remove", "Take a word out of the filter.") {
                    val word = argument("word", FilterWord(plugin), "A word in the filter.")
                    executes { ctx -> changeWords(ctx, ctx[word], add = false) }
                }
                literal("list", "List the filter's words.") {
                    executes { ctx ->
                        val words = plugin.compiled.config[ZChatConfig.filter.words].map { it.trim() }.filter { it.isNotEmpty() }
                        if (words.isEmpty()) plugin.texts.reply(ctx.source, ZChatMessages.filterListEmpty)
                        // Words go in as plain text, so a word can never be read as markup.
                        else plugin.texts.reply(ctx.source, ZChatMessages.filterList, "count" to words.size, "words" to words.joinToString(", "))
                    }
                }
                literal("test", "See what the filter would do to a message.") {
                    val text = argument("message", ArgumentTypes.text, "The message to test.")
                    executes { ctx ->
                        val result = plugin.compiled.filter.apply(ctx[text])
                        val reply = when {
                            !result.matched -> ZChatMessages.filterTestPasses
                            plugin.compiled.config[ZChatConfig.filter.mode] == ZChatConfig.FilterMode.BLOCK -> ZChatMessages.filterTestBlocked
                            else -> ZChatMessages.filterTestCensored
                        }
                        plugin.texts.reply(ctx.source, reply, "message" to result.text)
                    }
                }
            }
        }
        permissions = perms
        warnings = problems
        return tree
    }

    private fun info(ctx: CommandContext) {
        plugin.texts.reply(ctx.source, ZChatMessages.info, "version" to plugin.version, "platform" to plugin.platformName)
    }

    // One word, optionally with * at either end: "/filter add two words" is refused rather than half added.
    private fun changeWords(ctx: CommandContext, typed: String, add: Boolean) {
        val word = typed.trim().lowercase()
        if (!WORD.matches(word)) fail(ctx, ZChatMessages.filterInvalid)
        val source = ctx.source
        plugin.editWords(source, word, add)
    }

    private fun fail(ctx: CommandContext, message: MessageKey, vararg placeholders: Pair<String, Any>): Nothing =
        ctx.fail(plugin.texts.render(ctx.source.player, message, *placeholders) ?: Component.EMPTY)

    private companion object {
        val WORD = Regex("\\*?[^\\s*]+\\*?")
    }

    /** `/msg`'s player: a name, as typed (selectors are read in the command), suggesting who the sender may message. */
    private class Recipient(private val plugin: ZChatPlugin) : ArgumentType<String> {
        override val name: String get() = "player"
        override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> = Outcome.Ok(input)
        override fun suggest(source: CommandSource): List<String> = plugin.messages.names(source)
    }

    /** `/filter remove`'s word, suggesting the words in the filter. */
    private class FilterWord(private val plugin: ZChatPlugin) : ArgumentType<String> {
        override val name: String get() = "word"
        override val greedy: Boolean get() = true
        override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> = Outcome.Ok(input)
        override fun suggest(source: CommandSource): List<String> = plugin.compiled.config[ZChatConfig.filter.words].map { it.trim() }
    }
}

/** `/msg` and `/reply`: delivery, spies and reply targets. */
internal class PrivateMessages(private val plugin: ZChatPlugin) {
    private val server get() = plugin.server
    private val state get() = plugin.state

    /** Who [source] may message, for completion: online players they can see. */
    fun names(source: CommandSource): List<String> {
        val viewer = source.player
        val here = state.online().filter { viewer == null || it == viewer || plugin.visible(viewer, it) }.mapNotNull { state.name(it) }
        return (here + remote()?.names().orEmpty()).distinct().sorted()
    }

    // The network, when private messages cross it.
    private fun remote(): co.xenastudios.zchat.network.NetworkChat? =
        plugin.network?.takeIf { plugin.compiled.config[ZChatConfig.network.privateMessages] }

    fun msg(ctx: CommandContext, name: String, text: String) {
        val source = ctx.source
        // Not here, but on another server of the network.
        if (state.online(name) == null && !name.startsWith("@")) {
            val there = remote()?.find(name)
            if (there != null) {
                if (!muted(source)) deliverRemote(ctx, there.name, text, reply = false)
                return
            }
        }
        val targets = targets(ctx, name)
        // Once, not per target: a selector can name many.
        if (muted(source)) return
        for (target in targets) deliver(ctx, target, state.name(target) ?: name, text, reply = false)
    }

    fun reply(ctx: CommandContext, text: String) {
        val source = ctx.source
        val self = source.player
        val partner = (if (self == null) state.consolePartner else state.partner(self))
            ?: fail(ctx, ZChatMessages.replyNobody)
        if (muted(source)) return
        if (partner.remote) return deliverRemote(ctx, partner.name, text, reply = true)
        val to = partner.player
        if (to == null) return deliver(ctx, null, "CONSOLE", text, reply = true)
        if (!state.isOnline(to) || (self != null && !plugin.visible(self, to))) fail(ctx, CoreMessages.commandUnknownPlayer, "player" to partner.name)
        deliver(ctx, to, state.name(to) ?: partner.name, text, reply = true)
    }

    // A name, or a selector like Minecraft's /msg takes (@p, @a[distance=..10]), for senders
    // Minecraft lets use them (operators, the console and command blocks by default): otherwise
    // anyone could message the whole server with one command. For the rest, "@a" is a name.
    private fun targets(ctx: CommandContext, name: String): List<PlayerRef> {
        val source = ctx.source
        val viewer = source.player
        if (name.startsWith("@") && source.hasPermission(ZChatPermissions.SELECTORS)) {
            val found = try {
                server.select(source, name)
            } catch (e: IllegalArgumentException) {
                fail(ctx, CoreMessages.commandUnknownPlayer, "player" to name)
            }
            if (found != null) {
                val visible = found.filter { viewer == null || it == viewer || plugin.visible(viewer, it) }
                if (visible.isEmpty()) fail(ctx, CoreMessages.commandUnknownPlayer, "player" to name)
                return visible
            }
        }
        val target = state.online(name)?.takeIf { viewer == null || it == viewer || plugin.visible(viewer, it) }
            ?: fail(ctx, CoreMessages.commandUnknownPlayer, "player" to name)
        return listOf(target)
    }

    /** Whether other plugins' mutes stop [source]'s private messages; tells them why. */
    private fun muted(source: CommandSource): Boolean {
        val player = source.player ?: return false
        if (!plugin.hooks.hasMutes) return false
        val reason = plugin.hooks.muteReason(player, MessageKind.PRIVATE_MESSAGE) ?: return false
        source.reply(co.xenastudios.core.common.text.Markup.parse(reason, plugin.compiled.theme.tags).render())
        return true
    }

    /**
     * Delivers a private message from [ctx]'s sender to [to] (a player here, or `null` for the
     * console): filtered, to both sides, to spies, and remembered for `/reply`.
     */
    private fun deliver(ctx: CommandContext, to: PlayerRef?, toName: String, text: String, reply: Boolean) {
        val source = ctx.source
        val from = source.player
        var message: Component = Component.text(text)
        var plain = text
        if (from != null) {
            val screen = plugin.screen(from, text, MessageKind.PRIVATE_MESSAGE)
            if (screen.blocked) fail(ctx, ZChatMessages.filterBlocked)
            message = screen.message
            plain = screen.plain
        }
        val bus = plugin.bus
        if (bus.hasListeners(co.xenastudios.zchat.api.event.PrivateMessageEvent.TYPE)) {
            val event = bus.fire(
                co.xenastudios.zchat.api.event.PrivateMessageEvent.TYPE,
                co.xenastudios.zchat.api.event.PrivateMessageEvent(from, source.name, to, toName, plain, reply, network = false),
            )
            if (event.isCancelled) return
            if (event.message != plain) message = Component.text(event.message)
        }
        val formats = plugin.compiled.formats
        source.reply(formats.render(formats.sent, from, server, mapOf("player" to text(toName), "message" to component(message))))
        val received = formats.render(formats.received, from, server, mapOf("player" to text(source.name), "message" to component(message)))
        if (to == null) server.console(received) else server.send(to, received)
        if (to == from) return // Talking to yourself: no ping, nothing to reply to, nothing for spies.
        val config = plugin.compiled.config
        val pm = ZChatConfig.privateMessages
        if (to != null) {
            val sound = config[pm.msg.sound]
            if (!sound.isNone) plugin.onPlayer(to) { server.playSound(to, sound) }
            state.partner(to, ChatState.Partner(from, source.name))
        } else {
            state.consolePartner = ChatState.Partner(from, source.name)
        }
        if (from != null) state.partner(from, ChatState.Partner(to, toName)) else state.consolePartner = ChatState.Partner(to, toName)
        plugin.spy(from, source.name, to, toName, message)
    }

    /**
     * A private message to [toName] on another server: filtered and its event fired here, then sent
     * through the proxy; the sender sees their line once the other server says it arrived.
     */
    private fun deliverRemote(ctx: CommandContext, toName: String, text: String, reply: Boolean) {
        val source = ctx.source
        val network = remote() ?: fail(ctx, CoreMessages.commandUnknownPlayer, "player" to toName)
        val from = source.player ?: fail(ctx, CoreMessages.commandPlayersOnly)
        val screen = plugin.screen(from, text, MessageKind.PRIVATE_MESSAGE)
        if (screen.blocked) fail(ctx, ZChatMessages.filterBlocked)
        var message = screen.message
        var plain = screen.plain
        val bus = plugin.bus
        if (bus.hasListeners(co.xenastudios.zchat.api.event.PrivateMessageEvent.TYPE)) {
            val event = bus.fire(
                co.xenastudios.zchat.api.event.PrivateMessageEvent.TYPE,
                co.xenastudios.zchat.api.event.PrivateMessageEvent(from, source.name, null, toName, plain, reply, network = true),
            )
            if (event.isCancelled) return
            if (event.message != plain) {
                plain = event.message
                message = Component.text(plain)
            }
        }
        network.privateMessage(from, source.name, toName, message, plain, reply) { to, name, delivered ->
            if (!delivered) {
                plugin.texts.render(from, CoreMessages.commandUnknownPlayer, "player" to name)?.let(source::reply)
                return@privateMessage
            }
            val formats = plugin.compiled.formats
            source.reply(formats.render(formats.sent, from, server, mapOf("player" to text(name), "message" to component(message))))
            state.partner(from, ChatState.Partner(to, name, remote = true))
            plugin.spy(from, source.name, to, name, message)
        }
    }

    /**
     * A private message from [fromName] on another server to the player here called [toName]:
     * delivered as one from here would be, or `null` if there's no such player (or they're hidden
     * from the sender).
     */
    fun receiveRemote(from: PlayerRef, fromName: String, toName: String, message: Component): PlayerRef? {
        val to = state.online(toName) ?: return null
        if (plugin.hooks.isHidden(from, to)) return null
        val formats = plugin.compiled.formats
        server.send(to, formats.render(formats.received, null, server, mapOf("player" to text(fromName), "message" to component(message))))
        val sound = plugin.compiled.config[ZChatConfig.privateMessages.msg.sound]
        if (!sound.isNone) plugin.onPlayer(to) { server.playSound(to, sound) }
        state.partner(to, ChatState.Partner(from, fromName, remote = true))
        plugin.spy(from, fromName, to, state.name(to) ?: toName, message)
        return to
    }

    private fun fail(ctx: CommandContext, message: MessageKey, vararg placeholders: Pair<String, Any>): Nothing =
        ctx.fail(plugin.texts.render(ctx.source.player, message, *placeholders) ?: Component.EMPTY)

    private fun text(value: String) = co.xenastudios.core.common.text.Placeholder.text(value)
    private fun component(value: Component) = co.xenastudios.core.common.text.Placeholder.component(value)
}
