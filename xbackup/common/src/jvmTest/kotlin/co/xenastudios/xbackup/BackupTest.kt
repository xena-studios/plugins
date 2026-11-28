package co.xenastudios.xbackup

import co.xenastudios.xbackup.backup.Backup
import co.xenastudios.xbackup.restore.Restore
import co.xenastudios.xbackup.restore.Swap
import co.xenastudios.xbackup.store.Maintenance
import co.xenastudios.xbackup.store.Retention
import co.xenastudios.xbackup.store.Session
import co.xenastudios.xbackup.store.ZoneCalendar
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A backup and a restore, start to finish, on real files: 1.x's BackupTest, the parts that hold the store together. */
class BackupTest {
    private val folder: Path = Files.createTempDirectory("xbackup-test")
    private val root: Path = Fixtures.server(folder.resolve("server"))
    private val repo: Path = folder.resolve("repository")

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    private fun backup(reason: String = "test"): Backup.Result = Fixtures.repository(repo).use { repository ->
        repository.lock(true).use { Backup.run(repository, Fixtures.plan(root, repo, reason), Backup.Pause.None, Fixtures.sqlite, { false }) {} }
    }

    @Test
    fun aBackupRestoresEveryFileAsItWas() {
        val result = backup()
        assertTrue(result.snapshot.warnings.isEmpty(), "warnings: ${result.snapshot.warnings}")
        assertEquals(52, result.chunksRead, "every chunk of both regions")
        val out = folder.resolve("out")
        Fixtures.repository(repo).use { repository ->
            val snapshot = repository.snapshots(ArrayList()).single()
            val items = Restore.expand(repository, snapshot, listOf(""), emptySet())
            Restore.write(repository, items, out)
        }
        val before = Fixtures.files(root).filterKeys { !it.startsWith("plugins/Some/data.db") }
        val after = Fixtures.files(out).filterKeys { !it.startsWith("plugins/Some/data.db") }
        assertEquals(before.keys, after.keys)
        for ((name, bytes) in before) assertContentEquals(bytes, after[name], name)
        // A database comes back as SQLite's own consistent copy.
        assertEquals(50, Fixtures.rows(out.resolve("plugins/Some/data.db")))
    }

    @Test
    fun theSecondBackupReadsOnlyTheChunkThatChanged() {
        backup()
        // Region timestamps are whole seconds; a chunk stamped before the last read's second is reused.
        Thread.sleep(1100)
        // Stamped a second before the backup starts, as the server stamps a chunk it just saved.
        Fixtures.touchChunk(root.resolve("world/region/r.0.0.mca"), 7, 9, (System.currentTimeMillis() / 1000).toInt() - 1)
        val second = backup("again")
        assertEquals(1, second.chunksRead, "one chunk changed")
        assertTrue(second.chunksReused >= 51, "the rest reused: ${second.chunksReused}")
        assertTrue(second.snapshot.added < 64 * 1024, "the big file isn't stored again: ${second.snapshot.added} bytes added")
        // And the restored region is the changed one, byte for byte.
        val out = folder.resolve("out2")
        Fixtures.repository(repo).use { repository ->
            val newest = repository.snapshots(ArrayList()).last()
            Restore.write(repository, Restore.expand(repository, newest, listOf("world"), emptySet()), out)
        }
        assertContentEquals(Fixtures.live(root.resolve("world/region/r.0.0.mca")), Files.readAllBytes(out.resolve("world/region/r.0.0.mca")))
    }

    @Test
    fun aFullCheckReadsEverythingBackAndRetentionKeepsTheNewest() {
        backup("one")
        backup("two")
        Fixtures.repository(repo).use { repository ->
            repository.lock(true).use {
                val maintenance = Maintenance(repository) { false }
                val report = maintenance.check(1.0, null, Random(1))
                assertTrue(report.ok, "problems: ${report.problems}")
                assertEquals(2, report.snapshots)
                val forgotten = maintenance.forget(Retention(1, 0, 0, 0, 0), 0, ZoneCalendar())
                assertEquals(1, forgotten.removed.size)
                maintenance.collect(Session.Options(6, 4L shl 20, 0), 0.3)
                assertTrue(maintenance.check(1.0, null, Random(2)).ok)
                assertEquals("two", repository.snapshots(ArrayList()).single().reason)
            }
        }
    }

    @Test
    fun aStagedRestoreSwapsInAndKeepsWhatWasThere() {
        backup()
        Files.write(root.resolve("plugins/Some/config.yml"), "a: 2\n".toByteArray())
        val restore = root.resolve("plugins/xBackup/restore")
        Fixtures.repository(repo).use { repository ->
            val snapshot = repository.snapshots(ArrayList()).single()
            val items = Restore.expand(repository, snapshot, listOf("plugins/Some/config.yml"), emptySet())
            val plan = Restore.dryRun(repository, items, root)
            assertEquals(1, plan.changed)
            Restore.write(repository, items, restore.resolve(Swap.STAGED), root)
            Swap.schedule(restore, snapshot.key.hex(), items.map { Swap.Entry(it.path, false) })
        }
        assertNotNull(Swap.pending(restore))
        val aside = Swap.apply(restore, root) {}!!
        assertEquals("a: 1\n", String(Files.readAllBytes(root.resolve("plugins/Some/config.yml"))))
        assertEquals("a: 2\n", String(Files.readAllBytes(aside.resolve("plugins/Some/config.yml"))))
        assertEquals(null, Swap.pending(restore))
    }

    @Test
    fun theSnapshotSaysWhatIsntInIt() {
        val result = Fixtures.repository(repo).use { repository ->
            repository.lock(true).use {
                Backup.run(repository, Fixtures.plan(root, repo, external = listOf("xMoney: MySQL at db:3306/mc (xmoney_ tables)")), Backup.Pause.None, Fixtures.sqlite, { false }) {}
            }
        }
        Fixtures.repository(repo).use { repository ->
            assertEquals(listOf("xMoney: MySQL at db:3306/mc (xmoney_ tables)"), repository.snapshots(ArrayList()).single().external)
        }
        assertTrue(result.snapshot.warnings.isEmpty())
    }
}
