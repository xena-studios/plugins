package co.xenastudios.xbackup

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.platform.Capability
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.SystemClock
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.jvm.family.JvmFamilyBoard
import co.xenastudios.core.jvm.scheduler.ProxyScheduler
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.MemoryStorage
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xbackup.api.XBackup
import co.xenastudios.xbackup.api.event.BackupFinishedEvent
import co.xenastudios.xbackup.api.event.BackupStartEvent
import co.xenastudios.xbackup.backup.Backup
import co.xenastudios.xbackup.config.XBackupConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/backup` and `/xbackup` from the console, on the JVM engine and a real repository: each
 * command's replies as players read them, from lang/en.yml's defaults. The scheduler is a real one
 * with threads (the proxy platform's), as the engine's replies come back from its own thread.
 */
class CommandsTest {
    private val root: Path = Fixtures.server(Files.createTempDirectory("xbackup-commands"))
    private val home: Path = root.resolve("plugins/xBackup")
    private val platform = TestPlatform()
    private val server = TestServer(root)
    private val loaded = Loaded(XBackupConfig.defaults, Translations.of(XBackupMessages.catalog, emptyMap()))
    private val plugin = XBackupPlugin(platform, server, Same(loaded), NoLocale, { p ->
        JvmEngine(p, server, root, home, home.resolve("repository"), JvmFamilyBoard(JvmFamilyBoard.newRoot()), { Fixtures.sqlite }, null)
    })
    private val console = FakeCommandSource.console()

    init {
        onGlobal { plugin.start(loaded) }
    }

    @AfterTest
    fun stop() {
        onGlobal { plugin.stop() }
        platform.scheduler.close()
        root.toFile().deleteRecursively()
    }

    private class TestPlatform : Platform {
        override val name: String = "Test"
        override val threading: Threading = Threading.PROXY
        override val capabilities: Set<Capability> = emptySet()
        override val log: RecordingLog = RecordingLog()
        override val scheduler: ProxyScheduler = ProxyScheduler("test", log) { false }
        override val clock = SystemClock
        override val storage = MemoryStorage()
        override val development: Boolean = true
    }

    private class Same(private val loaded: Loaded) : co.xenastudios.xbackup.Files {
        override fun load(): Loaded = loaded
    }

    private object NoLocale : LanguageSource {
        override fun clientLocale(player: PlayerRef): String? = null
    }

    private class TestServer(private val root: Path) : BackupServer, JvmServer {
        val told = java.util.Collections.synchronizedList(ArrayList<String>())

        override fun broadcast(draw: (PlayerRef?) -> Component?) {
            draw(null)?.let { told += it.toString() }
        }

        override val anyoneOnline: Boolean get() = true

        override fun watchJoins(joined: () -> Unit): AutoCloseable = AutoCloseable {}

        override fun worldFolders(): List<Path> = listOf(root.resolve("world"))

        override fun pause(maxPauseMillis: Long, onWatchdog: () -> Unit): Backup.Pause = Backup.Pause.None

        override val pluginsFolder: Path get() = root.resolve("plugins")

        override fun enabled(owner: String): Boolean = true
    }

    private fun <T> onGlobal(work: () -> T): T = Pauses.onGlobal(platform.scheduler, 30, work)

    /** Runs [line] as the console and waits until a reply has [until] in it; every reply so far. */
    private fun run(line: String, until: String): List<String> {
        onGlobal {
            console.clear()
            plugin.dispatcher.dispatch(console, line)
        }
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val replies = onGlobal { console.replies.toList() }
            if (replies.any { until in it }) return replies
            Thread.sleep(20)
        }
        throw AssertionError("\"$line\" never said \"$until\": ${onGlobal { console.replies.toList() }}")
    }

    @Test
    fun aBackupItsListAndStatusAndACheck() {
        val now = run("backup now first", "Backed up")
        assertTrue(now.first().contains("Backing up..."), now.toString())
        assertTrue(run("backup list", "backups,").any { "first" in it })
        val status = run("backup status", "Problem")
        assertTrue(status.any { it.startsWith("Last backup:") }, status.toString())
        assertTrue(status.any { it == "Problem: none" }, status.toString())
        assertTrue(run("backup verify full", "All good.").last().startsWith("» All good."))
    }

    @Test
    fun aRestoreIsPlannedThenStagedAndCanBeCalledOff() {
        run("backup now first", "Backed up")
        Files.write(root.resolve("server.properties"), "motd=changed\n".toByteArray())
        val plan = run("backup restore latest server.properties", "would change")
        assertTrue(plan.any { "server.properties" in it }, plan.toString())
        run("backup restore latest server.properties confirm", "Staged")
        assertTrue(Files.exists(home.resolve("restore/pending")))
        run("backup status", "Restore waiting")
        run("backup restore cancel", "Cancelled")
        // Paths that climb out of the server folder are refused before anything is read.
        run("backup restore latest ../etc", "can't go up")
    }

    @Test
    fun pinningKeepsABackupThroughAPrune() {
        run("backup now first", "Backed up")
        val id = XBackup.api().listSnapshots().single().id.take(8)
        run("backup pin $id", "Pinned")
        run("backup prune", "Removed")
        assertEquals(1, XBackup.api().listSnapshots().size)
        run("backup unpin $id", "Unpinned")
    }

    @Test
    fun theApiBacksUpAndItsEventsHaveTheirSay() {
        val api = XBackup.api()
        val finished = java.util.Collections.synchronizedList(ArrayList<BackupFinishedEvent>())
        api.events.subscribe(BackupFinishedEvent.TYPE) { finished += it }
        val snapshot = api.backupNow("an api test").get(60, TimeUnit.SECONDS)
        assertEquals("an api test", snapshot.reason)
        assertEquals(1, finished.size)
        // A start listener can call a backup off.
        api.events.subscribe(BackupStartEvent.TYPE) { if (it.reason == "blocked") it.isCancelled = true }
        val blocked = runCatching { api.backupNow("blocked").get(60, TimeUnit.SECONDS) }
        assertTrue(blocked.isFailure, "a cancelled backup didn't fail its future")
        assertEquals(1, api.listSnapshots().size)
        assertTrue(api.getStatus().last != null)
    }

    @Test
    fun infoReloadAndDebug() {
        assertTrue(run("xbackup info", "xBackup").any { "xBackup 2.0.0" in it })
        run("xbackup reload", "Reloaded xBackup")
    }
}
