package co.xenastudios.xbackup.restore

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the command-line restore tells a server is running (1.x's ServerCheckTest). */
class ServerCheckTest {
    private val dir: Path = Files.createTempDirectory("xbackup-servercheck")

    @AfterTest
    fun clean() {
        dir.toFile().deleteRecursively()
    }

    // Locks the file as a running server would. A second JVM is costly, so this JVM's lock stands
    // in: ServerCheck treats "already locked here" the same.
    private fun lock(file: Path): AutoCloseable {
        Files.createDirectories(file.parent)
        val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = channel.lock()
        return AutoCloseable {
            lock.release()
            channel.close()
        }
    }

    @Test
    fun aWorldInACustomContainerIsSeen() {
        val server = dir.resolve("server")
        Files.createDirectories(server.resolve("worlds/survival"))
        Files.write(server.resolve("bukkit.yml"), "settings:\n  allow-end: true\n  world-container: worlds\n".toByteArray())
        Files.write(server.resolve("worlds/survival/level.dat"), ByteArray(0))
        val home = server.resolve("plugins/xBackup")
        assertNull(ServerCheck.running(server, home, listOf("worlds/survival")))
        lock(server.resolve("worlds/survival/session.lock")).use {
            val running = ServerCheck.running(server, home, listOf("plugins"))
            assertNotNull(running)
            assertTrue("session.lock" in running, running)
        }
    }

    @Test
    fun aContainerOutsideTheServerFolderIsSeen() {
        val server = dir.resolve("server")
        Files.createDirectories(server)
        val elsewhere = dir.resolve("disk2/worlds")
        Files.write(server.resolve("bukkit.yml"), "settings:\n  world-container: '$elsewhere'\n".toByteArray())
        lock(elsewhere.resolve("world/session.lock")).use {
            assertNotNull(ServerCheck.running(server, server.resolve("plugins/xBackup"), listOf("")))
        }
    }

    @Test
    fun xBackupsOwnMarkerIsSeen() {
        val server = dir.resolve("server")
        val home = server.resolve("plugins/xBackup")
        Files.createDirectories(home)
        lock(home.resolve(ServerCheck.MARKER)).use {
            val running = ServerCheck.running(server, home, listOf("world"))
            assertNotNull(running)
            assertTrue(ServerCheck.MARKER in running, running)
        }
        assertNull(ServerCheck.running(server, home, listOf("world")), "a stopped server leaves the file unlocked")
    }

    @Test
    fun fabricsFolderIsSeenToo() {
        val server = dir.resolve("server")
        val home = server.resolve("config/xbackup")
        Files.createDirectories(home)
        lock(home.resolve(ServerCheck.MARKER)).use { assertNotNull(ServerCheck.running(server, home, listOf("world"))) }
    }

    @Test
    fun aServerStartingDuringACommandLineRestoreFindsTheMarkerTaken() {
        val home = dir.resolve("server/plugins/xBackup")
        Files.createDirectories(home)
        val claim = ServerCheck.claim(home)
        assertNotNull(claim)
        claim.use {
            // RestoreOnStart stops the server on BUSY.
            assertEquals(ServerCheck.Hold.BUSY, ServerCheck.hold(home))
            assertNull(ServerCheck.claim(home), "a second restore is refused too")
        }
    }

    @Test
    fun toMustBeNewOrEmpty() {
        val server = dir.resolve("server")
        Files.createDirectories(server)
        assertNull(ServerCheck.notEmpty(dir.resolve("new"), server))
        Files.createDirectories(dir.resolve("empty"))
        assertNull(ServerCheck.notEmpty(dir.resolve("empty"), server))
        Files.createDirectories(dir.resolve("full"))
        Files.write(dir.resolve("full/x"), ByteArray(0))
        assertNotNull(ServerCheck.notEmpty(dir.resolve("full"), server))
        assertNotNull(ServerCheck.notEmpty(server, server), "the live server folder")
        assertNotNull(ServerCheck.notEmpty(dir, server), "a folder holding the server")
    }
}
