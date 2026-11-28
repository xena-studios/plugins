package co.xenastudios.xbackup.remote

import co.xenastudios.xbackup.Fixtures
import co.xenastudios.xbackup.backup.Backup
import co.xenastudios.xbackup.restore.Restore
import co.xenastudios.xbackup.store.Backend
import co.xenastudios.xbackup.store.Hash
import co.xenastudios.xbackup.store.LocalBackend
import co.xenastudios.xbackup.store.Maintenance
import co.xenastudios.xbackup.store.PackFormat
import co.xenastudios.xbackup.store.Repository
import co.xenastudios.xbackup.store.Retention
import co.xenastudios.xbackup.store.Session
import co.xenastudios.xbackup.store.Snapshot
import co.xenastudios.xbackup.store.ZoneCalendar
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.Random
import java.util.TreeSet
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a copy to a remote may delete there, as 1.x's MirrorTest: retention reaches the remote,
 * but damage here, an emptied repository or a disk rolled back never deletes the remote's only
 * good copy of anything, and tidying what only the remote has needs a confirm.
 */
class MirrorTest {
    private val dir: Path = Files.createTempDirectory("xbackup-mirror")
    private val random = Random(7)
    private val server: Path = Fixtures.server(dir.resolve("server"))

    @AfterTest
    fun clean() {
        dir.toFile().deleteRecursively()
    }

    private fun local() = Repository.open(LocalBackend(dir.resolve("repo")), dir.resolve("temp"), true)

    private fun remote() = LocalBackend(dir.resolve("remote"))

    private fun backup(repository: Repository): Snapshot {
        Files.createDirectories(server.resolve("plugins/Other"))
        Files.write(server.resolve("plugins/Other/round.bin"), ByteArray(1_500_000).also(random::nextBytes))
        return repository.lock(true).use {
            Backup.run(repository, Fixtures.plan(server, dir.resolve("repo")), Backup.Pause.None, Fixtures.sqlite, { false }) {}.snapshot
        }
    }

    private fun names(backend: Backend, prefix: String): Set<String> = backend.list(prefix).mapTo(TreeSet()) { it.name }

    private fun prune(repository: Repository, keep: Int) {
        repository.lock(true).use {
            val maintenance = Maintenance(repository) { false }
            maintenance.forget(Retention(keep, 0, 0, 0, 0), 0, ZoneCalendar(ZoneOffset.UTC))
            maintenance.collect(Session.Options.DEFAULTS, 0.3)
        }
        repository.release()
    }

    private fun push(repository: Repository) = Mirror.push(repository, remote(), { false }) {}

    private fun copy(from: Path, to: Path) {
        Files.walk(from).use { walk ->
            for (path in walk) {
                val target = to.resolve(from.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(target) else if (path.fileName.toString() != "lock") Files.copy(path, target)
            }
        }
    }

    @Test
    fun retentionReachesTheRemote() {
        local().use { repository ->
            repeat(4) { backup(repository) }
            push(repository)
            prune(repository, 2)
            val result = push(repository)
            assertNull(result.held)
            assertTrue(result.filesDeleted > 2, "${result.filesDeleted} deleted")
            for (prefix in listOf("snapshots/", "data/", "index/")) assertEquals(names(repository.backend, prefix), names(remote(), prefix), prefix)
        }
    }

    @Test
    fun localDamageDeletesNothing() {
        local().use { repository ->
            backup(repository)
            backup(repository)
            push(repository)
            val before = names(remote(), "")
            // A pack the index lists goes missing here (a bad disk, a careless rm).
            Files.delete(dir.resolve("repo").resolve(names(repository.backend, "data/").first()))
            repository.release()
            val result = push(repository)
            assertEquals(0, result.filesDeleted)
            assertTrue(result.held!!.contains("missing pack"), result.held)
            assertEquals(before, names(remote(), ""))
        }
    }

    @Test
    fun aRolledBackDiskKeepsTheRemotesNewerBackups() {
        local().use { repository -> repeat(2) { backup(repository) } }
        // The host's snapshot of the disk, taken now.
        copy(dir.resolve("repo"), dir.resolve("disk-image"))
        val lost = local().use { repository ->
            repeat(2) { backup(repository) }
            push(repository)
            repository.snapshots(ArrayList()).subList(2, 4)
        }
        // The disk is rolled back, and the server carries on: a new backup, then a copy.
        dir.resolve("repo").toFile().deleteRecursively()
        copy(dir.resolve("disk-image"), dir.resolve("repo"))
        local().use { repository ->
            backup(repository)
            val result = push(repository)
            assertEquals(0, result.filesDeleted)
            assertTrue(result.held!!.contains("2 backups"), result.held)
        }
        Repository.open(remote(), dir.resolve("temp2"), false).use { copy ->
            val ids = copy.snapshots(ArrayList()).map { it.key }.toSet()
            for (snapshot in lost) assertTrue(snapshot.key in ids)
            val report = Maintenance(copy) { false }.check(1.0, null, Random())
            assertTrue(report.ok, report.problems.toString())
        }
    }

    @Test
    fun anEmptiedRepositoryDeletesNothing() {
        local().use { repository ->
            backup(repository)
            push(repository)
            val before = names(remote(), "")
            for (name in names(repository.backend, "snapshots/")) Files.delete(dir.resolve("repo").resolve(name))
            val result = push(repository)
            assertEquals(0, result.filesDeleted)
            assertTrue(result.held!!.contains("no backups"), result.held)
            assertEquals(before, names(remote(), ""))
        }
    }

    @Test
    fun forgettingTheNewestByHandWaitsForANewerOne() {
        local().use { repository ->
            backup(repository)
            val newest = backup(repository)
            push(repository)
            repository.lock(true).use { repository.deleteSnapshot(newest) }
            val result = push(repository)
            assertTrue(Snapshot.fileName(newest.key) in names(remote(), "snapshots/"), "newer than any left here")
            assertNull(result.held, "removed on purpose: no warning")
            backup(repository)
            push(repository)
            assertEquals(names(repository.backend, "snapshots/"), names(remote(), "snapshots/"))
        }
    }

    @Test
    fun aPackLostHereAndDroppedByTheNextBackupStaysOnTheRemote() {
        local().use { repository ->
            backup(repository)
            backup(repository)
            push(repository)
            val pack = names(repository.backend, "data/").first()
            Files.delete(dir.resolve("repo").resolve(pack))
            repository.release()
            backup(repository) // the scheduled backup that always runs before a copy
            val result = push(repository)
            assertTrue(pack in names(remote(), "data/"), "the remote's only good copy of $pack was deleted")
            assertTrue(result.held!!.contains("lost"), result.held)
        }
    }

    @Test
    fun aLostPackStaysRestorableFromTheRemoteThroughLaterCleanUps() {
        local().use { repository ->
            backup(repository)
            backup(repository)
            push(repository)
            // A pack of file contents: the backups' structure is all still here, so clean-ups run.
            val structure = HashSet<Hash>()
            repository.index().forEach { if (it.entry.type.isMetadata) structure += it.pack }
            val pack = names(repository.backend, "data/").first { PackFormat.idOf(it) !in structure }
            Files.delete(dir.resolve("repo").resolve(pack))
            repository.release()
            repeat(3) {
                backup(repository)
                prune(repository, 3)
                push(repository)
            }
            assertTrue(pack in names(remote(), "data/"))
            // Collected packs did leave the remote.
            val collected = repository.collected().map { PackFormat.fileName(it) }
            assertTrue(collected.isNotEmpty(), "the clean-ups collected something")
            for (name in collected) assertTrue(name !in names(remote(), "data/"), "$name was collected here but kept there")
            // A restore from the remote reads past the index entries for packs it no longer has.
            Fetch.metadata(remote(), dir.resolve("cache")) {}.use { cached ->
                val latest = cached.snapshots(ArrayList()).last()
                val items = Restore.expand(cached, latest, listOf("world"), emptySet())
                Fetch.data(remote(), cached, items) {}
                val out = dir.resolve("out")
                Files.createDirectories(out)
                Restore.write(cached, items, out)
                assertTrue(Files.exists(out.resolve("world/level.dat")))
            }
        }
    }

    @Test
    fun onlyPacksCollectedHereLeaveTheRemote() {
        val stranger = "data/ff/ff" + "0".repeat(62)
        local().use { repository ->
            backup(repository)
            backup(repository)
            push(repository)
            // A pack on the remote this repository never had, and never collected.
            Files.createDirectories(dir.resolve("remote/data/ff"))
            Files.write(dir.resolve("remote").resolve(stranger), byteArrayOf(1))
        }
        local().use { repository ->
            prune(repository, 1)
            push(repository)
            val expected = TreeSet(names(repository.backend, "data/")).apply { add(stranger) }
            assertEquals(expected, names(remote(), "data/"))
        }
    }

    @Test
    fun tidyingRemovesWhatOnlyTheRemoteHasOnceConfirmed() {
        local().use { repository -> repeat(2) { backup(repository) } }
        copy(dir.resolve("repo"), dir.resolve("disk-image"))
        local().use { repository ->
            repeat(2) { backup(repository) }
            push(repository)
        }
        dir.resolve("repo").toFile().deleteRecursively()
        copy(dir.resolve("disk-image"), dir.resolve("repo"))
        local().use { repository ->
            backup(repository)
            assertNotNull(push(repository).held)
            val before = names(remote(), "")
            val look = Mirror.tidy(repository, remote(), false, { false }) {}
            assertEquals(2, look.extra.size)
            assertTrue(look.packs > 0)
            assertEquals(before, names(remote(), ""), "looking changes nothing")
            Mirror.tidy(repository, remote(), true, { false }) {}
            assertEquals(names(repository.backend, "snapshots/"), names(remote(), "snapshots/"))
            assertEquals(names(repository.backend, "data/"), names(remote(), "data/"))
            assertNull(push(repository).held)
        }
        Repository.open(remote(), dir.resolve("temp2"), false).use { copy ->
            val report = Maintenance(copy) { false }.check(1.0, null, Random())
            assertTrue(report.ok, report.problems.toString())
        }
    }
}
