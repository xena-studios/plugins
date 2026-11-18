package co.xenastudios.xbackup.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xbackup.Fixtures;
import co.xenastudios.xbackup.backup.Backup;
import co.xenastudios.xbackup.backup.Databases;
import co.xenastudios.xbackup.restore.Swap;
import co.xenastudios.xbackup.store.Backend;
import co.xenastudios.xbackup.store.LocalBackend;
import co.xenastudios.xbackup.store.Maintenance;
import co.xenastudios.xbackup.store.Repository;
import co.xenastudios.xbackup.store.Retention;
import co.xenastudios.xbackup.store.Session;
import co.xenastudios.xbackup.store.Snapshot;
import co.xenastudios.xbackup.store.Throttle;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The copy only removes from a remote what the local repository removed on purpose. */
class MirrorTest {

    @TempDir
    Path dir;

    private final Random random = new Random(7);

    private Path server() {
        return dir.resolve("server");
    }

    private Repository local() throws IOException {
        return Repository.open(new LocalBackend(dir.resolve("repo")), dir.resolve("temp"), true);
    }

    private final LocalBackend remote() {
        return new LocalBackend(dir.resolve("remote"));
    }

    private Snapshot backup(Repository repository) throws Exception {
        Fixtures.bytes(server().resolve("plugins/Other/round.bin"), random, 1_500_000);
        Backup.Plan plan = new Backup.Plan(server(), List.of("world"), List.of("plugins"), List.of("**/session.lock"),
            Set.of(dir.resolve("repo")), Map.of(), "test", "test", dir.resolve("temp"), Throttle.NONE,
            Session.Options.DEFAULTS);
        try (AutoCloseable lock = repository.lock(true)) {
            return Backup.run(repository, plan, Backup.Pause.NONE, Databases.sqlite(), () -> false, step -> {}).snapshot();
        }
    }

    private static Set<String> names(Backend backend, String prefix) throws IOException {
        Set<String> names = new TreeSet<>();
        for (Backend.FileInfo file : backend.list(prefix)) {
            names.add(file.name());
        }
        return names;
    }

    private static void prune(Repository repository, int keep) throws Exception {
        try (AutoCloseable lock = repository.lock(true)) {
            Maintenance maintenance = new Maintenance(repository, () -> false);
            maintenance.forget(new Retention(keep, 0, 0, 0, 0), 0, ZoneOffset.UTC);
            maintenance.collect(Session.Options.DEFAULTS, 0.3);
        }
        repository.release();
    }

    @Test
    void retentionReachesTheRemote() throws Exception {
        Fixtures.server(server(), random);
        try (Repository repository = local()) {
            for (int i = 0; i < 4; i++) {
                backup(repository);
            }
            Mirror.push(repository, remote(), () -> false, step -> {});
            prune(repository, 2);
            Mirror.Result result = Mirror.push(repository, remote(), () -> false, step -> {});
            assertNull(result.held());
            assertTrue(result.filesDeleted() > 2, result.toString());
            assertEquals(names(repository.backend(), "snapshots/"), names(remote(), "snapshots/"));
            assertEquals(names(repository.backend(), "data/"), names(remote(), "data/"));
            assertEquals(names(repository.backend(), "index/"), names(remote(), "index/"));
        }
    }

    @Test
    void localDamageDeletesNothing() throws Exception {
        Fixtures.server(server(), random);
        try (Repository repository = local()) {
            backup(repository);
            backup(repository);
            Mirror.push(repository, remote(), () -> false, step -> {});
            Set<String> before = names(remote(), "");
            // A pack the index lists goes missing locally (a bad disk, a careless rm).
            String pack = names(repository.backend(), "data/").iterator().next();
            Files.delete(dir.resolve("repo").resolve(pack));
            repository.release();
            Mirror.Result result = Mirror.push(repository, remote(), () -> false, step -> {});
            assertEquals(0, result.filesDeleted());
            assertNotNull(result.held());
            assertTrue(result.held().contains("missing pack"), result.held());
            assertEquals(before, names(remote(), ""));
        }
    }

    @Test
    void aRolledBackDiskKeepsTheRemotesNewerBackups() throws Exception {
        Fixtures.server(server(), random);
        try (Repository repository = local()) {
            backup(repository);
            backup(repository);
        }
        // The host's snapshot of the disk, taken now.
        copy(dir.resolve("repo"), dir.resolve("disk-image"));
        List<Snapshot> lost;
        try (Repository repository = local()) {
            backup(repository);
            backup(repository);
            Mirror.push(repository, remote(), () -> false, step -> {});
            lost = repository.snapshots(new java.util.ArrayList<>()).subList(2, 4);
        }
        // The disk is rolled back, and the server carries on: a new backup, then a copy.
        Swap.deleteTree(dir.resolve("repo"));
        copy(dir.resolve("disk-image"), dir.resolve("repo"));
        try (Repository repository = local()) {
            backup(repository);
            Mirror.Result result = Mirror.push(repository, remote(), () -> false, step -> {});
            assertEquals(0, result.filesDeleted(), result.toString());
            assertNotNull(result.held());
            assertTrue(result.held().contains("2 backups"), result.held());
        }
        // The remote still holds the two backups the rolled-back disk lost, whole.
        try (Repository copy = Repository.open(remote(), dir.resolve("temp2"), false)) {
            Set<String> ids = new TreeSet<>();
            for (Snapshot snapshot : copy.snapshots(new java.util.ArrayList<>())) {
                ids.add(snapshot.id().hex());
            }
            for (Snapshot snapshot : lost) {
                assertTrue(ids.contains(snapshot.id().hex()));
            }
            Maintenance.Report report = new Maintenance(copy, () -> false).check(1.0, null, new Random());
            assertTrue(report.ok(), report.problems().toString());
        }
    }

    @Test
    void anEmptiedRepositoryDeletesNothing() throws Exception {
        Fixtures.server(server(), random);
        try (Repository repository = local()) {
            backup(repository);
            Mirror.push(repository, remote(), () -> false, step -> {});
            Set<String> before = names(remote(), "");
            for (String name : names(repository.backend(), "snapshots/")) {
                Files.delete(dir.resolve("repo").resolve(name));
            }
            Mirror.Result result = Mirror.push(repository, remote(), () -> false, step -> {});
            assertEquals(0, result.filesDeleted());
            assertTrue(result.held().contains("no backups"), result.held());
            assertEquals(before, names(remote(), ""));
        }
    }

    @Test
    void forgettingTheNewestByHandWaitsForANewerOne() throws Exception {
        Fixtures.server(server(), random);
        try (Repository repository = local()) {
            backup(repository);
            Snapshot newest = backup(repository);
            Mirror.push(repository, remote(), () -> false, step -> {});
            try (AutoCloseable lock = repository.lock(true)) {
                repository.deleteSnapshot(newest);
            }
            Mirror.Result result = Mirror.push(repository, remote(), () -> false, step -> {});
            assertTrue(names(remote(), "snapshots/").contains(Snapshot.fileName(newest.id())), "newer than any left here");
            assertNull(result.held(), "removed on purpose: no warning");
            backup(repository);
            Mirror.push(repository, remote(), () -> false, step -> {});
            assertEquals(names(repository.backend(), "snapshots/"), names(remote(), "snapshots/"));
        }
    }

    // The review's probe: a pack lost here is dropped from the index by the next backup, but
    // the remote's copy, the only good one, stays.
    @Test
    void probeDamageThenBackupThenCopy() throws Exception {
        Fixtures.server(server(), random);
        try (Repository repository = local()) {
            backup(repository);
            backup(repository);
            Mirror.push(repository, remote(), () -> false, step -> {});
            String pack = names(repository.backend(), "data/").iterator().next();
            Files.delete(dir.resolve("repo").resolve(pack));
            repository.release();
            backup(repository); // the scheduled backup that always runs before a copy
            Mirror.Result result = Mirror.push(repository, remote(), () -> false, step -> {});
            assertTrue(names(remote(), "data/").contains(pack), "the remote's only good copy of " + pack + " was deleted");
            assertNotNull(result.held());
            assertTrue(result.held().contains("lost"), result.held());
        }
    }

    @Test
    void aLostPackStaysRestorableFromTheRemoteThroughLaterCleanUps() throws Exception {
        Fixtures.server(server(), random);
        try (Repository repository = local()) {
            backup(repository);
            backup(repository);
            Mirror.push(repository, remote(), () -> false, step -> {});
            // A pack of file contents: the backups' structure is all still here, so clean-ups run.
            Set<co.xenastudios.xbackup.store.Hash> structure = new java.util.HashSet<>();
            repository.index().forEach(location -> {
                if (location.entry().type().isMetadata()) {
                    structure.add(location.pack());
                }
            });
            String pack = names(repository.backend(), "data/").stream()
                .filter(name -> !structure.contains(co.xenastudios.xbackup.store.Pack.idOf(name))).findFirst().orElseThrow();
            Files.delete(dir.resolve("repo").resolve(pack));
            repository.release();
            for (int i = 0; i < 3; i++) {
                backup(repository);
                prune(repository, 3);
                Mirror.push(repository, remote(), () -> false, step -> {});
            }
            assertTrue(names(remote(), "data/").contains(pack));
            // Collected packs did leave the remote, while its index files were kept.
            Set<String> collected = new TreeSet<>();
            for (co.xenastudios.xbackup.store.Hash id : repository.collected()) {
                collected.add(co.xenastudios.xbackup.store.Pack.fileName(id));
            }
            assertTrue(!collected.isEmpty(), "the clean-ups collected something");
            for (String name : collected) {
                assertTrue(!names(remote(), "data/").contains(name), name + " was collected here but kept there");
            }
            // A restore from the remote reads past the index entries for packs it no longer has.
            Path cache = dir.resolve("cache");
            try (Repository cached = Fetch.metadata(remote(), cache, step -> {})) {
                List<Snapshot> snapshots = cached.snapshots(new java.util.ArrayList<>());
                Snapshot latest = snapshots.get(snapshots.size() - 1);
                List<co.xenastudios.xbackup.restore.Restore.Item> items =
                    co.xenastudios.xbackup.restore.Restore.expand(cached, latest, List.of("world"), Set.of());
                Fetch.data(remote(), cached, items, step -> {});
                Path out = dir.resolve("out");
                Files.createDirectories(out);
                co.xenastudios.xbackup.restore.Restore.write(cached, items, out, out, false, Throttle.NONE, () -> false,
                    step -> {});
                assertTrue(Files.exists(out.resolve("world/level.dat")));
            }
        }
    }

    @Test
    void onlyPacksCollectedHereLeaveTheRemote() throws Exception {
        Fixtures.server(server(), random);
        try (Repository repository = local()) {
            backup(repository);
            backup(repository);
            Mirror.push(repository, remote(), () -> false, step -> {});
            // A pack on the remote this repository never had, and never collected.
            Files.createDirectories(dir.resolve("remote/data/ff"));
            Files.write(dir.resolve("remote/data/ff").resolve("ff" + "0".repeat(62)), new byte[] {1});
        }
        try (Repository repository = local()) {
            prune(repository, 1);
            Mirror.push(repository, remote(), () -> false, step -> {});
            assertTrue(names(remote(), "data/").contains("data/ff/ff" + "0".repeat(62)));
            Set<String> expected = new TreeSet<>(names(repository.backend(), "data/"));
            expected.add("data/ff/ff" + "0".repeat(62));
            assertEquals(expected, names(remote(), "data/"));
        }
    }

    @Test
    void tidyingRemovesWhatOnlyTheRemoteHasOnceConfirmed() throws Exception {
        Fixtures.server(server(), random);
        try (Repository repository = local()) {
            backup(repository);
            backup(repository);
        }
        copy(dir.resolve("repo"), dir.resolve("disk-image"));
        try (Repository repository = local()) {
            backup(repository);
            backup(repository);
            Mirror.push(repository, remote(), () -> false, step -> {});
        }
        Swap.deleteTree(dir.resolve("repo"));
        copy(dir.resolve("disk-image"), dir.resolve("repo"));
        try (Repository repository = local()) {
            backup(repository);
            assertNotNull(Mirror.push(repository, remote(), () -> false, step -> {}).held());
            Set<String> before = names(remote(), "");
            Maintenance.Tidy look = Mirror.tidy(repository, remote(), false, () -> false, step -> {});
            assertEquals(2, look.extra().size());
            assertTrue(look.packs() > 0, look.toString());
            assertEquals(before, names(remote(), ""), "looking changes nothing");
            Mirror.tidy(repository, remote(), true, () -> false, step -> {});
            assertEquals(names(repository.backend(), "snapshots/"), names(remote(), "snapshots/"));
            assertEquals(names(repository.backend(), "data/"), names(remote(), "data/"));
            assertNull(Mirror.push(repository, remote(), () -> false, step -> {}).held());
        }
        try (Repository copy = Repository.open(remote(), dir.resolve("temp2"), false)) {
            Maintenance.Report report = new Maintenance(copy, () -> false).check(1.0, null, new Random());
            assertTrue(report.ok(), report.problems().toString());
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(folder).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.getFileName().toString().equals("lock")) {
                    Files.copy(file, to.resolve(from.relativize(file).toString()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
