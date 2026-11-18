package co.xenastudios.xbackup.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import co.xenastudios.xbackup.Fixtures;
import co.xenastudios.xbackup.restore.Restore;
import co.xenastudios.xbackup.store.Backend;
import co.xenastudios.xbackup.store.Hash;
import co.xenastudios.xbackup.store.LocalBackend;
import co.xenastudios.xbackup.store.Maintenance;
import co.xenastudios.xbackup.store.Repository;
import co.xenastudios.xbackup.store.Retention;
import co.xenastudios.xbackup.store.Session;
import co.xenastudios.xbackup.store.Snapshot;
import co.xenastudios.xbackup.store.Throttle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Kill-partway fuzzing: backups, garbage collection and index rebuilds die at a random write,
 * with no clean-up allowed to run, over and over. After every death the repository must
 * still check clean, every snapshot that exists must restore to exactly what it saved, and
 * the next backup must work.
 */
class CrashTest {

    @TempDir
    Path dir;

    @Test
    void anyCrashLeavesEverySnapshotWhole() throws Exception {
        for (long seed = 1; seed <= 4; seed++) {
            fuzz(seed, 40);
        }
    }

    private void fuzz(long seed, int rounds) throws Exception {
        Random random = new Random(seed);
        Path base = dir.resolve("run-" + seed);
        Path server = base.resolve("server");
        Path repoFolder = base.resolve("repo");
        Fixtures.server(server, random);
        Map<Hash, Map<String, byte[]>> saved = new HashMap<>();
        int deaths = 0;
        for (int round = 0; round < rounds; round++) {
            mutate(server, random, round);
            LocalBackend local = new LocalBackend(repoFolder);
            boolean dies = random.nextInt(3) != 0;
            Fixtures.Dying dying = new Fixtures.Dying(local, dies ? random.nextInt(6) : Integer.MAX_VALUE);
            int op = random.nextInt(10);
            Map<String, byte[]> before = Fixtures.contents(server, BackupTest.COMPARED);
            try {
                Repository repository = Repository.open(dying, base.resolve("temp"), true);
                if (op < 7) {
                    Backup.Result result = Backup.run(repository, plan(server, repoFolder, base), Backup.Pause.NONE,
                        Databases.sqlite(), () -> false, step -> {});
                    saved.put(result.snapshot().id(), before);
                } else if (op < 9) {
                    Maintenance maintenance = new Maintenance(repository, () -> false);
                    maintenance.forget(new Retention(2 + random.nextInt(3), 0, 0, 0, 0), 0, ZoneOffset.UTC);
                    maintenance.collect(new Session.Options(6, 64 << 10, 0), 0.2);
                } else {
                    new Maintenance(repository, () -> false).rebuildIndex();
                }
            } catch (IOException e) {
                if (!dying.dead) {
                    throw e;
                }
                deaths++;
            }
            // "Restart": a fresh repository on the plain folder, as the next start would open.
            local.clearTemp();
            try (Repository repository = Repository.open(new LocalBackend(repoFolder), base.resolve("temp"), true)) {
                List<String> problems = new ArrayList<>();
                List<Snapshot> snapshots = repository.snapshots(problems);
                assertTrue(problems.isEmpty(), problems.toString());
                Maintenance.Report report = new Maintenance(repository, () -> false).check(1.0, null, random);
                assertTrue(report.ok(), "seed " + seed + " round " + round + ": " + report.problems());
                for (Snapshot snapshot : snapshots) {
                    if (!saved.containsKey(snapshot.id())) {
                        fail("seed " + seed + " round " + round + ": snapshot " + snapshot.id().shortHex()
                            + " exists but its backup never finished");
                    }
                }
                // Restore the newest and one at random, byte for byte.
                if (!snapshots.isEmpty()) {
                    check(repository, snapshots.get(snapshots.size() - 1), saved, base.resolve("restore-" + round + "a"));
                    check(repository, snapshots.get(random.nextInt(snapshots.size())), saved,
                        base.resolve("restore-" + round + "b"));
                }
            }
        }
        assertTrue(deaths >= 8, "only " + deaths + " deaths in seed " + seed);
    }

    private static void check(Repository repository, Snapshot snapshot, Map<Hash, Map<String, byte[]>> saved, Path target)
        throws IOException {
        List<Restore.Item> items = Restore.expand(repository, snapshot, List.of(""), Set.of());
        Restore.write(repository, items, target, Throttle.NONE, () -> false, step -> {});
        Map<String, byte[]> expected = saved.get(snapshot.id());
        Map<String, byte[]> actual = Fixtures.contents(target, BackupTest.COMPARED);
        assertEquals(expected.keySet(), actual.keySet());
        for (String path : expected.keySet()) {
            assertArrayEquals(expected.get(path), actual.get(path), path);
        }
        co.xenastudios.xbackup.restore.Swap.deleteTree(target);
    }

    private static Backup.Plan plan(Path server, Path repo, Path base) {
        // Small packs, so a backup writes many files and dies in many different places.
        return new Backup.Plan(server, List.of("world", "world_nether"), List.of("plugins", "server.properties", "bukkit.yml"),
            BackupTest.EXCLUDES, Set.of(repo), Map.of(), "fuzz", "test", base.resolve("temp"), Throttle.NONE,
            new Session.Options(6, 64 << 10, 0));
    }

    private static void mutate(Path server, Random random, int round) throws IOException {
        int region = random.nextInt(3);
        Path file = server.resolve("world/dimensions/minecraft/overworld/region/r." + region + ".0.mca");
        Fixtures.Region current = BackupTest.readRegion(file);
        List<Integer> chunks = new ArrayList<>(current.payloads.keySet());
        java.util.Collections.shuffle(chunks, random);
        Fixtures.Region.rewrite(file, Set.copyOf(chunks.subList(0, 1 + random.nextInt(8))), random,
            1_800_000_000 + round);
        switch (random.nextInt(4)) {
            case 0 -> Fixtures.bytes(server.resolve("plugins/Other/r" + random.nextInt(5) + ".bin"), random,
                random.nextInt(400_000));
            case 1 -> Files.writeString(server.resolve("plugins/Other/config.yml"), "a: " + random.nextInt() + "\n");
            case 2 -> {
                try (var files = Files.list(server.resolve("plugins/Other"))) {
                    List<Path> list = files.filter(p -> p.getFileName().toString().startsWith("r")).toList();
                    if (!list.isEmpty()) {
                        Files.delete(list.get(random.nextInt(list.size())));
                    }
                }
            }
            default -> Fixtures.bytes(server.resolve("world/playerdata/p" + random.nextInt(3) + ".dat"), random, 2000);
        }
    }

    /** Backends in tests: a sanity check that the dying wrapper really stops everything. */
    @Test
    void aDeadBackendStaysDead() throws IOException {
        Fixtures.Dying dying = new Fixtures.Dying(new LocalBackend(dir.resolve("d")), 1);
        dying.put("a", new byte[1]);
        try {
            dying.put("b", new byte[1]);
            fail("should have died");
        } catch (IOException expected) {
            // Dead.
        }
        try {
            dying.list("");
            fail("should stay dead");
        } catch (IOException expected) {
            // Still dead.
        }
        Backend inner = dying.inner;
        assertEquals(1, inner.list("").size());
    }
}
