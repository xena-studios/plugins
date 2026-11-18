package co.xenastudios.xbackup.backup;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real thing: another JVM backs up and collects garbage in a loop and is killed with
 * SIGKILL at random moments, mid-write. After each kill the repository is opened as the
 * next start would, checked in full, and its newest snapshot restored with every file
 * checked against its hash.
 */
class KillTest {

    @TempDir
    Path dir;

    @Test
    void killedMidWriteTheRepositoryStaysWhole() throws Exception {
        Random random = new Random(42);
        Path server = dir.resolve("server");
        Path repo = dir.resolve("repo");
        Fixtures.server(server, random);
        int snapshotsSeen = 0;
        for (int kill = 0; kill < 6; kill++) {
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Child.class.getName(), server.toString(), repo.toString(),
                Long.toString(kill))
                .redirectErrorStream(true).redirectOutput(dir.resolve("child-" + kill + ".log").toFile()).start();
            // Let it get going, then kill it somewhere in the middle of its work.
            Thread.sleep(700 + random.nextInt(1500));
            child.destroyForcibly();
            child.waitFor(30, TimeUnit.SECONDS);

            new LocalBackend(repo).clearTemp();
            try (Repository repository = Repository.open(new LocalBackend(repo), dir.resolve("temp"), false)) {
                Maintenance.Report report = new Maintenance(repository, () -> false).check(1.0, null, random);
                assertTrue(report.ok(), "after kill " + kill + ": " + report.problems());
                List<Snapshot> snapshots = repository.snapshots(new ArrayList<>());
                if (!snapshots.isEmpty()) {
                    Snapshot newest = snapshots.get(snapshots.size() - 1);
                    Path target = dir.resolve("restore-" + kill);
                    // Checks each file's hash as it writes.
                    Restore.write(repository, Restore.expand(repository, newest, List.of(""), Set.of()), target,
                        Throttle.NONE, () -> false, step -> {});
                    snapshotsSeen = Math.max(snapshotsSeen, snapshots.size());
                }
            }
        }
        assertTrue(snapshotsSeen > 0, "the child never finished a backup; see child-*.log");
    }

    /** Backs up, changes the server, collects garbage, forever, until killed. */
    public static final class Child {
        public static void main(String[] args) throws Exception {
            Path server = Path.of(args[0]);
            Path repo = Path.of(args[1]);
            Random random = new Random(Long.parseLong(args[2]));
            Path temp = repo.resolveSibling("temp");
            for (int round = 0; ; round++) {
                try (Repository repository = Repository.open(new LocalBackend(repo), temp, true);
                     AutoCloseable lock = repository.lock(true)) {
                    Backup.Plan plan = new Backup.Plan(server, List.of("world", "world_nether"),
                        List.of("plugins", "server.properties", "bukkit.yml"), BackupTest.EXCLUDES, Set.of(repo), Map.of(),
                        "kill", "test", temp, Throttle.NONE, new Session.Options(6, 128 << 10, 0));
                    Backup.Result result = Backup.run(repository, plan, Backup.Pause.NONE, Databases.sqlite(), () -> false,
                        step -> {});
                    System.out.println("snapshot " + result.snapshot().id().shortHex() + " " + lock);
                    if (round % 3 == 2) {
                        Maintenance maintenance = new Maintenance(repository, () -> false);
                        maintenance.forget(new Retention(3, 0, 0, 0, 0), 0, ZoneOffset.UTC);
                        maintenance.collect(new Session.Options(6, 128 << 10, 0), 0.2);
                        System.out.println("collected");
                    }
                }
                Fixtures.bytes(server.resolve("plugins/Other/r" + random.nextInt(4) + ".bin"), random,
                    100_000 + random.nextInt(900_000));
                Path region = server.resolve("world/dimensions/minecraft/overworld/region/r." + random.nextInt(3) + ".0.mca");
                List<Integer> chunks = new ArrayList<>(BackupTest.readRegion(region).payloads.keySet());
                Fixtures.Region.rewrite(region, Set.copyOf(chunks.subList(0, 1 + random.nextInt(chunks.size() - 1))),
                    random, 1_800_000_000 + round);
                Files.writeString(server.resolve("plugins/Other/config.yml"), "round: " + round + "\n");
            }
        }
    }
}
