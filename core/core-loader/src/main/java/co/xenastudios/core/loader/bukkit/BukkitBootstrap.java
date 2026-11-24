package co.xenastudios.core.loader.bukkit;

import co.xenastudios.core.loader.JavaVersion;
import co.xenastudios.core.loader.Libraries;
import co.xenastudios.core.loader.Library;
import co.xenastudios.core.loader.LibraryCache;
import co.xenastudios.core.loader.LibraryException;
import co.xenastudios.core.loader.Manifest;
import co.xenastudios.core.loader.PluginEntry;
import co.xenastudios.core.loader.Strategy;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    /** The family's shared folder, beside each plugin's own. */
    private static final String SHARED = "Xena";
    private static final String SETTINGS = "libraries.yml";

    private PluginEntry entry;

    /** The binary name of the plugin's {@link PluginEntry}, such as {@code co.xenastudios.xutilities.XUtilities}. */
    protected abstract String entryPoint();

    @Override
    public final void onLoad() {
        try {
            entry = start();
            if (entry != null) {
                entry.load();
            }
        } catch (LibraryException e) {
            entry = null;
            getLogger().severe(e.getMessage());
        } catch (Throwable e) {
            entry = null;
            getLogger().log(Level.SEVERE, getName() + " couldn't start: " + e, e);
        }
    }

    @Override
    public final void onEnable() {
        if (entry != null) {
            try {
                entry.enable();
                return;
            } catch (Throwable e) {
                getLogger().log(Level.SEVERE, getName() + " couldn't start: " + e, e);
                entry = null;
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

    /** The entry point, or null (having said why) when this server can't run the plugin. */
    private PluginEntry start() throws Exception {
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
            File shared = new File(getDataFolder().getParentFile(), SHARED);
            YamlConfiguration settings = settings(new File(shared, SETTINGS));
            String help = "If this server can't reach Maven Central, set mirror: in " + new File(shared, SETTINGS)
                + " to a Maven repository it can reach.";
            LibraryCache cache = new LibraryCache(new File(shared, "libraries").toPath(), mirrors(settings), help);
            loader = Libraries.load(libraries, own, getFile().toURI().toURL(), cache, running, strategy(settings),
                getLogger()).loader();
            checkKotlin(libraries, loader);
        }
        return construct(Class.forName(entryPoint(), true, loader));
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

    private static YamlConfiguration settings(File file) {
        return file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    private static List<String> mirrors(YamlConfiguration settings) {
        String flag = System.getProperty("xena.libraries.mirror");
        if (flag != null && !flag.trim().isEmpty()) {
            List<String> mirrors = new ArrayList<>();
            Collections.addAll(mirrors, flag.split(","));
            return mirrors;
        }
        if (settings.isList("mirror")) {
            return settings.getStringList("mirror");
        }
        String mirror = settings.getString("mirror");
        return mirror == null || mirror.trim().isEmpty() ? LibraryCache.DEFAULT_MIRRORS : Collections.singletonList(mirror);
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
