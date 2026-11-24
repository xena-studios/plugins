package co.xenastudios.core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Hashing, the manifest and Java versions. */
class BasicsTest {

    @TempDir
    Path dir;

    @Test
    void hashesAsLowercaseHex() throws IOException {
        Path file = Files.write(dir.resolve("abc"), "abc".getBytes(StandardCharsets.US_ASCII));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.of(file));
        assertEquals("00ff10", Sha256.hex(new byte[] {0, (byte) 0xff, 0x10}));
        Path empty = Files.write(dir.resolve("empty"), new byte[0]);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.of(empty));
    }

    @Test
    void readsTheManifestTheBuildWrites() throws IOException {
        String sha = "bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3";
        List<Library> libraries = Manifest.parse(stream("# written by the build\n\nxena-libraries 1\n"
            + "org.jetbrains.kotlin:kotlin-stdlib:2.4.21 " + sha + " kotlin.Lazy  # the stdlib\n"));
        assertEquals(1, libraries.size());
        Library stdlib = libraries.get(0);
        assertEquals("org.jetbrains.kotlin:kotlin-stdlib:2.4.21", stdlib.coordinates());
        assertEquals(sha, stdlib.sha256());
        assertEquals("kotlin.Lazy", stdlib.probe());
        assertEquals("kotlin-stdlib 2.4.21", stdlib.toString());
        assertEquals("org/jetbrains/kotlin/kotlin-stdlib/2.4.21/kotlin-stdlib-2.4.21.jar", stdlib.path());
    }

    @Test
    void refusesAManifestItDoesNotUnderstand() {
        Fixtures.assertContains(assertThrows(IOException.class, () -> Manifest.parse(stream("xena-libraries 2\n")))
            .getMessage(), "another version of the loader");
        Fixtures.assertContains(assertThrows(IOException.class, () -> Manifest.parse(stream(
            "xena-libraries 1\norg.jetbrains.kotlin:kotlin-stdlib:2.4.21 not-a-hash kotlin.Lazy\n"))).getMessage(), "line 2");
        Fixtures.assertContains(assertThrows(IOException.class, () -> Manifest.parse(stream(
            "xena-libraries 1\nkotlin-stdlib " + "0".repeat(64) + " kotlin.Lazy\n"))).getMessage(), "group:artifact:version");
    }

    @Test
    void aJarWithoutAManifestHasNoLibraries() throws IOException {
        assertTrue(Manifest.read(new java.net.URLClassLoader(new java.net.URL[0], null)).isEmpty());
    }

    @Test
    void readsJavaVersions() throws IOException {
        assertEquals(8, JavaVersion.parse("1.8"));
        assertEquals(16, JavaVersion.parse("16"));
        assertEquals(21, JavaVersion.parse("21.0.1"));
        assertEquals(26, JavaVersion.parse("26-ea"));
        assertEquals(8, JavaVersion.parse("unknown"));
        assertEquals(Runtime.version().feature(), JavaVersion.current());
        assertEquals(0, JavaVersion.ofClassFile(stream("not a class file")));
        assertEquals(0, JavaVersion.ofClassFile(stream("")));
    }

    /** The loader and the shim run before anything else on Java 8 servers, so they're Java 8 class files. */
    @Test
    void theLoaderIsJava8Bytecode() throws IOException {
        for (String type : List.of("Libraries", "LibraryCache", "PluginEntry", "bukkit/BukkitBootstrap")) {
            try (InputStream in = Libraries.class.getResourceAsStream(type + ".class")) {
                assertEquals(8, JavaVersion.ofClassFile(in), type);
            }
        }
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
