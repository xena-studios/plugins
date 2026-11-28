package co.xenastudios.xbackup.remote

import co.xenastudios.xbackup.Fixtures
import co.xenastudios.xbackup.backup.Backup
import co.xenastudios.xbackup.restore.Restore
import co.xenastudios.xbackup.store.Backend
import co.xenastudios.xbackup.store.LocalBackend
import co.xenastudios.xbackup.store.Repository
import co.xenastudios.xbackup.store.Throttle
import co.xenastudios.xbackup.store.readAll
import org.junit.Assume.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Random
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every network remote against a real server, as 1.x's RemoteTest: only with XB_REMOTES set, and
 * the servers started as xbackup/README.md says (MinIO, two WebDAV servers, SFTP, FTP and FTPS in
 * Docker). The FTP and SFTP backends load their libraries through the isolated loader they ship with.
 */
class RemoteTest {
    private val dir: Path = Files.createTempDirectory("xbackup-remotes")
    private val types = listOf("s3", "webdav", "webdav-below-root", "sftp", "ftp", "ftps")

    @AfterTest
    fun clean() {
        dir.toFile().deleteRecursively()
    }

    private fun context(): Remotes.Context = Remotes.Context(dir.resolve("home"), Throttle.NONE, {}).also { Files.createDirectories(it.home) }

    private fun open(type: String): Backend {
        val folder = "xb-" + UUID.randomUUID().toString().substring(0, 8)
        val settings = when (type) {
            "s3" -> RemoteConfig("s3", true, folder, "http://127.0.0.1:19000", "us-east-1", "xbackup-test", true, "xbackup", "xbackupsecret", "", 0, "", "", "explicit", "", "")
            "webdav" -> RemoteConfig("webdav", true, folder, "http://127.0.0.1:18080/", "", "", false, "xbackup", "pass", "", 0, "", "", "explicit", "", "")
            "sftp" -> RemoteConfig("sftp", true, "upload/$folder", "", "", "", false, "xbackup", "pass", "127.0.0.1", 12222, "", "", "explicit", "", "")
            "webdav-below-root" -> RemoteConfig("webdav", true, folder, "http://127.0.0.1:18081/remote.php/dav/files/xbackup", "", "", false, "xbackup", "pass", "", 0, "", "", "explicit", "", "")
            "ftps" -> RemoteConfig("ftp", true, folder, "", "", "", false, "xbackup", "pass", "127.0.0.1", 12990, "", System.getenv("XB_FTPS_PIN") ?: "", "explicit", "", "")
            else -> RemoteConfig("ftp", true, folder, "", "", "", false, "xbackup", "pass", "127.0.0.1", 12121, "", "", "none", "", "")
        }
        val backend = Remotes.open(if (type.startsWith("webdav")) "webdav" else if (type.startsWith("ftp")) "ftp" else type, settings, context())
        (backend as? S3Backend)?.createBucket()
        return backend
    }

    @Test
    fun putGetListReadDelete() {
        assumeTrue("XB_REMOTES isn't set", System.getenv("XB_REMOTES") != null)
        for (type in types) {
            open(type).use { backend ->
                val data = ByteArray(300_000).also(Random(1)::nextBytes)
                backend.put("data/ab/one", data)
                val file = dir.resolve("file")
                Files.write(file, data)
                backend.put("index/two", file)
                backend.put("index/two", file)
                // A pin is an empty file.
                val pin = "keep/" + "ab".repeat(32)
                backend.put(pin, ByteArray(0))
                assertEquals(setOf(Backend.FileInfo("data/ab/one", 300_000), Backend.FileInfo("index/two", 300_000), Backend.FileInfo(pin, 0)), backend.list("").toSet(), type)
                backend.delete(pin)
                assertEquals(1, backend.list("data/").size, type)
                assertContentEquals(data, backend.readAll("data/ab/one"), type)
                assertContentEquals(data.copyOfRange(1000, 1500), backend.read("index/two", 1000, 500), type)
                assertFailsWith<NoSuchFileException>(type) { backend.get("data/ab/missing").close() }
                backend.delete("data/ab/one")
                backend.delete("data/ab/one")
                assertEquals(1, backend.list("").size, type)
                backend.delete("index/two")
            }
        }
    }

    @Test
    fun copyThenRestoreStraightFromTheRemote() {
        assumeTrue("XB_REMOTES isn't set", System.getenv("XB_REMOTES") != null)
        val server = Fixtures.server(dir.resolve("server"))
        for (type in types) {
            val restored = dir.resolve("restored-$type")
            Repository.open(LocalBackend(dir.resolve("repo-$type")), dir.resolve("temp"), true).use { repository ->
                val snapshot = repository.lock(true).use { Backup.run(repository, Fixtures.plan(server, dir.resolve("repo-$type")), Backup.Pause.None, Fixtures.sqlite, { false }) {}.snapshot }
                open(type).use { remote ->
                    val first = Mirror.push(repository, remote, { false }) {}
                    assertTrue(first.filesSent > 3, "$type: ${first.filesSent} sent")
                    // Nothing new: nothing sent.
                    assertEquals(0, Mirror.push(repository, remote, { false }) {}.filesSent, type)
                    Fetch.metadata(remote, dir.resolve("cache-$type")) {}.use { cached ->
                        val items = Restore.expand(cached, snapshot, listOf("world"), emptySet())
                        Fetch.data(remote, cached, items) {}
                        Restore.write(cached, items, restored)
                    }
                    for (file in remote.list("")) remote.delete(file.name)
                }
            }
            val world = server.resolve("world")
            val expected = Fixtures.files(world).mapValues { (p, b) -> if (p.endsWith(".mca")) Fixtures.live(world.resolve(p)) else b }
            val actual = Fixtures.files(restored.resolve("world")).mapValues { (p, b) -> if (p.endsWith(".mca")) Fixtures.live(restored.resolve("world").resolve(p)) else b }
            assertEquals(expected.keys, actual.keys, type)
            for ((path, bytes) in expected) assertContentEquals(bytes, actual[path], "$type: $path")
        }
    }

    @Test
    fun ftpsRefusesACertificateJavaDoesntTrust() {
        assumeTrue("XB_REMOTES isn't set", System.getenv("XB_REMOTES") != null)
        val unpinned = RemoteConfig("ftp", true, "xb", "", "", "", false, "xbackup", "pass", "127.0.0.1", 12990, "", "", "explicit", "", "")
        Remotes.open("ftps", unpinned, context()).use { backend ->
            val refused = assertFailsWith<IOException> { backend.list("") }
            val messages = generateSequence<Throwable>(refused) { it.cause }.joinToString(" | ") { it.message.toString() }
            assertTrue("SHA256:" in messages, "says the fingerprint to pin: $messages")
        }
        val wrong = RemoteConfig("ftp", true, "xb", "", "", "", false, "xbackup", "pass", "127.0.0.1", 12990, "", "SHA256:" + "A".repeat(43), "explicit", "", "")
        Remotes.open("ftps", wrong, context()).use { backend -> assertFailsWith<IOException> { backend.list("") } }
    }
}
