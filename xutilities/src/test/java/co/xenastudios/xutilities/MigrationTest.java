package co.xenastudios.xutilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.config.ConfigLoader;
import co.xenastudios.xutilities.config.XConfig;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** xUtilities 1.0.1's default config, exactly as it shipped, carried forward to 2.0. */
class MigrationTest {

    @TempDir
    Path dir;

    @Test
    void aOnePointZeroConfigCarriesForwardCleanly() throws Exception {
        Path file = dir.resolve("config.yml");
        try (InputStream old = getClass().getResourceAsStream("/config-1.0.1.yml")) {
            Files.copy(old, file);
        }
        String original = Files.readString(file);
        // An owner's own change, which must survive.
        Files.writeString(file, original.replace("max: 10", "max: 7").replace("https://discord.gg/example", "https://discord.gg/mine")
            .replace("<green>You have been healed.</green>", "<gold>Patched up!"));

        ConfigLoader.Result<XConfig> result = ConfigLoader.load(XConfig.SPEC, file);
        assertEquals(List.of(), result.warnings(), "1.0.1's defaults are all valid in 2.0");
        assertEquals(7, result.config().commands().speed().max());
        assertTrue(result.config().infoCommands().get("discord").message().template().contains("discord.gg/mine"));
        assertEquals(XConfig.DEFAULTS.commands().stonecutter(), result.config().commands().stonecutter(), "new commands added");
        // Messages the owner never touched get 2.0's look; ones they changed are theirs.
        assertEquals(XConfig.DEFAULTS.commands().fly().messageOnSelf(), result.config().commands().fly().messageOnSelf());
        assertEquals(XConfig.DEFAULTS.messages().error().noPermission(), result.config().messages().error().noPermission());
        assertEquals("<gold>Patched up!", result.config().commands().heal().messageSelf().template());
        assertEquals(XConfig.DEFAULTS.theme(), result.config().theme());
        // Every 1.0.1 message the owner left alone is now 2.0's.
        XConfig.Commands expected = XConfig.DEFAULTS.commands();
        XConfig.Commands actual = result.config().commands();
        assertEquals(expected.survival(), actual.survival());
        assertEquals(expected.fly(), actual.fly());
        assertEquals(expected.top(), actual.top());
        assertEquals(expected.back(), actual.back());
        assertEquals(expected.feed(), actual.feed());
        assertEquals(expected.repair(), actual.repair());
        assertEquals(expected.clear(), actual.clear());
        assertEquals(expected.ping(), actual.ping());
        assertEquals(expected.setrotation(), actual.setrotation());
        assertEquals(expected.enderchest(), actual.enderchest());
        assertEquals(XConfig.DEFAULTS.messages(), result.config().messages());
        assertEquals(XConfig.DEFAULTS.infoCommands().get("store"), result.config().infoCommands().get("store"));
        assertTrue(result.notices().stream().anyMatch(notice -> notice.startsWith("Gave ")), result.notices().toString());

        String migrated = Files.readString(file);
        assertTrue(migrated.contains("config-version: 2"), migrated);
        assertFalse(migrated.contains("disabled: \"<red>This command is disabled."), "1.x's error for disabled commands is gone");
        assertTrue(migrated.contains("stonecutter:"), migrated);
        assertTrue(migrated.contains("update-checker:"), migrated);
        assertTrue(migrated.contains("max: 7"), migrated);
        assertFalse(migrated.contains("RESTART"), "1.x's notes about restarts are replaced");
        assertTrue(migrated.startsWith("# xUtilities: every command you need."), migrated);
        assertTrue(Files.exists(dir.resolve("config.yml.bak-v1")));

        // Loading again changes nothing.
        ConfigLoader.Result<XConfig> again = ConfigLoader.load(XConfig.SPEC, file);
        assertEquals(List.of(), again.warnings());
        assertEquals(List.of(), again.notices());
        assertEquals(result.config(), again.config());
    }
}
