package co.xenastudios.m0.loader;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Makes the libraries in {@code META-INF/m0/libraries.txt} visible to the plugin's classes.
 * Each jar has a probe class: if the plugin's class loader already sees it (Paper's bundled
 * Adventure, or {@code libraries:} on Spigot 1.16.5+), nothing is fetched. Otherwise every
 * jar of the group is downloaded once into {@code plugins/<name>/libraries/}, checked against
 * the SHA-256 pinned at build time, and added with the strategy in {@code -Dm0.loader}:
 *
 * <ul>
 *   <li>{@code reflect}: {@code URLClassLoader.addURL} through {@code setAccessible}. Java 8-15 only:
 *       Java 16+ refuses it (java.base doesn't open java.net).</li>
 *   <li>{@code lookup}: the same {@code addURL}, through the JDK's trusted {@code IMPL_LOOKUP},
 *       read with {@code sun.misc.Unsafe} (jdk.unsupported, open to everyone).</li>
 *   <li>{@code global}: no JDK internals: Bukkit's global class cache also asks a library loader
 *       of ours. Works on 1.8.8-1.14.4 only (1.15.2 casts the class's loader to PluginClassLoader,
 *       1.21.11 has no such field), so it isn't in {@code auto}.</li>
 *   <li>{@code isolated}: no injection: the plugin's own Kotlin classes and the libraries load in a
 *       child-first class loader of their own, like LuckPerms' jar-in-jar loader.</li>
 *   <li>{@code auto} (default): {@code reflect} on Java 8, {@code lookup} on 9+, then {@code isolated}.</li>
 * </ul>
 */
public final class Libraries {

    private static final List<String> MIRRORS = Arrays.asList(
        "https://maven-central.storage-download.googleapis.com/maven2/",
        "https://repo1.maven.org/maven2/");

    private Libraries() {}

    /** Returns the class loader to load the plugin's Kotlin classes from. */
    public static ClassLoader ensure(JavaPlugin plugin) throws IOException {
        ClassLoader own = plugin.getClass().getClassLoader();
        Map<String, List<String[]>> groups = read(own);
        List<Path> fetched = new ArrayList<>();
        for (Map.Entry<String, List<String[]>> group : groups.entrySet()) {
            // The server provides the whole group (Paper's Adventure), or libraries: did.
            Class<?> provided = visible(group.getKey(), own);
            if (provided != null && !ours(provided.getClassLoader())) {
                plugin.getLogger().info("M0 libraries: " + group.getKey() + " group provided by the server: " + source(provided));
                continue;
            }
            long start = System.nanoTime();
            long bytes = 0;
            int downloaded = 0;
            List<String> elsewhere = new ArrayList<>();
            List<String[]> missing = new ArrayList<>();
            for (String[] jar : group.getValue()) {
                Class<?> found = visible(jar[2], own);
                if (found == null) {
                    missing.add(jar);
                } else {
                    elsewhere.add(jar[0].split(":")[1] + " from " + source(found));
                }
            }
            if (!elsewhere.isEmpty()) {
                plugin.getLogger().info("M0 libraries: " + group.getKey() + " group, already visible: " + elsewhere);
            }
            if (missing.isEmpty()) {
                continue;
            }
            Path folder = plugin.getDataFolder().toPath().resolve("libraries");
            for (String[] jar : missing) {
                Path path = folder.resolve(fileName(jar[0]));
                if (!(Files.exists(path) && sha256(path).equals(jar[1]))) {
                    download(jar[0], jar[1], path);
                    downloaded++;
                }
                bytes += Files.size(path);
                fetched.add(path);
            }
            plugin.getLogger().info(String.format("M0 libraries: %s group, %d jars missing (%d KiB, %d downloaded) ready in %.1f ms",
                group.getKey(), missing.size(), bytes / 1024, downloaded, (System.nanoTime() - start) / 1e6));
        }
        if (fetched.isEmpty()) {
            return own;
        }
        String strategy = System.getProperty("m0.loader", "auto");
        List<String> tried = new ArrayList<>();
        // Java 9-15 allow it but print an illegal-access warning, and 16+ refuse it: auto uses it on 8 only.
        boolean java8 = System.getProperty("java.specification.version").startsWith("1.");
        if ((strategy.equals("auto") && java8) || strategy.equals("reflect")) {
            try {
                Method addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                addUrl.setAccessible(true);
                for (Path path : fetched) {
                    addUrl.invoke(own, path.toUri().toURL());
                }
                return injected(plugin, own, "reflect", groups);
            } catch (Throwable e) {
                tried.add("reflect: " + e);
            }
        }
        if (strategy.equals("auto") || strategy.equals("lookup")) {
            try {
                MethodHandle addUrl = trustedLookup().findVirtual(URLClassLoader.class, "addURL",
                    MethodType.methodType(void.class, URL.class));
                for (Path path : fetched) {
                    addUrl.invoke((URLClassLoader) own, path.toUri().toURL());
                }
                if (!tried.isEmpty()) {
                    plugin.getLogger().info("M0 libraries: before lookup, " + tried);
                }
                return injected(plugin, own, "lookup", groups);
            } catch (Throwable e) {
                tried.add("lookup: " + e);
            }
        }
        if (strategy.equals("global")) {
            try {
                return global(plugin, own, fetched, groups);
            } catch (Throwable e) {
                tried.add("global: " + e);
            }
        }
        if (!tried.isEmpty()) {
            plugin.getLogger().warning("M0 libraries: couldn't add to the plugin's class loader (" + tried + ")");
        }
        List<URL> urls = new ArrayList<>();
        urls.add(ownJar());
        for (Path path : fetched) {
            urls.add(path.toUri().toURL());
        }
        ClassLoader isolated = new ChildFirst(urls.toArray(new URL[0]), own,
            Libraries.class.getName().substring(0, Libraries.class.getName().lastIndexOf('.') + 1));
        plugin.getLogger().info("M0 libraries: loaded by strategy isolated (a child-first class loader of the plugin's)");
        return isolated;
    }

    private static ClassLoader injected(JavaPlugin plugin, ClassLoader own, String strategy,
                                        Map<String, List<String[]>> groups) {
        for (String probe : groups.keySet()) {
            Class<?> found = visible(probe, own);
            plugin.getLogger().info("M0 libraries: strategy " + strategy + ", " + probe + " now "
                + (found == null ? "STILL MISSING" : "visible from " + source(found)));
        }
        return own;
    }

    /** A plugin's class loader, or a shared library loader made by this class: not the server's own. */
    private static boolean ours(ClassLoader loader) {
        String name = loader.getClass().getName();
        return name.endsWith("PluginClassLoader") || name.endsWith("Libraries$Shared");
    }

    /**
     * No JDK internals: the libraries get a class loader of their own (parent: the server's,
     * like Spigot 1.16.5's {@code libraries:}), and Bukkit's global class cache, which every
     * plugin's class loader asks before its own jar up to 1.16.4, is wrapped so it also asks that
     * loader for the libraries' packages. Every plugin then sees the libraries.
     */
    @SuppressWarnings("unchecked")
    private static ClassLoader global(JavaPlugin plugin, ClassLoader own, List<Path> jars,
                                      Map<String, List<String[]>> groups) throws ReflectiveOperationException, IOException {
        Object pluginLoader = plugin.getPluginLoader();
        Field field = pluginLoader.getClass().getDeclaredField("classes");
        field.setAccessible(true);
        Map<String, Class<?>> cache = (Map<String, Class<?>>) field.get(pluginLoader);
        URL[] urls = new URL[jars.size()];
        java.util.Set<String> packages = new java.util.HashSet<>();
        for (int i = 0; i < urls.length; i++) {
            urls[i] = jars.get(i).toUri().toURL();
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jars.get(i).toFile())) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String entry = entries.nextElement().getName();
                    if (entry.endsWith(".class") && entry.indexOf('/') > 0 && !entry.startsWith("META-INF/")) {
                        packages.add(entry.substring(0, entry.lastIndexOf('/')).replace('/', '.'));
                    }
                }
            }
        }
        Shared shared = new Shared(urls, org.bukkit.Bukkit.class.getClassLoader());
        Map<String, Class<?>> wrapped = cache instanceof Cache ? cache : new Cache(cache);
        ((Cache) wrapped).add(shared, packages);
        if (wrapped != cache) {
            field.set(pluginLoader, wrapped);
        }
        return injected(plugin, own, "global", groups);
    }

    /** The libraries' own loader, for the {@code global} strategy. */
    private static final class Shared extends URLClassLoader {
        Shared(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }
    }

    /** Bukkit's global class cache, which also finds the shared libraries' classes. */
    private static final class Cache extends java.util.AbstractMap<String, Class<?>> {
        private final Map<String, Class<?>> cache;
        private final Map<String, ClassLoader> byPackage = new java.util.concurrent.ConcurrentHashMap<>();

        Cache(Map<String, Class<?>> cache) {
            this.cache = cache;
        }

        void add(ClassLoader loader, java.util.Set<String> packages) {
            for (String p : packages) {
                byPackage.putIfAbsent(p, loader);
            }
        }

        @Override
        public Class<?> get(Object key) {
            Class<?> found = cache.get(key);
            if (found == null && key instanceof String) {
                String name = (String) key;
                int dot = name.lastIndexOf('.');
                ClassLoader loader = dot < 0 ? null : byPackage.get(name.substring(0, dot));
                if (loader != null) {
                    try {
                        found = Class.forName(name, false, loader);
                    } catch (ClassNotFoundException | LinkageError e) {
                        return null;
                    }
                }
            }
            return found;
        }

        @Override
        public boolean containsKey(Object key) {
            return get(key) != null;
        }

        @Override
        public Class<?> put(String key, Class<?> value) {
            return cache.put(key, value);
        }

        @Override
        public Class<?> remove(Object key) {
            return cache.remove(key);
        }

        @Override
        public java.util.Set<Entry<String, Class<?>>> entrySet() {
            return cache.entrySet();
        }
    }

    /** The JDK's fully trusted lookup, which can call protected methods in java.base. */
    static MethodHandles.Lookup trustedLookup() throws ReflectiveOperationException {
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

    private static Class<?> visible(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    static String source(Class<?> type) {
        CodeSource code = type.getProtectionDomain().getCodeSource();
        String where = code == null || code.getLocation() == null ? "?" : code.getLocation().getPath();
        where = where.substring(where.lastIndexOf('/', where.length() - 2) + 1);
        return where + " (" + type.getClassLoader().getClass().getSimpleName() + ")";
    }

    private static Map<String, List<String[]>> read(ClassLoader own) throws IOException {
        Map<String, List<String[]>> groups = new LinkedHashMap<>();
        InputStream in = own.getResourceAsStream("META-INF/m0/libraries.txt");
        if (in == null) {
            return groups;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String[]> current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split(" ");
                if (parts[0].equals("probe")) {
                    current = new ArrayList<>();
                    groups.put(parts[1], current);
                } else if (parts[0].equals("jar") && current != null) {
                    current.add(new String[] {parts[1], parts[2], parts[3]});
                }
            }
        }
        return groups;
    }

    private static String fileName(String coordinates) {
        String[] gav = coordinates.split(":");
        return gav[1] + "-" + gav[2] + ".jar";
    }

    private static void download(String coordinates, String sha256, Path target) throws IOException {
        String[] gav = coordinates.split(":");
        String path = gav[0].replace('.', '/') + "/" + gav[1] + "/" + gav[2] + "/" + fileName(coordinates);
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        IOException last = null;
        for (String mirror : MIRRORS) {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(mirror + path).openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(60_000);
                if (connection.getResponseCode() != 200) {
                    throw new IOException("HTTP " + connection.getResponseCode() + " from " + mirror);
                }
                try (InputStream body = connection.getInputStream()) {
                    Files.copy(body, part, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!sha256(part).equals(sha256)) {
                    throw new IOException(coordinates + " from " + mirror + " didn't match its pinned SHA-256");
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                last = e;
            } finally {
                Files.deleteIfExists(part);
            }
        }
        throw new IOException("couldn't download " + coordinates + ": " + (last == null ? "" : last.getMessage()), last);
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static URL ownJar() throws IOException {
        CodeSource code = Libraries.class.getProtectionDomain().getCodeSource();
        if (code == null) {
            throw new IOException("can't find the plugin's own jar");
        }
        return new File(code.getLocation().getPath()).toURI().toURL();
    }

    /**
     * Loads everything in its URLs (the plugin's jar, then the libraries) itself, before asking
     * the plugin's class loader, except the bootstrap's own package, which must stay the plugin
     * loader's so {@link Delegate} is one type on both sides.
     */
    private static final class ChildFirst extends URLClassLoader {
        static {
            ClassLoader.registerAsParallelCapable();
        }

        private final String parentFirst;

        ChildFirst(URL[] urls, ClassLoader parent, String parentFirst) {
            super(urls, parent);
            this.parentFirst = parentFirst;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("java.") || name.startsWith(parentFirst) || name.startsWith("org.bukkit.")
                || name.startsWith("net.minecraft.") || name.startsWith("com.google.") || name.startsWith("org.yaml.")) {
                return super.loadClass(name, resolve);
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
