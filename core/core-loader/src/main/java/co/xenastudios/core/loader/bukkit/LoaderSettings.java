package co.xenastudios.core.loader.bukkit;

import co.xenastudios.core.loader.LibraryCache;
import co.xenastudios.core.loader.LibrarySettings;
import java.io.File;
import java.util.Collections;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The family's library settings on Bukkit-family servers, {@code plugins/Xena/libraries.yml} or
 * {@code -D} flags, and the cache they describe: for the shim ({@link BukkitBootstrap}) and
 * Paper's plugin loader alike.
 */
public final class LoaderSettings {

    static final String SHARED = LibrarySettings.SHARED;
    static final String FILE = "libraries.yml";

    private LoaderSettings() {}

    /** {@code plugins/Xena/libraries.yml}, read; empty when there's none. */
    static YamlConfiguration read(File plugins) {
        File file = new File(new File(plugins, SHARED), FILE);
        return file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    /** The family's cache in {@code plugins/Xena/libraries/}, with the owner's mirrors. */
    public static LibraryCache cache(File plugins) {
        return cache(plugins, read(plugins));
    }

    static LibraryCache cache(File plugins, YamlConfiguration settings) {
        List<String> configured = null;
        if (settings.isList("mirror")) {
            configured = settings.getStringList("mirror");
        } else if (settings.getString("mirror") != null && !settings.getString("mirror").trim().isEmpty()) {
            configured = Collections.singletonList(settings.getString("mirror"));
        }
        return LibrarySettings.cache(plugins, configured);
    }
}
