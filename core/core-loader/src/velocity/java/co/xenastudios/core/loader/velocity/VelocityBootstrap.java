package co.xenastudios.core.loader.velocity;

import co.xenastudios.core.loader.JavaVersion;
import co.xenastudios.core.loader.JulBridge;
import co.xenastudios.core.loader.Libraries;
import co.xenastudios.core.loader.Library;
import co.xenastudios.core.loader.LibraryCache;
import co.xenastudios.core.loader.LibraryException;
import co.xenastudios.core.loader.LibrarySettings;
import co.xenastudios.core.loader.Manifest;
import co.xenastudios.core.loader.OnDemandLibraries;
import co.xenastudios.core.loader.PluginEntry;
import co.xenastudios.core.loader.Startup;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The main class of a Xena plugin on Velocity (3.4 on, and 4.x), in Java, so nothing of Kotlin
 * is touched before the stdlib is there. Velocity makes it with Guice, which fills in the proxy,
 * the plugin's logger and its folder; on {@link ProxyInitializeEvent} it makes the libraries in
 * the jar's {@value Manifest#RESOURCE} visible (checked against their pins on threads of their own
 * from the moment Velocity makes the plugin), then loads the plugin's Kotlin
 * {@link PluginEntry} by name, with a public constructor taking this object. A plugin's main
 * class is a one-line subclass, named in its {@code velocity-plugin.json}:
 *
 * <pre>{@code
 * public final class ZChatVelocityBootstrap extends VelocityBootstrap {
 *     @Override protected String entryPoint() { return "co.xenastudios.zchat.velocity.ZChatVelocity"; }
 * }
 * }</pre>
 *
 * <p>Velocity has no {@code libraries:}, so the libraries come from the family's cache,
 * {@code plugins/Xena/libraries/}, downloaded and checked against their pins as on Bukkit, and are
 * added with Velocity's own {@code PluginManager.addToClasspath}, the API it has for this
 * (DECISIONS.md, "The proxy platforms"). The owner's mirror and loader settings are
 * {@code plugins/Xena/libraries.yml} or the same {@code -D} flags as on Bukkit.
 *
 * <p>It's also how the Xena plugins on a proxy find each other's family board: Velocity has no
 * registry for objects plugins share, so each Xena plugin's main object hands out the board it
 * holds as a JDK {@link Supplier}, which every plugin's relocated core reads the same.
 */
public abstract class VelocityBootstrap implements Supplier<Object> {

    @Inject
    private ProxyServer server;

    @Inject
    private org.slf4j.Logger logger;

    private Path dataFolder;

    private volatile Object shared;
    private PluginEntry entry;
    private FutureTask<Ahead> ahead;
    private long injectNanos;

    /** The binary name of the plugin's {@link PluginEntry}. */
    protected abstract String entryPoint();

    /** The proxy. */
    public final ProxyServer server() {
        return server;
    }

    /** The plugin's logger, which Velocity's console shows as {@code [<plugin id>]}. */
    public final org.slf4j.Logger logger() {
        return logger;
    }

    /** The plugin's folder, {@code plugins/<plugin id>/}. */
    public final Path dataFolder() {
        return dataFolder;
    }

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
        return OnDemandLibraries.read(getClass().getClassLoader(), LibrarySettings.read(plugins()).cache(plugins()));
    }

    /**
     * Called by Guice once the fields are in, as Velocity makes the plugin, before it makes the
     * next one and well before {@link ProxyInitializeEvent}, when the plugin may first add a jar
     * to its class path: starts reading the jar's libraries and checking the cached ones against
     * their pins (about 20 ms of hashing on a fast machine, more where the JIT is slower to warm),
     * each on a thread of its own, so that's done, or nearly, by the time it's wanted. Nothing
     * else happens on Velocity's thread here.
     */
    @Inject
    private void prepare(@DataDirectory Path folder) {
        long started = System.nanoTime();
        dataFolder = folder;
        final ClassLoader own = getClass().getClassLoader();
        final File plugins = plugins();
        final String name = getClass().getSimpleName() + " libraries";
        FutureTask<Ahead> task = new FutureTask<>(new Callable<Ahead>() {
            @Override
            public Ahead call() throws IOException {
                List<Library> libraries = Manifest.read(own);
                LibraryCache cache = LibrarySettings.read(plugins).cache(plugins);
                cache.fetchAhead(libraries, name);
                return new Ahead(libraries, cache);
            }
        });
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        ahead = task;
        injectNanos = System.nanoTime() - started;
    }

    @Subscribe
    public final void onProxyInitialize(ProxyInitializeEvent event) {
        long started = System.nanoTime();
        Logger log = JulBridge.to(name(), sink());
        try {
            Class<?> type = start(log);
            if (type == null) {
                return;
            }
            long loaded = System.nanoTime();
            PluginEntry made = Startup.construct(type, this);
            made.load();
            made.enable();
            entry = made;
            if (Startup.TIMES) {
                // Each part on the proxy's threads: making the plugin, its libraries, its start.
                long now = System.nanoTime();
                logger.info(String.format(Locale.ROOT, "Startup times: inject %.1f ms, libraries %.1f ms, enable %.1f ms",
                    injectNanos / 1e6, (loaded - started) / 1e6, (now - loaded) / 1e6));
            }
        } catch (LibraryException e) {
            logger.error(e.getMessage());
        } catch (Throwable e) {
            logger.error(name() + " couldn't start: " + e, e);
        }
    }

    @Subscribe
    public final void onProxyShutdown(ProxyShutdownEvent event) {
        PluginEntry stopping = entry;
        entry = null;
        if (stopping != null) {
            try {
                stopping.disable();
            } catch (Throwable e) {
                logger.error(name() + " didn't stop cleanly: " + e, e);
            }
        }
    }

    /** The entry point's class, or null (having said why) when this proxy can't run the plugin. */
    private Class<?> start(Logger log) throws Exception {
        ClassLoader own = getClass().getClassLoader();
        int running = JavaVersion.current();
        int needed = Startup.neededJava(own, entryPoint());
        if (running < needed) {
            logger.error(name() + " needs Java " + needed + " or newer, and this proxy runs Java "
                + System.getProperty("java.version") + ". Start the proxy with Java " + needed + " or newer.");
            return null;
        }
        Ahead prepared = prepared(own);
        List<Library> libraries = prepared.libraries;
        if (!libraries.isEmpty()) {
            final Object plugin = this;
            Libraries.load(libraries, own, prepared.cache,
                jar -> server.getPluginManager().addToClasspath(plugin, jar), "Velocity's addToClasspath", log);
            Startup.checkKotlin(name(), libraries, own, log);
            Startup.preload(libraries, own, own, name() + " preloading");
        }
        return Class.forName(entryPoint(), false, own);
    }

    /** What {@link #prepare} started, once it's done; read here if Guice never called it. */
    private Ahead prepared(ClassLoader own) throws Exception {
        if (ahead == null) {
            File plugins = plugins();
            return new Ahead(Manifest.read(own), LibrarySettings.read(plugins).cache(plugins));
        }
        try {
            return ahead.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : e;
        }
    }

    /** The jar's libraries, and the cache checking them. */
    private static final class Ahead {
        final List<Library> libraries;
        final LibraryCache cache;

        Ahead(List<Library> libraries, LibraryCache cache) {
            this.libraries = libraries;
            this.cache = cache;
        }
    }

    private File plugins() {
        return dataFolder.toAbsolutePath().getParent().toFile();
    }

    private String name() {
        PluginContainer container = server == null ? null : server.getPluginManager().fromInstance(this).orElse(null);
        return container == null ? getClass().getSimpleName() : container.getDescription().getName().orElse(container.getDescription().getId());
    }

    private JulBridge.Sink sink() {
        return new JulBridge.Sink() {
            @Override
            public void info(String message) {
                logger.info(message);
            }

            @Override
            public void warn(String message, Throwable cause) {
                logger.warn(message, cause);
            }

            @Override
            public void error(String message, Throwable cause) {
                logger.error(message, cause);
            }
        };
    }
}
