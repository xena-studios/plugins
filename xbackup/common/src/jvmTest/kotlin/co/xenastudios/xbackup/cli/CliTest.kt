package co.xenastudios.xbackup.cli

import co.xenastudios.xbackup.Fixtures
import co.xenastudios.xbackup.backup.Backup
import co.xenastudios.xbackup.restore.ServerCheck
import co.xenastudios.xbackup.restore.Swap
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `java -jar xBackup.jar restore ...`, which never restores under a running server (1.x's MainTest). */
class CliTest {
    private val dir: Path = Files.createTempDirectory("xbackup-cli")
    private val server: Path = Fixtures.server(dir.resolve("server"))
    private val level: ByteArray = Files.readAllBytes(server.resolve("world/level.dat"))

    init {
        val repo = server.resolve("plugins/xBackup/repository")
        Fixtures.repository(repo).use { repository ->
            repository.lock(true).use { Backup.run(repository, Fixtures.plan(server, repo), Backup.Pause.None, Fixtures.sqlite, { false }) {} }
        }
    }

    @AfterTest
    fun clean() {
        dir.toFile().deleteRecursively()
    }

    private class Run(val code: Int, val out: String)

    private fun run(vararg args: String, answers: BufferedReader = BufferedReader(StringReader(""))): Run {
        val bytes = ByteArrayOutputStream()
        val code = Cli(PrintStream(bytes, true, "UTF-8"), answers, null).run(arrayOf("--server", server.toString(), *args))
        return Run(code, String(bytes.toByteArray(), Charsets.UTF_8))
    }

    // Answers "yes" after running something, as if it happened while the restore waited.
    private fun answer(meanwhile: () -> Unit): BufferedReader = object : BufferedReader(StringReader("")) {
        override fun readLine(): String {
            meanwhile()
            return "yes"
        }
    }

    @Test
    fun listAndVerify() {
        val list = run("list")
        assertEquals(0, list.code, list.out)
        assertTrue("1 backups" in list.out || "1 backup" in list.out, list.out)
        val verify = run("verify", "--full")
        assertEquals(0, verify.code, verify.out)
        assertTrue("All good." in verify.out, verify.out)
    }

    @Test
    fun aRestoreInPlacePutsTheBackupBack() {
        Files.write(server.resolve("world/level.dat"), byteArrayOf(1, 2, 3))
        val dry = run("restore", "latest", "world", "--dry-run")
        assertEquals(0, dry.code, dry.out)
        assertTrue("Dry run" in dry.out, dry.out)
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(server.resolve("world/level.dat")))
        val done = run("restore", "latest", "world", "--yes")
        assertEquals(0, done.code, done.out)
        assertContentEquals(level, Files.readAllBytes(server.resolve("world/level.dat")))
    }

    @Test
    fun aRunningServerIsNeverRestoredUnder() {
        val marker = server.resolve("plugins/xBackup").resolve(ServerCheck.MARKER)
        FileChannel.open(marker, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                Files.write(server.resolve("world/level.dat"), byteArrayOf(9))
                val refused = run("restore", "latest", "world", "--yes")
                assertEquals(1, refused.code, refused.out)
                assertTrue("The server is running" in refused.out, refused.out)
                assertContentEquals(byteArrayOf(9), Files.readAllBytes(server.resolve("world/level.dat")), "nothing changed")
                // Into another, empty folder is fine while it runs.
                val elsewhere = run("restore", "latest", "world", "--to", dir.resolve("copy").toString(), "--yes")
                assertEquals(0, elsewhere.code, elsewhere.out)
                assertContentEquals(level, Files.readAllBytes(dir.resolve("copy/world/level.dat")))
            }
        }
    }

    @Test
    fun toMustBeEmpty() {
        Files.createDirectories(dir.resolve("full"))
        Files.write(dir.resolve("full/keep.txt"), "mine".toByteArray())
        val refused = run("restore", "latest", "--to", dir.resolve("full").toString(), "--yes")
        assertEquals(1, refused.code, refused.out)
        assertTrue("isn't empty" in refused.out, refused.out)
        assertEquals(1, run("restore", "latest", "--to", server.toString(), "--yes").code)
        assertEquals("mine", String(Files.readAllBytes(dir.resolve("full/keep.txt"))))
    }

    @Test
    fun theMarkerIsHeldForTheWholeRestore() {
        val marker = server.resolve("plugins/xBackup").resolve(ServerCheck.MARKER)
        Files.write(server.resolve("world/level.dat"), byteArrayOf(7))
        var heldWhileAsking = false
        val done = run("restore", "latest", "world", answers = answer {
            // A server starting now would find the marker taken and stop as it loads xBackup.
            try {
                FileChannel.open(marker, StandardOpenOption.WRITE).use { it.tryLock() }
            } catch (e: OverlappingFileLockException) {
                heldWhileAsking = true
            }
        })
        assertEquals(0, done.code, done.out)
        assertTrue(heldWhileAsking, "the marker wasn't held while the restore asked")
        assertContentEquals(level, Files.readAllBytes(server.resolve("world/level.dat")))
        FileChannel.open(marker, StandardOpenOption.WRITE).use { channel -> channel.tryLock().use { assertNotNull(it, "let go once the restore is done") } }
    }

    @Test
    fun aWorldOpenedWhileStagingStopsTheSwap() {
        Files.write(server.resolve("world/level.dat"), byteArrayOf(5))
        val sessionLock = server.resolve("world/session.lock")
        Files.write(sessionLock, ByteArray(0))
        var channel: FileChannel? = null
        try {
            val refused = run("restore", "latest", "world", answers = answer {
                channel = FileChannel.open(sessionLock, StandardOpenOption.WRITE).also { it.lock() }
            })
            assertEquals(1, refused.code, refused.out)
            assertTrue("nothing was changed" in refused.out.lowercase(), refused.out)
            assertContentEquals(byteArrayOf(5), Files.readAllBytes(server.resolve("world/level.dat")), "nothing changed")
            assertFalse(Files.exists(server.resolve("plugins/xBackup/restore").resolve(Swap.PENDING)), "no journal left")
        } finally {
            channel?.close()
        }
    }
}
