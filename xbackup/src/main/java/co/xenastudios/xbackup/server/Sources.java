package co.xenastudios.xbackup.server;

import co.xenastudios.xbackup.config.XBConfig;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Works out what a backup takes, as paths relative to the server folder.
 *
 * <p>Worlds are found by their save folder, never {@code World#getWorldFolder()} alone: on
 * 26.x a world is one save folder with each dimension under {@code dimensions/<ns>/<dim>/},
 * and {@code getWorldFolder()} returns the dimension's folder. Walking up to the folder with
 * {@code level.dat} gives the save folder on both layouts (1.21.11's {@code world_nether}
 * has its own).
 */
public final class Sources {

    private static final Set<String> SERVER_FILE_ENDINGS = Set.of(".yml", ".yaml", ".properties", ".json", ".toml", ".txt");

    private Sources() {}

    /** What to take: world save folders (read during the pause) and everything else. */
    public record Resolved(List<String> worlds, List<String> others) {}

    /** Call on the global thread: reads the server's worlds. */
    public static List<Path> worldFolders() {
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Set<Path> folders = new LinkedHashSet<>();
        for (World world : Bukkit.getWorlds()) {
            Path folder = world.getWorldFolder().toPath().toAbsolutePath().normalize();
            Path save = folder;
            while (save != null && !Files.exists(save.resolve("level.dat")) && save.startsWith(container)
                && !save.equals(container)) {
                save = save.getParent();
            }
            folders.add(save != null && Files.exists(save.resolve("level.dat")) ? save : folder);
        }
        return List.copyOf(folders);
    }

    /**
     * Resolves the config's sources.
     *
     * @param worldFolders from {@link #worldFolders()}
     * @param extra paths other plugins registered through the API
     */
    public static Resolved resolve(Path root, XBConfig.Sources settings, List<Path> worldFolders, Path pluginsFolder,
                                   List<Path> extra, Logger logger) {
        List<String> worlds = new ArrayList<>();
        List<String> others = new ArrayList<>();
        if (settings.worlds()) {
            for (Path folder : worldFolders) {
                String relative = relative(root, folder);
                if (relative == null) {
                    logger.warning("The world folder " + folder + " is outside the server folder, so xBackup can't"
                        + " back it up. Keep the world container inside the server folder.");
                } else if (!worlds.contains(relative)) {
                    worlds.add(relative);
                }
            }
        }
        if (settings.plugins()) {
            String plugins = relative(root, pluginsFolder);
            if (plugins != null) {
                others.add(plugins);
            }
        }
        if (settings.serverFiles()) {
            others.addAll(serverFiles(root));
        }
        for (String path : settings.extra()) {
            String clean = path.strip().replace('\\', '/');
            while (clean.endsWith("/")) {
                clean = clean.substring(0, clean.length() - 1);
            }
            if (clean.isEmpty() || clean.startsWith("/") || clean.contains("..")) {
                logger.warning("sources.extra: \"" + path + "\" must be a path inside the server folder; it's ignored.");
                continue;
            }
            others.add(clean);
        }
        for (Path path : extra) {
            String relative = relative(root, path);
            if (relative != null) {
                others.add(relative);
            }
        }
        // A world inside another source is still read during the pause, as a world.
        return new Resolved(worlds, others.stream().distinct().toList());
    }

    /** The server's own settings files beside server.properties, and its config folder. */
    static List<String> serverFiles(Path root) {
        List<String> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (name.startsWith(".") || !Files.isRegularFile(file)) {
                    continue;
                }
                for (String ending : SERVER_FILE_ENDINGS) {
                    if (lower.endsWith(ending)) {
                        files.add(name);
                        break;
                    }
                }
            }
        } catch (IOException e) {
            // An unreadable server folder shows up when the backup runs.
        }
        if (Files.isDirectory(root.resolve("config"))) {
            files.add("config");
        }
        files.sort(null);
        return files;
    }

    /** The path relative to the root with {@code /}, "" for the root itself, or null if it's outside. */
    public static String relative(Path root, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path base = root.toAbsolutePath().normalize();
        if (!absolute.startsWith(base)) {
            return null;
        }
        return base.relativize(absolute).toString().replace('\\', '/');
    }
}
