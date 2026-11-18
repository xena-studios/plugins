package co.xenastudios.xbackup.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The libraries xBackup downloads are pinned by hash in {@link Libraries}. The same versions
 * are test dependencies (from gradle/libs.versions.toml), so a version bump that forgets the
 * hash fails here, not on a server.
 */
class LibrariesTest {

    @TempDir
    Path dir;

    @Test
    void pinnedHashesMatchTheCatalogVersions() throws Exception {
        check(Libraries.JSCH, com.jcraft.jsch.JSch.class);
        check(Libraries.COMMONS_NET, org.apache.commons.net.ftp.FTPClient.class);
    }

    private static Path jarOf(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static void check(Libraries.Library library, Class<?> type) throws Exception {
        Path jar = jarOf(type);
        assertEquals(library.fileName(), jar.getFileName().toString(),
            "gradle/libs.versions.toml and Libraries disagree on the version");
        assertEquals(library.sha256(), Libraries.sha256(jar), "update the SHA-256 in Libraries for " + library.fileName());
    }

    @Test
    void theDownloadIsCheckedAgainstItsHash() throws Exception {
        byte[] real = Files.readAllBytes(jarOf(com.jcraft.jsch.JSch.class));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/good/", exchange -> {
            exchange.sendResponseHeaders(200, real.length);
            exchange.getResponseBody().write(real);
            exchange.close();
        });
        server.createContext("/tampered/", exchange -> {
            byte[] bad = real.clone();
            bad[bad.length / 2] ^= 1;
            exchange.sendResponseHeaders(200, bad.length);
            exchange.getResponseBody().write(bad);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            IOException refused = assertThrows(IOException.class, () -> Libraries.fetch(Libraries.JSCH, dir.resolve("a"),
                "offsite", List.of(base + "/tampered/")));
            assertTrue(refused.getMessage().contains("offsite"), refused.getMessage());
            assertTrue(Files.notExists(dir.resolve("a").resolve(Libraries.JSCH.fileName())), "a bad download is never kept");
            // The first mirror's copy is bad: the next one's is used.
            Path jar = Libraries.fetch(Libraries.JSCH, dir.resolve("b"), "offsite", List.of(base + "/tampered/",
                base + "/good/"));
            assertEquals(Libraries.JSCH.sha256(), Libraries.sha256(jar));
            // Already there and right: no download at all.
            assertEquals(jar, Libraries.fetch(Libraries.JSCH, dir.resolve("b"), "offsite", List.of()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theLibraryAndXBackupsCodeLoadFromTheirOwnJars() throws Exception {
        URL own = Libraries.class.getProtectionDomain().getCodeSource().getLocation();
        URL jsch = jarOf(com.jcraft.jsch.JSch.class).toUri().toURL();
        ClassLoader loader = Libraries.isolated(own, jsch, Libraries.JSCH);
        Class<?> backend = Class.forName("co.xenastudios.xbackup.remote.lib.SftpBackend", true, loader);
        assertSame(loader, backend.getClassLoader());
        Class<?> library = Class.forName("com.jcraft.jsch.JSch", false, loader);
        assertSame(loader, library.getClassLoader());
        assertNotSame(com.jcraft.jsch.JSch.class, library, "the library's classes come from its own jar");
        // Everything else is shared, so the backend is a Backend to the rest of xBackup.
        assertSame(co.xenastudios.xbackup.store.Backend.class,
            Class.forName("co.xenastudios.xbackup.store.Backend", false, loader));
        // A library jar can't stand in for xBackup's classes, even if it had one by that name.
        ClassLoader swapped = Libraries.isolated(jsch, own, Libraries.JSCH);
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("co.xenastudios.xbackup.remote.lib.SftpBackend", true, swapped));
    }
}
