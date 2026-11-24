package co.xenastudios.core.loader;

/**
 * The plugin's real code, in Kotlin, which a platform's Java shim loads by name once the
 * libraries are visible ({@code BukkitBootstrap} on Bukkit). The class needs a public
 * constructor taking the platform's plugin object (on Bukkit, the bootstrap, {@code JavaPlugin}
 * or {@code Plugin}).
 */
public interface PluginEntry {

    /** When the server loads the plugin, before any plugin is enabled. */
    default void load() {}

    void enable();

    void disable();
}
