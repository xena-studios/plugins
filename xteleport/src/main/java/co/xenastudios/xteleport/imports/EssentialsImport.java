package co.xenastudios.xteleport.imports;

import co.xenastudios.xteleport.api.Home;
import co.xenastudios.xteleport.api.Warp;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Reads homes, warps and the spawn from EssentialsX's files, without changing them:
 * {@code Essentials/userdata/<uuid>.yml} ({@code homes:}), {@code Essentials/warps/*.yml}
 * and {@code Essentials/spawn.yml}. Reading blocks on the disk, so it runs off the server's
 * threads.
 */
public final class EssentialsImport {

    /** What was found, and what couldn't be read. */
    public record Found(List<Home> homes, List<Warp> warps, Warp spawn, int players, List<String> problems) {}

    private static final int MAX_PROBLEMS_KEPT = 20;

    private EssentialsImport() {}

    /**
     * Reads EssentialsX's data.
     *
     * @param folder EssentialsX's plugin folder, {@code plugins/Essentials}
     * @param worldName a world's name from its uuid, or null if it isn't loaded (EssentialsX
     *     saves worlds by uuid, with the name beside it in newer versions)
     * @return what was found; empty if the folder isn't there
     */
    public static Found read(Path folder, Function<UUID, String> worldName) {
        List<Home> homes = new ArrayList<>();
        List<Warp> warps = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int players = 0;
        for (Path file : ymlFiles(folder.resolve("userdata"), problems)) {
            String base = file.getFileName().toString();
            UUID owner;
            try {
                owner = UUID.fromString(base.substring(0, base.length() - 4));
            } catch (IllegalArgumentException e) {
                continue; // Not a player's file.
            }
            YamlConfiguration yaml = load(file, problems);
            ConfigurationSection section = yaml == null ? null : yaml.getConfigurationSection("homes");
            if (section == null) {
                continue;
            }
            int before = homes.size();
            for (String name : section.getKeys(false)) {
                Warp place = place(section.getConfigurationSection(name), worldName);
                String clean = cleanName(name);
                if (place == null || clean == null) {
                    problem(problems, "Skipped home \"" + name + "\" in " + base + ": " + (clean == null ? "its name" : "its world") + ".");
                    continue;
                }
                homes.add(new Home(owner, clean, place.world(), place.x(), place.y(), place.z(), place.yaw(), place.pitch()));
            }
            if (homes.size() > before) {
                players++;
            }
        }
        for (Path file : ymlFiles(folder.resolve("warps"), problems)) {
            YamlConfiguration yaml = load(file, problems);
            if (yaml == null) {
                continue;
            }
            String base = file.getFileName().toString();
            String name = cleanName(yaml.getString("name", base.substring(0, base.length() - 4)));
            Warp place = place(yaml, worldName);
            if (place == null || name == null) {
                problem(problems, "Skipped warp " + base + ": " + (name == null ? "its name" : "its world") + ".");
                continue;
            }
            warps.add(new Warp(name, place.world(), place.x(), place.y(), place.z(), place.yaw(), place.pitch()));
        }
        Warp spawn = null;
        Path spawnFile = folder.resolve("spawn.yml");
        if (Files.isRegularFile(spawnFile)) {
            YamlConfiguration yaml = load(spawnFile, problems);
            ConfigurationSection section = yaml == null ? null : yaml.getConfigurationSection("spawns.default");
            spawn = place(section, worldName);
        }
        return new Found(List.copyOf(homes), List.copyOf(warps), spawn, players, List.copyOf(problems));
    }

    private static void problem(List<String> problems, String problem) {
        if (problems.size() < MAX_PROBLEMS_KEPT) {
            problems.add(problem);
        }
    }

    private static List<Path> ymlFiles(Path folder, List<String> problems) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.yml")) {
            stream.forEach(files::add);
        } catch (IOException e) {
            problem(problems, "Couldn't list " + folder + ": " + e.getMessage());
        }
        files.sort(null);
        return files;
    }

    private static YamlConfiguration load(Path file, List<String> problems) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
            return yaml;
        } catch (IOException | InvalidConfigurationException e) {
            problem(problems, "Couldn't read " + file.getFileName() + ": " + e.getMessage().lines().findFirst().orElse(""));
            return null;
        }
    }

    /** A place from EssentialsX's keys, or null if its world can't be named. The name is left blank. */
    static Warp place(ConfigurationSection section, Function<UUID, String> worldName) {
        if (section == null || !section.contains("x") || !section.contains("y") || !section.contains("z")) {
            return null;
        }
        String world = section.getString("world-name");
        if (world == null || world.isBlank()) {
            String raw = section.getString("world", "");
            try {
                world = worldName.apply(UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                world = raw; // Old versions saved the name itself.
            }
        }
        if (world == null || world.isBlank()) {
            return null;
        }
        return new Warp("", world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
            (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }

    /** A name xTeleport accepts: other characters become _, and it's cut to 32. Null if nothing's left. */
    static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String clean = name.strip().replaceAll("[^A-Za-z0-9_-]", "_");
        if (clean.length() > 32) {
            clean = clean.substring(0, 32);
        }
        return clean.isEmpty() || clean.chars().allMatch(c -> c == '_') ? null : clean;
    }
}
