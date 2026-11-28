package co.xenastudios.xbackup.fabric

import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.DriverFetcher
import co.xenastudios.core.jvm.db.DriverLibrary
import co.xenastudios.core.jvm.db.IsolatedDriver
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.common.Outcome
import co.xenastudios.xbackup.Engine
import co.xenastudios.xbackup.JvmEngine
import co.xenastudios.xbackup.JvmFiles
import co.xenastudios.xbackup.RestoreOnStart
import co.xenastudios.xbackup.XBackupPlugin
import co.xenastudios.xbackup.backup.Databases
import co.xenastudios.xbackup.config.XBackupConfig
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * xBackup as a server-side Fabric mod: the shared plugin on platform-fabric. Its folder is
 * `config/xbackup/`, its repository `config/xbackup/repository/` unless `repository.path` says
 * otherwise.
 *
 * - As the mod initializes (before any world loads): `running.lock` held, and a staged restore
 *   put in place ([RestoreOnStart]).
 * - Then `config.yml` and `lang/` are read and the commands registered; the repository isn't read
 *   until something needs it.
 */
public object XBackupMod : ModInitializer {
    override fun onInitialize() {
        val started = System.nanoTime()
        val loader = FabricLoader.getInstance()
        val root = loader.gameDir.toAbsolutePath().normalize()
        val home = loader.configDir.resolve(XBackupPlugin.PLUGIN_ID).toAbsolutePath().normalize()
        RestoreOnStart.run(home, Say, root)

        val platform = FabricPlatform(XBackupPlugin.PLUGIN_ID, XBackupPlugin.NAME)
        val family = platform.family
        val server = FabricBackupServer(platform)
        val files = JvmFiles(home, family.family, shipped(), platform.log)
        val version = loader.getModContainer(XBackupPlugin.PLUGIN_ID).map { it.metadata.version.friendlyString }
            .orElse(XBackupPlugin.VERSION).substringBefore('+')
        val engine = Engines(platform, server, root, home)
        val xbackup = XBackupPlugin(
            platform, server, files, family.players, engine,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else Mclogs,
            version = version,
        )
        platform.commands.feedback = co.xenastudios.core.common.command.CommandFeedback.of(xbackup.texts.admin)
        RestoreOnStart.report(home) { platform.log.info(it) }
        val loaded = files.load()
        xbackup.start(loaded)
        // The update checker; bStats has no Fabric platform, so there's no metrics here.
        xbackup.updates = platform.checkUpdates(XBackupPlugin.PLUGIN_ID, xbackup.admin.updates, XBackupConfig.updates.settings(loaded.config)) {
            xbackup.texts.translations
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            try {
                xbackup.stop()
            } catch (e: Exception) {
                platform.log.warn("Stopping xBackup: $e")
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xBackup ${xbackup.version} on ${platform.name}, enabled in $millis ms")
    }

    private object Say : RestoreOnStart.Say {
        private val log = org.slf4j.LoggerFactory.getLogger("xBackup")

        override fun info(message: String) = log.info(message)

        override fun error(message: String, cause: Throwable?) = log.error(message, cause)
    }

    /** The engine, made with the plugin: the JVM's, with the game folder and the family's board. */
    private class Engines(
        private val platform: FabricPlatform,
        private val server: FabricBackupServer,
        private val root: Path,
        private val home: Path,
    ) : (XBackupPlugin) -> Engine {
        override fun invoke(xbackup: XBackupPlugin): Engine = JvmEngine(
            xbackup, server, root, home, home.resolve("repository"), platform.family.family.board,
            Sqlite(platform.drivers(XBackupMod::class.java.classLoader)), ownJar(),
        )
    }

    /**
     * SQLite's own copy for the databases no Xena mod has open: Fabric servers have no driver, so
     * it's fetched the first time a backup needs one.
     */
    private class Sqlite(private val fetcher: DriverFetcher?) : () -> Databases? {
        @Volatile
        private var made: Databases? = null

        override fun invoke(): Databases? {
            made?.let { return it }
            val driver = (IsolatedDriver.open(fetcher, DriverLibrary.SQLITE) as? Outcome.Ok)?.value ?: return null
            return Databases.Sqlite(Connect(driver)).also { made = it }
        }

        private class Connect(private val driver: IsolatedDriver) : (String) -> java.sql.Connection? {
            override fun invoke(url: String): java.sql.Connection? = driver.connect(url, java.util.Properties())
        }
    }

    private fun ownJar(): Path? = FabricLoader.getInstance().getModContainer(XBackupPlugin.PLUGIN_ID).orElse(null)
        ?.origin?.paths?.firstOrNull()

    private object Mclogs : co.xenastudios.core.common.admin.PasteService {
        override val name: String get() = "mclo.gs"

        override fun upload(text: String) = MclogsPaste().upload(text)
    }

    // The jar's translations, from this mod's own jar: every Fabric mod shares one class loader,
    // so a resource found through it could be another mod's lang/ file.
    private fun shipped(): Map<LanguageTag, String> {
        val mod = FabricLoader.getInstance().getModContainer(XBackupPlugin.PLUGIN_ID).orElse(null) ?: return emptyMap()
        val out = LinkedHashMap<LanguageTag, String>()
        // The shrunk jar has them in one entry (LangFolder.BUNDLE); a dev build, one file each.
        val bundle = mod.findPath(LangFolder.BUNDLE).orElse(null)?.let { LangFolder.bundle(String(Files.readAllBytes(it), StandardCharsets.UTF_8)) }.orEmpty()
        for (tag in JvmFiles.LANGUAGES) {
            val path = mod.findPath("lang/$tag.yml").orElse(null)
            if (path == null) {
                bundle[tag.toString()]?.let { out[tag] = it }
                continue
            }
            out[tag] = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        }
        return out
    }
}
