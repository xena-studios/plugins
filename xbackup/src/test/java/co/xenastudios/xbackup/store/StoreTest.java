package co.xenastudios.xbackup.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xbackup.Fixtures;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The repository format and its pieces, without a server. */
class StoreTest {

    @TempDir
    Path dir;

    private Repository open() throws IOException {
        LocalBackend backend = new LocalBackend(dir.resolve("repo"));
        return Repository.open(backend, dir.resolve("temp"), true);
    }

    @Test
    void blobsRoundTripAndAreStoredOnce() throws IOException {
        Random random = new Random(1);
        List<byte[]> blobs = new ArrayList<>();
        List<Hash> ids = new ArrayList<>();
        try (Repository repository = open(); Session session = repository.session(Session.Options.DEFAULTS)) {
            for (int i = 0; i < 300; i++) {
                byte[] data = new byte[random.nextInt(50_000)];
                if (i % 2 == 0) {
                    random.nextBytes(data);
                } else {
                    java.util.Arrays.fill(data, (byte) i);
                }
                blobs.add(data);
                ids.add(session.store(BlobType.DATA, data, true));
            }
            long added = session.addedBlobs();
            // The same content again costs nothing.
            for (byte[] data : blobs) {
                session.store(BlobType.DATA, data, true);
            }
            assertEquals(added, session.addedBlobs());
            session.flush();
        }
        try (Repository repository = open()) {
            for (int i = 0; i < blobs.size(); i++) {
                assertArrayEquals(blobs.get(i), repository.readBlob(ids.get(i)));
            }
            Maintenance.Report report = new Maintenance(repository, () -> false).check(1.0, null, new Random());
            assertTrue(report.ok(), report.problems().toString());
        }
    }

    @Test
    void indexRebuildsFromPackFooters() throws IOException {
        Hash id;
        try (Repository repository = open(); Session session = repository.session(Session.Options.DEFAULTS)) {
            id = session.store(BlobType.DATA, "hello".repeat(1000).getBytes(), true);
            session.flush();
        }
        // Lose every index file.
        try (var files = Files.list(dir.resolve("repo/index"))) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        try (Repository repository = open()) {
            assertThrows(CorruptException.class, () -> repository.readBlob(id));
            repository.release();
            Maintenance.Rebuilt rebuilt = new Maintenance(repository, () -> false).rebuildIndex();
            assertEquals(1, rebuilt.blobs());
            assertArrayEquals("hello".repeat(1000).getBytes(), repository.readBlob(id));
        }
    }

    @Test
    void damageIsFound() throws IOException {
        try (Repository repository = open(); Session session = repository.session(Session.Options.DEFAULTS)) {
            byte[] data = new byte[100_000];
            new Random(2).nextBytes(data);
            session.store(BlobType.DATA, data, true);
            session.flush();
        }
        Path pack;
        try (var walk = Files.walk(dir.resolve("repo/data"))) {
            pack = walk.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Fixtures.corrupt(pack, 5000);
        try (Repository repository = open()) {
            Maintenance.Report report = new Maintenance(repository, () -> false).check(1.0, null, new Random());
            assertFalse(report.ok());
            assertTrue(report.problems().get(0).contains("damaged"), report.problems().toString());
        }
    }

    @Test
    void treesAndLayoutsEncodeExactly() throws IOException {
        Hash a = Hash.sha256(new byte[] {1});
        Hash b = Hash.sha256(new byte[] {2});
        Tree tree = new Tree(List.of(
            Tree.Node.file("z.txt", 123, 5, 0644, List.of(a, b), a),
            Tree.Node.dir("dir", 456, 0755, b),
            Tree.Node.region("r.0.0.mca", 1, 8192, -1, a),
            Tree.Node.database("x.db", 9, 4096, 0600, List.of(b), 2048, a, 100, 200),
            Tree.Node.link("link", 7, "../elsewhere")));
        Tree back = Tree.decode(tree.encode(), "test");
        assertEquals(tree, back);
        assertEquals("dir", back.nodes().get(0).name());
        assertNull(back.get("missing"));

        int[] location = new int[RegionLayout.CHUNKS];
        int[] timestamp = new int[RegionLayout.CHUNKS];
        int[] length = new int[RegionLayout.CHUNKS];
        Hash[] blob = new Hash[RegionLayout.CHUNKS];
        location[5] = 2 << 8 | 1;
        timestamp[5] = 99;
        length[5] = 300;
        blob[5] = a;
        timestamp[7] = 42;
        RegionLayout layout = new RegionLayout(12288, location, timestamp, length, blob);
        assertEquals(layout, RegionLayout.decode(layout.encode(), "test"));
    }

    @Test
    void chunkerCutsAtTheSamePlacesAfterAnInsert() throws IOException {
        byte[] data = new byte[8 << 20];
        new Random(3).nextBytes(data);
        List<Hash> before = pieces(data);
        byte[] shifted = new byte[data.length + 100];
        System.arraycopy(data, 0, shifted, 0, 1_000_000);
        System.arraycopy(data, 1_000_000, shifted, 1_000_100, data.length - 1_000_000);
        List<Hash> after = pieces(shifted);
        Set<Hash> shared = new HashSet<>(before);
        shared.retainAll(after);
        // All but the piece holding the insert (and maybe its neighbour) are the same.
        assertTrue(shared.size() >= before.size() - 2, shared.size() + " of " + before.size());
        for (byte[] piece : piecesBytes(data)) {
            assertTrue(piece.length <= Chunker.MAX);
        }
    }

    private static List<Hash> pieces(byte[] data) throws IOException {
        List<Hash> out = new ArrayList<>();
        for (byte[] piece : piecesBytes(data)) {
            out.add(Hash.sha256(piece));
        }
        return out;
    }

    private static List<byte[]> piecesBytes(byte[] data) throws IOException {
        Chunker chunker = new Chunker(new ByteArrayInputStream(data));
        List<byte[]> out = new ArrayList<>();
        byte[] piece;
        while ((piece = chunker.next()) != null) {
            out.add(piece);
        }
        return out;
    }

    @Test
    void retentionKeepsTheNewestOfEachBucket() {
        Instant now = Instant.parse("2026-11-17T12:30:00Z");
        List<Snapshot> snapshots = new ArrayList<>();
        // Hourly for 10 days.
        for (int h = 0; h < 240; h++) {
            Instant time = now.minus(h, ChronoUnit.HOURS);
            snapshots.add(new Snapshot(Hash.sha256(("s" + h).getBytes()), time, time, Hash.sha256(new byte[0]), null,
                "", 0, 0, 0, 0, 0, "", List.of()));
        }
        Retention policy = new Retention(0, 24, 7, 4, 0);
        Set<Hash> keep = policy.keep(snapshots, Set.of(), ZoneOffset.UTC);
        // 24 hourly, plus one a day for 7 days (today's is already an hourly one, as is part of
        // yesterday's), plus weekly ones not already kept.
        assertTrue(keep.size() >= 24 + 5 && keep.size() <= 24 + 7 + 4, "kept " + keep.size());
        assertTrue(keep.contains(snapshots.get(0).id()));
        assertFalse(keep.contains(snapshots.get(30).id()));
        // A pinned one always stays.
        Set<Hash> pinned = Set.of(snapshots.get(200).id());
        assertTrue(policy.keep(snapshots, pinned, ZoneOffset.UTC).contains(snapshots.get(200).id()));
        // Nothing kept but the newest when every tier is zero.
        assertEquals(1, new Retention(0, 0, 0, 0, 0).keep(snapshots, Set.of(), ZoneOffset.UTC).size());
    }

    @Test
    void indexTableHoldsManyBlobs() {
        Index index = new Index();
        Random random = new Random(4);
        Hash pack = Hash.sha256(new byte[] {9});
        List<PackEntry> entries = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            byte[] id = new byte[32];
            random.nextBytes(id);
            entries.add(new PackEntry(Hash.of(id), BlobType.DATA, i % 2 == 0, i * 10L, 10, 20));
        }
        index.add(pack, entries);
        assertEquals(50_000, index.size());
        for (PackEntry entry : entries) {
            assertEquals(entry, index.find(entry.id()).entry());
        }
        assertNull(index.find(Hash.sha256(new byte[] {1, 2, 3})));
        Map<Hash, List<PackEntry>> byPack = index.byPack();
        assertEquals(50_000, byPack.get(pack).size());
    }

    @Test
    void sizesReadAndPrint() {
        assertEquals(512L << 20, Sizes.parse("512MB"));
        assertEquals(2L << 30, Sizes.parse("2 gb"));
        assertEquals(1536L << 20, Sizes.parse("1.5GiB"));
        assertEquals(0, Sizes.parse("off"));
        assertThrows(IllegalArgumentException.class, () -> Sizes.parse("lots"));
        assertEquals("1.50 GB", Sizes.format(1536L << 20));
        assertEquals("812 B", Sizes.format(812));
    }

    @Test
    void lockKeepsOtherWritersOut() throws Exception {
        try (Repository repository = open(); AutoCloseable lock = repository.lock(true)) {
            assertThrows(Repository.LockedException.class, () -> repository.lock(false));
        }
        try (Repository repository = open(); AutoCloseable lock = repository.lock(false)) {
            assertNotEquals(null, lock);
        }
    }
}
