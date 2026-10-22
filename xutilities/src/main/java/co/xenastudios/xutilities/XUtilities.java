package co.xenastudios.xutilities;

import co.xenastudios.core.Setup;
import co.xenastudios.core.XenaPlugin;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.xutilities.command.BackTracker;
import co.xenastudios.xutilities.command.Commands;
import co.xenastudios.xutilities.config.XConfig;
import co.xenastudios.core.text.SoundEffect;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** xUtilities: every command you need. None you don't. Not final: MockBukkit subclasses it in tests. */
public class XUtilities extends XenaPlugin<XConfig> {

    private BackTracker backTracker;

    @Override
    protected ConfigSpec<XConfig> configSpec() {
        return XConfig.SPEC;
    }

    @Override
    protected List<String> adminAliases() {
        return List.of("xu");
    }

    @Override
    protected int bStatsId() {
        return 34866;
    }

    @Override
    protected void setup(Setup setup, XConfig config) {
        // One tracker for the plugin's life, so /back survives reloads.
        if (backTracker == null) {
            backTracker = new BackTracker(this);
        }
        Commands.register(setup, this, config, backTracker);
        warnAboutUnknownSounds(config.commands());
    }

    // A typo in a sound name would otherwise just play nothing.
    private void warnAboutUnknownSounds(XConfig.Commands commands) {
        Map<String, SoundEffect> sounds = new LinkedHashMap<>();
        sounds.put("enderchest", commands.enderchest().sound());
        sounds.put("anvil", commands.anvil().sound());
        sounds.put("craft", commands.craft().sound());
        sounds.put("grinder", commands.grinder().sound());
        sounds.put("loom", commands.loom().sound());
        sounds.put("smith", commands.smith().sound());
        sounds.put("stonecutter", commands.stonecutter().sound());
        sounds.put("cartography", commands.cartography().sound());
        sounds.put("trash", commands.trash().sound());
        sounds.forEach((command, sound) -> {
            if (!sound.isKnown()) {
                getLogger().warning("config.yml: commands." + command + ".sound \"" + sound
                    + "\" isn't a sound this server knows. Only a resource pack that adds it will play it.");
            }
        });
    }
}
