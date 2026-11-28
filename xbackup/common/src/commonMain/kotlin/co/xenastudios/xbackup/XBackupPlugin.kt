package co.xenastudios.xbackup

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.map
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.HealthChecks
import co.xenastudios.core.common.admin.PasteService
import co.xenastudios.core.common.admin.RecentErrors
import co.xenastudios.core.common.command.AdminCommands
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.ModuleReloader
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleContext
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.xbackup.api.BackupSnapshot
import co.xenastudios.xbackup.api.BackupStatus
import co.xenastudios.xbackup.api.QuietMoment
import co.xenastudios.xbackup.api.StorageBackend
import co.xenastudios.xbackup.api.XBackup
import co.xenastudios.xbackup.api.XBackupApi
import co.xenastudios.xbackup.api.event.BackupFailedEvent
import co.xenastudios.xbackup.api.event.BackupFinishedEvent
import co.xenastudios.xbackup.api.event.BackupStartEvent
import co.xenastudios.xbackup.config.XBackupConfig
import co.xenastudios.xbackup.store.Snapshot
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Instant

/** What the plugin's config files said when last read: the config, the lang files, and the problems found. */
public class Loaded(
    public val config: Config,
    public val translations: Translations,
    public val problems: List<String> = emptyList(),
)

/** xBackup's files: on the JVM, `JvmFiles`. */
public fun interface Files {
    /** `config.yml` (its remotes' passwords moved to `secrets.yml` first) and `lang/`. */
    public fun load(): Loaded
}

/** What xBackup needs from the server beside the platform: telling everyone, and who's online. */
public interface BackupServer {
    /** Sends each player online (and the console, with `null`) what [draw] makes for them. */
    public fun broadcast(draw: (PlayerRef?) -> Component?)

    public val anyoneOnline: Boolean

    /** Calls [joined] whenever a player joins, until closed: for `schedule.skip-when-idle`. */
    public fun watchJoins(joined: () -> Unit): AutoCloseable
}

/** A folder or file another plugin added to every backup, through the API. */
public class ApiSource(public val path: String, public val excludes: List<String>)

/**
 * xBackup, the same on every platform: each adapter makes one with its [Platform], its
 * [BackupServer], its [Files] and the engine it runs on ([engine], on the JVM `JvmEngine`), reads
 * the config and calls [start] as the plugin enables. The engine reads the repository off the
 * server's threads; until then commands that need the list say they're reading it.
 */
public class XBackupPlugin(
    public val platform: Platform,
    public val server: BackupServer,
    private val files: Files,
    languages: LanguageSource,
    engine: (XBackupPlugin) -> Engine,
    private val registrar: CommandRegistrar? = null,
    private val permissionRegistrar: PermissionRegistrar? = null,
    about: () -> Map<String, String> = { emptyMap() },
    paste: PasteService? = null,
    override val version: String = VERSION,
) : XBackupApi, EngineHooks {
    public val log: RecentErrors = RecentErrors(platform.log, platform.clock)
    public val texts: Texts = Texts(languages)
    public val bus: SimpleEventBus = SimpleEventBus(log)
    override val events: EventBus get() = bus
    public val modules: ModuleSet = ModuleSet(platform)

    @Volatile
    public var config: Config = XBackupConfig.defaults
        private set

    /** [config] read into the forms the engine uses. */
    @Volatile
    public var settings: Settings = Settings.of(config) {}
        private set

    @Volatile
    internal var problems: List<String> = emptyList()
        private set

    public val engine: Engine = engine(this)

    // From other plugins, through the API.
    public val apiSources: MutableMap<String, List<ApiSource>> = concurrentMap()
    public val quietMoments: MutableMap<String, Pair<String, QuietMoment>> = concurrentMap()
    public val backends: MutableMap<String, Pair<String, StorageBackend>> = concurrentMap()

    public val admin: AdminCommands = AdminCommands(
        PLUGIN_ID, NAME, platform, reloader(),
        debug = object : () -> Debug {
            override fun invoke() = Debug(
                platform, "$NAME $version",
                listOf(HealthChecks.threading(platform), HealthChecks.modules(modules), HealthChecks.config { problems + listOfNotNull(this@XBackupPlugin.engine.problem) }),
                about = {
                    about() + mapOf(
                        "Repository" to this@XBackupPlugin.engine.repositoryPath,
                        "Backups" to this@XBackupPlugin.engine.snapshots.size.toString(),
                        "Remotes" to settings.remotes.entries.joinToString(", ") { "${it.key} (${it.value.type})" }.ifEmpty { "none" },
                        "Family databases" to this@XBackupPlugin.engine.family.joinToString(", ").ifEmpty { "none" },
                    )
                },
                errors = log, paste = paste,
            )
        },
        text = texts.admin,
    )

    private val commands = Commands(this)

    @Volatile
    public var dispatcher: CommandDispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        private set

    public val permissions: XBackupPermissions get() = commands.permissions

    @Volatile
    public var updates: UpdateChecker? = null

    internal fun now(): Instant = platform.clock.now()

    /** Uses what [Files] read and starts the engine: as the plugin enables. */
    public fun start(loaded: Loaded) {
        apply(loaded)
        XBackup.register(this)
    }

    /** Turns the plugin off: the engine stopped (autosave back on at once, if a backup had it off). */
    public fun stop() {
        XBackup.register(null)
        updates?.close()
        updates = null
        modules.clear()
        registrar?.clear()
        permissionRegistrar?.sync(emptyList())
        engine.stop()
    }

    @Suppress("UNCHECKED_CAST")
    private fun reloader(): ModuleReloader =
        ModuleReloader(modules, reloadFiles = Reloading(0) as () -> List<String>, wanted = Reloading(1) as () -> Map<String, () -> Module>)

    private inner class Reloading(private val kind: Int) : () -> Any {
        override fun invoke(): Any = if (kind == 0) apply(files.load()) else wanted()
    }

    /**
     * The lang files for the config [start] used, read after it off the server thread: the texts
     * and the problems change, nothing else. A reload since then wins. On the global thread.
     */
    public fun translated(loaded: Loaded) {
        if (loaded.config !== config) return
        texts.translations = loaded.translations
        problems = loaded.problems + applied
    }

    // What the last apply warned of, besides what the files said.
    private var applied: List<String> = emptyList()

    /** Uses what [Files] read: the settings to the engine, the commands rebuilt. On the global thread. */
    public fun apply(loaded: Loaded): List<String> {
        config = loaded.config
        texts.translations = loaded.translations
        val warnings = ArrayList<String>()
        settings = Settings.of(config) { warnings += "config.yml: $it" }
        dispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        permissionRegistrar?.sync(permissions.permissions + admin.permissions.permissions)
        warnings += registrar?.sync(dispatcher).orEmpty() + commands.warnings
        warnings.forEach(log::warn)
        applied = warnings
        problems = loaded.problems + warnings
        updates?.apply(XBackupConfig.updates.settings(config))
        modules.sync(wanted())
        engine.configure(settings)
        return problems
    }

    private fun wanted(): Map<String, () -> Module> = if (settings.skipWhenIdle) mapOf("idle-watch" to IDLE) else emptyMap()

    private val IDLE = object : () -> Module {
        override fun invoke(): Module = IdleWatch()
    }

    /** Only to know whether anyone was on since the last backup: `schedule.skip-when-idle`. */
    private inner class IdleWatch : Module, () -> Unit {
        private var handle: AutoCloseable? = null

        override fun enable(context: ModuleContext) {
            handle = server.watchJoins(this)
        }

        override fun disable() {
            handle?.close()
            handle = null
        }

        override fun invoke() = engine.playerSeen()
    }

    // ---- what the engine asks ----

    override fun starting(reason: String): Pending<Boolean> {
        if (!bus.hasListeners(BackupStartEvent.TYPE)) return pendingOf(true)
        val done = newPending<Boolean>()
        platform.scheduler.run(Where.Global, retired = { done.resolve(true) }) {
            done.resolve(try {
                !bus.fire(BackupStartEvent.TYPE, BackupStartEvent(reason)).isCancelled
            } catch (e: Exception) {
                log.warn("A BackupStartEvent listener failed: $e")
                true
            })
        }
        return done
    }

    override fun finished(snapshot: Snapshot) {
        if (bus.hasListeners(BackupFinishedEvent.TYPE)) bus.fire(BackupFinishedEvent.TYPE, BackupFinishedEvent(toApi(snapshot)))
    }

    override fun failed(reason: String, message: String) {
        if (bus.hasListeners(BackupFailedEvent.TYPE)) bus.fire(BackupFailedEvent.TYPE, BackupFailedEvent(reason, message))
    }

    override fun broadcast(elapsed: Duration?) {
        val on = if (elapsed == null) config[XBackupConfig.broadcasts.start] else config[XBackupConfig.broadcasts.done]
        if (!on) return
        platform.scheduler.run(Where.Global) {
            server.broadcast { player ->
                if (elapsed == null) texts.render(player, XBackupMessages.broadcastStart) else texts.render(player, XBackupMessages.broadcastDone, "time" to elapsed)
            }
        }
    }

    override val anyoneOnline: Boolean get() = server.anyoneOnline

    // ---- the API ----

    /** A snapshot as the API shows it. */
    public fun toApi(snapshot: Snapshot): BackupSnapshot = BackupSnapshot(
        snapshot.key.hex(), snapshot.time, snapshot.durationMillis, snapshot.reason, snapshot.files, snapshot.bytes, snapshot.added,
        snapshot.pauseMillis, engine.pinned(snapshot.key), snapshot.warnings,
    )

    override fun backupNow(reason: String): Pending<BackupSnapshot> = engine.backup(reason.trim().ifEmpty { "api" }, false).map { toApi(it) }

    override fun listSnapshots(): List<BackupSnapshot> = engine.snapshots.asReversed().map { toApi(it) }

    override fun getStatus(): BackupStatus {
        val list = engine.snapshots
        return BackupStatus(engine.activity, list.lastOrNull()?.let { toApi(it) }, engine.nextBackup, list.size, engine.repositoryBytes, engine.problem)
    }

    override fun registerSource(owner: String, path: String, excludes: List<String>) {
        locked(apiSources) { apiSources[owner] = apiSources[owner].orEmpty() + ApiSource(path, excludes.toList()) }
    }

    override fun unregisterSources(owner: String) {
        apiSources.remove(owner)
    }

    override fun registerQuietMoment(owner: String, folder: String, quietMoment: QuietMoment) {
        quietMoments[owner] = folder to quietMoment
    }

    override fun unregisterQuietMoment(owner: String) {
        quietMoments.remove(owner)
    }

    override fun registerBackend(owner: String, type: String, backend: StorageBackend) {
        val key = type.trim().lowercase()
        require(key !in OWN_TYPES) { "\"$type\" is one of xBackup's own remote types." }
        backends[key] = owner to backend
    }

    override fun unregisterBackend(type: String) {
        backends.remove(type.trim().lowercase())
    }

    /** Everything [owner] registered, as it goes away (on Bukkit, as that plugin is disabled). */
    public fun forgetOwner(owner: String) {
        apiSources.remove(owner)
        quietMoments.remove(owner)
        for ((type, entry) in backends.entries.toList()) if (entry.first == owner) backends.remove(type)
    }

    public companion object {
        public const val PLUGIN_ID: String = "xbackup"
        public const val NAME: String = "xBackup"

        /** xBackup's version. The adapters' builds check theirs is the same. */
        public const val VERSION: String = "2.0.0"

        /** The remote types xBackup has itself, which no plugin may take. */
        public val OWN_TYPES: List<String> = listOf("local", "folder", "disk", "s3", "webdav", "dav", "sftp", "ftp", "ftps", "drive", "onedrive", "dropbox")
    }
}
