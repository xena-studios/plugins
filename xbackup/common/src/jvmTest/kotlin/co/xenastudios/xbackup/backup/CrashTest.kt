package co.xenastudios.xbackup.backup

import co.xenastudios.xbackup.Fixtures
import co.xenastudios.xbackup.restore.Restore
import co.xenastudios.xbackup.store.Backend
import co.xenastudios.xbackup.store.Hash
import co.xenastudios.xbackup.store.LocalBackend
import co.xenastudios.xbackup.store.Maintenance
import co.xenastudios.xbackup.store.Repository
import co.xenastudios.xbackup.store.Retention
import co.xenastudios.xbackup.store.Session
import co.xenastudios.xbackup.store.ZoneCalendar
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Kill-partway fuzzing, as 1.x's CrashTest: backups, clean-ups and index rebuilds die at a random
 * write, with no clean-up allowed to run, over and over. After every death the repository must
 * still check clean, no backup may exist that never finished, every one that exists must restore
 * to exactly what it saved, and the next backup must work.
 */
class CrashTest {
    private val dir: Path = Files.createTempDirectory("xbackup-crash")

    @AfterTest
    fun clean() {
        dir.toFile().deleteRecursively()
    }

    /** A backend that dies for good at its [writes]th write: every call after throws. */
    class Dying(val inner: LocalBackend, writes: Int) : Backend {
        private val left = AtomicInteger(writes)
        private val done = AtomicInteger()

        @Volatile
        var dead: Boolean = false

        val written: Int get() = done.get()

        private fun write() {
            if (dead || left.decrementAndGet() < 0) {
                dead = true
                throw IOException("killed")
            }
            done.incrementAndGet()
        }

        private fun alive() {
            if (dead) throw IOException("killed")
        }

        override fun describe(): String = "dying ${inner.describe()}"

        override fun put(name: String, file: Path) {
            write()
            inner.put(name, file)
        }

        override fun put(name: String, data: ByteArray) {
            write()
            inner.put(name, data)
        }

        override fun get(name: String): InputStream {
            alive()
            return inner.get(name)
        }

        override fun read(name: String, offset: Long, length: Int): ByteArray {
            alive()
            return inner.read(name, offset, length)
        }

        override fun list(prefix: String): List<Backend.FileInfo> {
            alive()
            return inner.list(prefix)
        }

        override fun delete(name: String) {
            write()
            inner.delete(name)
        }
    }

    @Test
    fun anyCrashLeavesEveryBackupWhole() {
        for (seed in 1L..4L) fuzz(seed, 40)
    }

    private fun fuzz(seed: Long, rounds: Int) {
        val random = Random(seed)
        val base = dir.resolve("run-$seed")
        val server = Fixtures.server(base.resolve("server"))
        val repoFolder = base.resolve("repo")
        val saved = HashMap<Hash, Map<String, ByteArray>>()
        var deaths = 0
        // How many writes each kind of job made the last time it ran to the end, so the kill
        // point falls anywhere in a job, not only in its first few writes.
        val writes = intArrayOf(6, 6, 6)
        for (round in 0 until rounds) {
            mutate(server, random, round)
            val local = LocalBackend(repoFolder)
            val dies = random.nextInt(3) != 0
            val op = random.nextInt(10)
            val kind = if (op < 7) 0 else if (op < 9) 1 else 2
            val dying = Dying(local, if (dies) random.nextInt(writes[kind] + 1) else Int.MAX_VALUE)
            val before = contents(server)
            try {
                val repository = Repository.open(dying, base.resolve("temp"), true)
                when (kind) {
                    0 -> {
                        val result = Backup.run(repository, Fixtures.plan(server, repoFolder, "fuzz"), Backup.Pause.None, Fixtures.sqlite, { false }) {}
                        saved[result.snapshot.key] = before
                    }
                    1 -> {
                        val maintenance = Maintenance(repository) { false }
                        maintenance.forget(Retention(2 + random.nextInt(3), 0, 0, 0, 0), 0, ZoneCalendar(ZoneOffset.UTC))
                        maintenance.collect(Session.Options(6, 64L shl 10, 0), 0.2)
                    }
                    else -> Maintenance(repository) { false }.rebuildIndex()
                }
                if (!dying.dead) writes[kind] = maxOf(1, dying.written)
            } catch (e: IOException) {
                if (!dying.dead) throw e
                deaths++
            }
            // The next start: a fresh repository on the plain folder.
            local.clearTemp()
            Repository.open(LocalBackend(repoFolder), base.resolve("temp"), true).use { repository ->
                val problems = ArrayList<String>()
                val snapshots = repository.snapshots(problems)
                assertTrue(problems.isEmpty(), problems.toString())
                val report = Maintenance(repository) { false }.check(1.0, null, random)
                assertTrue(report.ok, "seed $seed round $round: ${report.problems}")
                for (snapshot in snapshots) {
                    if (snapshot.key !in saved) fail("seed $seed round $round: backup ${snapshot.key} exists but never finished")
                }
                if (snapshots.isNotEmpty()) {
                    check(repository, snapshots.last(), saved, base.resolve("restore-${round}a"))
                    check(repository, snapshots[random.nextInt(snapshots.size)], saved, base.resolve("restore-${round}b"))
                }
            }
        }
        assertTrue(deaths >= 8, "only $deaths deaths in seed $seed")
    }

    private fun check(repository: Repository, snapshot: co.xenastudios.xbackup.store.Snapshot, saved: Map<Hash, Map<String, ByteArray>>, target: Path) {
        Restore.write(repository, Restore.expand(repository, snapshot, listOf(""), emptySet()), target)
        val expected = saved.getValue(snapshot.key)
        val actual = contents(target)
        assertEquals(expected.keys, actual.keys)
        for ((path, bytes) in expected) assertContentEquals(bytes, actual[path], path)
        target.toFile().deleteRecursively()
    }

    // The server as a restore should bring it back: region files by their live chunks (a restore
    // zeroes sectors no chunk uses), SQLite databases by their rows (they're copies), the rest by bytes.
    private fun contents(folder: Path): Map<String, ByteArray> = Fixtures.files(folder)
        .filterKeys { !it.startsWith("plugins/xBackup/") && !it.endsWith("-wal") && !it.endsWith("-shm") && !it.endsWith("-journal") }
        .mapValues { (path, bytes) ->
            when {
                path.endsWith(".mca") -> Fixtures.live(folder.resolve(path))
                path.endsWith(".db") -> Fixtures.rows(folder.resolve(path)).toString().toByteArray()
                else -> bytes
            }
        }

    private fun mutate(server: Path, random: Random, round: Int) {
        val region = server.resolve(if (random.nextBoolean()) "world/region/r.0.0.mca" else "world/region/r.0.-1.mca")
        repeat(1 + random.nextInt(4)) { Fixtures.touchChunk(region, random.nextInt(12), random.nextLong(), 1_800_000_000 + round) }
        val other = server.resolve("plugins/Other")
        Files.createDirectories(other)
        when (random.nextInt(4)) {
            0 -> Files.write(other.resolve("r${random.nextInt(5)}.bin"), ByteArray(random.nextInt(400_000)).also(random::nextBytes))
            1 -> Files.write(other.resolve("config.yml"), "a: ${random.nextInt()}\n".toByteArray())
            2 -> Files.list(other).use { files -> files.filter { it.fileName.toString().startsWith("r") }.findFirst().orElse(null)?.let(Files::delete) }
            else -> Files.write(server.resolve("server.properties"), "motd=${random.nextInt()}\n".toByteArray())
        }
    }

    @Test
    fun aDeadBackendStaysDead() {
        val dying = Dying(LocalBackend(dir.resolve("d")), 1)
        dying.put("a", ByteArray(1))
        assertFailsWith<IOException> { dying.put("b", ByteArray(1)) }
        assertFailsWith<IOException> { dying.list("") }
        assertEquals(1, dying.inner.list("").size)
    }
}
