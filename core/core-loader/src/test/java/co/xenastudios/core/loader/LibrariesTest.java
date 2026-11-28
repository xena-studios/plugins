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

    /**
     * Old Bukkit's plugin class loader (1.8.8 to 1.16.4) as the loader sees it: a URLClassLoader
     * with the {@code findClass(String, boolean)} other plugins' loaders define classes through.
     */
    static final class OldBukkit extends URLClassLoader {
        OldBukkit(ClassLoader server) {
            super(new URL[0], server);
        }

        Class<?> findClass(String name, boolean checkGlobal) throws ClassNotFoundException {
            return super.findClass(name);
        }
    }

    /**
     * Java 9 and later's application class loader, the server's on old Bukkit, as the loader sees
     * it: no URLClassLoader, but the method {@code Instrumentation} adds a jar with.
     */
    static final class AppLike extends ClassLoader {
        private final List<URL> jars = new ArrayList<>();

        AppLike(ClassLoader parent) {
            super(parent);
        }

        void appendToClassPathForInstrumentation(String path) throws IOException {
            jars.add(java.nio.file.Paths.get(path).toUri().toURL());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            try (URLClassLoader jar = new URLClassLoader(jars.toArray(new URL[0]), null)) {
                try (java.io.InputStream in = jar.getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (in == null) {
                        throw new ClassNotFoundException(name);
                    }
                    byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                }
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    @Test
    void onOldBukkitTheLibrariesGoToTheServersClassLoader() throws Exception {
        // Every plugin's loader asks the server's first, under its locks, so no plugin's loader
        // defines a library class for another without its lock (two at once: LinkageError).
        try (URLClassLoader server = new URLClassLoader(new URL[0], getClass().getClassLoader());
             OldBukkit first = new OldBukkit(server); OldBukkit second = new OldBukkit(server)) {
            Libraries.Loaded loaded = Libraries.load(List.of(library), first, null, mirrored(), 17, null, logger);
            assertSame(first, loaded.loader());
            assertEquals(Strategy.LOOKUP, loaded.strategy());
            assertSame(server, Class.forName("fixture.Probe", false, first).getClassLoader());
            assertEquals(1, log.size(), log.toString());
            Fixtures.assertContains(log.get(0), "INFO Loaded probe 1.0 through the JDK's trusted lookup on the server's class loader");
            // The next plugin finds it there: nothing to add.
            Libraries.Loaded next = Libraries.load(List.of(library), second, null, cache, 17, null, logger);
            assertNull(next.strategy());
            assertSame(server, Class.forName("fixture.Probe", false, second).getClassLoader());
        }
    }

    @Test
    void onNewerJavaTheServersClassLoaderIsAppendedToAsInstrumentationDoes() throws Exception {
        AppLike server = new AppLike(getClass().getClassLoader());
        try (OldBukkit own = new OldBukkit(server)) {
            Libraries.Loaded loaded = Libraries.load(List.of(library), own, null, mirrored(), 21, null, logger);
            assertEquals(Strategy.LOOKUP, loaded.strategy());
            assertSame(server, Class.forName("fixture.Probe", false, own).getClassLoader());
            assertEquals("hi", Class.forName("fixture.Probe", true, own).getMethod("hi").invoke(null));
        }
    }

    @Test
    void onOldBukkitAServerLoaderThatTakesNoJarFallsBackToThePluginsWithAWarning() throws Exception {
        ClassLoader server = new ClassLoader(getClass().getClassLoader()) {};
        try (OldBukkit own = new OldBukkit(server)) {
            Libraries.Loaded loaded = Libraries.load(List.of(library), own, null, mirrored(), 21, null, logger);
            assertSame(own, loaded.loader());
            assertEquals(Strategy.LOOKUP, loaded.strategy());
            assertSame(own, Class.forName("fixture.Probe", false, own).getClassLoader());
            Fixtures.assertContains(log.get(0), "WARNING Couldn't add the libraries to the server's class loader (lookup on the server's class loader: ",
                "plugins starting at the same time can break each other's classes");
        }
    }

    @Test
    void onOldBukkitIsolatedStaysTheOwnersChoice() throws Exception {
        Path pluginJar = Fixtures.jar(dir, "plugin", Map.of("own.Entry", "package own; public class Entry {}"));
        try (URLClassLoader server = new URLClassLoader(new URL[0], getClass().getClassLoader()); OldBukkit own = new OldBukkit(server)) {
            Libraries.Loaded loaded = Libraries.load(List.of(library), own, pluginJar.toUri().toURL(), mirrored(), 21, Strategy.ISOLATED, logger);
            assertEquals(Strategy.ISOLATED, loaded.strategy());
            assertNull(Libraries.find("fixture.Probe", server));
        }
    }
}
