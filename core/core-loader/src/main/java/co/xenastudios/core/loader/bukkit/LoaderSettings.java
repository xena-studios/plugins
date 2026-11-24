package co.xenastudios.core.loader.bukkit;

import co.xenastudios.core.loader.LibraryCache;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The family's library settings on Bukkit-family servers, {@code plugins/Xena/libraries.yml} or
 * {@code -D} flags, and the cache they describe: for the shim ({@link BukkitBootstrap}) and
 * Paper's plugin loader alike.
 */
public final class LoaderSettings {

    /** The family's shared folder, beside each plugin's own. */
    static final String SHARED = "Xena";
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
        File shared = new File(plugins, SHARED);
        String help = "If this server can't reach Maven Central, set mirror: in " + new File(shared, FILE)
            + " to a Maven repository it can reach.";
        return new LibraryCache(new File(shared, "libraries").toPath(), mirrors(settings), help);
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
}
