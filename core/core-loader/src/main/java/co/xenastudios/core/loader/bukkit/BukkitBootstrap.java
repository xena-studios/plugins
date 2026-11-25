package co.xenastudios.core.loader.bukkit;

import co.xenastudios.core.loader.JavaVersion;
import co.xenastudios.core.loader.Libraries;
import co.xenastudios.core.loader.Library;
import co.xenastudios.core.loader.LibraryException;
import co.xenastudios.core.loader.Manifest;
import co.xenastudios.core.loader.OnDemandLibraries;
import co.xenastudios.core.loader.PluginEntry;
import co.xenastudios.core.loader.Strategy;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The main class of a Bukkit-family plugin (Bukkit, Spigot, Paper, Folia and their forks,
 * 1.8.8 on), in Java compiled to Java 8 bytecode, so even a server on Java 8 can load it. It
 * never touches Kotlin: it checks the Java version, makes the libraries in the jar's
 * {@value Manifest#RESOURCE} visible, then loads the plugin's Kotlin {@link PluginEntry} by
 * name. A plugin's main class is a one-line subclass:
 *
 * <pre>{@code
 * public final class XUtilitiesBootstrap extends BukkitBootstrap {
 *     @Override protected String entryPoint() { return "co.xenastudios.xutilities.XUtilities"; }
 * }
 * }</pre>
 *
 * <p>The plugin.yml lists the same libraries under {@code libraries:}, so servers that read it
 * (Spigot 1.16.5 builds from May 2021 on, and Paper) fetch them and nothing else happens here.
 * Elsewhere they're downloaded into {@code plugins/Xena/libraries/}, shared by the family. An
 * owner can set, in {@code plugins/Xena/libraries.yml} or as {@code -D} flags:
 *
 * <ul>
 *   <li>{@code mirror} ({@code -Dxena.libraries.mirror}): a Maven repository to download from
 *       instead of Maven Central, or a list of them;</li>
 *   <li>{@code loader} ({@code -Dxena.libraries.loader}): {@code auto}, {@code reflect},
 *       {@code lookup} or {@code isolated} (see {@link Strategy}).</li>
 * </ul>
 *
 * <p>Nothing it does reaches the server as an exception: a plugin that can't start says why in
 * one line and is disabled.
 */
public abstract class BukkitBootstrap extends JavaPlugin {

    /**
     * {@code -Dxena.startupTimes=true}: each plugin logs how long its onLoad and onEnable took,
     * for the budget check (scripts/budgets/check.sh), which can't tell from the log where the
     * last plugin's enable ends.
     */
    private static final boolean STARTUP_TIMES = Boolean.getBoolean("xena.startupTimes");

    /**
     * The stdlib's biggest classes, which nearly every plugin needs at once (`listOf` alone
     * loads all of {@code ArraysKt}, 800 KiB of class files). Paper passes each class from
     * {@code libraries:} through ASM as it loads it, about 80 ms for these on Paper 1.21.11, so
     * a thread of the plugin's own loads them while the server prepares its worlds between
     * onLoad and onEnable, instead of the server thread at enable (DECISIONS.md, "Budgets").
     */
    private static final String[] PRELOAD = {
        "kotlin.collections.ArraysKt", "kotlin.collections.CollectionsKt", "kotlin.collections.MapsKt",
        "kotlin.collections.SetsKt", "kotlin.text.StringsKt", "kotlin.sequences.SequencesKt", "kotlin.ranges.RangesKt",
        "kotlin.text.CharsKt", "kotlin.text.Regex", "kotlin.text.HexExtensionsKt", "kotlin.time.Duration",
        "kotlin.time.DurationKt", "kotlin.uuid.Uuid", "kotlin.uuid.UuidKt", "kotlin.collections.ArrayDeque",
        "kotlin.collections.builders.MapBuilder",
    };

    /** The classes a plugin loads as it starts, in the jar, written by build-logic's R8Shrink. */
    private static final String STARTUP_CLASSES = "META-INF/xena/startup-classes.txt";

    private PluginEntry entry;
    /** The entry point's class, made at onEnable when it has nothing to do at onLoad. */
    private Class<?> entryType;
    private long loadNanos;

    /** The binary name of the plugin's {@link PluginEntry}, such as {@code co.xenastudios.xutilities.XUtilities}. */
    protected abstract String entryPoint();

    @Override
    public final void onLoad() {
        long started = System.nanoTime();
        try {
            Class<?> type = start();
            if (type != null) {
                // An entry point with work at onLoad (a service other plugins look up as they
                // enable) is made now; the rest wait for onEnable, so the stdlib's classes have
                // loaded on their own thread by then.
                if (overridesLoad(type)) {
                    entry = construct(type);
                    entry.load();
                } else {
                    entryType = type;
                }
            }
        } catch (LibraryException e) {
            entry = null;
            entryType = null;
            getLogger().severe(e.getMessage());
        } catch (Throwable e) {
            entry = null;
            entryType = null;
            getLogger().log(Level.SEVERE, getName() + " couldn't start: " + e, e);
        }
        loadNanos = System.nanoTime() - started;
    }

    @Override
    public final void onEnable() {
        if (entry != null || entryType != null) {
            long started = System.nanoTime();
            try {
                if (entry == null) {
                    entry = construct(entryType);
                    entryType = null;
                }
                entry.enable();
                if (STARTUP_TIMES) {
                    // On Paper with paper-plugin.yml, the plugin's loader too (its libraries handed
                    // to Paper), which no line of the server's shows.
                    String loader = System.getProperty("xena.startupTimes.loader." + getName());
                    String provider = loader == null ? ""
                        : String.format(Locale.ROOT, "loader %.1f ms, ", Long.parseLong(loader) / 1e6);
                    getLogger().info(String.format(Locale.ROOT, "Startup times: %sonLoad %.1f ms, onEnable %.1f ms",
                        provider, loadNanos / 1e6, (System.nanoTime() - started) / 1e6));
                }
                return;
            } catch (Throwable e) {
                getLogger().log(Level.SEVERE, getName() + " couldn't start: " + e, e);
                entry = null;
                entryType = null;
            }
        }
        getServer().getPluginManager().disablePlugin(this);
    }

    @Override
    public final void onDisable() {
        PluginEntry stopping = entry;
        entry = null;
        if (stopping != null) {
            try {
                stopping.disable();
            } catch (Throwable e) {
                getLogger().log(Level.SEVERE, getName() + " didn't stop cleanly: " + e, e);
            }
        }
    }

    /** The entry point's class, or null (having said why) when this server can't run the plugin. */
    private Class<?> start() throws Exception {
        int running = JavaVersion.current();
        int needed = neededJava();
        if (running < needed) {
            getLogger().severe(getName() + " needs Java " + needed + " or newer, and this server runs Java "
                + System.getProperty("java.version") + ". Start the server with Java " + needed + " or newer.");
            return null;
        }
        ClassLoader own = getClass().getClassLoader();
        List<Library> libraries = Manifest.read(own);
        ClassLoader loader = own;
        if (!libraries.isEmpty()) {
            File plugins = getDataFolder().getParentFile();
            YamlConfiguration settings = LoaderSettings.read(plugins);
            loader = Libraries.load(libraries, own, getFile().toURI().toURL(), LoaderSettings.cache(plugins, settings), running, strategy(settings),
                getLogger()).loader();
            checkKotlin(libraries, loader);
            preload(libraries, loader);
        }
        return Class.forName(entryPoint(), false, loader);
    }

    /** Whether the entry point has its own {@link PluginEntry#load()}. */
    private static boolean overridesLoad(Class<?> type) {
        try {
            return type.getMethod("load").getDeclaringClass() != PluginEntry.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Loads, without initializing, the classes the plugin needs as it starts, on a daemon thread
     * of its own while the server prepares its worlds between onLoad and onEnable: the jar's
     * {@value #STARTUP_CLASSES} (written at build time from a list recorded on a real server,
     * DECISIONS.md), or {@link #PRELOAD} when the jar has none and uses the stdlib. Class
     * loading is safe from any thread: if the server's thread wants a class this one is loading,
     * it waits for that class alone, as it would have loaded it itself. Nothing in them runs, so
     * no listener, task or static state of a module starts here.
     */
    private void preload(List<Library> libraries, final ClassLoader loader) {
        final List<String> names = startupClasses();
        if (names.isEmpty()) {
            boolean kotlin = false;
            for (Library library : libraries) {
                kotlin |= library.coordinates().startsWith("org.jetbrains.kotlin:kotlin-stdlib:");
            }
            if (!kotlin) {
                return;
            }
            Collections.addAll(names, PRELOAD);
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                for (String name : names) {
                    try {
                        Class.forName(name, false, loader);
                    } catch (Throwable ignored) {
                        // A class this server doesn't have (another Kotlin release, a platform path
                        // that isn't this one's): the plugin loads what it uses itself.
                    }
                }
            }
        }, getName() + " preloading");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    /** The jar's {@value #STARTUP_CLASSES}, one class a line; empty when it has none. */
    private List<String> startupClasses() {
        List<String> names = new ArrayList<>();
        InputStream in = getClass().getClassLoader().getResourceAsStream(STARTUP_CLASSES);
        if (in == null) {
            return names;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.trim().isEmpty()) {
                    names.add(line.trim());
                }
            }
        } catch (IOException e) {
            names.clear();
        }
        return names;
    }

    /**
     * The libraries the plugin fetches only when a feature needs one (its JDBC drivers), from its
     * jar's {@value OnDemandLibraries#RESOURCE}, in the family's shared cache with the owner's
     * mirror settings: for the Kotlin side, which can't reach the cache otherwise. Reads the
     * settings file, so call it off the server's threads, or once at startup.
     */
    public final OnDemandLibraries onDemandLibraries() throws IOException {
        return OnDemandLibraries.read(getClass().getClassLoader(), LoaderSettings.cache(getDataFolder().getParentFile()));
    }

    /** The Java the plugin's Kotlin classes were compiled for, from the entry point's class file. */
    private int neededJava() throws IOException {
        InputStream in = getClass().getClassLoader().getResourceAsStream(entryPoint().replace('.', '/') + ".class");
        if (in == null) {
            throw new IOException("the jar has no " + entryPoint() + ": entryPoint() names a class that isn't there");
        }
        try (InputStream header = in) {
            return Math.max(8, JavaVersion.ofClassFile(header));
        }
    }

    private Strategy strategy(YamlConfiguration settings) {
        String name = System.getProperty("xena.libraries.loader", settings.getString("loader"));
        try {
            return Strategy.parse(name);
        } catch (IllegalArgumentException e) {
            getLogger().warning("loader: " + e.getMessage() + ", so auto is used.");
            return null;
        }
    }

    /**
     * Before 1.16.5 every plugin sees the stdlib the first one loaded, so a plugin built with
     * another Kotlin runs on that one. Every Xena plugin pins the same version; this says so
     * when one doesn't, or when another plugin brought its own Kotlin.
     */
    private void checkKotlin(List<Library> libraries, ClassLoader loader) {
        for (Library library : libraries) {
            if (!library.coordinates().startsWith("org.jetbrains.kotlin:kotlin-stdlib:")) {
                continue;
            }
            try {
                Class<?> version = Class.forName("kotlin.KotlinVersion", true, loader);
                String current = String.valueOf(version.getField("CURRENT").get(null));
                if (!current.equals(library.version())) {
                    CodeSource source = version.getProtectionDomain().getCodeSource();
                    getLogger().warning(getName() + " was built for Kotlin " + library.version() + ", but Kotlin "
                        + current + " is already loaded" + (source == null ? "" : " from " + source.getLocation())
                        + ". Update every Xena plugin to the same release, and check other plugins for an old Kotlin.");
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                getLogger().warning("Couldn't read the Kotlin version: " + e);
            }
        }
    }

    /** The entry point's public constructor taking this plugin (as itself, JavaPlugin or Plugin). */
    private PluginEntry construct(Class<?> type) throws Exception {
        if (!PluginEntry.class.isAssignableFrom(type)) {
            throw new ClassCastException(type.getName() + " doesn't implement " + PluginEntry.class.getName());
        }
        for (Constructor<?> constructor : type.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isInstance(this)) {
                try {
                    return (PluginEntry) constructor.newInstance(this);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    throw cause instanceof Exception ? (Exception) cause : e;
                }
            }
        }
        throw new NoSuchMethodException(type.getName() + " has no public constructor taking the plugin");
    }
}
