package co.xenastudios.xteleport.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xteleport.api.Home;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** EssentialsX's files as it writes them: worlds by uuid with the name beside, or by name in old versions. */
class EssentialsImportTest {

    static final UUID WORLD = UUID.fromString("5a6b7c8d-0000-4000-8000-000000000001");

    @TempDir
    Path essentials;

    @Test
    void readsHomesWarpsAndSpawn() throws Exception {
        UUID steve = UUID.randomUUID();
        Files.createDirectories(essentials.resolve("userdata"));
        Files.writeString(essentials.resolve("userdata").resolve(steve + ".yml"), """
            last-account-name: Steve
            homes:
              base:
                world: 5a6b7c8d-0000-4000-8000-000000000001
                world-name: world
                x: 10.5
                y: 64.0
                z: -20.5
                yaw: 90.0
                pitch: 0.0
              my farm:
                world: 5a6b7c8d-0000-4000-8000-000000000001
                x: 1.0
                y: 70.0
                z: 2.0
                yaw: 0.0
                pitch: 0.0
              lost:
                world: 00000000-0000-4000-8000-00000000dead
                x: 1.0
                y: 70.0
                z: 2.0
            """);
        Files.writeString(essentials.resolve("userdata").resolve(UUID.randomUUID() + ".yml"), "money: '5'\n");
        Files.writeString(essentials.resolve("userdata").resolve("not-a-uuid.yml"), "homes: {}\n");
        Files.createDirectories(essentials.resolve("warps"));
        Files.writeString(essentials.resolve("warps").resolve("shop.yml"), """
            world: world_nether
            x: 5.0
            y: 80.0
            z: 5.0
            yaw: 0.0
            pitch: 0.0
            name: Shop
            """);
        Files.writeString(essentials.resolve("spawn.yml"), """
            spawns:
              default:
                world: 5a6b7c8d-0000-4000-8000-000000000001
                world-name: world
                x: 0.5
                y: 65.0
                z: 0.5
                yaw: 180.0
                pitch: 0.0
            """);

        EssentialsImport.Found found = EssentialsImport.read(essentials, id -> id.equals(WORLD) ? "world" : null);

        assertEquals(2, found.homes().size());
        assertEquals(1, found.players());
        Home base = found.homes().getFirst();
        assertEquals("base", base.name());
        assertEquals(steve, base.owner());
        assertEquals("world", base.world());
        assertEquals(10.5, base.x());
        assertEquals(90f, base.yaw());
        assertEquals("my_farm", found.homes().get(1).name(), "a space isn't allowed in a name");
        assertEquals(1, found.problems().size(), "the home in an unknown world is reported: " + found.problems());
        assertEquals("Shop", found.warps().getFirst().name());
        assertEquals("world_nether", found.warps().getFirst().world());
        assertNotNull(found.spawn());
        assertEquals(180f, found.spawn().yaw());
    }

    @Test
    void nothingWithoutEssentials() {
        EssentialsImport.Found found = EssentialsImport.read(essentials.resolve("missing"), id -> null);
        assertTrue(found.homes().isEmpty() && found.warps().isEmpty());
        assertNull(found.spawn());
    }

    @Test
    void namesThatCleanTheSameAreReportedNotDropped() throws Exception {
        UUID steve = UUID.randomUUID();
        Files.createDirectories(essentials.resolve("userdata"));
        Files.writeString(essentials.resolve("userdata").resolve(steve + ".yml"), """
            homes:
              my farm:
                world-name: world
                x: 1.0
                y: 70.0
                z: 2.0
              my_farm:
                world-name: world
                x: 3.0
                y: 70.0
                z: 4.0
            """);
        Files.createDirectories(essentials.resolve("warps"));
        Files.writeString(essentials.resolve("warps").resolve("a.yml"), "name: Shop\nworld: world\nx: 1.0\ny: 64.0\nz: 1.0\n");
        Files.writeString(essentials.resolve("warps").resolve("b.yml"), "name: shop\nworld: world\nx: 2.0\ny: 64.0\nz: 2.0\n");

        EssentialsImport.Found found = EssentialsImport.read(essentials, id -> null);

        assertEquals(1, found.homes().size());
        assertEquals(1, found.warps().size());
        assertEquals(2, found.problems().size(), found.problems().toString());
        assertTrue(found.problems().getFirst().contains("my_farm"), found.problems().getFirst());
        assertTrue(found.problems().get(1).contains("b.yml"), found.problems().get(1));
    }

    @Test
    void cleansNames() {
        assertEquals("a_b", EssentialsImport.cleanName("a b"));
        assertNull(EssentialsImport.cleanName("??"));
        assertEquals(32, EssentialsImport.cleanName("x".repeat(40)).length());
    }
}
