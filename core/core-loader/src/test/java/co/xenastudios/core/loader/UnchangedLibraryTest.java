package co.xenastudios.core.loader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The record that lets an unchanged cached jar skip its hash at start, and everything that makes it hash again. */
class UnchangedLibraryTest {

    private static final String HELP = "Set a mirror.";

    @TempDir
    Path dir;

    private byte[] real;
    private Library library;
    private LibraryCache cache;
    private Path jar;

    @BeforeEach
    void cached() throws IOException {
        Path built = Fixtures.probeJar(dir.resolve("built"));
        real = Files.readAllBytes(built);
        library = Fixtures.library(built, "fixture.Probe");
        cache = new LibraryCache(dir.resolve("plugins/Xena/libraries"), Fixtures.none(), HELP);
        jar = cache.location(library);
        Files.createDirectories(jar.getParent());
        Files.write(jar, real);
        // An old file: a change below gets a different time even on a coarse file system.
        Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
    }

    private Path record() {
        return LibraryCache.record(jar);
    }

    @Test
    void theFirstStartHashesAndWritesTheRecordTheSecondSkips() throws Exception {
        LibraryCache.Fetched first = cache.fetch(library);
        assertTrue(first.hashed());
        assertNull(first.downloadedFrom());
        assertEquals(real.length, first.bytes());
        assertTrue(Files.isRegularFile(record()));
        String text = new String(Files.readAllBytes(record()), StandardCharsets.UTF_8);
        Fixtures.assertContains(text, "# Matched its pin.", "sha256=" + library.sha256() + "\n", "size=" + real.length + "\n");

        // Another plugin of the family, or the next start: the record vouches for the jar.
        LibraryCache.Fetched second = new LibraryCache(cache.folder(), Fixtures.none(), HELP).fetch(library);
        assertFalse(second.hashed());
        assertEquals(jar, second.jar());
        assertEquals(real.length, second.bytes());
        assertEquals(List.of(), leftovers(), "only the jar and its record are left");
    }

    @Test
    void fetchingAheadSkipsTooAndSaysSo() throws Exception {
        cache.fetch(library);
        LibraryCache ahead = new LibraryCache(cache.folder(), Fixtures.none(), HELP);
        ahead.fetchAhead(List.of(library), "test libraries");
        assertFalse(ahead.fetch(library).hashed());
    }

    @Test
    void aTouchedFileIsHashedAgain() throws Exception {
        cache.fetch(library);
        Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis()));
        LibraryCache.Fetched touched = cache.fetch(library);
        assertTrue(touched.hashed());
        // ... and gets a new record, so the start after it skips again.
        assertFalse(cache.fetch(library).hashed());
    }

    @Test
    void aChangedFileIsHashedAndRefused() throws Exception {
        cache.fetch(library);
        FileTime time = Files.getLastModifiedTime(jar);
        byte[] changed = real.clone();
        changed[changed.length / 2] ^= 1;
        Files.write(jar, changed);
        // Same size, a new time: hashed, and refused as before (no mirror to fetch it again).
        Fixtures.assertContains(assertThrows(LibraryException.class, () -> cache.fetch(library)).getMessage(),
            "Couldn't download probe 1.0", "no mirror is set");
        assertFalse(Files.exists(record()), "a mismatch drops the record");

        // Even with the old time put back, the record is gone: it's hashed and refused again.
        Files.setLastModifiedTime(jar, time);
        assertThrows(LibraryException.class, () -> cache.fetch(library));
    }

    @Test
    void theLimitIsAnInPlaceChangeThatRestoresTheTime() throws Exception {
        // Documented in Verified: the same size, inode and modified time can't be told apart
        // without reading the file. Anyone who can do that to the cache can replace the plugin's jar.
        cache.fetch(library);
        FileTime time = Files.getLastModifiedTime(jar);
        byte[] changed = real.clone();
        changed[changed.length / 2] ^= 1;
        Files.write(jar, changed);
        Files.setLastModifiedTime(jar, time);
        assertFalse(cache.fetch(library).hashed());
    }

    @Test
    void aReplacedFileIsHashedAgainEvenWithTheSameSizeAndTime() throws Exception {
        cache.fetch(library);
        String before = stamp();
        Path copy = dir.resolve("copy.jar");
        Files.copy(jar, copy, StandardCopyOption.COPY_ATTRIBUTES);
        Files.move(copy, jar, StandardCopyOption.REPLACE_EXISTING);
        String after = stamp();
        // Where the platform has file keys (inodes), a new file is a new key.
        if (!before.equals(after)) {
            assertTrue(cache.fetch(library).hashed());
        }
    }

    @Test
    void aNewPinAlwaysHashes() throws Exception {
        cache.fetch(library);
        String text = new String(Files.readAllBytes(record()), StandardCharsets.UTF_8);
        // The same jar under another pin (a bump that kept the path, or a different build's list).
        String other = library.sha256().replace(library.sha256().charAt(0), library.sha256().charAt(0) == 'a' ? 'b' : 'a');
        Library repinned = new Library(library.group(), library.artifact(), library.version(), other, library.probe());
        assertThrows(LibraryException.class, () -> cache.fetch(repinned));
        assertFalse(Files.exists(record()));
        // A record for the other pin, for this very file, doesn't vouch for this pin: hashed, and
        // the right pin's own record written.
        Files.write(record(), text.replace(library.sha256(), other).getBytes(StandardCharsets.UTF_8));
        assertTrue(cache.fetch(library).hashed());
        assertFalse(cache.fetch(library).hashed());
    }

    @Test
    void aCorruptOrUnreadableRecordMeansAHash() throws Exception {
        cache.fetch(library);
        String text = new String(Files.readAllBytes(record()), StandardCharsets.UTF_8);
        for (String corrupt : new String[] {"", "garbage", text.replace("# Matched", "# Marched"), text + "more\n", text.replace("\n", "\r\n"),
            text.replaceAll("size=\\d+", "size=lots"), text.replaceAll("\nkey=[^\n]*", ""), text.substring(0, text.length() / 2),
            text.replaceAll("size=\\d+", "size=" + (real.length + 1))}) {
            Files.write(record(), corrupt.getBytes(StandardCharsets.UTF_8));
            assertTrue(cache.fetch(library).hashed(), "hashed with the record: " + corrupt);
            assertFalse(cache.fetch(library).hashed(), "and its record rewritten");
        }
        // A folder where the record should be: hashed every time, never trusted.
        Files.delete(record());
        Files.createDirectory(record());
        assertTrue(cache.fetch(library).hashed());
        assertTrue(cache.fetch(library).hashed());
    }

    @Test
    void aDownloadWritesTheRecord() throws Exception {
        Files.delete(jar);
        try (Fixtures.Mirror mirror = new Fixtures.Mirror(Map.of("/good/", real))) {
            LibraryCache.Fetched downloaded = new LibraryCache(cache.folder(), List.of(mirror.url("/good/")), HELP).fetch(library);
            assertTrue(downloaded.hashed());
            assertEquals("127.0.0.1", downloaded.downloadedFrom());
            assertArrayEquals(real, Files.readAllBytes(jar));
        }
        assertTrue(Files.isRegularFile(record()));
        assertFalse(cache.fetch(library).hashed());
    }

    private String stamp() throws IOException {
        return LibraryCache.stamp(Files.readAttributes(jar, BasicFileAttributes.class), library.sha256());
    }

    private List<Path> leftovers() throws IOException {
        try (Stream<Path> files = Files.walk(cache.folder())) {
            return files.filter(Files::isRegularFile).filter(f -> !f.equals(jar) && !f.equals(record())).toList();
        }
    }
}
