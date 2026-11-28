package co.xenastudios.xlimbo

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.PasteService
import co.xenastudios.core.common.admin.PlayerDataRegistry
import co.xenastudios.core.common.admin.RecentErrors
import co.xenastudios.core.common.command.AdminCommands
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.ModuleReloader
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleContext
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.every
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.xlimbo.api.EnterReason
import co.xenastudios.xlimbo.api.HoldHook
import co.xenastudios.xlimbo.api.PriorityHook
import co.xenastudios.xlimbo.api.ReturnCause
import co.xenastudios.xlimbo.api.ReturnRoute
import co.xenastudios.xlimbo.api.XLimbo
import co.xenastudios.xlimbo.api.XLimboApi
import co.xenastudios.xlimbo.api.event.EnteredLimboEvent
import co.xenastudios.xlimbo.api.event.PreEnterLimboEvent
import co.xenastudios.xlimbo.api.event.PreReturnEvent
import co.xenastudios.xlimbo.api.event.ReturnedEvent
import co.xenastudios.xlimbo.config.XLimboConfig
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

/** What the plugin's files said when they were last read: the config, the lang files, and the problems found. */
public class Loaded(
    public val config: Config,
    public val translations: Translations,
    /** Each problem, in English, already logged. */
    public val problems: List<String> = emptyList(),
)

/** xLimbo's files: on the JVM, `JvmFiles`. [load] reads `config.yml` and `lang/`. */
public fun interface Files {
    public fun load(): Loaded
}

/**
 * xLimbo, the logic every platform shares: the return queue and its status ping, `/join`, the
 * admin commands, arrivals, the action bar, and the API. Each adapter makes one with its
 * [Platform] and its [LimboServer], and calls [start] as the plugin enables. The world, its
 * generator and the protections are the adapter's ([LimboServer.sync], [LimboServer.listen]).
 *
 * Paper only for now, so the server thread is the one game thread: the queue, the history and the
 * cooldowns are its state; everything else reads snapshots.
 */
public class XLimboPlugin(
    public val platform: Platform,
    public val server: LimboServer,
    private val files: Files,
    languages: LanguageSource,
    /** Who's online, by name, as each command source can see them. */
    private val players: OnlinePlayers,
    /** Asks the main server for its status, for server-check. */
    internal val pinger: StatusPinger,
    /** The family's player cache, for `/xlimbo forget` by name. */
    private val cache: PlayerCache? = null,
    private val registrar: CommandRegistrar? = null,
    private val permissionRegistrar: PermissionRegistrar? = null,
    /** What `/xlimbo debug`'s report says about the server. */
    about: () -> Map<String, String> = { emptyMap() },
    /** Where `/xlimbo debug` uploads its report; `null` to write a file. */
    paste: PasteService? = null,
    override val version: String = VERSION,
) : XLimboApi {

    /** Where the plugin's console lines go; the last 50 warnings and errors are kept for `/xlimbo debug`. */
    public val log: RecentErrors = RecentErrors(platform.log, platform.clock)

    /** The messages, in each reader's language. */
    public val texts: Texts = Texts(languages, server)

    /** The API's events. On Paper the adapter bridges them to Bukkit events. */
    public val bus: SimpleEventBus = SimpleEventBus(log)

    override val events: EventBus get() = bus

    /** The features, one module each that's on. */
    public val modules: ModuleSet = ModuleSet(platform)

    @Volatile
    public var config: Config = XLimboConfig.defaults
        private set

    @Volatile
    internal var problems: List<String> = emptyList()
        private set

    /** How players go back, as the config says. */
    public val route: ReturnRoute get() = config[XLimboConfig.sendBack.via]

    /** When players arrived and were sent back, kept across reloads. Server thread only. */
    public val history: ReturnHistory = ReturnHistory()

    /** The return queue, while automatic returns are on (auto-join or server-check); otherwise `null`. */
    @Volatile
    public var returns: Returns? = null
        internal set

    // /join's last use per player, in elapsed milliseconds. Server thread.
    private val joinCooldowns = HashMap<PlayerRef, Long>()

    private val holds = concurrentMap<String, List<HoldHook>>()
    private val priorities = concurrentMap<String, List<PriorityHook>>()

    /** Whether the plugin is running: started and not stopped. */
    @Volatile
    public var running: Boolean = false
        private set

    /** `/xlimbo reload`, `debug` and `forget`. */
    @Suppress("UNCHECKED_CAST")
    public val admin: AdminCommands = AdminCommands(
        PLUGIN_ID, NAME, platform,
        ModuleReloader(modules, reloadFiles = Reloading(0) as () -> List<String>, wanted = Reloading(1) as () -> Map<String, () -> Module>),
        // Objects, not lambdas, so neither class loads until the command runs (AdminCommands).
        debug = object : () -> Debug {
            override fun invoke() = AdminParts.debug(this@XLimboPlugin, about, paste)
        },
        playerData = object : () -> PlayerDataRegistry {
            override fun invoke() = AdminParts.playerData(this@XLimboPlugin, cache)
        },
        text = texts.admin,
    )

    private val commands = Commands(this)

    /** What runs the commands; built again on reload, from the new config. */
    @Volatile
    public var dispatcher: CommandDispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        private set

    /** The permissions the config declares. */
    @Volatile
    public var permissions: XLimboPermissions = commands.permissions
        private set

    /** The platform's update checker, which the adapter starts; every reload gives it the config's settings again. */
    @Volatile
    public var updates: UpdateChecker? = null

    public val updateSettings: UpdateSettings get() = XLimboConfig.updates.settings(config)

    /**
     * What the adapter does just before the first event could be heard: Paper's bridges the API's
     * events to Bukkit events there, so their classes don't load as the server starts.
     */
    @Volatile
    public var beforeFirstEvent: (() -> Unit)? = null

    private fun eventsReady() {
        val bridge = beforeFirstEvent ?: return
        val run = co.xenastudios.core.common.locked(this) { if (beforeFirstEvent === bridge) true.also { beforeFirstEvent = null } else false }
        if (run) bridge()
    }

    /** Fires the event [make] builds, only if something listens; returns it, or `null` if nothing did. */
    internal inline fun <E : Event> fire(type: EventType<E>, make: () -> E): E? {
        eventsReady()
        if (!bus.hasListeners(type)) return null
        return bus.fire(type, make())
    }

    // /xlimbo reload: the files again, then the modules. One class for both.
    private inner class Reloading(private val kind: Int) : () -> Any {
        override fun invoke(): Any = if (kind == 0) apply(files.load()) else wanted()
    }

    /** Elapsed milliseconds on the platform's clock: what the history and the cooldowns count in. */
    internal fun now(): Long = platform.clock.elapsed().inWholeMilliseconds

    /** Turns the plugin on with what [Files] read: the commands, the modules, the world, and the players already here (a reload). Server thread. */
    public fun start(loaded: Loaded) {
        running = true
        apply(loaded)
        XLimbo.register(this)
    }

    /** Turns the plugin off: the modules, the commands and the API. The adapter lets go of the world. */
    public fun stop() {
        running = false
        XLimbo.register(null)
        updates?.close()
        updates = null
        modules.clear()
        registrar?.clear()
        permissionRegistrar?.sync(emptyList())
    }

    /** Uses what [Files] read: the commands rebuilt, the modules as the config says, the world kept matching it. Server thread. */
    public fun apply(loaded: Loaded): List<String> {
        config = loaded.config
        texts.translations = loaded.translations
        dispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        permissions = commands.permissions
        permissionRegistrar?.sync(permissions.permissions + admin.permissions.permissions)
        val warnings = ArrayList<String>()
        warnings += registrar?.sync(dispatcher).orEmpty()
        warnings.forEach(log::warn)
        problems = loaded.problems + warnings
        updates?.apply(updateSettings)
        modules.sync(wanted())
        server.sync(config)
        // Every setting applies on reload: players already waiting get creative and flight too.
        for (player in server.online()) if (server.isInLimbo(player)) server.equip(player, config)
        return problems
    }

    private fun wanted(): Map<String, () -> Module> {
        val c = config
        val p = XLimboConfig.protections
        val on = HashMap<String, () -> Module>()
        fun want(switch: Int, enabled: Boolean, key: String = Switch.NAMES[switch]) {
            if (enabled) on[key] = Make(switch)
        }
        want(Switch.ARRIVALS, true)
        want(Switch.HIDE_OTHERS, c[XLimboConfig.player.hideOthers])
        val teleport = c[XLimboConfig.sendBack.via] == ReturnRoute.TELEPORT
        val checking = !teleport && c[XLimboConfig.serverCheck.enabled]
        val automatic = checking || c[XLimboConfig.autoJoin.enabled]
        if (automatic) {
            // Keyed by what decides how it runs, so changing it on reload starts it again.
            val key = if (checking) "returns.check.${c[XLimboConfig.serverCheck.intervalSeconds]}" else "returns.auto"
            on["$key.${c[XLimboConfig.sendBack.via].name.lowercase()}"] = Make(RETURNS)
        }
        if (c[XLimboConfig.actionBar.enabled] || automatic) on["action-bar.${c[XLimboConfig.actionBar.intervalTicks]}"] = Make(ACTION_BAR)
        want(Switch.FLOOR, c[p.floor])
        // Two listeners, so a move handler only runs while the rescue is on.
        want(Switch.VOID, c[p.voidProtection.enabled])
        want(Switch.VOID_DAMAGE, c[p.voidProtection.cancelVoidDamage])
        want(Switch.PORTALS, c[p.portals])
        want(Switch.LIQUIDS, c[p.liquids])
        want(Switch.GRAVITY, c[p.gravityBlocks])
        want(Switch.FIRE, c[p.fire])
        want(Switch.EXPLOSIONS, c[p.explosions])
        want(Switch.MOBS, c[p.mobSpawns])
        want(Switch.REDSTONE, c[p.redstone])
        want(Switch.ITEMS, c[p.itemDrops])
        want(Switch.ENTITY_ITEMS, c[p.entities.blockEntityItems])
        want(Switch.BUILD_GUARD, c[p.buildGuard.enabled])
        val cleanup = c[p.entities.cleanupIntervalSeconds]
        // Keyed by the interval, so a changed interval restarts the sweep.
        want(Switch.CLEANUP, cleanup > 0, "protect.cleanup.$cleanup")
        return on
    }

    // A module's maker, one class told apart by what it makes.
    private inner class Make(private val kind: Int) : () -> Module {
        override fun invoke(): Module = when (kind) {
            RETURNS -> Returns(this@XLimboPlugin)
            ACTION_BAR -> ActionBar(this@XLimboPlugin)
            else -> Listen(kind)
        }
    }

    private inner class Listen(private val switch: Int) : Module {
        private var handle: AutoCloseable? = null

        override fun enable(context: ModuleContext) {
            handle = server.listen(switch, this@XLimboPlugin)
        }

        override fun disable() {
            handle?.close()
            handle = null
        }

        // The adapter's listeners read the config as they run: nothing to start again.
        override fun reload(context: ModuleContext): Boolean = true
    }

    // ---- arrivals ----

    /**
     * [player] arrived in limbo: joined this server ([joined]), or was sent into its world. Called
     * by the adapter on the server thread, as they join (before anyone hears of it) or once they're
     * in the world.
     */
    public fun arrived(player: PlayerRef, joined: Boolean) {
        val now = now()
        val reason = when {
            !joined -> EnterReason.SENT
            history.bounced(player, now) -> EnterReason.REFUSED
            route == ReturnRoute.PROXY && config[XLimboConfig.serverCheck.enabled] && returns?.up == false -> EnterReason.MAIN_SERVER_DOWN
            else -> EnterReason.UNKNOWN
        }
        history.arrived(player, now)
        server.equip(player, config)
        // A moment later: a client's language reaches the server after it joins (a proxy's switch
        // too), and the welcome is in it.
        platform.scheduler.run(Where.on(player), WELCOME_DELAY) {
            if (server.isInLimbo(player)) {
                if (config[XLimboConfig.join.clearChatOnJoin]) server.clearChat(player)
                texts.send(player, if (joined) XLimboMessages.welcome else XLimboMessages.sentWelcome)
            }
        }
        fire(EnteredLimboEvent.TYPE) { EnteredLimboEvent(player, reason) }
    }

    /** [player] left limbo: quit, or (sent back by a teleport) left its world. Server thread. */
    public fun left(player: PlayerRef, quit: Boolean) {
        returns?.left(player)
        history.left(player)
        if (quit) joinCooldowns.remove(player)
    }

    // ---- sending back ----

    /** Where players go: the proxy's name for the main server, or the teleport's destination. */
    internal val destination: String
        get() = if (route == ReturnRoute.PROXY) config[XLimboConfig.mainServer] else config[XLimboConfig.sendBack.destination]

    /** Whether something keeps [player] in limbo: a hold, or a listener cancelling [PreReturnEvent]. Server thread. */
    internal fun mayReturn(player: PlayerRef, cause: ReturnCause): Boolean {
        for (list in holds.values) for (hook in list) {
            val held = try {
                hook.holds(player)
            } catch (e: Exception) {
                log.warn("A hold on xLimbo's returns threw ${e::class.simpleName}: ${e.message}")
                false
            }
            if (held) return false
        }
        val event = fire(PreReturnEvent.TYPE) { PreReturnEvent(player, cause, route, destination) }
        return event?.isCancelled != true
    }

    internal fun hasPriorities(): Boolean = priorities.isNotEmpty()

    /** The highest priority any hook gives [player]. */
    internal fun priority(player: PlayerRef): Int {
        var best = 0
        for (list in priorities.values) for (hook in list) {
            val p = try {
                hook.priority(player)
            } catch (e: Exception) {
                0
            }
            if (p > best) best = p
        }
        return best
    }

    /**
     * Sends [player] back now, if nothing keeps them: the queue's way, the API's while the queue is
     * off, and `/xlimbo back`'s. Server thread. Whether they were sent.
     */
    internal fun sendNow(player: PlayerRef, cause: ReturnCause, message: Boolean = true): Boolean {
        if (!mayReturn(player, cause)) return false
        val now = now()
        if (cause == ReturnCause.QUEUE) history.tried(player, now) else history.sent(player, now)
        if (message) texts.send(player, XLimboMessages.autoSending)
        go(player, cause)
        return true
    }

    // The route itself, once it's decided they go.
    private fun go(player: PlayerRef, cause: ReturnCause) {
        val where = destination
        when (route) {
            ReturnRoute.PROXY -> {
                server.connect(player, where)
                fire(ReturnedEvent.TYPE) { ReturnedEvent(player, cause, ReturnRoute.PROXY, where) }
            }
            ReturnRoute.TELEPORT -> server.teleportBack(player, where).onSettled { done, _ ->
                platform.scheduler.now(Where.Global) {
                    if (done == true) {
                        fire(ReturnedEvent.TYPE) { ReturnedEvent(player, cause, ReturnRoute.TELEPORT, where) }
                    } else if (server.name(player) != null) {
                        texts.send(player, XLimboMessages.returnFailed)
                    }
                }
            }
        }
    }

    /** `/join`, run by [player]: the cooldown, the hold and event, then off they go. Server thread. */
    internal fun join(player: PlayerRef, reply: (Component) -> Unit) {
        if (route == ReturnRoute.TELEPORT && !server.isInLimbo(player)) {
            texts.render(player, XLimboMessages.notInLimbo)?.let(reply)
            return
        }
        val cooldown = config[XLimboConfig.joinCommand.cooldownSeconds] * 1000L
        val now = now()
        val last = joinCooldowns[player]
        val left = if (last == null) 0 else last + cooldown - now
        if (left > 0) {
            texts.render(player, XLimboMessages.cooldown, "seconds" to ((left + 999) / 1000).toInt())?.let(reply)
            return
        }
        // A plugin keeping them here doesn't use up the cooldown.
        if (!mayReturn(player, ReturnCause.JOIN_COMMAND)) return
        joinCooldowns[player] = now
        val queue = returns
        if (queue != null) queue.sentByHand(player) else history.sent(player, now)
        texts.render(player, XLimboMessages.sending, "server" to destination)?.let(reply)
        go(player, ReturnCause.JOIN_COMMAND)
    }

    /** `/xlimbo back` and the API: through the queue while it's on, otherwise at once. Server thread. */
    internal fun sendBackHere(player: PlayerRef, cause: ReturnCause): Boolean {
        if (server.name(player) == null || !server.isInLimbo(player)) return false
        val queue = returns
        if (queue != null) {
            if (!mayReturn(player, cause)) return false
            return queue.enqueue(player)
        }
        return sendNow(player, cause)
    }

    /** Sends online [player] into limbo, firing [PreEnterLimboEvent] first. Server thread. */
    internal fun sendToLimboHere(player: PlayerRef): Pending<Boolean> {
        if (server.name(player) == null || server.isInLimbo(player) || !server.isReady()) return pendingOf(false)
        if (!mayEnter(player)) return pendingOf(false)
        return server.enterLimbo(player)
    }

    /** Whether [player] may be sent into limbo: [PreEnterLimboEvent], for every way in on this server (xTeleport's `xlimbo` destination too). Server thread. */
    public fun mayEnter(player: PlayerRef): Boolean {
        val event = fire(PreEnterLimboEvent.TYPE) { PreEnterLimboEvent(player) }
        return event?.isCancelled != true
    }

    // ---- who's here, as each viewer may see it ----

    /** Everyone in limbo [viewer] (the console for `null`) can see: vanished players only for those xVanish lets. */
    internal fun inLimboFor(viewer: PlayerRef?): List<PlayerRef> =
        server.online().filter { server.isInLimbo(it) && (it == viewer || !server.vanishedFrom(viewer, it)) }

    // ---- the API: memory only, or onto the server thread ----

    override fun route(): ReturnRoute = route

    override fun isServerCheckEnabled(): Boolean = route == ReturnRoute.PROXY && config[XLimboConfig.serverCheck.enabled]

    override fun isMainServerUp(): Boolean = isServerCheckEnabled() && returns?.up == true

    override fun isInLimbo(player: PlayerRef): Boolean = server.name(player) != null && server.isInLimbo(player)

    override fun playersInLimbo(): List<PlayerRef> = server.online().filter(server::isInLimbo)

    override fun visibleCount(): Int = server.online().count { server.isInLimbo(it) && !server.vanishedFrom(null, it) }

    override fun queuePosition(player: PlayerRef): Int = returns?.positions?.get(player) ?: 0

    override fun queueSize(): Int = returns?.positions?.size ?: 0

    override fun sendBack(player: PlayerRef): Pending<Boolean> = onServer { sendBackHere(player, ReturnCause.PLUGIN) }

    override fun sendBackAll() {
        platform.scheduler.now(Where.Global) {
            for (player in server.online()) if (server.isInLimbo(player)) sendBackHere(player, ReturnCause.PLUGIN)
        }
    }

    override fun sendToLimbo(player: PlayerRef): Pending<Boolean> {
        val done = newPending<Boolean>()
        platform.scheduler.now(Where.Global) { sendToLimboHere(player).onSettled { value, _ -> done.resolve(value == true) } }
        return done
    }

    override fun registerHold(owner: String, hook: HoldHook) {
        co.xenastudios.core.common.locked(holds) { holds[owner] = holds[owner].orEmpty() + hook }
    }

    override fun registerPriority(owner: String, hook: PriorityHook) {
        co.xenastudios.core.common.locked(priorities) { priorities[owner] = priorities[owner].orEmpty() + hook }
    }

    override fun unregisterAll(owner: String) {
        holds.remove(owner)
        priorities.remove(owner)
    }

    private fun onServer(action: () -> Boolean): Pending<Boolean> {
        val done = newPending<Boolean>()
        platform.scheduler.now(Where.Global) { done.resolve(action()) }
        return done
    }

    // ---- for the commands ----

    /** An online player called [name] whom [viewer] can see. */
    internal fun find(name: String, viewer: co.xenastudios.core.common.command.CommandSource): PlayerRef? = players.find(name, viewer)

    /** The names [source] might type. */
    internal fun names(source: co.xenastudios.core.common.command.CommandSource): List<String> = players.names(source)

    /** `/xlimbo forget`: what's kept about [player] here, the return tries and the cooldown. Server thread. */
    internal fun forget(player: PlayerRef): Long {
        val had = history.forget(player)
        return if (joinCooldowns.remove(player) != null || had) 1L else 0L
    }

    public companion object {
        public const val PLUGIN_ID: String = "xlimbo"
        public const val NAME: String = "xLimbo"

        /** xLimbo's version. The adapter's build checks theirs is the same. */
        public const val VERSION: String = "3.0.0"

        /** How long after an arrival the welcome is sent, so it's in the client's language. */
        internal val WELCOME_DELAY: kotlin.time.Duration = kotlin.time.Duration.parse("1s")

        private const val RETURNS = 100
        private const val ACTION_BAR = 101
    }
}

/**
 * Repeats a hint on every player's action bar, or their place in line while they wait to go
 * back. Keyed by its interval, so a changed interval restarts it.
 */
internal class ActionBar(private val plugin: XLimboPlugin) : Module {
    override fun enable(context: ModuleContext) {
        val ticks = plugin.config[XLimboConfig.actionBar.intervalTicks]
        context.scheduler.every(Where.Global, (ticks * 50L).milliseconds) { show() }
    }

    // Keyed by its interval; the rest is read as it runs.
    override fun reload(context: ModuleContext): Boolean = true

    private fun show() {
        val server = plugin.server
        val hintOn = plugin.config[XLimboConfig.actionBar.enabled]
        val line = plugin.returns?.shown
        if (!hintOn && (line == null || line.places.isEmpty())) return
        for (player in server.online()) {
            if (!server.isInLimbo(player)) continue
            val place = line?.places?.get(player)
            val bar = if (place != null) {
                val vanished = server.vanishedFrom(null, player)
                plugin.texts.render(player, XLimboMessages.queueBar, "position" to place, "total" to line.total(player, vanished))
                    ?.takeIf { it.plain().isNotBlank() }
                    ?: if (hintOn) plugin.texts.render(player, XLimboMessages.hint) else null
            } else if (hintOn) {
                plugin.texts.render(player, XLimboMessages.hint)
            } else {
                null
            }
            bar?.let { server.actionBar(player, it) }
        }
    }
}
