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

    /** The stats folders a world can have, newest layout first. */
    public static List<Path> folders(Path world) {
        List<Path> folders = new ArrayList<>();
        for (Path folder : List.of(world.resolve("players").resolve("stats"), world.resolve("stats"))) {
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
                    players.add(new Store.Imported(entry.getKey(), names.apply(entry.getKey()), values, counters));
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

    private static UUID uuid(Path file) {
        String name = file.getFileName().toString();
        try {
            return UUID.fromString(name.substring(0, name.length() - ".json".length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
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
