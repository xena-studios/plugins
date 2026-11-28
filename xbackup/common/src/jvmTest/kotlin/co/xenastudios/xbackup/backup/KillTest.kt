package co.xenastudios.xbackup.backup

import co.xenastudios.xbackup.Fixtures
import co.xenastudios.xbackup.restore.Restore
import co.xenastudios.xbackup.store.LocalBackend
import co.xenastudios.xbackup.store.Maintenance
import co.xenastudios.xbackup.store.Repository
import co.xenastudios.xbackup.store.Retention
import co.xenastudios.xbackup.store.Session
import co.xenastudios.xbackup.store.ZoneCalendar
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.ZoneOffset
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The real thing, as 1.x's KillTest: another JVM backs up and cleans up in a loop and is killed
 * with SIGKILL at random moments, mid-write. After each kill the repository is opened as the next
 * start would, checked in full, and its newest backup restored, every file checked against its hash.
 */
class KillTest {
    private val dir: Path = Files.createTempDirectory("xbackup-kill")

    @AfterTest
    fun clean() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun killedMidWriteTheRepositoryStaysWhole() {
        val random = Random(42)
        val server = Fixtures.server(dir.resolve("server"))
        val repo = dir.resolve("repo")
        var seen = 0
        for (kill in 0 until 6) {
            val child = ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Child::class.java.name, server.toString(), repo.toString(), kill.toString(),
            ).redirectErrorStream(true).redirectOutput(dir.resolve("child-$kill.log").toFile()).start()
            try {
                // Once it's working (a JVM's start takes its own time on a busy machine), kill it
                // somewhere in the middle of its work. The first child finishes a backup first, so
                // there's always one to restore.
                awaitLine(child, dir.resolve("child-$kill.log"), if (kill == 0) "backup " else "working")
                Thread.sleep(random.nextInt(1500).toLong())
            } finally {
                child.destroyForcibly()
                child.waitFor(30, TimeUnit.SECONDS)
            }
            LocalBackend(repo).clearTemp()
            if (!Files.exists(repo)) continue
            Repository.open(LocalBackend(repo), dir.resolve("temp"), false).use { repository ->
                val report = Maintenance(repository) { false }.check(1.0, null, random)
                assertTrue(report.ok, "after kill $kill: ${report.problems}")
                val snapshots = repository.snapshots(ArrayList())
                if (snapshots.isNotEmpty()) {
                    Restore.write(repository, Restore.expand(repository, snapshots.last(), listOf(""), emptySet()), dir.resolve("restore-$kill"))
                    seen = maxOf(seen, snapshots.size)
                }
            }
        }
        assertTrue(seen > 0, "the child never finished a backup; see ${dir.resolve("child-0.log")}")
    }

    // Waits for the child to print a line starting with [start], for at most two minutes.
    private fun awaitLine(child: Process, log: Path, start: String) {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
        while (true) {
            if (Files.exists(log) && Files.readAllLines(log).any { it.startsWith(start) }) return
            check(child.isAlive) { "the child stopped before \"$start\"; see $log" }
            check(System.nanoTime() < deadline) { "the child didn't print \"$start\" within two minutes; see $log" }
            Thread.sleep(20)
        }
    }

    /** Backs up, changes the server, cleans up, forever, until killed. */
    object Child {
        @JvmStatic
        fun main(args: Array<String>) {
            val server = Paths.get(args[0])
            val repo = Paths.get(args[1])
            val random = Random(args[2].toLong())
            val temp = repo.resolveSibling("temp")
            var round = 0
            println("working")
            while (true) {
                Repository.open(LocalBackend(repo), temp, true).use { repository ->
                    repository.lock(true).use {
                        val result = Backup.run(repository, Fixtures.plan(server, repo, "kill"), Backup.Pause.None, Fixtures.sqlite, { false }) {}
                        println("backup ${result.snapshot.key}")
                        if (round % 3 == 2) {
                            val maintenance = Maintenance(repository) { false }
                            maintenance.forget(Retention(3, 0, 0, 0, 0), 0, ZoneCalendar(ZoneOffset.UTC))
                            maintenance.collect(Session.Options(6, 128L shl 10, 0), 0.2)
                            println("collected")
                        }
                    }
                }
                Files.createDirectories(server.resolve("plugins/Other"))
                Files.write(server.resolve("plugins/Other/r${random.nextInt(4)}.bin"), ByteArray(100_000 + random.nextInt(900_000)).also(random::nextBytes))
                Fixtures.touchChunk(server.resolve("world/region/r.0.0.mca"), random.nextInt(40), random.nextLong(), 1_800_000_000 + round)
                Files.write(server.resolve("plugins/Other/config.yml"), "round: $round\n".toByteArray())
                round++
            }
        }
    }
}
