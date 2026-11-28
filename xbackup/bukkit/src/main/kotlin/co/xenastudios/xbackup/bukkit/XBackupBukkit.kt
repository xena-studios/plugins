package co.xenastudios.xbackup.bukkit

import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.xbackup.Engine
import co.xenastudios.xbackup.JvmEngine
import co.xenastudios.xbackup.JvmFiles
import co.xenastudios.xbackup.Loaded
import co.xenastudios.xbackup.RestoreOnStart
import co.xenastudios.xbackup.XBackupPlugin
import co.xenastudios.xbackup.api.XBackupApi
import co.xenastudios.xbackup.config.XBackupConfig
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Level

/**
 * xBackup on Paper, Folia, Spigot and CraftBukkit, 1.8.8 on: the shared plugin on platform-bukkit,
 * loaded by the core's Java shim once the stdlib is there.
 *
 * - As the server loads it (before any world): `running.lock` held, and a staged restore put in
 *   place ([RestoreOnStart]).
 * - As it enables: `config.yml` read (the remotes' passwords moved to `secrets.yml` first),
 *   commands registered; `lang/` and the player cache read on an async task right after; the
 *   repository isn't read until something needs it.
 * - The API's events are Bukkit events too, and the API is in the services manager.
 */
public class XBackupBukkit(private val plugin: JavaPlugin) : PluginEntry, (XBackupPlugin) -> Engine {
    private var platform: BukkitPlatform? = null
    private var xbackup: XBackupPlugin? = null
    private var server: BukkitBackupServer? = null
    private var disables: AutoCloseable? = null

    private val home: Path get() = plugin.dataFolder.toPath().toAbsolutePath().normalize()

    override fun load() {
        RestoreOnStart.run(home, Say(plugin))
    }

    private class Say(private val plugin: JavaPlugin) : RestoreOnStart.Say {
        override fun info(message: String) = plugin.logger.info(message)

        override fun error(message: String, cause: Throwable?) = plugin.logger.log(Level.SEVERE, message, cause)
    }

    override fun enable() {
        val started = System.nanoTime()
        val platform = BukkitPlatform.create(plugin, development = java.lang.Boolean.getBoolean("xena.development"))
        this.platform = platform
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XBackupPlugin.PLUGIN_ID)
        platform.record(cache)
        val server = BukkitBackupServer(platform)
        this.server = server
        val files = JvmFiles(home, family, Shipped(plugin.javaClass.classLoader), platform.log)
        val xbackup = XBackupPlugin(
            platform, server, files, cache, this,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else Mclogs,
            version = plugin.description.version,
        )
        this.xbackup = xbackup
        server.xbackup = xbackup
        platform.commands.feedback = co.xenastudios.core.common.command.CommandFeedback.of(xbackup.texts.admin)
        EventBridge.bridge(plugin.server, xbackup.bus)
        plugin.server.servicesManager.register(XBackupApi::class.java, xbackup, plugin, ServicePriority.Normal)
        disables = server.watchDisables()
        RestoreOnStart.report(home) { platform.log.info(it) }
        // config.yml alone here; the lang files (eight languages) and the player cache are read on
        // an async task right after, as xBans does: English until then.
        val loaded = files.config()
        xbackup.start(loaded)
        platform.scheduler.run(Where.Async, action = Steps(platform, xbackup, cache, files, loaded))
        xbackup.updates = platform.checkUpdates(XBackupPlugin.PLUGIN_ID, xbackup.admin.updates, XBackupConfig.updates.settings(loaded.config)) { xbackup.texts.translations }
        // bStats: 1.x's service id.
        platform.metrics(35420)
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xBackup ${xbackup.version} on ${platform.name}: ${platform.threading} threading, text as ${platform.text}, enabled in $millis ms")
    }

    /** The engine, made with the plugin: the JVM's, with the server's folder and the family's board. */
    override fun invoke(xbackup: XBackupPlugin): Engine {
        val platform = platform!!
        val server = server!!
        val root = Paths.get("").toAbsolutePath().normalize()
        return JvmEngine(xbackup, server, root, home, home.resolve("repository"), platform.family.board, Sqlite(plugin.javaClass.classLoader, platform.driverFetcher()), ownJar())
    }

    /**
     * SQLite's own copy for the databases no Xena plugin has open: through the server's driver
     * (Paper and Spigot ship one), or one fetched where there's none, made the first time a
     * backup needs it.
     */
    private class Sqlite(private val loader: ClassLoader, private val fetcher: co.xenastudios.core.jvm.db.DriverFetcher?) : () -> co.xenastudios.xbackup.backup.Databases? {
        @Volatile
        private var made: co.xenastudios.xbackup.backup.Databases? = null

        override fun invoke(): co.xenastudios.xbackup.backup.Databases? {
            made?.let { return it }
            val server = try {
                Class.forName("org.sqlite.JDBC", true, loader).getDeclaredConstructor().newInstance() as java.sql.Driver
            } catch (e: Throwable) {
                null
            }
            val databases = if (server != null) {
                co.xenastudios.xbackup.backup.Databases.Sqlite(server)
            } else {
                val fetched = co.xenastudios.core.jvm.db.IsolatedDriver.open(fetcher, co.xenastudios.core.jvm.db.DriverLibrary.SQLITE)
                val driver = (fetched as? co.xenastudios.core.common.Outcome.Ok)?.value ?: return null
                co.xenastudios.xbackup.backup.Databases.Sqlite(Connect(driver))
            }
            made = databases
            return databases
        }

        private class Connect(private val driver: co.xenastudios.core.jvm.db.IsolatedDriver) : (String) -> java.sql.Connection? {
            override fun invoke(url: String): java.sql.Connection? = driver.connect(url, java.util.Properties())
        }
    }

    // The jar's translations, read with the lang files on the async task.
    private class Shipped(private val loader: ClassLoader) : () -> Map<LanguageTag, String> {
        override fun invoke(): Map<LanguageTag, String> = LangFolder.shipped(loader, JvmFiles.LANGUAGES)
    }

    /**
     * After enable, as one class rather than a lambda each: on an async task, the player cache
     * and the lang files read; then on the global thread, the lang files used.
     */
    private class Steps(
        private val platform: BukkitPlatform,
        private val xbackup: XBackupPlugin,
        private val cache: PlayerCache,
        private val files: JvmFiles,
        private val config: Loaded,
    ) : (Task) -> Unit {
        @Volatile
        private var loaded: Loaded? = null

        override fun invoke(task: Task) {
            val read = loaded
            if (read == null) {
                cache.load()
                loaded = files.translations(config.config, config.problems)
                platform.scheduler.run(Where.Global, action = this)
            } else {
                xbackup.translated(read)
            }
        }
    }

    private fun ownJar(): Path? = try {
        Paths.get(plugin.javaClass.protectionDomain.codeSource.location.toURI())
    } catch (e: Exception) {
        null
    }

    override fun disable() {
        val platform = platform ?: return
        plugin.server.servicesManager.unregisterAll(plugin)
        disables?.close()
        try {
            xbackup?.stop()
        } catch (e: Exception) {
            platform.log.warn("Stopping xBackup: $e")
        }
        platform.close()
        this.platform = null
        xbackup = null
        server = null
        // running.lock stays held: Paper saves the worlds after plugins are disabled, and the
        // operating system lets go when the process exits.
    }

    private object Mclogs : co.xenastudios.core.common.admin.PasteService {
        override val name: String get() = "mclo.gs"

        override fun upload(text: String) = MclogsPaste().upload(text)
    }
}
