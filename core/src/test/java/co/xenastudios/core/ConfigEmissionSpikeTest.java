package co.xenastudios.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Spike: can a config schema write the commented default config.yml without a
 * server running? If so, the build can generate each plugin's default config
 * for the docs from the same code that reads it.
 */
class ConfigEmissionSpikeTest {

    @Test
    void writesCommentedYamlWithoutAServer() throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("xUtilities", "Apply changes with /xutilities reload."));
        yaml.set("config-version", 2);
        yaml.setComments("config-version", List.of("Managed by the plugin. Don't edit."));
        yaml.set("commands.fly.enabled", true);
        yaml.set("commands.fly.aliases", List.of("flight"));
        yaml.set("commands.fly.message-self", "<gray>Flight <green><state></green>.</gray>");
        yaml.setInlineComments("commands.fly.enabled", List.of("false removes the command"));

        String text = yaml.saveToString();
        assertTrue(text.startsWith("# xUtilities\n"), text);
        assertTrue(text.contains("# Managed by the plugin. Don't edit.\nconfig-version: 2"), text);
        assertTrue(text.contains("enabled: true # false removes the command"), text);

        // And it reads back to the same values.
        YamlConfiguration back = new YamlConfiguration();
        back.loadFromString(text);
        assertEquals(List.of("flight"), back.getStringList("commands.fly.aliases"));
        assertEquals("<gray>Flight <green><state></green>.</gray>", back.getString("commands.fly.message-self"));
    }
}
