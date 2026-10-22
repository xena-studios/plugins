package co.xenastudios.zchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.config.ConfigLoader;
import co.xenastudios.zchat.config.ZConfig;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationTest {

    @TempDir
    Path dir;

    @Test
    void aZChatTwoConfigCarriesForward() throws Exception {
        Path file = dir.resolve("config.yml");
        try (InputStream old = getClass().getResourceAsStream("/config-2.0.0.yml")) {
            Files.copy(old, file);
        }
        // An owner's own changes, which must survive.
        Files.writeString(file, Files.readString(file)
            .replace("seconds: 3", "seconds: 5")
            .replace("<red>Chat is currently muted.</red>", "<gold>Shh!")
            .replace("    - \"badword2\"\n", "    - \"badword2\"\n    - \"spam\\\\d+\"\n"));

        ConfigLoader.Result<ZConfig> result = ConfigLoader.load(ZConfig.SPEC, file);
        ZConfig config = result.config();
        assertEquals(List.of(), result.warnings(), "zChat 2's defaults are all valid in zChat 3");
        assertEquals(5, config.cooldown().seconds());
        assertEquals("<gold>Shh!", config.mutechat().messageBlocked().template());
        assertEquals(List.of("badword1", "badword2", "spam\\d+"), config.filter().patterns(), "changed patterns are the owner's");
        // Untouched messages get zChat 3's look; formats are kept as they were.
        assertEquals(ZConfig.DEFAULTS.clearchat(), config.clearchat());
        assertEquals(ZConfig.DEFAULTS.togglechat(), config.togglechat());
        assertEquals(ZConfig.DEFAULTS.formatting(), config.formatting());
        assertEquals(ZConfig.DEFAULTS.privateMessages(), config.privateMessages(), "new sections added");

        String migrated = Files.readString(file);
        assertTrue(migrated.contains("config-version: 2"), migrated);
        assertFalse(migrated.contains("restart-only"), "zChat 2's notes about restarts are replaced");
        assertTrue(Files.exists(dir.resolve("config.yml.bak-v1")));

        ConfigLoader.Result<ZConfig> again = ConfigLoader.load(ZConfig.SPEC, file);
        assertEquals(List.of(), again.notices(), "loading again changes nothing");
        assertEquals(config, again.config());
    }

    @Test
    void untouchedExamplePatternsMakeWayForWords() throws Exception {
        Path file = dir.resolve("config.yml");
        try (InputStream old = getClass().getResourceAsStream("/config-2.0.0.yml")) {
            Files.copy(old, file);
        }
        ZConfig config = ConfigLoader.load(ZConfig.SPEC, file).config();
        assertEquals(List.of(), config.filter().patterns());
        assertEquals(ZConfig.DEFAULTS.filter(), config.filter());
    }

    @Test
    void aZChatConfigMissingItsVersionIsNeverSetAside() throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, ConfigLoader.defaultText(ZConfig.SPEC).replace("config-version: 2\n", "").replace("seconds: 3\n", "seconds: 7\n"));
        assertEquals(7, ConfigLoader.load(ZConfig.SPEC, file).config().cooldown().seconds());
        Files.writeString(file, "config-version: 2\ncooldown:\n  seconds: 9\n");
        assertEquals(9, ConfigLoader.load(ZConfig.SPEC, file).config().cooldown().seconds());
        Files.writeString(file, "");
        assertEquals(ZConfig.DEFAULTS, ConfigLoader.load(ZConfig.SPEC, file).config());
        assertFalse(Files.exists(dir.resolve("config.yml.old")));
    }

    @Test
    void aZChatOneConfigIsSetAsideForTheDefaults() throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "chat-format: \"&7%player%: &f%message%\"\nanti-swear: true\n");
        ConfigLoader.Result<ZConfig> result = ConfigLoader.load(ZConfig.SPEC, file);
        assertEquals(ZConfig.DEFAULTS, result.config());
        assertTrue(result.notices().getFirst().contains("zChat 1.x"), result.notices().toString());
        assertTrue(Files.readString(dir.resolve("config.yml.old")).contains("anti-swear"));
        assertTrue(Files.readString(file).startsWith("# zChat: format your chat."));
    }
}
