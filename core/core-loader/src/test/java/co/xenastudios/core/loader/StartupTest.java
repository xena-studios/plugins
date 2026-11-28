package co.xenastudios.core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The startup classes loaded ahead, and where they mustn't be. */
class StartupTest {

    private static final List<String> LISTED = Arrays.asList("fixture.First", "fixture.Second", "fixture.Third");

    @TempDir
    Path dir;

    /** A plugin's class loader that records which classes it's asked for, on any thread. */
    static class Recording extends URLClassLoader {
        final Set<String> asked = ConcurrentHashMap.newKeySet();

        Recording(URL jar) {
            super(new URL[] {jar}, null);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            asked.add(name);
            return super.loadClass(name, resolve);
        }
    }

    /**
     * Old Bukkit's plugin class loader (1.8.8 to 1.16.4) as the JDK sees it: the package-private
     * {@code findClass(String, boolean)} other plugins' loaders call to define a class here.
     */
    static final class OldBukkit extends Recording {
        OldBukkit(URL jar) {
            super(jar);
        }

        Class<?> findClass(String name, boolean checkGlobal) throws ClassNotFoundException {
            return super.findClass(name);
        }
    }

    /** Bukkit 1.16.5 and later, Paper and BungeeCord: other plugins' classes through {@code loadClass0}, under the lock. */
    static final class Modern extends Recording {
        Modern(URL jar) {
            super(jar);
        }

        Class<?> loadClass0(String name, boolean resolve, boolean checkGlobal, boolean checkLibraries) throws ClassNotFoundException {
            return loadClass(name, resolve);
        }
    }

    private URL jarWithStartupList() throws IOException {
        Path list = dir.resolve("META-INF/xena/startup-classes.txt");
        Files.createDirectories(list.getParent());
        Files.write(list, String.join("\n", LISTED).getBytes(StandardCharsets.UTF_8));
        return dir.toUri().toURL();
    }

    @Test
    void oldBukkitsLoaderDefinesClassesForOtherPlugins() throws IOException {
        URL jar = jarWithStartupList();
        assertTrue(Startup.definesForOthers(new OldBukkit(jar)));
        assertFalse(Startup.definesForOthers(new Modern(jar)));
        assertFalse(Startup.definesForOthers(new Recording(jar)));
        assertFalse(Startup.definesForOthers(new URLClassLoader(new URL[0], null)));
    }

    @Test
    void preloadsTheListOnItsOwnThreads() throws Exception {
        Recording loader = new Modern(jarWithStartupList());
        Startup.preload(Collections.<Library>emptyList(), loader, loader, "test preloading");
        long until = System.nanoTime() + 10_000_000_000L;
        while (!loader.asked.containsAll(LISTED) && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertTrue(loader.asked.containsAll(LISTED), "asked for " + loader.asked);
    }

    @Test
    void preloadsNothingWhereALoaderDefinesForOtherPlugins() throws Exception {
        // Where another plugin's loader can define the same class at the same moment, outside
        // this one's lock, a second thread would define it twice (LinkageError), so none starts.
        Recording loader = new OldBukkit(jarWithStartupList());
        Set<Thread> before = Thread.getAllStackTraces().keySet();
        Startup.preload(Collections.<Library>emptyList(), loader, loader, "old bukkit preloading");
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (!before.contains(thread)) {
                assertFalse(thread.getName().equals("old bukkit preloading"), "a preload thread started");
            }
        }
        Thread.sleep(200);
        assertEquals(Collections.emptySet(), loader.asked);
    }
}
