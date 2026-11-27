package co.xenastudios.xvanish

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.Importer
import co.xenastudios.core.common.admin.Imports
import co.xenastudios.core.common.admin.PasteService
import co.xenastudios.core.common.admin.PlayerDataRegistry
import co.xenastudios.core.common.admin.RecentErrors
import co.xenastudios.core.common.command.AdminCommands
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.ModuleReloader
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.Database
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
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.xvanish.api.VanishCause
import co.xenastudios.xvanish.api.XVanish
import co.xenastudios.xvanish.api.XVanishApi
import co.xenastudios.xvanish.api.event.UnvanishedEvent
import co.xenastudios.xvanish.api.event.VanishEvent
import co.xenastudios.xvanish.api.event.VanishedEvent
import co.xenastudios.xvanish.config.XVanishConfig
import kotlin.concurrent.Volatile

/** What the plugin's files said when they were last read: the config, the lang files, and the problems found. */
public class Loaded(
    public val config: Config,
    public val translations: Translations,
    /** Each problem, in English, already logged. */
    public val problems: List<String> = emptyList(),
)

/** xVanish's files: on the JVM, `JvmFiles`. [load] reads `config.yml` and `lang/`. */
public fun interface Files {
    public fun load(): Loaded
}

/**
 * xVanish, the same on every platform: each adapter makes one with its [Platform] and its
 * [VanishServer], and calls [start] as the plugin enables, before anyone can join. Who's vanished
 * is in memory, read once from `vanished.txt` ([Remembered]) and, on a network, from the shared
 * database ([openNetwork]); every check answers from memory.
 *
 * Nothing repeats: state changes on a command, a join or a quit. Hiding and showing run on each
 * viewer's own thread (Folia's entity schedulers), or, where the server can, once on the vanished
 * player's ([VanishServer.hiddenByDefault]).
 */
public class XVanishPlugin(
    public val platform: Platform,
    public val server: VanishServer,
    private val files: Files,
    languages: LanguageSource,
    /** Who's online, by name, as each command source can see them. */
    private val players: OnlinePlayers,
    /** The family's player cache: xVanish marks who's vanished there, for the other Xena plugins. */
    private val cache: PlayerCache? = null,
    private val registrar: CommandRegistrar? = null,
    private val permissionRegistrar: PermissionRegistrar? = null,
    /** What `/xvanish debug`'s report says about the server. */
    about: () -> Map<String, String> = { emptyMap() },
    /** Where `/xvanish debug` uploads its report; `null` to write a file. */
    paste: PasteService? = null,
    /** SuperVanish's and PremiumVanish's state, for `/xvanish import`; `null` where there's none (Fabric). */
    importers: ((XVanishPlugin) -> List<Importer<*>>)? = null,
    override val version: String = VERSION,
) : XVanishApi, VanishView {

    /** Where the plugin's console lines go; the last 50 warnings and errors are kept for `/xvanish debug`. */
    public val log: RecentErrors = RecentErrors(platform.log, platform.clock)

    /** The messages, in each reader's language. */
    public val texts: Texts = Texts(languages, server)

    /** The API's events. On Bukkit the adapter bridges them to Bukkit events. */
    public val bus: SimpleEventBus = SimpleEventBus(log)

    override val events: EventBus get() = bus

    /** The listeners, one module per switch that's on. */
    public val modules: ModuleSet = ModuleSet(platform)

    @Volatile
    public var config: Config = XVanishConfig.defaults
        private set

    @Volatile
    internal var problems: List<String> = emptyList()
        private set

    /** Who stays vanished through a relog and a restart: `vanished.txt`. */
    public val remembered: Remembered = Remembered(platform.storage, platform.scheduler, log)

    // Online and vanished here, by player: their level when they vanished.
    private val here = concurrentMap<PlayerRef, Int>()

    // Each online player's level, and the level they see up to (0 without xvanish.see).
    private val levels = concurrentMap<PlayerRef, Int>()
    private val sees = concurrentMap<PlayerRef, Int>()

    // Vanished players who pick things up anyway (/vanish pickup).
    private val pickingUp = concurrentMap<PlayerRef, Boolean>()

    // A vanished player's public chat held back, and when.
    private val held = concurrentMap<PlayerRef, Pair<String, Long>>()

    /** Network mode, once its database is open; `null` on a single server, which never loads its classes. */
    @Volatile
    public var network: Network? = null
        private set

    /** Whether the plugin is running: started and not stopped. */
    @Volatile
    public var running: Boolean = false
        private set

    /** `/xvanish reload`, `debug`, `forget` and `import`. */
    @Suppress("UNCHECKED_CAST")
    public val admin: AdminCommands = AdminCommands(
        PLUGIN_ID, NAME, platform,
        ModuleReloader(modules, reloadFiles = Reloading(0) as () -> List<String>, wanted = Reloading(1) as () -> Map<String, () -> Module>),
        // Objects, not lambdas, so neither class loads until the command runs (AdminCommands).
        debug = object : () -> Debug {
            override fun invoke() = AdminParts.debug(this@XVanishPlugin, about, paste)
        },
        imports = importers?.let { Imports(platform, it(this)) },
        playerData = object : () -> PlayerDataRegistry {
            override fun invoke() = AdminParts.playerData(this@XVanishPlugin, cache)
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
    public var permissions: XVanishPermissions = commands.permissions
        private set

    /** The platform's update checker, which the adapter starts; every reload gives it the config's settings again. */
    @Volatile
    public var updates: UpdateChecker? = null

    public val updateSettings: UpdateSettings get() = XVanishConfig.updates.settings(config)

    /** The placeholders, made when PlaceholderAPI first asks. */
    public val placeholders: Placeholders get() = madePlaceholders ?: Placeholders(this).also { madePlaceholders = it }

    @Volatile
    private var madePlaceholders: Placeholders? = null

    /**
     * What the adapter does just before the first event could be heard: Bukkit's bridges the API's
     * events to Bukkit events there, so their classes don't load as the server starts.
     */
    @Volatile
    public var beforeFirstEvent: (() -> Unit)? = null

    private fun eventsReady() {
        val bridge = beforeFirstEvent ?: return
        val run = co.xenastudios.core.common.locked(this) { if (beforeFirstEvent === bridge) true.also { beforeFirstEvent = null } else false }
        if (run) bridge()
    }

    // /xvanish reload: the files again, then the modules. One class for both.
    private inner class Reloading(private val kind: Int) : () -> Any {
        override fun invoke(): Any = if (kind == 0) apply(files.load()) else wanted()
    }

    /**
     * Turns the plugin on with what [Files] read, before anyone can join: `vanished.txt`, the
     * commands, the listeners, and the players already online (a reload), hidden again as they were.
     */
    public fun start(loaded: Loaded) {
        running = true
        remembered.load()
        apply(loaded)
        XVanish.register(this)
        for (player in server.online()) platform.scheduler.now(Where.on(player)) { server.name(player)?.let { joining(player, it) } }
    }

    /** Turns the plugin off, as the server stops: everyone shown again (but remembered), the listeners, the commands and the network. */
    public fun stop(): Pending<Unit> {
        running = false
        XVanish.register(null)
        updates?.close()
        updates = null
        modules.clear()
        val shown = here.keys.toList()
        here.clear()
        for (player in shown) {
            unhide(player)
            platform.scheduler.now(Where.on(player)) { server.effects(player, null) }
        }
        registrar?.clear()
        permissionRegistrar?.sync(emptyList())
        val network = network ?: return remembered.flush()
        this.network = null
        return network.close()
    }

    /** Uses what [Files] read: the commands rebuilt, the levels worked out again, the effects redrawn. On the global thread. */
    public fun apply(loaded: Loaded): List<String> {
        config = loaded.config
        texts.translations = loaded.translations
        dispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        permissions = commands.permissions
        permissionRegistrar?.sync(permissions.permissions + admin.permissions.permissions)
        val warnings = ArrayList<String>()
        warnings += registrar?.sync(dispatcher).orEmpty() + commands.warnings
        warnings.forEach(log::warn)
        problems = loaded.problems + warnings
        updates?.apply(updateSettings)
        modules.sync(wanted())
        // Permissions and the boss bar's words may have changed: everyone again.
        for (player in server.online()) {
            platform.scheduler.now(Where.on(player)) {
                workOut(player)
                if (player in here) server.effects(player, effects(player))
            }
        }
        for (target in here.keys.toList()) refresh(target)
        return problems
    }

    private fun wanted(): Map<String, () -> Module> {
        val c = config
        val on = HashMap<String, () -> Module>()
        fun want(switch: Int, enabled: Boolean) {
            if (enabled && switch in server.features.switches) on[Switch.NAMES[switch]] = Make(switch)
        }
        want(Switch.PLAYERS, true)
        want(Switch.SERVER_LIST, c[XVanishConfig.hide.serverList])
        want(Switch.DEATH_MESSAGES, c[XVanishConfig.hide.deathMessages])
        want(Switch.ADVANCEMENT_MESSAGES, c[XVanishConfig.hide.advancementMessages])
        want(Switch.MOB_TARGETING, c[XVanishConfig.noTrace.mobTargeting])
        want(Switch.PICKUP, c[XVanishConfig.noTrace.pickup])
        want(Switch.PHYSICAL, c[XVanishConfig.noTrace.physical])
        want(Switch.SCULK, c[XVanishConfig.noTrace.sculk])
        want(Switch.PROJECTILES, c[XVanishConfig.noTrace.projectiles])
        want(Switch.RAIDS, c[XVanishConfig.noTrace.raids])
        want(Switch.SILENT_CONTAINERS, c[XVanishConfig.silentContainers.enabled])
        want(Switch.INVULNERABLE, c[XVanishConfig.whileVanished.invulnerable])
        want(Switch.CHAT, c[XVanishConfig.chat.confirmPublic])
        return on
    }

    // A module's maker, one class told apart by its switch.
    private inner class Make(private val switch: Int) : () -> Module {
        override fun invoke(): Module = Listen(switch)
    }

    private inner class Listen(private val switch: Int) : Module {
        private var handle: AutoCloseable? = null

        override fun enable(context: ModuleContext) {
            handle = server.listen(switch, this@XVanishPlugin)
        }

        override fun disable() {
            handle?.close()
            handle = null
        }
    }

    // ---- permissions and levels ----

    private val otherPlugins: Boolean get() = config[XVanishConfig.vanish.otherPluginsPermissions]

    /** Whether online [player] has `xvanish.<suffix>`, or with other-plugins-permissions, `sv.<other>` or `pv.<other>`. */
    internal fun has(player: PlayerRef, suffix: String, other: String? = null): Boolean {
        if (server.hasPermission(player, "$PLUGIN_ID.$suffix")) return true
        val also = other ?: return false
        return otherPlugins && (server.hasPermission(player, "sv.$also") || server.hasPermission(player, "pv.$also"))
    }

    // Works out [player]'s level and what they see, from their permissions now.
    private fun workOut(player: PlayerRef): Int {
        val max = config[XVanishConfig.vanish.maxLevel]
        val others = otherPlugins
        // SuperVanish's and PremiumVanish's layered permissions: sv.use.level<n> and
        // sv.see.level<n> (pv. the same), a see level apart from the use level.
        fun theirs(kind: String, n: Int) = others && (server.hasPermission(player, "sv.$kind.level$n") || server.hasPermission(player, "pv.$kind.level$n"))
        val level = (max downTo 2).firstOrNull { server.hasPermission(player, XVanish.LEVEL_PERMISSION + it) || theirs("use", it) } ?: 1
        val seeLevel = if (others) (max downTo 1).firstOrNull { theirs("see", it) } ?: 0 else 0
        levels[player] = level
        sees[player] = when {
            has(player, "see", "see") -> maxOf(level, seeLevel)
            seeLevel > 0 -> seeLevel
            // xBans' staff layer: whom it counts as staff see the first level.
            config[XVanishConfig.vanish.xbansStaffSee] && server.hasPermission(player, XBANS_STAFF) -> 1
            else -> 0
        }
        return level
    }

    override fun level(player: PlayerRef): Int = levels[player] ?: 1

    // ---- what the API and the listeners read: memory only ----

    override fun isVanished(player: PlayerRef): Boolean = player in here || (network?.isVanished(player) == true)

    override val nobody: Boolean get() = here.isEmpty()

    override fun canSee(viewer: PlayerRef, target: PlayerRef): Boolean {
        if (viewer == target) return true
        // Not vanished here: as the network says, at the level they vanished at on their server.
        val level = here[target] ?: network?.takeIf { it.isVanished(target) }?.level(target) ?: return true
        return (sees[viewer] ?: 0) >= level
    }

    override fun vanished(): List<PlayerRef> = here.keys.toList()

    override fun visibleOnlineCount(): Int {
        val online = server.online()
        return online.size - online.count { it in here }
    }

    override fun picksUp(player: PlayerRef): Boolean = pickingUp[player] == true

    /** Turns `/vanish pickup` on or off for [player]; returns whether they pick up now. */
    internal fun togglePickup(player: PlayerRef): Boolean {
        val now = pickingUp.remove(player) == null
        if (now) pickingUp[player] = true
        return now
    }

    // ---- vanishing and showing ----

    override fun setVanished(player: PlayerRef, vanished: Boolean, silent: Boolean): Pending<Boolean> =
        change(player, vanished, silent, VanishCause.API, null)

    /**
     * Vanishes or shows [player] on their own thread: the event first, then memory and
     * `vanished.txt` (and the network), then who sees them, their own effects, the fake message
     * and the staff's notice. [by] is told the outcome, unless it's [player] themselves.
     */
    internal fun change(player: PlayerRef, vanished: Boolean, silent: Boolean, cause: VanishCause, by: CommandSource?): Pending<Boolean> {
        if (server.name(player) == null) return pendingOf(false)
        val done = newPending<Boolean>()
        platform.scheduler.now(Where.on(player), retired = { done.resolve(false) }) {
            done.resolve(changeHere(player, vanished, silent, cause, by))
        }
        return done
    }

    private fun changeHere(player: PlayerRef, vanished: Boolean, silent: Boolean, cause: VanishCause, by: CommandSource?): Boolean {
        val name = server.name(player) ?: return false
        if ((player in here) == vanished) {
            by?.let { texts.reply(it, if (vanished) XVanishMessages.alreadyVanished else XVanishMessages.alreadyShown, "player" to name) }
            return false
        }
        if (cause != VanishCause.NETWORK && refused(player, vanished, silent, cause)) {
            by?.let { texts.reply(it, XVanishMessages.refused) }
            return false
        }
        val level = workOut(player)
        if (vanished) here[player] = level else here.remove(player)
        // Heard from the network, which has it already.
        remember(player, name, vanished, publish = cause != VanishCause.NETWORK)
        if (vanished && !silent) fake(player, name, joined = false)
        refresh(player)
        server.effects(player, if (vanished) effects(player) else null)
        if (!vanished && !silent) fake(player, name, joined = true)
        if (!vanished) {
            pickingUp.remove(player)
            held.remove(player)
        }
        texts.send(player, if (vanished) XVanishMessages.vanished else XVanishMessages.shown)
        if (by != null && by.player != player) texts.reply(by, if (vanished) XVanishMessages.vanishedOther else XVanishMessages.shownOther, "player" to name)
        notifyStaff(player, name, vanished)
        changed(player, vanished, silent, cause)
        return true
    }

    private fun refused(player: PlayerRef, vanished: Boolean, silent: Boolean, cause: VanishCause): Boolean {
        eventsReady()
        return bus.hasListeners(VanishEvent.TYPE) && bus.fire(VanishEvent.TYPE, VanishEvent(player, vanished, silent, cause)).isCancelled
    }

    private fun changed(player: PlayerRef, vanished: Boolean, silent: Boolean, cause: VanishCause) {
        eventsReady()
        if (vanished) {
            if (bus.hasListeners(VanishedEvent.TYPE)) bus.fire(VanishedEvent.TYPE, VanishedEvent(player, silent, cause))
        } else if (bus.hasListeners(UnvanishedEvent.TYPE)) {
            bus.fire(UnvanishedEvent.TYPE, UnvanishedEvent(player, silent, cause))
        }
    }

    // Memory, the file, the family's cache and the network, as [player] vanishes or shows.
    private fun remember(player: PlayerRef, name: String, vanished: Boolean, publish: Boolean = true) {
        if (vanished && config[XVanishConfig.vanish.remember]) remembered.add(player, name) else if (!vanished) remembered.remove(player)
        cache?.setVanished(player, vanished)
        if (publish) network?.publish(player, name, here[player] ?: level(player), vanished)
    }

    /** An offline [player] vanished by an import: `vanished.txt`, and the network's table. */
    internal fun rememberOffline(player: PlayerRef, name: String) {
        remembered.add(player, name)
        network?.publish(player, name, 1, true)
    }

    /**
     * Who sees [target] now: everyone who can, nobody else. On Folia (and where the server can)
     * the target is hidden by default on their own thread first, so no viewer sees them for a
     * moment; then each viewer's own thread shows or hides them, as it stands when it runs.
     */
    internal fun refresh(target: PlayerRef) {
        val vanished = target in here
        val byDefault = server.hiddenByDefault(target, vanished)
        if (!vanished && byDefault) return
        for (viewer in server.online()) {
            if (viewer == target) continue
            platform.scheduler.now(Where.on(viewer)) {
                if (target in here) {
                    workOut(viewer)
                    if (canSee(viewer, target)) server.show(viewer, target) else if (!byDefault) server.hide(viewer, target)
                } else {
                    server.show(viewer, target)
                }
            }
        }
    }

    // [player] shown to everyone again, each on their own thread.
    private fun unhide(player: PlayerRef) {
        if (server.hiddenByDefault(player, false)) return
        for (viewer in server.online()) if (viewer != player) platform.scheduler.now(Where.on(viewer)) { server.show(viewer, player) }
    }

    /** What vanishing gives [player], from the config, the boss bar in their language. */
    internal fun effects(player: PlayerRef): Effects {
        val c = config
        val w = XVanishConfig.whileVanished
        val h = XVanishConfig.hide
        return Effects(
            fly = c[w.fly], nightVision = c[w.nightVision], noCollisions = c[XVanishConfig.noTrace.collisions], ignoreSleep = c[h.sleeping],
            noSpawning = c[h.mobSpawning], offLocatorBar = c[h.locatorBar],
            bossBar = if (c[w.bossBar]) texts.render(player, XVanishMessages.bossBar)?.takeIf { it.plain().isNotBlank() } else null,
        )
    }

    /** The fake join or quit message, to everyone who can't see [player], and the console. */
    internal fun fake(player: PlayerRef, name: String, joined: Boolean) {
        if (!config[XVanishConfig.vanish.fakeMessages]) return
        val message = if (joined) XVanishMessages.fakeJoin else XVanishMessages.fakeQuit
        for (viewer in server.online()) {
            if (viewer == player || (sees[viewer] ?: 0) >= level(player)) continue
            texts.send(viewer, message, "player" to name)
        }
        texts.render(null, message, "player" to name)?.let(server::console)
    }

    /** `/vanish login` and `logout`: the fake message by hand, to everyone who can't see them. */
    internal fun fakeByHand(player: PlayerRef, joined: Boolean) {
        val name = server.name(player) ?: return
        val message = if (joined) XVanishMessages.fakeJoin else XVanishMessages.fakeQuit
        for (viewer in server.online()) {
            // Whoever can see them knows they're here.
            if (viewer == player || (player in here && canSee(viewer, player))) continue
            texts.send(viewer, message, "player" to name)
        }
        texts.render(null, message, "player" to name)?.let(server::console)
    }

    private fun notifyStaff(player: PlayerRef, name: String, vanished: Boolean) {
        if (!config[XVanishConfig.vanish.notifyStaff]) return
        val level = level(player)
        for (viewer in server.online()) {
            if (viewer != player && (sees[viewer] ?: 0) >= level) texts.send(viewer, if (vanished) XVanishMessages.staffVanished else XVanishMessages.staffShown, "player" to name)
        }
    }

    // ---- joins and quits ----

    override fun joining(player: PlayerRef, name: String): Boolean {
        val level = workOut(player)
        var vanished = player in remembered || network?.isVanished(player) == true || has(player, "join", "joinvanished")
        if (vanished && refused(player, true, true, VanishCause.JOIN)) vanished = false
        if (vanished) {
            here[player] = level
            remember(player, name, true)
            refresh(player)
            server.effects(player, effects(player))
            // A moment later: a client's language reaches the server after it joins (a proxy's
            // switch too), and the message is in it.
            platform.scheduler.run(Where.on(player), JOINED_MESSAGE_DELAY) { if (player in here) texts.send(player, XVanishMessages.joinedVanished) }
            // The family's cache records the join itself, maybe after this: marked again once it has.
            cache?.let { c -> platform.scheduler.run(Where.Async) { if (player in here) c.setVanished(player, true) } }
            changed(player, true, true, VanishCause.JOIN)
        }
        // The joiner as a viewer: the vanished they may see, and those they mayn't.
        for (target in here.keys) {
            if (target == player) continue
            if (canSee(player, target)) server.show(player, target) else server.hide(player, target)
        }
        return vanished
    }

    override fun quitting(player: PlayerRef): Boolean {
        val was = here.remove(player) != null
        if (was) {
            server.effects(player, null)
            // A server keeps whom each viewer hid by uuid while the viewer stays: shown again, so
            // they aren't still hidden if they come back unvanished.
            unhide(player)
            if (!config[XVanishConfig.vanish.remember]) {
                remembered.remove(player)
                cache?.setVanished(player, false)
                // Not at once: they may be switching servers, still vanished there.
                network?.left(player, server.name(player) ?: "")
            }
        }
        levels.remove(player)
        sees.remove(player)
        pickingUp.remove(player)
        held.remove(player)
        return was
    }

    override fun mayChat(player: PlayerRef, message: String): Boolean {
        if (player !in here || !config[XVanishConfig.chat.confirmPublic]) return true
        val now = platform.clock.elapsed().inWholeMilliseconds
        val last = held[player]
        if (last != null && last.first == message && now - last.second <= CHAT_CONFIRM_MILLIS) {
            held.remove(player)
            return true
        }
        held[player] = message to now
        texts.send(player, XVanishMessages.chatHeld)
        return false
    }

    // ---- the network ----

    /**
     * The shared database the adapter opened for network mode (off the server's threads), or why
     * it couldn't be: its table made, every vanished player read, and the change feed started.
     */
    public fun openNetwork(result: Outcome<Database, String>) {
        when (result) {
            is Outcome.Err -> log.error("${result.error} Network mode is off until it's fixed: each server keeps its own vanished players.")
            is Outcome.Ok -> Network.open(this, result.value, networkOnAnyDatabase) { network = it }
        }
    }

    /** For tests: network mode's change feed on SQLite too, where two plugins sharing a file stand in for two servers. */
    internal var networkOnAnyDatabase: Boolean = false

    /**
     * The network says [player] ([name]) vanished or showed: `vanished.txt` follows it, so a
     * server that can't reach the database later still knows, and they're hidden or shown here
     * too if they're online.
     */
    internal fun heard(player: PlayerRef, name: String, vanished: Boolean) {
        if (!vanished) remembered.remove(player) else if (config[XVanishConfig.vanish.remember]) remembered.add(player, name)
        cache?.setVanished(player, vanished)
        if (server.name(player) != null && (player in here) != vanished) change(player, vanished, true, VanishCause.NETWORK, null)
    }

    // ---- for the commands ----

    /** An online player called [name] whom [viewer] can see. */
    internal fun find(name: String, viewer: CommandSource): PlayerRef? = players.find(name, viewer)

    /** The names [source] might type. */
    internal fun names(source: CommandSource): List<String> = players.names(source)

    public companion object {
        public const val PLUGIN_ID: String = "xvanish"
        public const val NAME: String = "xVanish"

        /** xVanish's version. The adapters' builds check theirs is the same. */
        public const val VERSION: String = "1.0.0"

        /** xBans' staff permission (its API's `XBans.STAFF_PERMISSION`), which operators have. */
        internal const val XBANS_STAFF: String = "xbans.staff"

        /** How long after a vanished join the player is told, so it's in their client's language. */
        internal val JOINED_MESSAGE_DELAY: kotlin.time.Duration = kotlin.time.Duration.parse("1s")

        /** How long a held-back chat message waits to be said again. */
        internal const val CHAT_CONFIRM_MILLIS: Long = 15_000
    }
}
