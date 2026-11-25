package co.xenastudios.core.loader.bukkit;

import co.xenastudios.core.loader.JavaVersion;
import co.xenastudios.core.loader.Libraries;
import co.xenastudios.core.loader.Library;
import co.xenastudios.core.loader.LibraryException;
import co.xenastudios.core.loader.Manifest;
import co.xenastudios.core.loader.OnDemandLibraries;
import co.xenastudios.core.loader.PluginEntry;
import co.xenastudios.core.loader.Startup;
import co.xenastudios.core.loader.Strategy;
import java.io.File;
import java.io.IOException;
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
                if (Startup.overridesLoad(type)) {
                    entry = Startup.construct(type, this);
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
                    entry = Startup.construct(entryType, this);
                    entryType = null;
                }
                entry.enable();
                if (Startup.TIMES) {
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
        int needed = Startup.neededJava(getClass().getClassLoader(), entryPoint());
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
            Startup.checkKotlin(getName(), libraries, loader, getLogger());
            Startup.preload(libraries, own, loader, getName() + " preloading");
        }
        return Class.forName(entryPoint(), false, loader);
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

    private Strategy strategy(YamlConfiguration settings) {
        String name = System.getProperty("xena.libraries.loader", settings.getString("loader"));
        try {
            return Strategy.parse(name);
        } catch (IllegalArgumentException e) {
            getLogger().warning("loader: " + e.getMessage() + ", so auto is used.");
            return null;
        }
    }
}
