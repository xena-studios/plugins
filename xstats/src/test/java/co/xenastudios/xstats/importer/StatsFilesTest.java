package co.xenastudios.xstats.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.xenastudios.xstats.api.StatUnit;
import co.xenastudios.xstats.stat.Catalog;
import co.xenastudios.xstats.stat.StatDef;
import co.xenastudios.xstats.stat.Vanilla;
import co.xenastudios.xstats.storage.Store;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The world's stats files, in 26.x's folder and the old one. */
class StatsFilesTest {

    @TempDir
    Path world;

    static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    static final UUID ALEX = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
    static final UUID BEDROCK = UUID.fromString("00000000-0000-0000-0009-01f2a3b4c5d6");

    @Test
    void namesComeFromTheUserCache() throws Exception {
        Path cache = world.resolve("usercache.json");
        Files.writeString(cache, "[{\"uuid\":\"" + STEVE + "\",\"name\":\"Steve\",\"expiresOn\":\"2099-01-01 00:00:00 +0000\"},"
            + "{\"uuid\":\"" + ALEX + "\",\"name\":\"<b>bad</b>\"},{\"uuid\":\"nope\",\"name\":\"X\"},"
            + "{\"uuid\":\"" + BEDROCK + "\",\"name\":\".Bedrock_Sam\"}]");
        assertEquals(java.util.Map.of(STEVE, "Steve", BEDROCK, ".Bedrock_Sam"), StatsFiles.userCache(cache), "Floodgate's names too");
        assertEquals(java.util.Map.of(), StatsFiles.userCache(world.resolve("missing.json")));
    }

    @Test
    void a26WorldIsFoundFromItsDimensionFolder() throws Exception {
        Path overworld = Files.createDirectories(world.resolve("dimensions/minecraft/overworld"));
        Files.writeString(world.resolve("level.dat"), "");
        Path stats = Files.createDirectories(world.resolve("players/stats"));
        assertEquals(world.toAbsolutePath(), StatsFiles.levelRoot(overworld));
        assertEquals(List.of(stats.toAbsolutePath()), StatsFiles.folders(overworld));
    }

    @Test
    void bothFoldersAreReadAndTheNewerFileWins() throws Exception {
        Path old = Files.createDirectories(world.resolve("stats"));
        Path current = Files.createDirectories(world.resolve("players/stats"));
        Files.writeString(old.resolve(STEVE + ".json"),
            "{\"stats\":{\"minecraft:custom\":{\"minecraft:player_kills\":3,\"minecraft:play_one_minute\":24000}},\"DataVersion\":2586}");
        Files.setLastModifiedTime(old.resolve(STEVE + ".json"), FileTime.fromMillis(1_000_000));
        Files.writeString(current.resolve(STEVE + ".json"),
            "{\"stats\":{\"minecraft:custom\":{\"minecraft:player_kills\":5,\"minecraft:play_time\":72000,\"minecraft:leave_game\":4},"
                + "\"minecraft:mined\":{\"minecraft:stone\":10,\"minecraft:dirt\":1}},\"DataVersion\":4671}");
        Files.writeString(old.resolve(ALEX + ".json"), "{\"stats\":{\"minecraft:custom\":{\"minecraft:player_kills\":1}}}");
        // Not stats files: pre-1.13, not json, not a uuid.
        Files.writeString(old.resolve("1ab32c51-1e2d-4c4b-9c1e-1d2c3b4a5f6e.json"), "{\"stat.playerKills\":9}");
        Files.writeString(old.resolve("notes.json"), "{}");
        Files.writeString(old.resolve(UUID.randomUUID() + ".json"), "not json");

        Catalog catalog = new Catalog();
        List<StatDef> stats = List.of(
            new StatDef(catalog.index("kills"), "kills", "Kills", StatUnit.COUNT, 0, true, true, true, "minecraft",
                new StatDef.Vanillas(List.of(Vanilla.term("player_kills")), List.of())),
            new StatDef(catalog.index("mined"), "mined", "Mined", StatUnit.COUNT, 0, true, true, true, "minecraft",
                new StatDef.Vanillas(List.of(Vanilla.term("mined:*")), List.of())),
            new StatDef(catalog.index("playtime"), "playtime", "Playtime", StatUnit.TIME, 0, true, true, true, "xstats",
                new StatDef.Tracked(StatDef.Tracked.Kind.PLAYTIME)),
            new StatDef(catalog.index("sessions"), "sessions", "Sessions", StatUnit.COUNT, 0, true, true, true, "xstats",
                new StatDef.Tracked(StatDef.Tracked.Kind.SESSIONS)));
        List<Path> folders = StatsFiles.folders(world);
        assertEquals(List.of(current.toAbsolutePath(), old.toAbsolutePath()), folders);
        StatsFiles.Result result = StatsFiles.read(folders, stats, id -> id.equals(STEVE) ? "Steve" : "Alex");
        Store.Imported steve = result.players().stream().filter(p -> p.uuid().equals(STEVE)).findFirst().orElseThrow();
        assertEquals(5L, steve.values().get("kills"), "the newer file");
        assertEquals(11L, steve.values().get("mined"));
        assertEquals(3600L, steve.values().get("playtime"));
        assertEquals(4L, steve.values().get("sessions"), "times they left");
        assertEquals(true, steve.counters().get("kills"));
        assertEquals(false, steve.counters().get("playtime"));
        assertEquals(2, result.players().size());
        assertEquals(3, result.skipped());
    }
}
