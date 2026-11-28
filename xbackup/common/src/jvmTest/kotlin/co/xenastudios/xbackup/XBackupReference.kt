package co.xenastudios.xbackup

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.manifest.EventInfo
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.common.manifest.PluginReference
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.jvm.family.JvmFamilyBoard
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.xbackup.api.event.BackupFailedEvent
import co.xenastudios.xbackup.api.event.BackupFinishedEvent
import co.xenastudios.xbackup.api.event.BackupStartEvent
import co.xenastudios.xbackup.backup.Backup
import co.xenastudios.xbackup.config.XBackupConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * xBackup's reference, for `./gradlew :xbackup:common:xenaManifest`: the plugin with its default
 * config on the fake platform, its engine on an empty folder it never reads.
 */
object XBackupReference : ManifestSource {
    override val reference: PluginReference
        get() {
            val root = Files.createTempDirectory("xbackup-reference")
            val platform = FakePlatform()
            val server = Idle(root)
            val loaded = Loaded(XBackupConfig.defaults, Translations.of(XBackupMessages.catalog, emptyMap()))
            val plugin = XBackupPlugin(platform, server, { loaded }, NoLocale, { p ->
                JvmEngine(p, server, root, root.resolve("plugins/xBackup"), root.resolve("plugins/xBackup/repository"), JvmFamilyBoard(JvmFamilyBoard.newRoot()), { null }, null)
            })
            platform.scheduler.runAs(Where.Global) { plugin.start(loaded) }
            return PluginReference(
                XBackupPlugin.PLUGIN_ID, XBackupPlugin.NAME, "Backups that never stall the server: deduplicated, checked, copied off-site, restored safely.",
                plugin.dispatcher.tree, listOf(plugin.permissions, plugin.admin.permissions),
                config = XBackupConfig,
                catalog = XBackupMessages.catalog,
                events = listOf(
                    EventInfo.of(BackupStartEvent.TYPE, "A backup is about to start. Cancel it to skip that backup.", since = "1.0.0"),
                    EventInfo.of(BackupFinishedEvent.TYPE, "A backup finished and is in the repository.", since = "1.0.0"),
                    EventInfo.of(BackupFailedEvent.TYPE, "A backup failed; nothing half-written was kept.", since = "1.0.0"),
                ),
            ).also {
                platform.scheduler.runAs(Where.Global) { plugin.stop() }
                root.toFile().deleteRecursively()
            }
        }

    private object NoLocale : LanguageSource {
        override fun clientLocale(player: PlayerRef): String? = null
    }

    private class Idle(private val root: Path) : BackupServer, JvmServer {
        override fun broadcast(draw: (PlayerRef?) -> Component?) {}

        override val anyoneOnline: Boolean get() = false

        override fun watchJoins(joined: () -> Unit): AutoCloseable = AutoCloseable {}

        override fun worldFolders(): List<Path> = emptyList()

        override fun pause(maxPauseMillis: Long, onWatchdog: () -> Unit): Backup.Pause = Backup.Pause.None

        override val pluginsFolder: Path get() = root.resolve("plugins")

        override fun enabled(owner: String): Boolean = true
    }
}
