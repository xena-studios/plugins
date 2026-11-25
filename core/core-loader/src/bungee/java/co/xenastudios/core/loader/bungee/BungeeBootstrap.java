package co.xenastudios.core.loader.bungee;

import co.xenastudios.core.loader.JavaVersion;
import co.xenastudios.core.loader.Libraries;
import co.xenastudios.core.loader.Library;
import co.xenastudios.core.loader.LibraryException;
import co.xenastudios.core.loader.LibrarySettings;
import co.xenastudios.core.loader.Manifest;
import co.xenastudios.core.loader.OnDemandLibraries;
import co.xenastudios.core.loader.PluginEntry;
import co.xenastudios.core.loader.Startup;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.md_5.bungee.api.plugin.Plugin;

/**
 * The main class of a Xena plugin on BungeeCord and Waterfall, in Java compiled to Java 8
 * bytecode, as the Bukkit shim is: it checks the Java version, makes the libraries in the jar's
 * {@value Manifest#RESOURCE} visible, then loads the plugin's Kotlin {@link PluginEntry} by
 * name, with a public constructor taking this plugin. A plugin's main class is a one-line
 * subclass, named in its {@code bungee.yml}:
 *
 * <pre>{@code
 * public final class ZChatBungeeBootstrap extends BungeeBootstrap {
 *     @Override protected String entryPoint() { return "co.xenastudios.zchat.bungee.ZChatBungee"; }
 * }
 * }</pre>
 *
 * <p>The {@code bungee.yml} lists the same libraries under {@code libraries:}, which BungeeCord
 * and Waterfall have read since 2021, so they fetch them and nothing happens here; on a proxy
 * that doesn't, they come from {@code plugins/Xena/libraries/}, added to the plugin's class
 * loader as on old Bukkit, with the same settings ({@code plugins/Xena/libraries.yml} or
 * {@code -D} flags).
 *
 * <p>BungeeCord has no registry for objects plugins share either, so, as on Velocity, each Xena
 * plugin's main object hands out the family board it holds as a JDK {@link Supplier}.
 */
public abstract class BungeeBootstrap extends Plugin implements Supplier<Object> {

    private volatile Object shared;
    private PluginEntry entry;
    private Class<?> entryType;
    private long loadNanos;

    /** The binary name of the plugin's {@link PluginEntry}. */
    protected abstract String entryPoint();

    /** What this plugin shares with the other Xena plugins on the proxy: its family board. */
    @Override
    public final Object get() {
        return shared;
    }

    /** Sets what {@link #get()} hands out: the family's board, once the Kotlin side has it. */
    public final void share(Object value) {
        shared = value;
    }

    /**
     * The libraries the plugin fetches only when a feature needs one (its JDBC drivers), from its
     * jar's {@value OnDemandLibraries#RESOURCE}, in the family's cache. Reads the settings file,
     * so call it off the proxy's threads, or once at startup.
     */
    public final OnDemandLibraries onDemandLibraries() throws IOException {
        File plugins = getDataFolder().getParentFile();
        return OnDemandLibraries.read(getClass().getClassLoader(), LibrarySettings.read(plugins).cache(plugins));
    }

    @Override
    public final void onLoad() {
        long started = System.nanoTime();
        try {
            Class<?> type = start();
            if (type != null && Startup.overridesLoad(type)) {
                entry = Startup.construct(type, this);
                entry.load();
            } else {
                entryType = type;
            }
        } catch (LibraryException e) {
            entry = null;
            entryType = null;
            getLogger().severe(e.getMessage());
        } catch (Throwable e) {
            entry = null;
            entryType = null;
            getLogger().log(Level.SEVERE, getDescription().getName() + " couldn't start: " + e, e);
        }
        loadNanos = System.nanoTime() - started;
    }

    @Override
    public final void onEnable() {
        if (entry == null && entryType == null) {
            return;
        }
        long started = System.nanoTime();
        try {
            if (entry == null) {
                entry = Startup.construct(entryType, this);
                entryType = null;
            }
            entry.enable();
            if (Startup.TIMES) {
                getLogger().info(String.format(Locale.ROOT, "Startup times: onLoad %.1f ms, onEnable %.1f ms",
                    loadNanos / 1e6, (System.nanoTime() - started) / 1e6));
            }
        } catch (Throwable e) {
            getLogger().log(Level.SEVERE, getDescription().getName() + " couldn't start: " + e, e);
            entry = null;
            entryType = null;
        }
    }

    @Override
    public final void onDisable() {
        PluginEntry stopping = entry;
        entry = null;
        if (stopping != null) {
            try {
                stopping.disable();
            } catch (Throwable e) {
                getLogger().log(Level.SEVERE, getDescription().getName() + " didn't stop cleanly: " + e, e);
            }
        }
    }

    /** The entry point's class, or null (having said why) when this proxy can't run the plugin. */
    private Class<?> start() throws Exception {
        String name = getDescription().getName();
        ClassLoader own = getClass().getClassLoader();
        int running = JavaVersion.current();
        int needed = Startup.neededJava(own, entryPoint());
        if (running < needed) {
            getLogger().severe(name + " needs Java " + needed + " or newer, and this proxy runs Java "
                + System.getProperty("java.version") + ". Start the proxy with Java " + needed + " or newer.");
            return null;
        }
        List<Library> libraries = Manifest.read(own);
        ClassLoader loader = own;
        if (!libraries.isEmpty()) {
            File plugins = getDataFolder().getParentFile();
            LibrarySettings settings = LibrarySettings.read(plugins);
            loader = Libraries.load(libraries, own, getFile().toURI().toURL(), settings.cache(plugins), running,
                settings.strategy(getLogger()), getLogger()).loader();
            Startup.checkKotlin(name, libraries, loader, getLogger());
            Startup.preload(libraries, own, loader, name + " preloading");
        }
        return Class.forName(entryPoint(), false, loader);
    }
}
