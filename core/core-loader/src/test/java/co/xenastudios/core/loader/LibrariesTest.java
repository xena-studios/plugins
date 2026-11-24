package co.xenastudios.core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the loader decides and does, with a real jar and real class loaders standing in for a
 * plugin's: nothing when the server has the library, otherwise download and add it. Java 8's
 * reflection is booted on Spigot 1.8.8 instead (core/core-loader/sandbox): this JVM refuses it,
 * which shows the fallback.
 */
class LibrariesTest {

    @TempDir
    Path dir;

    private Path probe;
    private Library library;
    private Fixtures.Mirror mirror;
    private LibraryCache cache;
    private final List<String> log = new ArrayList<>();
    private final Logger logger = Logger.getAnonymousLogger();

    @BeforeEach
    void setUp() throws IOException {
        probe = Fixtures.probeJar(dir);
        library = Fixtures.library(probe, "fixture.Probe");
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                log.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (mirror != null) {
            mirror.close();
        }
    }

    private LibraryCache mirrored() throws IOException {
        mirror = new Fixtures.Mirror(Map.of("/maven2/", Files.readAllBytes(probe)));
        cache = new LibraryCache(dir.resolve("plugins/Xena/libraries"), List.of(mirror.url("/maven2/")), "Set a mirror.");
        return cache;
    }

    /** A stand-in for a plugin's class loader: a URLClassLoader over the plugin's jar, under the test's. */
    private URLClassLoader plugin(Path... jars) throws IOException {
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) {
            urls[i] = jars[i].toUri().toURL();
        }
        return new URLClassLoader(urls, getClass().getClassLoader());
    }

    @Test
    void aLibraryTheServerHasIsLeftAlone() throws Exception {
        // The server (here, the parent) already has the probe: no mirror, no download, no log.
        try (URLClassLoader server = plugin(probe); URLClassLoader own = new URLClassLoader(new URL[0], server)) {
            Libraries.Loaded loaded = Libraries.load(List.of(library), own, null,
                new LibraryCache(dir.resolve("unused"), Fixtures.none(), ""), 8, null, logger);
            assertSame(own, loaded.loader());
            assertNull(loaded.strategy());
            assertEquals(List.of(library), loaded.visible());
            assertTrue(loaded.added().isEmpty());
            assertTrue(log.isEmpty(), log.toString());
            assertTrue(Files.notExists(dir.resolve("unused")));
        }
    }

    @Test
    void addsToThePluginsClassLoaderThroughTheTrustedLookup() throws Exception {
        try (URLClassLoader own = plugin()) {
            Libraries.Loaded loaded = Libraries.load(List.of(library), own, null, mirrored(), 17, null, logger);
            assertSame(own, loaded.loader());
            assertEquals(Strategy.LOOKUP, loaded.strategy());
            assertEquals("hi", Class.forName("fixture.Probe", true, own).getMethod("hi").invoke(null));
            assertEquals(1, log.size(), log.toString());
            Fixtures.assertContains(log.get(0), "INFO Loaded probe 1.0 through the JDK's trusted lookup (downloaded probe from 127.0.0.1, ");
        }
        // The next boot finds it in the cache.
        log.clear();
        try (URLClassLoader own = plugin()) {
            Libraries.load(List.of(library), own, null, cache, 17, null, logger);
            assertEquals(List.of("INFO Loaded probe 1.0 through the JDK's trusted lookup (from " + cache.folder() + ")"), log);
            assertEquals(1, mirror.requests.get());
        }
    }

    @Test
    void onJava8ReflectionComesFirstAndTheLookupCatchesItsRefusal() throws Exception {
        // This JVM is 17 or newer, which refuses URLClassLoader.addURL by plain reflection.
        try (URLClassLoader own = plugin()) {
            Libraries.Loaded loaded = Libraries.load(List.of(library), own, null, mirrored(), 8, null, logger);
            assertEquals(Strategy.LOOKUP, loaded.strategy());
            assertEquals("hi", Class.forName("fixture.Probe", true, own).getMethod("hi").invoke(null));
        }
    }

    @Test
    void isolatedLoadsThePluginsOwnClassesBesideTheLibraries() throws Exception {
        Path pluginJar = Fixtures.jar(dir, "plugin", Map.of("own.Entry",
            "package own; public class Entry { public static String run() { return fixture.Probe.hi() + \"!\"; } }"), probe);
        try (URLClassLoader own = plugin(pluginJar)) {
            Libraries.Loaded loaded = Libraries.load(List.of(library), own, pluginJar.toUri().toURL(), mirrored(), 21,
                Strategy.ISOLATED, logger);
            ClassLoader isolated = loaded.loader();
            assertNotSame(own, isolated);
            assertEquals(Strategy.ISOLATED, loaded.strategy());
            assertEquals("hi!", Class.forName("own.Entry", true, isolated).getMethod("run").invoke(null));
            // The plugin's own class loader still can't see the library: nothing was injected.
            assertNull(Libraries.find("fixture.Probe", own));
            // The loader's own types stay the plugin loader's, so PluginEntry is one type on both sides.
            assertSame(PluginEntry.class, Class.forName(PluginEntry.class.getName(), false, isolated));
            Fixtures.assertContains(log.get(0), "INFO Loaded probe 1.0 through a class loader of the plugin's own");
        }
    }

    @Test
    void aClassLoaderThatIsNoUrlClassLoaderGetsIsolated() throws Exception {
        Path pluginJar = Fixtures.jar(dir, "plugin", Map.of("own.Entry", "package own; public class Entry {}"));
        ClassLoader own = new ClassLoader(getClass().getClassLoader()) {};
        Libraries.Loaded loaded = Libraries.load(List.of(library), own, pluginJar.toUri().toURL(), mirrored(), 21, null, logger);
        assertEquals(Strategy.ISOLATED, loaded.strategy());
        assertTrue(log.stream().noneMatch(line -> line.startsWith("WARNING")), "nothing failed first: " + log);
    }

    @Test
    void aStrategyTheJdkRefusesIsAClearError() throws Exception {
        try (URLClassLoader own = plugin()) {
            LibraryException e = assertThrows(LibraryException.class,
                () -> Libraries.load(List.of(library), own, null, mirrored(), 21, Strategy.REFLECT, logger));
            Fixtures.assertContains(e.getMessage(), "Couldn't make [probe 1.0] visible to the plugin (reflect: ",
                "--add-opens java.base/java.net=ALL-UNNAMED");
        }
    }

    @Test
    void anOfflineServerFailsBeforeTouchingTheClassLoader() throws Exception {
        try (URLClassLoader own = plugin()) {
            LibraryCache offline = new LibraryCache(dir.resolve("cache"), Fixtures.none(), "Set a mirror.");
            LibraryException e = assertThrows(LibraryException.class,
                () -> Libraries.load(List.of(library), own, null, offline, 17, null, logger));
            Fixtures.assertContains(e.getMessage(), "Couldn't download probe 1.0", "Set a mirror.");
            assertNull(Libraries.find("fixture.Probe", own));
        }
    }
}
