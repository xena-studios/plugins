package co.xenastudios.xbackup.restore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xbackup.Fixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The swap that puts a staged restore in place: stopped or failing at every step. */
class SwapTest {

    @TempDir
    Path dir;

    private Path root;
    private Path restore;

    /** The process dying: nothing after it runs, no clean-up either. */
    static final class Crash extends Error {
        Crash() {
            super("crash", null, false, false);
        }
    }

    @AfterEach
    void unhook() {
        Swap.beforeStep = null;
    }

    private static final List<Swap.Entry> ENTRIES = List.of(new Swap.Entry("world", false),
        new Swap.Entry("world_nether", false), new Swap.Entry("plugins/Shop/data.db", true),
        new Swap.Entry("plugins/New", false));

    // A server and a staged restore of four paths: two worlds, a database whose old journal
    // files must be set aside, and a folder that didn't exist before.
    private void setUp(int run) throws IOException {
        Path base = dir.resolve("run-" + run);
        root = base.resolve("server");
        restore = root.resolve("plugins/xBackup/restore");
        Random random = new Random(run);
        Fixtures.bytes(root.resolve("world/level.dat"), random, 2000);
        Fixtures.bytes(root.resolve("world/region/r.0.0.mca"), random, 9000);
        Fixtures.bytes(root.resolve("world_nether/level.dat"), random, 1000);
        Fixtures.bytes(root.resolve("plugins/Shop/data.db"), random, 4096);
        Fixtures.bytes(root.resolve("plugins/Shop/data.db-wal"), random, 512);
        Files.writeString(root.resolve("plugins/Shop/config.yml"), "kept: true\n");
        Path staged = restore.resolve(Swap.STAGED);
        Fixtures.bytes(staged.resolve("world/level.dat"), random, 2100);
        Fixtures.bytes(staged.resolve("world/region/r.0.0.mca"), random, 8000);
        Fixtures.bytes(staged.resolve("world_nether/level.dat"), random, 900);
        Fixtures.bytes(staged.resolve("plugins/Shop/data.db"), random, 4096);
        Fixtures.bytes(staged.resolve("plugins/New/a.yml"), random, 100);
        Swap.schedule(restore, "ab".repeat(32), ENTRIES);
    }

    private Map<String, byte[]> server() throws IOException {
        return Fixtures.contents(root, path -> !path.startsWith("plugins/xBackup/"));
    }

    private Map<String, byte[]> expectedAfter() throws IOException {
        Map<String, byte[]> after = new TreeMap<>(server());
        after.keySet().removeIf(path -> path.startsWith("world/") || path.startsWith("world_nether/")
            || path.startsWith("plugins/Shop/data.db"));
        after.putAll(prefixed(Fixtures.contents(restore.resolve(Swap.STAGED), path -> true)));
        return after;
    }

    private static Map<String, byte[]> prefixed(Map<String, byte[]> files) {
        return new TreeMap<>(files);
    }

    private int steps() throws IOException {
        setUp(0);
        AtomicInteger count = new AtomicInteger();
        Swap.beforeStep = step -> count.incrementAndGet();
        Swap.apply(restore, root, line -> {});
        Swap.beforeStep = null;
        return count.get();
    }

    private static void assertSameFiles(Map<String, byte[]> expected, Map<String, byte[]> actual, String when) {
        assertEquals(expected.keySet(), actual.keySet(), when);
        for (String path : expected.keySet()) {
            assertTrue(java.util.Arrays.equals(expected.get(path), actual.get(path)), when + ": " + path);
        }
    }

    @Test
    void aCrashAtAnyStepIsFinishedOnTheNextRun() throws IOException {
        int steps = steps();
        assertTrue(steps >= 8, "steps " + steps);
        for (int k = 0; k < steps; k++) {
            setUp(k + 1);
            Map<String, byte[]> before = server();
            Map<String, byte[]> after = expectedAfter();
            int crashAt = k;
            AtomicInteger count = new AtomicInteger();
            Swap.beforeStep = step -> {
                if (count.getAndIncrement() == crashAt) {
                    throw new Crash();
                }
            };
            assertThrows(Crash.class, () -> Swap.apply(restore, root, line -> {}));
            Swap.beforeStep = null;
            Path aside = Swap.apply(restore, root, line -> {});
            assertSameFiles(after, server(), "crash at step " + k);
            assertFalse(Files.exists(restore.resolve(Swap.PENDING)));
            // What was there before is all set aside, the database's journal with it.
            Map<String, byte[]> old = Fixtures.contents(aside, path -> true);
            for (String path : before.keySet()) {
                if (!path.equals("plugins/Shop/config.yml")) {
                    assertTrue(old.containsKey(path), "crash at step " + k + ": " + path + " wasn't set aside");
                }
            }
        }
    }

    @Test
    void aFailureAtAnyStepIsUndone() throws IOException {
        int steps = steps();
        for (int k = 0; k < steps; k++) {
            setUp(k + 1);
            Map<String, byte[]> before = server();
            int failAt = k;
            AtomicInteger count = new AtomicInteger();
            Swap.beforeStep = step -> {
                if (count.getAndIncrement() == failAt) {
                    throw new UncheckedIOException(new IOException("the file is in use"));
                }
            };
            Swap.FailedException e = assertThrows(Swap.FailedException.class, () -> Swap.apply(restore, root, line -> {}),
                "failure at step " + k);
            assertTrue(e.getMessage().contains("undone"), e.getMessage());
            Swap.beforeStep = null;
            assertSameFiles(before, server(), "failure at step " + k);
            assertFalse(Files.exists(restore.resolve(Swap.PENDING)), "the failed restore isn't tried again");
            assertTrue(Files.exists(restore.resolve(Swap.FAILED)));
            assertFalse(Files.exists(restore.resolve(Swap.STAGED)));
        }
    }

    @Test
    void aCrashWhileUndoingIsFinishedForwards() throws IOException {
        int steps = steps();
        for (int k = 1; k < steps - 1; k++) {
            setUp(k + 1);
            Map<String, byte[]> after = expectedAfter();
            int failAt = k;
            AtomicInteger count = new AtomicInteger();
            Swap.beforeStep = step -> {
                int n = count.getAndIncrement();
                if (n == failAt) {
                    throw new UncheckedIOException(new IOException("the file is in use"));
                }
                if (n == failAt + 2) {
                    throw new Crash();
                }
            };
            try {
                Swap.apply(restore, root, line -> {});
            } catch (Crash | Swap.FailedException e) {
                // Either the undo was cut short, or it finished before the second fault.
            }
            Swap.beforeStep = null;
            if (Files.exists(restore.resolve(Swap.PENDING))) {
                Swap.apply(restore, root, line -> {});
                assertSameFiles(after, server(), "crash while undoing a failure at step " + k);
            }
        }
    }

    @Test
    void aMissingStagedCopyChangesNothing() throws IOException {
        setUp(99);
        Map<String, byte[]> before = server();
        Swap.deleteTree(restore.resolve(Swap.STAGED).resolve("world_nether"));
        IOException e = assertThrows(IOException.class, () -> Swap.apply(restore, root, line -> {}));
        assertTrue(e.getMessage().contains("Nothing was changed"), e.getMessage());
        assertSameFiles(before, server(), "missing staged copy");
        assertTrue(Files.exists(restore.resolve(Swap.PENDING)));
    }

    @Test
    void aWorldBehindALinkIsRestoredWhereTheLinkLeads() throws IOException {
        setUp(100);
        Path elsewhere = dir.resolve("other-disk/world");
        Files.createDirectories(elsewhere.getParent());
        Files.move(root.resolve("world"), elsewhere);
        Files.createSymbolicLink(root.resolve("world"), elsewhere);
        byte[] restored = Files.readAllBytes(restore.resolve(Swap.STAGED).resolve("world/level.dat"));
        Swap.apply(restore, root, line -> {});
        assertTrue(Files.isSymbolicLink(root.resolve("world")), "the link is kept");
        assertTrue(java.util.Arrays.equals(restored, Files.readAllBytes(elsewhere.resolve("level.dat"))));
        List<String> beside;
        try (var files = Files.list(dir.resolve("other-disk"))) {
            beside = files.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertEquals(2, beside.size(), beside.toString());
        assertTrue(beside.get(1).startsWith("world.replaced-"), "the old world is beside it: " + beside);
    }
}
