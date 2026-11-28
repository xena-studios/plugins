package co.xenastudios.xbackup.backup

import co.xenastudios.xbackup.Fixtures
import co.xenastudios.xbackup.restore.Restore
import co.xenastudios.xbackup.store.Maintenance
import co.xenastudios.xbackup.store.Throttle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Backups while files are still being written (#236): a library the core is downloading (a
 * `.part` moved into place when done), a save's `.<name>.<random>.tmp`, and files that go away or
 * keep changing while they're read. Each is left out, never a warning, a failed backup or a
 * mid-write copy, and the next backup takes the finished file.
 */
class InProgressTest {
    private val folder: Path = Files.createTempDirectory("xbackup-inprogress")
    private val root: Path = Fixtures.server(folder.resolve("server"))
    private val repo: Path = folder.resolve("repository")

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    private fun backup(throttle: Throttle = Throttle.NONE, progress: (String) -> Unit = {}): Backup.Result = Fixtures.repository(repo).use { repository ->
        val base = Fixtures.plan(root, repo)
        val plan = Backup.Plan(
            base.root, base.worlds, base.others, base.excludes, base.skip, base.quiet, base.reason, base.source, base.temp, throttle, base.options,
        )
        repository.lock(true).use { Backup.run(repository, plan, Backup.Pause.None, Fixtures.sqlite, { false }, progress) }
    }

    /** What [result]'s snapshot holds, restored: each path, with its file's sha-256 (`null` for a folder). */
    private fun paths(result: Backup.Result): Map<String, String?> = Fixtures.repository(repo).use { repository ->
        val snapshot = repository.snapshots(ArrayList()).single { it.key == result.snapshot.key }
        val out = folder.resolve("out-" + snapshot.key.shortHex())
        val items = Restore.expand(repository, snapshot, listOf(""), emptySet())
        Restore.write(repository, items, out)
        val sums = HashMap<String, String?>()
        Files.walk(out).use { walk -> for (file in walk) if (file != out) sums[out.relativize(file).toString().replace('\\', '/')] = if (Files.isRegularFile(file)) sha256(file) else null }
        out.toFile().deleteRecursively()
        sums
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checkOk() = Fixtures.repository(repo).use { repository ->
        repository.lock(true).use {
            val report = Maintenance(repository) { false }.check(1.0, null, Random(1))
            assertTrue(report.ok, "problems: ${report.problems}")
        }
    }

    @Test
    fun aLibraryDownloadingDuringBackupsIsLeftOutUntilItsDone() {
        val libraries = Files.createDirectories(root.resolve("plugins/Xena/libraries"))
        val stop = AtomicBoolean()
        val downloads = AtomicInteger()
        val failure = arrayOfNulls<Throwable>(1)
        // As the core's LibraryCache downloads: a temporary <name><random>.part beside the jar,
        // written a piece at a time, then moved into place. And as its config saves do: a
        // .<name>.<random>.tmp moved over the file.
        val writer = thread(name = "download") {
            try {
                val random = Random(7)
                var n = 0
                while (!stop.get()) {
                    val jar = libraries.resolve("lib-$n.jar")
                    val part = Files.createTempFile(libraries, jar.fileName.toString(), ".part")
                    val bytes = ByteArray(256 * 1024).also(random::nextBytes)
                    Files.newOutputStream(part, StandardOpenOption.WRITE).use { out ->
                        // A piece at a time, waiting on the network between them.
                        for (at in bytes.indices step 64 * 1024) {
                            out.write(bytes, at, 64 * 1024)
                            out.flush()
                            Thread.sleep(40)
                        }
                    }
                    Files.move(part, jar, StandardCopyOption.ATOMIC_MOVE)
                    val config = root.resolve("plugins/Xena/config.yml")
                    val temp = Files.createTempFile(config.parent, ".config.yml.", ".tmp")
                    Files.write(temp, "n: $n\n".toByteArray())
                    Thread.sleep(5)
                    Files.move(temp, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    downloads.incrementAndGet()
                    n++
                }
            } catch (e: Throwable) {
                failure[0] = e
            }
        }
        try {
            var backups = 0
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while ((backups < 6 || downloads.get() < 6) && System.nanoTime() < deadline) {
                val result = backup()
                assertTrue(result.snapshot.warnings.isEmpty(), "warnings: ${result.snapshot.warnings}")
                val taken = paths(result).keys
                val unfinished = taken.filter { name -> name.substringAfterLast('/').let { it.endsWith(".part") || it.startsWith(".") && it.endsWith(".tmp") } }
                assertTrue(unfinished.isEmpty(), "unfinished files were backed up: $unfinished")
                backups++
            }
            assertTrue(backups >= 6 && downloads.get() >= 6, "$backups backups ran beside ${downloads.get()} downloads")
        } finally {
            stop.set(true)
            writer.join(10_000)
        }
        assertNull(failure[0], "the download thread failed: ${failure[0]}")
        // Done downloading: the next backup has every finished jar, byte for byte.
        val taken = paths(backup())
        Files.list(libraries).use { files ->
            for (file in files) {
                val name = "plugins/Xena/libraries/${file.fileName}"
                assertEquals(sha256(file), taken[name], name)
            }
        }
        checkOk()
    }

    @Test
    fun aFileThatGoesAwayWhileItsReadIsLeftOutWithANote() {
        // 24 MiB at 8 MiB/s takes about two seconds to read; it's deleted a moment in.
        val file = Files.createDirectories(root.resolve("plugins/Aaa")).resolve("download.bin")
        Files.write(file, ByteArray(24 shl 20).also { Random(4).nextBytes(it) })
        val result = backup(Throttle(8L shl 20)) { step ->
            if (step == "reading plugins and server files") {
                thread(name = "delete") {
                    Thread.sleep(300)
                    Files.delete(file)
                }
            }
        }
        assertTrue(result.snapshot.warnings.isEmpty(), "warnings: ${result.snapshot.warnings}")
        assertEquals(listOf("plugins/Aaa/download.bin went away while it was read, so it was left out; it's read again next time."), result.notes)
        assertFalse("plugins/Aaa/download.bin" in paths(result))
        checkOk()
    }

    @Test
    fun aFileThatKeepsChangingIsNeverAMidWriteCopy() {
        val file = Files.createDirectories(root.resolve("plugins/Aaa")).resolve("growing.bin")
        val first = ByteArray(24 shl 20).also { Random(5).nextBytes(it) }
        Files.write(file, first)
        val firstSum = sha256(file)
        // Changes every few milliseconds for the whole backup: never read the same twice.
        fun changing(during: () -> Backup.Result): Backup.Result {
            val stop = AtomicBoolean()
            val appended = AtomicInteger()
            val writer = thread(name = "append") {
                while (!stop.get()) {
                    Files.write(file, byteArrayOf(1), StandardOpenOption.APPEND)
                    appended.incrementAndGet()
                    Thread.sleep(3)
                }
            }
            try {
                // Changing before the backup looks at it, however late the writer's thread starts:
                // else it can still match the last backup's copy and be taken from there unread.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (appended.get() < 2) {
                    assertTrue(System.nanoTime() < deadline, "the writer never started")
                    Thread.sleep(1)
                }
                return during()
            } finally {
                stop.set(true)
                writer.join(10_000)
            }
        }

        // No earlier copy: left out.
        val result = changing { backup(Throttle(64L shl 20)) }
        assertTrue(result.snapshot.warnings.isEmpty(), "warnings: ${result.snapshot.warnings}")
        assertEquals(listOf("plugins/Aaa/growing.bin kept changing while it was read, so it was left out; it's read again next time."), result.notes)
        assertFalse("plugins/Aaa/growing.bin" in paths(result))

        // Still: the next backup takes it.
        Files.write(file, first)
        Thread.sleep(Scanner.RACY_MILLIS + 100)
        val steady = backup()
        assertTrue(steady.notes.isEmpty(), "notes: ${steady.notes}")
        assertEquals(firstSum, paths(steady)["plugins/Aaa/growing.bin"])

        // Changing again: the last good copy is kept, not a mid-write one.
        val again = changing { backup(Throttle(64L shl 20)) }
        assertTrue(again.snapshot.warnings.isEmpty(), "warnings: ${again.snapshot.warnings}")
        assertEquals(listOf("plugins/Aaa/growing.bin kept changing while it was read, so the copy from the last backup was kept; it's read again next time."), again.notes)
        assertEquals(firstSum, paths(again)["plugins/Aaa/growing.bin"])
        checkOk()
    }
}
