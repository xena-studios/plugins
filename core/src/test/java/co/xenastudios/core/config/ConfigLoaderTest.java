package co.xenastudios.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.text.Message;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    enum Mode { CENSOR, BLOCK }

    record Speed(
        @Comment("The lowest speed.") @Range(min = 1, max = 10) int min,
        @Range(min = 1, max = 10) int max,
        Message message) {

        Speed {
            if (min > max) {
                throw new IllegalArgumentException("min is above max");
            }
        }
    }

    record Link(boolean enabled, List<String> aliases, String message) {}

    record Settings(
        @Comment({"Whether it's on.", "Second line."}) boolean enabled,
        Mode mode,
        double ratio,
        List<String> aliases,
        @Comment("Speed settings.") Speed speed,
        @Comment("Links.") Map<String, Link> links) {}

    static final Settings DEFAULTS = new Settings(
        true, Mode.CENSOR, 0.5, List.of("a", "b"),
        new Speed(1, 10, Message.of("<gray>Speed <speed>")),
        Map.of("discord", new Link(true, List.of(), "<blue>discord")));

    static ConfigSpec.Builder<Settings> spec() {
        return ConfigSpec.builder(Settings.class, DEFAULTS)
            .header("Test config")
            .template(Link.class, new Link(true, List.of(), ""));
    }

    @TempDir
    Path dir;

    Path file() {
        return dir.resolve("config.yml");
    }

    ConfigLoader.Result<Settings> load(ConfigSpec<Settings> spec, String yaml) throws Exception {
        Files.writeString(file(), yaml);
        return ConfigLoader.load(spec, file());
    }

    @Test
    void writesTheDefaultsOnFirstStartAndReadsThemBack() throws Exception {
        ConfigLoader.Result<Settings> first = ConfigLoader.load(spec().build(), file());
        assertEquals(DEFAULTS, first.config());
        String text = Files.readString(file());
        assertTrue(text.startsWith("# Test config\n"), text);
        assertTrue(text.contains("# Whether it's on.\n# Second line.\nenabled: true"), text);
        assertTrue(text.contains("mode: \"censor\""), text);
        assertTrue(text.contains("aliases: [\"a\", \"b\"]"), text);

        ConfigLoader.Result<Settings> second = ConfigLoader.load(spec().build(), file());
        assertEquals(DEFAULTS, second.config());
        assertEquals(List.of(), second.warnings());
        assertEquals(List.of(), second.notices());
        assertEquals(text, Files.readString(file()), "an up-to-date file is never rewritten");
    }

    @Test
    void aBadValueFallsBackToItsDefaultWithAWarning() throws Exception {
        String text = ConfigLoader.defaultText(spec().build())
            .replaceFirst("enabled: true", "enabled: maybe")
            .replace("mode: \"censor\"", "mode: \"shout\"")
            .replace("min: 1", "min: 0");
        ConfigLoader.Result<Settings> result = load(spec().build(), text);
        assertTrue(result.config().enabled());
        assertEquals(Mode.CENSOR, result.config().mode());
        assertEquals(1, result.config().speed().min());
        assertEquals(3, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().get(0).startsWith("enabled (\"maybe\") should be true or false"), result.warnings().toString());
        assertTrue(result.warnings().get(1).contains("should be one of censor, block"), result.warnings().toString());
        assertTrue(result.warnings().get(2).contains("must be from 1 to 10"), result.warnings().toString());
        assertTrue(Files.readString(file()).contains("enabled: maybe"), "a bad value is left for the owner to fix");
    }

    @Test
    void aSectionTheRecordRejectsFallsBackAsAWhole() throws Exception {
        String text = ConfigLoader.defaultText(spec().build()).replace("min: 1", "min: 9").replace("max: 10", "max: 2");
        ConfigLoader.Result<Settings> result = load(spec().build(), text);
        assertEquals(DEFAULTS.speed(), result.config().speed());
        assertEquals(List.of("speed: min is above max; using the defaults for this section."), result.warnings());
    }

    @Test
    void lenientScalars() throws Exception {
        ConfigLoader.Result<Settings> result = load(spec().build(), """
            enabled: "off"
            ratio: 2
            aliases: solo
            """);
        assertFalse(result.config().enabled());
        assertEquals(2.0, result.config().ratio());
        assertEquals(List.of("solo"), result.config().aliases());
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void anEmptyValueMeansBlankNotMissing() throws Exception {
        String text = ConfigLoader.defaultText(spec().build())
            .replace("aliases: [\"a\", \"b\"]", "aliases:")
            .replace("message: \"<gray>Speed <speed>\"", "message:");
        int links = text.indexOf("links:");
        text = text.substring(0, links) + "links:\n";
        ConfigLoader.Result<Settings> result = load(spec().build(), text);
        assertEquals(List.of(), result.config().aliases());
        assertTrue(result.config().speed().message().isEmpty());
        assertEquals(Map.of(), result.config().links(), "an empty map removes every entry");
        assertEquals(List.of(), result.warnings());
        assertEquals(List.of(), result.notices(), "nothing was missing");
        // And it stays blank, even after the file is next rewritten.
        assertEquals(result.config(), load(spec().build(), Files.readString(file())).config());
    }

    @Test
    void missingSettingsAreAddedWithTheirComments() throws Exception {
        ConfigLoader.Result<Settings> result = load(spec().build(), """
            # my own comment
            enabled: false
            """);
        assertFalse(result.config().enabled());
        assertEquals(DEFAULTS.speed(), result.config().speed());
        String text = Files.readString(file());
        assertTrue(text.contains("# my own comment\nenabled: false"), text);
        assertTrue(text.contains("# Speed settings.\nspeed:"), text);
        assertTrue(text.contains("config-version: 1"), text);
        assertTrue(result.notices().getFirst().startsWith("Added 5 new settings with their defaults: ") && result.notices().getFirst().contains("speed"), result.notices().toString());
    }

    @Test
    void mapEntriesFillGapsFromTheTemplateWithoutGrowingTheFile() throws Exception {
        String text = ConfigLoader.defaultText(spec().build()) + """
              vote:
                message: "<green>vote"
            """;
        ConfigLoader.Result<Settings> result = load(spec().build(), text);
        assertEquals(new Link(true, List.of(), "<green>vote"), result.config().links().get("vote"));
        assertEquals(List.of("discord", "vote"), List.copyOf(result.config().links().keySet()));
        assertFalse(Files.readString(file()).contains("aliases: []\n    message: \"<green>vote\""));
        assertEquals(List.of(), result.notices());
    }

    @Test
    void oldConfigsAreBackedUpAndMigrated() throws Exception {
        ConfigSpec<Settings> v2 = spec()
            .version(2)
            .migrate(1, root -> {
                root.set("ratio", root.getDouble("old-ratio"));
                root.set("old-ratio", null);
            })
            .build();
        String old = "config-version: 1\nold-ratio: 0.25\nenabled: false\n";
        ConfigLoader.Result<Settings> result = load(v2, old);
        assertEquals(0.25, result.config().ratio());
        assertFalse(result.config().enabled());
        assertEquals(old, Files.readString(dir.resolve("config.yml.bak-v1")));
        String text = Files.readString(file());
        assertTrue(text.contains("config-version: 2"), text);
        assertFalse(text.contains("old-ratio"), text);
        assertTrue(result.notices().get(0).startsWith("Carried config.yml forward from config version 1 to 2."));

        // A second migration of the same version doesn't overwrite the first backup.
        load(v2, old);
        assertTrue(Files.exists(dir.resolve("config.yml.bak-v1-2")));
    }

    @Test
    void aConfigFromANewerVersionIsReadButNeverWritten() throws Exception {
        String text = "config-version: 7\nenabled: false\n";
        ConfigLoader.Result<Settings> result = load(spec().build(), text);
        assertFalse(result.config().enabled());
        assertEquals(text, Files.readString(file()));
        assertTrue(result.warnings().get(0).contains("newer version"));
    }

    @Test
    void invalidYamlThrowsAndLeavesTheFileAlone() throws IOException {
        Files.writeString(file(), "enabled: [unclosed\n");
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(spec().build(), file()));
        assertTrue(e.getMessage().startsWith("config.yml isn't valid YAML"), e.getMessage());
        assertEquals("enabled: [unclosed\n", Files.readString(file()));
    }

    @Test
    void unsupportedTypesFailWhenTheSpecIsBuilt() {
        record Bad(Object thing) {}
        assertThrows(IllegalStateException.class, () -> ConfigSpec.builder(Bad.class, new Bad(1)).build());
        // A map value with no template.
        assertThrows(IllegalStateException.class, () -> ConfigSpec.builder(Settings.class, DEFAULTS).build());
        // A version with no migration to it.
        assertThrows(IllegalStateException.class, () -> spec().version(2).build());
    }
}
