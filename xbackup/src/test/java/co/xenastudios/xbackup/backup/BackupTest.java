package co.xenastudios.xbackup.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xbackup.Fixtures;
import co.xenastudios.xbackup.restore.Restore;
import co.xenastudios.xbackup.store.LocalBackend;
import co.xenastudios.xbackup.store.Maintenance;
import co.xenastudios.xbackup.store.Repository;
import co.xenastudios.xbackup.store.Retention;
import co.xenastudios.xbackup.store.Session;
import co.xenastudios.xbackup.store.Snapshot;
import co.xenastudios.xbackup.store.Throttle;
import co.xenastudios.xbackup.store.Tree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Backups of a fake server folder, end to end, and restores compared byte for byte. */
class BackupTest {

    @TempDir
    Path dir;

    static final List<String> EXCLUDES = List.of("**/session.lock", "logs/**");
    static final Predicate<String> COMPARED = path -> !path.endsWith("session.lock") && !path.startsWith("logs/");

    Path server() {
        return dir.resolve("server");
    }

    Repository repository() throws IOException {
        return Repository.open(new LocalBackend(dir.resolve("repo")), dir.resolve("temp"), true);
    }

    Backup.Plan plan(String reason) {
        return new Backup.Plan(server(), List.of("world", "world_nether"), List.of("plugins", "server.properties",
            "bukkit.yml"), EXCLUDES, Set.of(dir.resolve("repo")), Map.of(), reason, "test", dir.resolve("temp"),
            Throttle.NONE, Session.Options.DEFAULTS);
    }

    Backup.Result backup(Repository repository, Backup.Pause pause) throws Exception {
        try (AutoCloseable lock = repository.lock(true)) {
            return Backup.run(repository, plan("test"), pause, Databases.sqlite(), () -> false, step -> {});
        }
    }

    Map<String, byte[]> restore(Repository repository, Snapshot snapshot, String into) throws IOException {
        Path target = dir.resolve(into);
        List<Restore.Item> items = Restore.expand(repository, snapshot, List.of(""), Set.of());
        Restore.write(repository, items, target, Throttle.NONE, () -> false, step -> {});
        return Fixtures.contents(target, COMPARED);
    }

    static void assertSame(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String path : expected.keySet()) {
            assertArrayEquals(expected.get(path), actual.get(path), path);
        }
    }

    @Test
    void restoreGivesBackEveryByte() throws Exception {
        Fixtures.server(server(), new Random(10));
        Map<String, byte[]> before = Fixtures.contents(server(), COMPARED);
        try (Repository repository = repository()) {
            Backup.Result result = backup(repository, Backup.Pause.NONE);
            assertTrue(result.snapshot().files() > 10);
            assertSame(before, restore(repository, result.snapshot(), "restored"));
            Maintenance.Report report = new Maintenance(repository, () -> false).check(1.0, null, new Random());
            assertTrue(report.ok(), report.problems().toString());
        }
        // Region files came back chunk by chunk, not as whole files.
        assertTrue(Files.exists(dir.resolve("restored/world/dimensions/minecraft/overworld/region/r.0.0.mca")));
    }

    @Test
    void theNextBackupReadsOnlyChangedChunks() throws Exception {
        Random random = new Random(11);
        Fixtures.server(server(), random);
        try (Repository repository = repository()) {
            backup(repository, Backup.Pause.NONE);
            Path file = server().resolve("world/dimensions/minecraft/overworld/region/r.1.0.mca");
            Fixtures.Region region = readRegion(file);
            // Stamped in the past, as the server stamps them: a chunk stamped at or after the
            // second the last read started is read again, to be sure.
            Fixtures.Region.rewrite(file, Set.copyOf(region.payloads.keySet().stream().limit(3).toList()), random,
                1_750_000_000);
            Backup.Result second = backup(repository, Backup.Pause.NONE);
            assertEquals(3, second.chunksRead());
            // Three chunks of ~5 KB, the region's layout and a few trees: far less than a region.
            assertTrue(second.snapshot().added() < 200_000, "added " + second.snapshot().added());
            assertSame(Fixtures.contents(server(), COMPARED), restore(repository, second.snapshot(), "restored"));
        }
    }

    // Reads a fixture region back into the helper, to change and rewrite it.
    static Fixtures.Region readRegion(Path file) throws IOException {
        Fixtures.Region region = new Fixtures.Region();
        try (var channel = java.nio.channels.FileChannel.open(file)) {
            RegionFiles.Header header = RegionFiles.header(channel);
            for (int i = 0; i < 1024; i++) {
                if (header.location()[i] != 0) {
                    byte[] record = RegionFiles.record(channel, header.location()[i], channel.size());
                    region.payloads.put(i, java.util.Arrays.copyOfRange(record, 5, record.length));
                    region.timestamps[i] = header.timestamp()[i];
                }
            }
        }
        return region;
    }

    @Test
    void theSnapshotIsTheMomentOfThePause() throws Exception {
        Random random = new Random(12);
        Fixtures.server(server(), random);
        Path file = server().resolve("world/dimensions/minecraft/overworld/region/r.0.0.mca");
        Map<String, byte[]>[] atPause = new Map[1];
        Backup.Pause pause = new Backup.Pause() {
            @Override
            public long pause() throws Exception {
                // The flush writes chunks changed since the first pass.
                Fixtures.Region.rewrite(file, Set.of(readRegion(file).payloads.firstKey()), random, 1_800_000_001);
                atPause[0] = Fixtures.contents(server().resolve("world"), COMPARED);
                return 5;
            }

            @Override
            public void resume() {
                // Autosave back on: the server writes again, after the moment of the snapshot.
                try {
                    Fixtures.Region.rewrite(file, Set.of(readRegion(file).payloads.lastKey()), random, 1_800_000_002);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        try (Repository repository = repository()) {
            Backup.Result result = backup(repository, pause);
            assertEquals(5, result.snapshot().pauseMillis());
            Map<String, byte[]> restored = restore(repository, result.snapshot(), "restored");
            Map<String, byte[]> world = new java.util.TreeMap<>();
            restored.forEach((path, bytes) -> {
                if (path.startsWith("world/")) {
                    world.put(path.substring(6), bytes);
                }
            });
            assertSame(atPause[0], world);
        }
    }

    @Test
    void databasesAreCopiedBySqliteNotByBytes() throws Exception {
        Fixtures.server(server(), new Random(13));
        Path db = server().resolve("plugins/Other/data.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("CREATE TABLE t (n INTEGER)");
                for (int i = 0; i < 1000; i++) {
                    statement.execute("INSERT INTO t VALUES (" + i + ")");
                }
            }
            // The connection stays open, so the rows are still in the -wal file.
            assertTrue(Files.exists(server().resolve("plugins/Other/data.db-wal")));
            try (Repository repository = repository()) {
                Backup.Result result = backup(repository, Backup.Pause.NONE);
                Tree.Node node = Restore.find(repository, result.snapshot(), "plugins/Other/data.db");
                assertNotNull(node);
                assertEquals(Tree.Kind.SQLITE, node.kind());
                assertTrue(Restore.find(repository, result.snapshot(), "plugins/Other/data.db-wal") == null);
                restore(repository, result.snapshot(), "restored");
            }
        }
        Path restored = dir.resolve("restored/plugins/Other/data.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + restored);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*), SUM(n) FROM t")) {
            assertTrue(rows.next());
            assertEquals(1000, rows.getInt(1));
            assertEquals(499_500, rows.getInt(2));
        }
    }

    @Test
    void forgettingAndCollectingKeepsWhatsLeftWhole() throws Exception {
        Random random = new Random(14);
        Fixtures.server(server(), random);
        List<Snapshot> snapshots = new ArrayList<>();
        List<Map<String, byte[]>> states = new ArrayList<>();
        try (Repository repository = repository()) {
            for (int round = 0; round < 6; round++) {
                Path file = server().resolve("world/dimensions/minecraft/overworld/region/r." + (round % 3) + ".0.mca");
                Fixtures.Region.rewrite(file, Set.copyOf(readRegion(file).payloads.keySet().stream().limit(10).toList()),
                    random, 1_800_000_000 + round);
                Fixtures.bytes(server().resolve("plugins/Other/round.bin"), random, 300_000);
                snapshots.add(backup(repository, Backup.Pause.NONE).snapshot());
                states.add(Fixtures.contents(server(), COMPARED));
            }
            Maintenance maintenance = new Maintenance(repository, () -> false);
            try (AutoCloseable lock = repository.lock(true)) {
                Maintenance.Forgotten forgotten = maintenance.forget(new Retention(3, 0, 0, 0, 0), 0, ZoneOffset.UTC);
                assertEquals(3, forgotten.removed().size());
                Maintenance.Collected collected = maintenance.collect(Session.Options.DEFAULTS, 0.3);
                assertTrue(collected.bytesFreed() > 0, collected.toString());
            }
            repository.release();
            Maintenance.Report report = maintenance.check(1.0, null, new Random());
            assertTrue(report.ok(), report.problems().toString());
            for (int i = 3; i < 6; i++) {
                assertSame(states.get(i), restore(repository, snapshots.get(i), "restored-" + i));
            }
        }
    }

    @Test
    void collectingFreesSpaceWhenTheDiskIsFull() throws Exception {
        Random random = new Random(24);
        Fixtures.server(server(), random);
        List<Snapshot> snapshots = new ArrayList<>();
        List<Map<String, byte[]>> states = new ArrayList<>();
        try (Repository repository = repository()) {
            for (int round = 0; round < 4; round++) {
                Fixtures.bytes(server().resolve("plugins/Other/round.bin"), random, 2_000_000);
                snapshots.add(backup(repository, Backup.Pause.NONE).snapshot());
                states.add(Fixtures.contents(server(), COMPARED));
            }
            Maintenance maintenance = new Maintenance(repository, () -> false);
            try (AutoCloseable lock = repository.lock(true)) {
                maintenance.forget(new Retention(1, 0, 0, 0, 0), 0, ZoneOffset.UTC);
                // No room for even one new pack: repacking can't run, but the unused packs still go.
                Session.Options full = new Session.Options(6, 16L << 20, Long.MAX_VALUE / 4);
                Maintenance.Collected collected = maintenance.collect(full, 0.3);
                assertTrue(collected.bytesFreed() > 4_000_000, collected.toString());
                assertEquals(0, collected.packsRepacked());
            }
            repository.release();
            Maintenance.Report report = maintenance.check(1.0, null, new Random());
            assertTrue(report.ok(), report.problems().toString());
            assertSame(states.get(3), restore(repository, snapshots.get(3), "restored"));
        }
    }

    @Test
    void sourcesBehindLinksAreBackedUpAsTheFoldersTheyAre() throws Exception {
        Fixtures.server(server(), new Random(25));
        // The nether on another disk, the plugins folder too, with xBackup's repository inside
        // it (named by its real place, as an owner might), and a plugin linking to a folder
        // outside the server.
        Path nether = dir.resolve("disk2/world_nether");
        Files.createDirectories(nether.getParent());
        Files.move(server().resolve("world_nether"), nether);
        Files.createSymbolicLink(server().resolve("world_nether"), nether);
        Path plugins = dir.resolve("disk3/plugins");
        Files.createDirectories(plugins.getParent());
        Files.move(server().resolve("plugins"), plugins);
        Files.createSymbolicLink(server().resolve("plugins"), plugins);
        Fixtures.bytes(plugins.resolve("xBackup/repository/data/junk"), new Random(1), 1000);
        Files.createDirectories(dir.resolve("shared"));
        Files.writeString(dir.resolve("shared/a.yml"), "a: 1\n");
        Files.createSymbolicLink(plugins.resolve("Shared"), dir.resolve("shared"));
        Map<String, byte[]> before = new java.util.TreeMap<>(Fixtures.contents(server(),
            COMPARED.and(path -> !path.equals("plugins") && !path.equals("world_nether"))));
        Fixtures.contents(nether, path -> true).forEach((path, bytes) -> before.put("world_nether/" + path, bytes));
        Fixtures.contents(plugins, path -> !path.startsWith("xBackup/") && !path.equals("Shared"))
            .forEach((path, bytes) -> before.put("plugins/" + path, bytes));

        Backup.Plan plan = new Backup.Plan(server(), List.of("world", "world_nether"), List.of("plugins",
            "server.properties", "bukkit.yml"), EXCLUDES, Set.of(dir.resolve("repo"), plugins.resolve("xBackup/repository")),
            Map.of(), "test", "test", dir.resolve("temp"), Throttle.NONE, Session.Options.DEFAULTS);
        try (Repository repository = repository(); AutoCloseable lock = repository.lock(true)) {
            Snapshot snapshot = Backup.run(repository, plan, Backup.Pause.NONE, Databases.sqlite(), () -> false, step -> {})
                .snapshot();
            Tree top = repository.tree(snapshot.tree());
            assertEquals(Tree.Kind.DIR, top.get("world_nether").kind());
            assertEquals(Tree.Kind.DIR, top.get("plugins").kind());
            assertTrue(snapshot.warnings().stream().anyMatch(w -> w.contains("plugins/Shared") && w.contains("outside")),
                snapshot.warnings().toString());
            Path restored = dir.resolve("restored");
            Restore.write(repository, Restore.expand(repository, snapshot, List.of(""), Set.of()), restored, restored, true,
                Throttle.NONE, () -> false, step -> {});
            assertFalse(Files.isSymbolicLink(restored.resolve("world_nether")));
            assertTrue(Files.isSymbolicLink(restored.resolve("plugins/Shared")), "a link inside a source stays a link");
            assertFalse(Files.exists(restored.resolve("plugins/xBackup/repository")), "the repository was backed up");
            assertSame(before, Fixtures.contents(restored, COMPARED.and(path -> !path.equals("plugins/Shared"))));
        }
    }

    @Test
    void aQuietMomentThatFailsOrHangsOnlyWarns() throws Exception {
        Fixtures.server(server(), new Random(26));
        Files.createDirectories(server().resolve("plugins/Hangs"));
        Files.writeString(server().resolve("plugins/Hangs/data.yml"), "x: 1\n");
        java.util.concurrent.atomic.AtomicInteger resumed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch never = new java.util.concurrent.CountDownLatch(1);
        Map<String, Backup.Quiet> quiet = new java.util.LinkedHashMap<>();
        quiet.put("plugins/Other", new Backup.Quiet() {
            @Override
            public void pause() {
                throw new IllegalStateException("busy saving");
            }

            @Override
            public void resume() {
                resumed.incrementAndGet();
            }
        });
        quiet.put("plugins/Hangs", new Backup.Quiet() {
            @Override
            public void pause() throws Exception {
                never.await();
            }

            @Override
            public void resume() {
                resumed.incrementAndGet();
            }
        });
        Backup.Plan plan = new Backup.Plan(server(), List.of("world"), List.of("plugins"), EXCLUDES, Set.of(dir.resolve("repo")),
            quiet, "test", "test", dir.resolve("temp"), Throttle.NONE, Session.Options.DEFAULTS);
        long timeout = Backup.quietTimeoutMillis;
        Backup.quietTimeoutMillis = 300;
        try (Repository repository = repository(); AutoCloseable lock = repository.lock(true)) {
            Snapshot snapshot = Backup.run(repository, plan, Backup.Pause.NONE, Databases.sqlite(), () -> false, step -> {})
                .snapshot();
            List<String> warnings = snapshot.warnings();
            assertTrue(warnings.stream().anyMatch(w -> w.contains("plugins/Other") && w.contains("busy saving")), warnings.toString());
            assertTrue(warnings.stream().anyMatch(w -> w.contains("plugins/Hangs") && w.contains("didn't pause")), warnings.toString());
            Tree plugins = repository.tree(repository.tree(snapshot.tree()).get("plugins").ref());
            assertNotNull(plugins.get("Other"), "read anyway");
            assertNotNull(plugins.get("Hangs"), "read anyway");
            assertEquals(1, resumed.get(), "resumed after the failed pause; the hung one not yet");
            never.countDown();
            for (int i = 0; i < 100 && resumed.get() < 2; i++) {
                Thread.sleep(20);
            }
            assertEquals(2, resumed.get(), "resumed once its pause returned");
        } finally {
            Backup.quietTimeoutMillis = timeout;
        }
    }

    @Test
    void aChunkRewrittenInTheSameSecondIsReadAgain() throws Exception {
        Fixtures.server(server(), new Random(27));
        Path file = server().resolve("world/dimensions/minecraft/overworld/region/r.0.0.mca");
        try (Repository repository = repository()) {
            Snapshot first = backup(repository, Backup.Pause.NONE).snapshot();
            // The server writes a chunk again within the second that backup started, back into
            // the same sectors (it alternates slots), so its place and its timestamp match.
            int chunk = readRegion(file).payloads.firstKey();
            byte[] bytes = Files.readAllBytes(file);
            java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(bytes);
            int location = header.getInt(chunk * 4);
            int start = (location >>> 8) * 4096;
            int length = header.getInt(start);
            for (int i = start + 5; i < start + 4 + length; i++) {
                bytes[i] ^= 0x5A;
            }
            header.putInt(4096 + chunk * 4, (int) first.time().getEpochSecond());
            Files.write(file, bytes);
            Backup.Result second = backup(repository, Backup.Pause.NONE);
            assertTrue(second.chunksRead() >= 1, "the chunk wasn't read again");
            assertSame(Fixtures.contents(server(), COMPARED), restore(repository, second.snapshot(), "restored"));
        }
    }

    @Test
    void aMissingPackIsStoredAgainNotPointedAt() throws Exception {
        Fixtures.server(server(), new Random(28));
        try (Repository repository = repository()) {
            backup(repository, Backup.Pause.NONE);
            // A pack disappears (a bad disk, a careless clean-up); the index still lists it.
            Path pack;
            try (var walk = Files.walk(dir.resolve("repo/data"))) {
                pack = walk.filter(Files::isRegularFile).max(java.util.Comparator.comparingLong(p -> p.toFile().length()))
                    .orElseThrow();
            }
            Files.delete(pack);
            repository.release();
            Snapshot second = backup(repository, Backup.Pause.NONE).snapshot();
            assertTrue(second.warnings().stream().anyMatch(w -> w.contains("missing")), second.warnings().toString());
            assertSame(Fixtures.contents(server(), COMPARED), restore(repository, second, "restored"));
        }
    }

    @Test
    void anIndexThatInflatesHugelyIsRefused() throws Exception {
        java.io.ByteArrayOutputStream bomb = new java.io.ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream out = new java.util.zip.DeflaterOutputStream(bomb)) {
            out.write(new byte[64 << 20]);
        }
        assertTrue(bomb.size() < 1 << 20);
        Files.createDirectories(dir.resolve("repo/index"));
        try (Repository repository = repository()) {
            byte[] bytes = bomb.toByteArray();
            Files.write(dir.resolve("repo/index").resolve(co.xenastudios.xbackup.store.Hash.sha256(bytes).hex()), bytes);
            java.io.IOException refused = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                repository::index);
            assertTrue(refused.getMessage().contains("inflates"), refused.getMessage());
        }
    }

    @Test
    void aDamagedRegionIsKeptWhole() throws Exception {
        Fixtures.server(server(), new Random(15));
        Path file = server().resolve("world/dimensions/minecraft/overworld/region/r.2.0.mca");
        // Point a chunk past the end of the file.
        try (var channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.allocate(4).putInt(0, 100_000 << 8 | 1), 40);
        }
        try (Repository repository = repository()) {
            Backup.Result result = backup(repository, Backup.Pause.NONE);
            Tree.Node node = Restore.find(repository, result.snapshot(),
                "world/dimensions/minecraft/overworld/region/r.2.0.mca");
            assertEquals(Tree.Kind.FILE, node.kind());
            assertSame(Fixtures.contents(server(), COMPARED), restore(repository, result.snapshot(), "restored"));
        }
    }

    @Test
    void dryRunSaysWhatWouldChange() throws Exception {
        Random random = new Random(16);
        Fixtures.server(server(), random);
        try (Repository repository = repository()) {
            Snapshot snapshot = backup(repository, Backup.Pause.NONE).snapshot();
            List<Restore.Item> items = Restore.expand(repository, snapshot, List.of("world"), Set.of());
            assertEquals(0, Restore.dryRun(repository, items, server()).changed());
            Path file = server().resolve("world/dimensions/minecraft/overworld/region/r.0.0.mca");
            Fixtures.Region.rewrite(file, Set.of(readRegion(file).payloads.firstKey()), random, 1_900_000_000);
            Files.writeString(server().resolve("world/new.txt"), "new");
            Restore.Plan plan = Restore.dryRun(repository, items, server());
            assertEquals(1, plan.changed(), plan.examples().toString());
            assertEquals(1, plan.changedChunks());
            assertEquals(1, plan.removed());
            assertFalse(plan.examples().isEmpty());
        }
    }
}
