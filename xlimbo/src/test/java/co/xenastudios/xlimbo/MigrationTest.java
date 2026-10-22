package co.xenastudios.xlimbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.config.ConfigLoader;
import co.xenastudios.xlimbo.config.XLConfig;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** xLimbo 1.0.1's default config, exactly as it shipped, carried forward to 2.0. */
class MigrationTest {

    @TempDir
    Path dir;

    Path oldConfig(String... replacements) throws Exception {
        Path file = dir.resolve("config.yml");
        try (InputStream old = getClass().getResourceAsStream("/config-1.0.1.yml")) {
            Files.copy(old, file);
        }
        String text = Files.readString(file);
        for (int i = 0; i < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        Files.writeString(file, text);
        return file;
    }

    @Test
    void aOnePointZeroConfigCarriesForward() throws Exception {
        Path file = oldConfig("floor-y: 64", "floor-y: 100", "main-server: \"main\"", "main-server: \"hub\"",
            "<red>Please wait <yellow><seconds></yellow>s before trying again.</red>", "<gold>Slow down!");
        ConfigLoader.Result<XLConfig> result = ConfigLoader.load(XLConfig.SPEC, file);
        XLConfig config = result.config();
        assertEquals(List.of(), result.warnings(), "1.0.1's defaults are all valid in 2.0");
        assertEquals(100, config.world().floorY());
        assertEquals(Material.PURPLE_STAINED_GLASS, config.world().floorBlock(), "1.x's plain glass gets the new purple");
        assertEquals("hub", config.mainServer());
        assertEquals("<gold>Slow down!", config.joinCommand().messageCooldown().template());
        assertEquals(XLConfig.DEFAULTS.joinCommand().messageSending(), config.joinCommand().messageSending(), "untouched messages get the new look");
        assertEquals(XLConfig.DEFAULTS.actionBar(), config.actionBar());
        assertEquals(64, config.world().border().spawnRadius(), "1.x's 4000-block spread is narrowed");
        assertEquals(XLConfig.DEFAULTS.serverCheck(), config.serverCheck(), "new sections added");
        assertEquals(XLConfig.DEFAULTS.protections(), config.protections());

        String migrated = Files.readString(file);
        assertTrue(migrated.contains("config-version: 2"), migrated);
        assertTrue(migrated.contains("void:"), "1.x's protections.void key is kept");
        assertTrue(Files.exists(dir.resolve("config.yml.bak-v1")));
        assertFalse(migrated.contains("restart to change"));

        ConfigLoader.Result<XLConfig> again = ConfigLoader.load(XLConfig.SPEC, file);
        assertEquals(List.of(), again.notices(), "loading again changes nothing");
        assertEquals(config, again.config());
    }

    @Test
    void aSpawnRadiusTheOwnerChoseIsKept() throws Exception {
        Path file = oldConfig("spawn-radius: 4000", "spawn-radius: 500");
        assertEquals(500, ConfigLoader.load(XLConfig.SPEC, file).config().world().border().spawnRadius());
    }

    @Test
    void oneZerosUnlimitedBuildGuardBecomesOff() throws Exception {
        Path file = oldConfig("max-places-per-second: 30", "max-places-per-second: 0", "  build-guard:\n    enabled: false", "  build-guard:\n    enabled: true");
        ConfigLoader.Result<XLConfig> result = ConfigLoader.load(XLConfig.SPEC, file);
        assertEquals(List.of(), result.warnings());
        assertFalse(result.config().protections().buildGuard().enabled());
    }

    @Test
    void theFloorMustBeSolidAndStayPut() throws Exception {
        for (String block : List.of("sand", "torch", "water")) {
            Path file = oldConfig("floor-block: \"GLASS\"", "floor-block: \"" + block + "\"");
            ConfigLoader.Result<XLConfig> result = ConfigLoader.load(XLConfig.SPEC, file);
            assertEquals(Material.PURPLE_STAINED_GLASS, result.config().world().floorBlock(), block);
            assertEquals(1, result.warnings().size(), block + ": " + result.warnings());
            Files.delete(file);
            Files.deleteIfExists(dir.resolve("config.yml.bak-v1"));
        }
    }

    @Test
    void blocksAreReadAsMinecraftNamesThem() throws Exception {
        Path file = oldConfig("floor-block: \"GLASS\"", "floor-block: \"minecraft:white_stained_glass\"");
        assertEquals(Material.WHITE_STAINED_GLASS, ConfigLoader.load(XLConfig.SPEC, file).config().world().floorBlock());
        Files.writeString(file, Files.readString(file).replace("white_stained_glass", "diamond_sword"));
        ConfigLoader.Result<XLConfig> notABlock = ConfigLoader.load(XLConfig.SPEC, file);
        assertEquals(Material.PURPLE_STAINED_GLASS, notABlock.config().world().floorBlock());
        assertEquals(1, notABlock.warnings().size(), notABlock.warnings().toString());
    }
}
