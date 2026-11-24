package co.xenastudios.core.loader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Downloads, the SHA-256 check and the shared cache, against a local mirror. */
class LibraryCacheTest {

    private static final String HELP = "Set a mirror.";

    @TempDir
    Path dir;

    private byte[] real;
    private byte[] tampered;
    private Library library;

    @BeforeEach
    void jar() throws IOException {
        Path jar = Fixtures.probeJar(dir);
        real = Files.readAllBytes(jar);
        tampered = real.clone();
        tampered[tampered.length / 2] ^= 1;
        library = Fixtures.library(jar, "fixture.Probe");
    }

    @Test
    void downloadsOnceIntoTheMavenLayout() throws Exception {
        try (Fixtures.Mirror mirror = new Fixtures.Mirror(Map.of("/good/", real))) {
            Path cache = dir.resolve("plugins/Xena/libraries");
            LibraryCache.Fetched first = new LibraryCache(cache, List.of(mirror.url("/good")), HELP).fetch(library);
            assertEquals(cache.resolve("co/xenastudios/test/probe/1.0/probe-1.0.jar"), first.jar());
            assertArrayEquals(real, Files.readAllBytes(first.jar()));
            assertEquals("127.0.0.1", first.downloadedFrom());
            assertEquals(real.length, first.bytes());
            assertFalse(first.replacedBadCopy());

            // Another plugin of the family, same folder, no mirror at all: the cached jar, checked.
            LibraryCache.Fetched second = new LibraryCache(cache, Fixtures.none(), HELP).fetch(library);
            assertEquals(first.jar(), second.jar());
            assertNull(second.downloadedFrom());
            assertEquals(1, mirror.requests.get());
        }
    }

    @Test
    void aDownloadThatDoesNotMatchIsNeverUsed() throws Exception {
        try (Fixtures.Mirror mirror = new Fixtures.Mirror(Map.of("/tampered/", tampered, "/good/", real))) {
            Path cache = dir.resolve("a");
            LibraryException refused = assertThrows(LibraryException.class,
                () -> new LibraryCache(cache, List.of(mirror.url("/tampered/")), HELP).fetch(library));
            Fixtures.assertContains(refused.getMessage(), "Couldn't download probe 1.0", "127.0.0.1: its jar's SHA-256 is ",
                "not the pinned " + library.sha256() + ", so it wasn't used", HELP, "Or put probe-1.0.jar from Maven Central at "
                    + cache.resolve(library.path()));
            assertTrue(leftovers(cache).isEmpty(), "nothing is kept: " + leftovers(cache));

            // The first mirror's copy is bad: the next one's is used.
            LibraryCache.Fetched fetched = new LibraryCache(dir.resolve("b"),
                List.of(mirror.url("/tampered/"), mirror.url("/good/")), HELP).fetch(library);
            assertEquals(library.sha256(), Sha256.of(fetched.jar()));
        }
    }

    @Test
    void aDamagedCopyInTheCacheIsReplaced() throws Exception {
        try (Fixtures.Mirror mirror = new Fixtures.Mirror(Map.of("/good/", real))) {
            LibraryCache cache = new LibraryCache(dir.resolve("cache"), List.of(mirror.url("/good/")), HELP);
            Files.createDirectories(cache.location(library).getParent());
            Files.write(cache.location(library), tampered);
            LibraryCache.Fetched fetched = cache.fetch(library);
            assertTrue(fetched.replacedBadCopy());
            assertArrayEquals(real, Files.readAllBytes(fetched.jar()));
        }
    }

    @Test
    void offlineSaysWhatToDo() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        try (Fixtures.Mirror mirror = new Fixtures.Mirror(Map.of())) {
            Path cache = dir.resolve("cache");
            LibraryException e = assertThrows(LibraryException.class, () -> new LibraryCache(cache,
                List.of("http://127.0.0.1:" + closed + "/maven2", mirror.url("/missing/")), HELP).timeouts(2000, 2000)
                .fetch(library));
            Fixtures.assertContains(e.getMessage(), "Couldn't download probe 1.0 (127.0.0.1: can't connect",
                "; 127.0.0.1: HTTP 404). Set a mirror. Or put probe-1.0.jar from Maven Central at " + cache.resolve(library.path()));
            assertTrue(leftovers(cache).isEmpty());
        }
        Fixtures.assertContains(assertThrows(LibraryException.class,
            () -> new LibraryCache(dir.resolve("none"), Fixtures.none(), HELP).fetch(library)).getMessage(), "no mirror is set");
    }

    @Test
    void mirrorsAreNormalized() {
        LibraryCache cache = new LibraryCache(dir, List.of(" https://mirror.example/maven2 ", "", "https://b.example/"), HELP);
        assertEquals(List.of("https://mirror.example/maven2/", "https://b.example/"), cache.mirrors());
    }

    private static List<Path> leftovers(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }
}
