package co.xenastudios.m0.loader;

import java.lang.reflect.Constructor;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The plugin's main class, in Java and compiled to Java 8 bytecode. It never touches a Kotlin
 * class itself: it makes the Kotlin stdlib (and any other fetched library) visible first, then
 * loads the Kotlin {@link Delegate} by name. On Spigot 1.16.5+ and Paper, {@code libraries:}
 * in plugin.yml has already put the stdlib on the plugin's class path and nothing is fetched.
 */
public abstract class Bootstrap extends JavaPlugin {

    private Delegate delegate;
    private long loadNanos;

    /** The Kotlin class implementing {@link Delegate}, with a constructor taking the plugin. */
    protected abstract String delegateClass();

    @Override
    public final void onLoad() {
        long start = System.nanoTime();
        String spec = System.getProperty("java.specification.version");
        int running = spec.startsWith("1.") ? Integer.parseInt(spec.substring(2)) : Integer.parseInt(spec);
        int needed = needed();
        if (running >= needed) {
            try {
                ClassLoader loader = Libraries.ensure(this);
                Class<?> type = Class.forName(delegateClass(), true, loader);
                Constructor<?> constructor = type.getConstructor(JavaPlugin.class);
                delegate = (Delegate) constructor.newInstance(this);
            } catch (Throwable e) {
                getLogger().log(Level.SEVERE, "couldn't start: " + e, e);
            }
        } else {
            getLogger().severe(getName() + " needs Java " + needed + " or newer; this server runs Java " + spec
                + ". Start it with Java " + needed + " or newer (1.8.8 servers run fine on 17 and 21).");
        }
        loadNanos = System.nanoTime() - start;
    }

    /** The Java version the Kotlin classes were compiled for, read from the delegate's class file. */
    private int needed() {
        try (java.io.InputStream in = getClass().getClassLoader()
                .getResourceAsStream(delegateClass().replace('.', '/') + ".class")) {
            byte[] header = new byte[8];
            if (in == null || in.read(header) < 8) {
                return 17;
            }
            return ((header[6] & 0xff) << 8 | (header[7] & 0xff)) - 44;
        } catch (java.io.IOException e) {
            return 17;
        }
    }

    @Override
    public final void onEnable() {
        if (delegate == null) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        long start = System.nanoTime();
        delegate.enable();
        long enable = System.nanoTime() - start;
        getLogger().info(String.format("M0 startup: onLoad %.1f ms (libraries + delegate), onEnable %.1f ms, total %.1f ms",
            loadNanos / 1e6, enable / 1e6, (loadNanos + enable) / 1e6));
    }

    @Override
    public final void onDisable() {
        if (delegate != null) {
            delegate.disable();
        }
    }
}
