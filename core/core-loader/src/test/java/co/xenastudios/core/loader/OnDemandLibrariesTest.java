package co.xenastudios.core.loader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Libraries fetched when a feature asks, such as a JDBC driver, from the list the build writes. */
class OnDemandLibrariesTest {

    @TempDir
    Path dir;

    @Test
    void readsItsListFromThePluginJarAndFetchesOnlyWhenAsked() throws Exception {
        Path jar = Fixtures.probeJar(dir);
        Library library = Fixtures.library(jar, "fixture.Probe");
        Path resources = Files.createDirectories(dir.resolve("plugin/META-INF/xena"));
        Files.write(resources.resolve("on-demand-libraries.txt"), ("xena-libraries 1\n" + library.coordinates() + ' '
            + library.sha256() + " fixture.Probe\n").getBytes(StandardCharsets.UTF_8));

        try (Fixtures.Mirror mirror = new Fixtures.Mirror(Map.of("/maven/", Files.readAllBytes(jar)));
             URLClassLoader plugin = new URLClassLoader(new URL[] {dir.resolve("plugin").toUri().toURL()}, null)) {
            LibraryCache cache = new LibraryCache(dir.resolve("libraries"), List.of(mirror.url("/maven")), "Set a mirror.");
            OnDemandLibraries onDemand = OnDemandLibraries.read(plugin, cache);
            assertEquals(List.of("probe 1.0"), onDemand.pinned().stream().map(Library::toString).toList());
            assertEquals(0, mirror.requests.get(), "nothing is fetched until it's asked for");

            Path fetched = onDemand.fetch("co.xenastudios.test", "probe");
            assertArrayEquals(Files.readAllBytes(jar), Files.readAllBytes(fetched));
            assertEquals(cache.location(library), fetched);
            onDemand.fetch("co.xenastudios.test", "probe");
            assertEquals(1, mirror.requests.get(), "the second time, it's the cached jar");

            assertNull(onDemand.find("org.xerial", "sqlite-jdbc"));
            LibraryException none = assertThrows(LibraryException.class, () -> onDemand.fetch("org.xerial", "sqlite-jdbc"));
            Fixtures.assertContains(none.getMessage(), "pins no org.xerial:sqlite-jdbc", "probe 1.0");
        }
    }

    @Test
    void aJarWithoutAListHasNothingToFetch() throws IOException {
        try (URLClassLoader plugin = new URLClassLoader(new URL[0], null)) {
            OnDemandLibraries onDemand = OnDemandLibraries.read(plugin, new LibraryCache(dir, Fixtures.none(), ""));
            assertEquals(List.of(), onDemand.pinned());
        }
    }
}
