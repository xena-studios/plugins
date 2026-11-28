package co.xenastudios.core.loader;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Makes a plugin's libraries visible to its classes. A library whose probe class the plugin's
 * class loader already sees is left alone: the server bundles it, {@code libraries:} in
 * plugin.yml fetched it (Spigot 1.16.5 builds from May 2021 on, and Paper), or another plugin
 * did (Bukkit before 1.16.5 shares classes between plugins). The rest come from the
 * {@link LibraryCache} and are added with the first {@link Strategy} that works: to the plugin's
 * class loader, or on Bukkit before 1.16.5 to the server's, which every plugin shares
 * ({@link Startup#definesForOthers}).
 */
public final class Libraries {

    private Libraries() {}

    /** What was done. */
    public static final class Loaded {
        private final ClassLoader loader;
        private final Strategy strategy;
        private final List<LibraryCache.Fetched> added;
        private final List<Library> visible;

        Loaded(ClassLoader loader, Strategy strategy, List<LibraryCache.Fetched> added, List<Library> visible) {
            this.loader = loader;
            this.strategy = strategy;
            this.added = Collections.unmodifiableList(added);
            this.visible = Collections.unmodifiableList(visible);
        }

        /** The class loader to load the plugin's Kotlin classes from. */
        public ClassLoader loader() {
            return loader;
        }

        /** How the jars were added, or null when every library was already visible. */
        public Strategy strategy() {
            return strategy;
        }

        public List<LibraryCache.Fetched> added() {
            return added;
        }

        /** The libraries the server (or another plugin) already had. */
        public List<Library> visible() {
            return visible;
        }
    }

    /**
     * @param own the plugin's class loader
     * @param ownJar the plugin's jar, for {@link Strategy#ISOLATED}
     * @param java the running Java's feature version ({@link JavaVersion#current()})
     * @param forced the strategy an owner chose, or null for the default order
     * @param log where to say what was downloaded and added; silent when nothing was
     */
    public static Loaded load(List<Library> libraries, ClassLoader own, URL ownJar, LibraryCache cache, int java,
                              Strategy forced, Logger log) throws LibraryException {
        List<Library> visible = new ArrayList<>();
        List<Library> missing = new ArrayList<>();
        for (Library library : libraries) {
            (find(library.probe(), own) != null ? visible : missing).add(library);
        }
        if (missing.isEmpty()) {
            return new Loaded(own, null, Collections.<LibraryCache.Fetched>emptyList(), visible);
        }
        List<LibraryCache.Fetched> fetched = fetch(missing, cache, log);
        List<String> failures = new ArrayList<>();
        // On old Bukkit the jars go into the server's class loader, which every plugin's loader
        // asks first, under its own locks: added to the plugin's, the plugins sharing them would
        // define their classes there without its lock (Startup.definesForOthers).
        ClassLoader server = own.getParent();
        if (forced != Strategy.ISOLATED && server != null && Startup.definesForOthers(own)) {
            for (Strategy strategy : Strategy.order(java, true, forced)) {
                if (!strategy.injects()) {
                    continue;
                }
                try {
                    inject(strategy, server, fetched);
                    check(missing, own);
                    log.info(describe(fetched, strategy.description() + " on the server's class loader, which every plugin shares", cache));
                    return new Loaded(own, strategy, fetched, visible);
                } catch (Throwable e) {
                    failures.add(strategy.name().toLowerCase(Locale.ROOT) + " on the server's class loader: " + e);
                }
            }
            log.warning("Couldn't add the libraries to the server's class loader (" + String.join("; ", failures)
                + "), so they're added to the plugin's, where plugins starting at the same time can break each other's classes."
                + " Starting the server with --add-opens java.base/java.net=ALL-UNNAMED"
                + " --add-opens java.base/jdk.internal.loader=ALL-UNNAMED fixes it.");
        }
        for (Strategy strategy : Strategy.order(java, own instanceof URLClassLoader, forced)) {
            try {
                ClassLoader loader = add(strategy, own, ownJar, fetched);
                check(missing, loader);
                if (strategy == Strategy.ISOLATED && !failures.isEmpty()) {
                    log.warning("Couldn't add the libraries to the plugin's class loader (" + String.join("; ", failures)
                        + "), so the plugin runs in a class loader of its own, where other plugins can't use its API."
                        + " Starting the server with --add-opens java.base/java.net=ALL-UNNAMED fixes it.");
                }
                log.info(describe(fetched, strategy.description(), cache));
                return new Loaded(loader, strategy, fetched, visible);
            } catch (Throwable e) {
                failures.add(strategy.name().toLowerCase(Locale.ROOT) + ": " + e);
            }
        }
        throw new LibraryException("Couldn't make " + missing + " visible to the plugin (" + String.join("; ", failures)
            + "). Remove the loader setting to use the default, or start the server with"
            + " --add-opens java.base/java.net=ALL-UNNAMED.");
    }

    private static void check(List<Library> missing, ClassLoader loader) {
        for (Library library : missing) {
            if (find(library.probe(), loader) == null) {
                throw new IllegalStateException(library + " was added, but " + library.probe() + " still isn't visible");
            }
        }
    }

    /** Adds a downloaded jar to the plugin's class path the platform's own way, such as Velocity's {@code addToClasspath}. */
    public interface Adder {
        void add(Path jar) throws Exception;
    }

    /**
     * As {@link #load(List, ClassLoader, URL, LibraryCache, int, Strategy, Logger)}, on a platform
     * with its own way to add a jar to a plugin's class loader: each missing library is fetched
     * into the cache and handed to {@code adder}, described as {@code how} in the console line.
     */
    public static Loaded load(List<Library> libraries, ClassLoader own, LibraryCache cache, Adder adder, String how, Logger log)
            throws LibraryException {
        List<Library> visible = new ArrayList<>();
        List<Library> missing = new ArrayList<>();
        for (Library library : libraries) {
            (find(library.probe(), own) != null ? visible : missing).add(library);
        }
        if (missing.isEmpty()) {
            return new Loaded(own, null, Collections.<LibraryCache.Fetched>emptyList(), visible);
        }
        List<LibraryCache.Fetched> fetched = fetch(missing, cache, log);
        try {
            for (LibraryCache.Fetched jar : fetched) {
                adder.add(jar.jar());
            }
        } catch (Exception e) {
            throw new LibraryException("Couldn't add " + missing + " to the plugin through " + how + ": " + e);
        }
        for (Library library : missing) {
            if (find(library.probe(), own) == null) {
                throw new LibraryException(library + " was added through " + how + ", but " + library.probe() + " still isn't visible");
            }
        }
        log.info(describe(fetched, how, cache));
        return new Loaded(own, null, fetched, visible);
    }

    private static List<LibraryCache.Fetched> fetch(List<Library> missing, LibraryCache cache, Logger log) throws LibraryException {
        List<LibraryCache.Fetched> fetched = new ArrayList<>();
        for (Library library : missing) {
            LibraryCache.Fetched jar = cache.fetch(library);
            if (jar.replacedBadCopy()) {
                log.warning("The copy of " + library + " in " + cache.folder() + " didn't match its pinned SHA-256,"
                    + " so it was downloaded again.");
            }
            fetched.add(jar);
        }
        return fetched;
    }

    /** The class, if the loader can see it, without initializing it. */
    static Class<?> find(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static ClassLoader add(Strategy strategy, ClassLoader own, URL ownJar, List<LibraryCache.Fetched> jars)
            throws Throwable {
        if (strategy.injects()) {
            inject(strategy, own, jars);
            return own;
        }
        URL[] urls = new URL[jars.size() + 1];
        urls[0] = ownJar;
        for (int i = 0; i < jars.size(); i++) {
            urls[i + 1] = url(jars.get(i));
        }
        String name = Libraries.class.getName();
        return new ChildFirst(urls, own, name.substring(0, name.lastIndexOf('.') + 1));
    }

    /**
     * Adds the jars to {@code target}'s class path: {@code URLClassLoader.addURL}, or on Java 9 and
     * later's application class loader, which isn't one, the method the JDK's
     * {@code Instrumentation.appendToSystemClassLoaderSearch} calls.
     */
    private static void inject(Strategy strategy, ClassLoader target, List<LibraryCache.Fetched> jars) throws Throwable {
        boolean urls = target instanceof URLClassLoader;
        Class<?> owner = urls ? URLClassLoader.class : target.getClass();
        String method = urls ? "addURL" : "appendToClassPathForInstrumentation";
        Class<?> parameter = urls ? URL.class : String.class;
        if (strategy == Strategy.REFLECT) {
            Method add = owner.getDeclaredMethod(method, parameter);
            add.setAccessible(true);
            for (LibraryCache.Fetched jar : jars) {
                add.invoke(target, urls ? url(jar) : jar.jar().toString());
            }
        } else {
            MethodHandle add = trustedLookup().findVirtual(owner, method, MethodType.methodType(void.class, parameter));
            for (LibraryCache.Fetched jar : jars) {
                add.invoke(target, urls ? (Object) url(jar) : jar.jar().toString());
            }
        }
    }

    private static URL url(LibraryCache.Fetched jar) throws IOException {
        return jar.jar().toUri().toURL();
    }

    /** The JDK's fully trusted lookup, which can call protected methods in java.base. */
    private static MethodHandles.Lookup trustedLookup() throws ReflectiveOperationException {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Field implLookup = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        Object base = unsafeType.getMethod("staticFieldBase", Field.class).invoke(unsafe, implLookup);
        Object offset = unsafeType.getMethod("staticFieldOffset", Field.class).invoke(unsafe, implLookup);
        return (MethodHandles.Lookup) unsafeType.getMethod("getObject", Object.class, long.class)
            .invoke(unsafe, base, offset);
    }

    private static String describe(List<LibraryCache.Fetched> jars, String how, LibraryCache cache) {
        List<String> names = new ArrayList<>();
        List<String> downloads = new ArrayList<>();
        for (LibraryCache.Fetched jar : jars) {
            names.add(jar.library().toString());
            if (jar.downloadedFrom() != null) {
                downloads.add(jar.library().artifact() + " from " + jar.downloadedFrom() + ", "
                    + (jar.bytes() + 1023) / 1024 + " KiB in " + jar.millis() + " ms");
            }
        }
        return "Loaded " + String.join(", ", names) + " through " + how + " ("
            + (downloads.isEmpty() ? "from " + cache.folder() : "downloaded " + String.join("; ", downloads)) + ")";
    }

    /**
     * Loads what's in its jars (the plugin's own, then the libraries) itself before asking the
     * plugin's class loader, except the loader's own package, which must stay the plugin
     * loader's so {@link PluginEntry} is one type on both sides, and the server's API.
     */
    private static final class ChildFirst extends URLClassLoader {
        static {
            ClassLoader.registerAsParallelCapable();
        }

        private final String[] parentFirst;

        ChildFirst(URL[] urls, ClassLoader parent, String loaderPackage) {
            super(urls, parent);
            this.parentFirst = new String[] {loaderPackage, "java.", "javax.", "org.bukkit.", "org.spigotmc.",
                "net.md_5.", "net.minecraft.", "com.google.", "org.yaml."};
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            for (String prefix : parentFirst) {
                if (name.startsWith(prefix)) {
                    return super.loadClass(name, resolve);
                }
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException e) {
                        return super.loadClass(name, resolve);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
