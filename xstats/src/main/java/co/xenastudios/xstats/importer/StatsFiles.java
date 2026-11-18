package co.xenastudios.xstats.importer;

import co.xenastudios.xstats.stat.StatDef;
import co.xenastudios.xstats.stat.Vanilla;
import co.xenastudios.xstats.storage.Store;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Reads players' history from the world's stats files, which Minecraft keeps whatever
 * plugins are installed: {@code <world>/players/stats/<uuid>.json} on 26.x,
 * {@code <world>/stats/<uuid>.json} before. Only reads; never on a server thread.
 */
public final class StatsFiles {

    /** Files bigger than this aren't stats files. A busy player's is a few tens of KiB. */
    static final long MAX_BYTES = 4L * 1024 * 1024;

    /** What reading the files found. */
    public record Result(List<Store.Imported> players, int files, int skipped, List<Path> folders) {}

    private StatsFiles() {}

    /**
     * The folder with the world's level.dat, where players' files are kept. On 26.x the main
     * world's own folder is {@code <world>/dimensions/minecraft/overworld}, three below it.
     */
    public static Path levelRoot(Path world) {
        Path at = world.toAbsolutePath();
        for (int up = 0; up <= 3 && at != null; up++, at = at.getParent()) {
            if (Files.isRegularFile(at.resolve("level.dat"))) {
                return at;
            }
        }
        return world;
    }

    /** The stats folders a world can have, newest layout first. */
    public static List<Path> folders(Path world) {
        List<Path> folders = new ArrayList<>();
        Path root = levelRoot(world);
        for (Path folder : List.of(root.resolve("players").resolve("stats"), root.resolve("stats"))) {
            if (Files.isDirectory(folder)) {
                folders.add(folder);
            }
        }
        return folders;
    }

    /**
     * Reads every file in the folders. A player in both (a world moved to 26.x keeps the old
     * folder until each player joins) is read from the newer file.
     *
     * @param names a player's last known name, or null
     */
    public static Result read(List<Path> folders, List<StatDef> stats, Function<UUID, String> names) {
        Map<UUID, Path> files = new LinkedHashMap<>();
        int skipped = 0;
        for (Path folder : folders) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.json")) {
                for (Path file : stream) {
                    UUID id = uuid(file);
                    if (id == null || Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
                        skipped++;
                        continue;
                    }
                    Path known = files.get(id);
                    if (known == null || Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(known)) > 0) {
                        files.put(id, file);
                    }
                }
            } catch (IOException e) {
                skipped++;
            }
        }
        List<Store.Imported> players = new ArrayList<>();
        for (Map.Entry<UUID, Path> entry : files.entrySet()) {
            try {
                Map<String, Map<String, Long>> parsed = parse(entry.getValue());
                if (parsed == null) {
                    skipped++;
                    continue;
                }
                Map<String, Long> values = new HashMap<>();
                Map<String, Boolean> counters = new HashMap<>();
                for (StatDef stat : stats) {
                    Long value = value(stat, parsed);
                    if (value != null && value > 0) {
                        values.put(stat.id(), value);
                        counters.put(stat.id(), stat.source() instanceof StatDef.Vanillas);
                    }
                }
                if (!values.isEmpty()) {
                    // The file is saved as the player leaves: when they were last seen, near enough.
                    long seen = Files.getLastModifiedTime(entry.getValue()).toMillis();
                    players.add(new Store.Imported(entry.getKey(), names.apply(entry.getKey()), seen, values, counters));
                }
            } catch (IOException | RuntimeException e) {
                skipped++;
            }
        }
        return new Result(players, files.size(), skipped, folders);
    }

    /** A stat's value from a parsed file, or null if the file can't give it. */
    static Long value(StatDef stat, Map<String, Map<String, Long>> parsed) {
        return switch (stat.source()) {
            case StatDef.Vanillas vanilla -> {
                long sum = 0;
                for (Vanilla.Term term : vanilla.terms()) {
                    sum += Vanilla.fromFile(term, parsed);
                }
                yield sum;
            }
            // Before xStats, playtime counted AFK time too: the best history there is.
            case StatDef.Tracked tracked -> Vanilla.fromFile(Vanilla.term(
                tracked.kind() == StatDef.Tracked.Kind.PLAYTIME ? "play_time" : "leave_game"), parsed);
            default -> null;
        };
    }

    /**
     * Names by uuid from the server's {@code usercache.json}, which remembers every player
     * who joined recently: the stats files only have uuids. Empty if it can't be read.
     */
    public static Map<UUID, String> userCache(Path file) {
        Map<UUID, String> names = new HashMap<>();
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 64L * 1024 * 1024) {
                return names;
            }
            JsonElement root;
            try (Reader reader = Files.newBufferedReader(file)) {
                root = JsonParser.parseReader(reader);
            }
            if (!root.isJsonArray()) {
                return names;
            }
            for (JsonElement entry : root.getAsJsonArray()) {
                if (!entry.isJsonObject()) {
                    continue;
                }
                JsonObject object = entry.getAsJsonObject();
                JsonElement uuid = object.get("uuid");
                JsonElement name = object.get("name");
                if (uuid != null && name != null && uuid.isJsonPrimitive() && name.isJsonPrimitive()) {
                    UUID id = uuid(uuid.getAsString() + ".json");
                    if (id != null && co.xenastudios.core.player.Profiles.validName(name.getAsString())) {
                        names.put(id, name.getAsString());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            names.clear();
        }
        return names;
    }

    private static UUID uuid(String name) {
        try {
            return UUID.fromString(name.substring(0, name.length() - ".json".length()));
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static UUID uuid(Path file) {
        return uuid(file.getFileName().toString());
    }

    /**
     * A file's {@code stats} object as category to key to value, or null if it isn't a stats
     * file from 1.13 or later (older ones have no categories).
     */
    static Map<String, Map<String, Long>> parse(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) {
            return null;
        }
        JsonElement root;
        try (Reader reader = Files.newBufferedReader(file)) {
            root = JsonParser.parseReader(reader);
        }
        if (!root.isJsonObject() || !root.getAsJsonObject().has("stats") || !root.getAsJsonObject().get("stats").isJsonObject()) {
            return null;
        }
        Map<String, Map<String, Long>> stats = new HashMap<>();
        for (Map.Entry<String, JsonElement> category : root.getAsJsonObject().getAsJsonObject("stats").entrySet()) {
            if (!category.getValue().isJsonObject()) {
                continue;
            }
            Map<String, Long> values = new HashMap<>();
            JsonObject object = category.getValue().getAsJsonObject();
            for (Map.Entry<String, JsonElement> value : object.entrySet()) {
                if (value.getValue().isJsonPrimitive() && value.getValue().getAsJsonPrimitive().isNumber()) {
                    values.put(value.getKey(), value.getValue().getAsLong());
                }
            }
            stats.put(category.getKey(), values);
        }
        return stats;
    }
}
