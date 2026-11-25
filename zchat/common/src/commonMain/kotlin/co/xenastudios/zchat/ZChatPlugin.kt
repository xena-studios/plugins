package co.xenastudios.zchat

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.HealthChecks
import co.xenastudios.core.common.admin.PasteService
import co.xenastudios.core.common.admin.PlayerDataRegistry
import co.xenastudios.core.common.admin.PlayerLookup
import co.xenastudios.core.common.admin.RecentErrors
import co.xenastudios.core.common.command.AdminCommands
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.ModuleReloader
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.i18n.Language
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Markup
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.common.text.Theme
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.zchat.api.ChatPlaceholder
import co.xenastudios.zchat.api.HiddenPlayers
import co.xenastudios.zchat.api.MessageKind
import co.xenastudios.zchat.api.MuteProvider
import co.xenastudios.zchat.api.ZChat
import co.xenastudios.zchat.api.ZChatApi
import co.xenastudios.zchat.config.ZChatConfig
import co.xenastudios.zchat.filter.ChatFilter
import kotlin.concurrent.Volatile

/** What the plugin's files said when they were last read: the config, the lang files, and the problems found. */
public class Loaded(
    public val config: Config,
    public val translations: Translations,
    /** Each problem, in English, already logged. */
    public val problems: List<String> = emptyList(),
)

/**
 * zChat's files: on the JVM, [JvmFiles]. [load] reads `config.yml` and `lang/` (off the server's
 * threads at startup, on the global thread for `/zchat reload`); [editWords] changes
 * `filter.words` in the file, for `/filter`.
 */
public interface Files {
    public fun load(): Loaded

    /**
     * Adds [word] to `filter.words` in `config.yml`, or with [add] `false` takes it out, comments
     * kept: `true` if the file changed, `false` if the word was (or wasn't) there already, or why
     * it couldn't be written.
     */
    public fun editWords(word: String, add: Boolean): Outcome<Boolean, String>
}

/**
 * zChat, the same on every platform: each adapter makes one with its [Platform], its
 * [ChatServer] and its chat bridge ([bridges]), loads the files off the server thread ([Files])
 * and calls [start] on the global thread. Everything the config says applies on
 * `/zchat reload`, commands included.
 */
public class ZChatPlugin(
    private val platform: Platform,
    /** What the adapter does to players. */
    public val server: ChatServer,
    private val files: Files,
    /** Each player's client language: the family's player cache. */
    languages: LanguageSource,
    private val bridges: ChatBridges,
    private val registrar: CommandRegistrar? = null,
    private val permissionRegistrar: PermissionRegistrar? = null,
    /** Finds a player by name for `/zchat forget`: the family's player cache. */
    lookup: PlayerLookup = PlayerLookup { co.xenastudios.core.api.pendingOf(null) },
    /** What `/zchat debug`'s report says about the server. */
    about: () -> Map<String, String> = { emptyMap() },
    /** Where `/zchat debug` uploads its report; `null` to write a file. */
    paste: PasteService? = null,
    override val version: String = VERSION,
) : ZChatApi {

    /** Where the plugin's console lines go; the last 50 warnings and errors are kept for `/zchat debug`. */
    public val log: RecentErrors = RecentErrors(platform.log, platform.clock)

    /** The messages, in each reader's language. */
    public val texts: Texts = Texts(languages)

    /** The API's events. On Bukkit the adapter bridges them to Bukkit events. */
    public val bus: SimpleEventBus = SimpleEventBus(log)

    override val events: EventBus get() = bus

    /** What zChat remembers while the server runs. */
    public val state: ChatState = ChatState()

    /** Other plugins' mutes, hidden players and placeholders. */
    public val hooks: Hooks = Hooks(log)

    /** The `/togglechat` and `/socialspy` choices kept per player. */
    public val settings: PlayerSettings = PlayerSettings(platform)

    /** The modules: chat handling, while it's on. */
    public val modules: ModuleSet = ModuleSet(platform)

    /** The server software, for `/zchat`. */
    public val platformName: String get() = platform.name

    /** The config and what's compiled from it, swapped whole on reload: chat reads one snapshot per message. */
    public class Compiled(public val config: Config, public val theme: Theme, public val formats: Formats, public val filter: ChatFilter)

    /** The config in use, with its formats and filter. */
    @Volatile
    public var compiled: Compiled = compile(ZChatConfig.defaults, Theme.DEFAULT, ArrayList())
        private set

    /** The config in use. */
    public val config: Config get() = compiled.config

    @Volatile
    private var problems: List<String> = emptyList()

    private val playerData = PlayerDataRegistry(lookup).apply { register("settings", settings) }

    /** `/zchat reload`, `/zchat debug` and `/zchat forget`. */
    public val admin: AdminCommands = AdminCommands(
        PLUGIN_ID, NAME, platform,
        ModuleReloader(modules, reloadFiles = ::reload, wanted = ::wanted),
        debug = Debug(
            platform, "$NAME $version",
            listOf(HealthChecks.threading(platform), HealthChecks.modules(modules), HealthChecks.config { problems }),
            about = about, errors = log, paste = paste,
        ),
        playerData = playerData,
        text = texts.admin,
    )

    internal val messages = PrivateMessages(this)
    private val commands = Commands(this)

    /** What runs the commands; built again on reload, from the new config. */
    @Volatile
    public var dispatcher: CommandDispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        private set

    /** The permissions the config declares. */
    @Volatile
    public var permissions: ZChatPermissions = commands.permissions
        private set

    /**
     * The platform's update checker, which the adapter starts with [updateSettings]; every reload
     * gives it the config's settings again, and [stop] closes it.
     */
    @Volatile
    public var updates: UpdateChecker? = null

    /** What the config says about checking for updates. */
    public val updateSettings: UpdateSettings get() = ZChatConfig.updates.settings(config)

    private var chat: ChatModule? = null

    /** Turns the plugin on with what [Files] read: permissions, commands and chat handling, then the API. On the global thread. */
    public fun start(loaded: Loaded) {
        apply(loaded)
        modules.sync(wanted())
        ZChat.register(this)
    }

    /** Turns the plugin off, as the server stops: the API, the modules, then the commands. */
    public fun stop() {
        ZChat.register(null)
        updates?.close()
        updates = null
        modules.clear()
        hooks.clear()
        registrar?.clear()
        permissionRegistrar?.sync(emptyList())
    }

    // /zchat reload: the files again, then everything built from them.
    private fun reload(): List<String> = apply(files.load())

    /** Uses what [Files] read: the formats and filter compiled, the commands rebuilt. On the global thread. */
    public fun apply(loaded: Loaded): List<String> {
        val warnings = ArrayList<String>()
        compiled = compile(loaded.config, loaded.translations.theme, warnings)
        texts.translations = loaded.translations
        // Turning /mutechat off would otherwise leave chat muted with no command to undo it.
        if (!loaded.config[ZChatConfig.mutechat.enabled] && state.muted) {
            state.muted = false
            log.info("mutechat is off, so chat is unmuted.")
        }
        dispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        permissions = commands.permissions
        permissionRegistrar?.sync(permissions.permissions + admin.permissions.permissions)
        warnings += registrar?.sync(dispatcher).orEmpty() + commands.warnings
        if (compiled.formats.enabled && compiled.formats.usesPlaceholders && !server.placeholders) {
            warnings += "config.yml: a chat format uses %placeholders%, but no placeholder plugin (PlaceholderAPI) is installed, so they show nothing."
        }
        warnings.forEach(log::warn)
        problems = loaded.problems + warnings
        updates?.apply(updateSettings)
        return problems
    }

    private fun compile(config: Config, theme: Theme, warnings: MutableList<String>): Compiled {
        val f = ZChatConfig.filter
        val filter = ChatFilter.compile(
            config[f.enabled], config[f.mode] == ZChatConfig.FilterMode.CENSOR, config[f.censorChar], config[f.smart],
            config[f.words], config[f.patterns], warnings,
        )
        return Compiled(config, theme, Formats.compile(config, theme, warnings), filter)
    }

    private fun wanted(): Map<String, () -> Module> = mapOf("chat" to { ChatModule(this, bridges).also { chat = it } })

    // Players.

    /** [player] joined as [name]: their remembered choices come back. On their thread. */
    public fun joined(player: PlayerRef, name: String) {
        state.joined(player, name)
        val kept = settings[player] ?: return
        val config = config
        if (kept.chatHidden && config[ZChatConfig.togglechat.enabled] && config[ZChatConfig.togglechat.remember]) state.hideChat(player, true)
        val spy = ZChatConfig.privateMessages.socialspy
        if (kept.spy && config[spy.enabled] && config[spy.remember] && config[spy.permission].let { it.isEmpty() || server.has(player, it) }) {
            state.spy(player, true)
        }
    }

    /** [player] left. */
    public fun quit(player: PlayerRef) {
        state.quit(player)
        chat?.quit(player)
    }

    /** Keeps [player]'s hidden chat and spying, as the config says to remember them. */
    internal fun remember(player: PlayerRef) {
        val config = config
        val hidden = config[ZChatConfig.togglechat.remember] && state.hidesChat(player)
        val spy = config[ZChatConfig.privateMessages.socialspy.remember] && state.spies(player)
        settings.set(player, PlayerSettings.Kept(hidden, spy))
    }

    /**
     * Brings in a choice an older zChat kept on the player itself (3.x's persistent data on
     * Bukkit): the Bukkit adapter calls it as a player joins, if they have one and nothing's kept
     * here yet.
     */
    public fun imported(player: PlayerRef, chatHidden: Boolean, spy: Boolean) {
        if (settings[player] != null || (!chatHidden && !spy)) return
        settings.set(player, PlayerSettings.Kept(chatHidden, spy))
    }

    /** Whether [viewer] may see [target] in chat: the server's visibility and every vanish hook. */
    internal fun visible(viewer: PlayerRef, target: PlayerRef): Boolean =
        server.canSee(viewer, target) && !hooks.isHidden(viewer, target)

    internal fun millis(): Long = platform.clock.elapsed().inWholeMilliseconds

    internal fun onPlayer(player: PlayerRef, action: () -> Unit) {
        platform.scheduler.now(Where.on(player)) { action() }
    }

    // Chat.

    /** What [typed] comes to through the filter, for [player]'s chat or private message. */
    internal fun screen(player: PlayerRef, typed: String, kind: MessageKind): Screen {
        val compiled = compiled
        val styled = compiled.formats.mayStyle(player, server)
        val plain = if (styled) ChatText.withoutStyles(typed) else typed
        if (!compiled.filter.isEmpty && !server.has(player, ZChatPermissions.BYPASS_FILTER)) {
            val result = compiled.filter.apply(plain)
            if (result.matched) {
                val block = compiled.config[ZChatConfig.filter.mode] == ZChatConfig.FilterMode.BLOCK
                filterMatched(player, kind, plain, result.matches(plain), block)
                if (block) return Screen(true, plain, Component.EMPTY)
                // Censored text loses its styles: they can't be lined up with what was hidden.
                return Screen(false, result.text, Component.text(result.text))
            }
        }
        return Screen(false, plain, ChatText.player(typed, styled))
    }

    /** One chat line in [format], for [sender] (called [name]) saying [message]. */
    internal fun line(format: Formats.Format, sender: PlayerRef, name: String, message: Component, added: Map<String, String>): Component {
        val tags = hooks.placeholders(sender).let { if (added.isEmpty()) it else it + added }
        return compiled.formats.render(format, sender, server, chatValues(name, server.displayName(sender), message), tags)
    }

    /** Tells [player] [message], in their language. */
    internal fun tell(player: PlayerRef, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        texts.render(player, message, *placeholders)?.let { server.send(player, it) }
    }

    /** Sends [message] to every player and the console, drawn once per language. */
    internal fun broadcast(message: MessageKey, vararg placeholders: Pair<String, Any>) {
        val drawn = HashMap<Language, Component?>()
        for (player in state.online()) {
            val line = drawn.getOrPut(texts.language(player)) { texts.render(texts.language(player), message, *placeholders) } ?: continue
            server.send(player, line)
        }
        texts.render(null, message, *placeholders)?.let(server::console)
    }

    /** `/clearchat`: blank lines for everyone without the bypass, then who cleared it. */
    internal fun clearChat(source: CommandSource) {
        // One message of blank lines: a single packet per player, not one per line.
        val blank = Component.text(List(config[ZChatConfig.clearchat.lines]) { " " }.joinToString("\n"))
        for (player in state.online()) if (!server.has(player, ZChatPermissions.BYPASS_CLEARCHAT)) server.send(player, blank)
        broadcast(ZChatMessages.clearchatCleared, "player" to source.name)
    }

    /** `/filter add` or `remove`: the file changed, then the config applied, then the answer. */
    internal fun editWords(source: CommandSource, word: String, add: Boolean) {
        val reply = when (val edited = files.editWords(word, add)) {
            is Outcome.Err -> {
                log.warn("/filter couldn't change config.yml: ${edited.error}")
                ZChatMessages.filterSaveFailed
            }
            is Outcome.Ok -> {
                if (edited.value) apply(files.load())
                when {
                    !edited.value -> if (add) ZChatMessages.filterAlready else ZChatMessages.filterNotFound
                    add -> ZChatMessages.filterAdded
                    else -> ZChatMessages.filterRemoved
                }
            }
        }
        texts.reply(source, reply, "word" to word)
        if (reply == ZChatMessages.filterAdded && !word.startsWith("*") && !word.endsWith("*")) texts.reply(source, ZChatMessages.filterAddedTip, "word" to word)
    }

    /** A private message to every spy who isn't in it, and the console if the config says. */
    internal fun spy(from: PlayerRef?, fromName: String, to: PlayerRef?, toName: String, message: Component) {
        val config = config
        val spy = ZChatConfig.privateMessages.socialspy
        if (!config[spy.enabled]) return
        val formats = compiled.formats
        val line = formats.render(
            formats.spy, from, server,
            mapOf("sender" to Placeholder.text(fromName), "receiver" to Placeholder.text(toName), "message" to Placeholder.component(message)),
        )
        val node = config[spy.permission]
        for (watcher in state.spies()) {
            if (watcher != from && watcher != to && (node.isEmpty() || server.has(watcher, node))) server.send(watcher, line)
        }
        if (config[spy.logToConsole] && from != null && to != null) server.console(line)
    }

    // The API.

    override fun registerMuteProvider(owner: String, provider: MuteProvider) {
        hooks.addMute(owner, provider)
    }

    override fun unregisterMuteProvider(provider: MuteProvider) {
        hooks.removeMute(provider)
    }

    override fun registerHiddenPlayers(owner: String, hook: HiddenPlayers) {
        hooks.addHidden(owner, hook)
    }

    override fun unregisterHiddenPlayers(hook: HiddenPlayers) {
        hooks.removeHidden(hook)
    }

    override fun registerPlaceholder(name: String, placeholder: ChatPlaceholder) {
        require(TAG.matches(name)) { "a tag's name is lower-case letters, digits, _ and -: \"$name\"" }
        require(name !in Formats.OWN && name !in Theme.TAG_NAMES && !name.startsWith("papi-")) { "<$name> is zChat's own" }
        hooks.addPlaceholder(name, name, placeholder)
    }

    override fun unregisterPlaceholder(name: String) {
        hooks.removePlaceholder(name)
    }

    override fun broadcast(message: String) {
        val line = styled(message)
        for (player in state.online()) if (!hasChatHidden(player)) server.send(player, line)
        server.console(line)
    }

    override fun send(player: PlayerRef, message: String): Boolean {
        if (!state.isOnline(player) || hasChatHidden(player)) return false
        server.send(player, styled(message))
        return true
    }

    // zChat's style: the theme's prefix and colour, as /mutechat's announcement has.
    private fun styled(message: String): Component {
        val theme = compiled.theme
        return theme.chat(Markup.parse("<prefix>$message", theme.tags).render())
    }

    override fun hasChatHidden(player: PlayerRef): Boolean = config[ZChatConfig.togglechat.enabled] && state.hidesChat(player)

    override fun isChatMuted(): Boolean = state.muted

    override fun getFormatName(player: PlayerRef): String =
        if (!state.isOnline(player)) ZChatApi.DEFAULT_FORMAT else compiled.formats.of(player, server).name

    public companion object {
        public const val PLUGIN_ID: String = "zchat"
        public const val NAME: String = "zChat"

        /** zChat's version. The adapters' builds check theirs is the same. */
        public const val VERSION: String = "4.0.0"

        private val TAG = Regex("[a-z0-9_-]+")
    }
}
