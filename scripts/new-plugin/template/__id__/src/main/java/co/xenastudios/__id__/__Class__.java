package co.xenastudios.{{id}};

import co.xenastudios.core.Setup;
import co.xenastudios.core.XenaPlugin;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.{{id}}.config.{{Class}}Config;

/** {{display}}: {{tagline}} Not final: MockBukkit subclasses it in tests. */
public class {{Class}} extends XenaPlugin<{{Class}}Config> {

    @Override
    protected ConfigSpec<{{Class}}Config> configSpec() {
        return {{Class}}Config.SPEC;
    }

    // TODO: add {{display}} on bstats.org (Bukkit) and return its id. 0 sends nothing.
    @Override
    protected int bStatsId() {
        return 0;
    }

    @Override
    protected void setup(Setup setup, {{Class}}Config config) {
        // Each feature is a module, on only while its config says so: a feature that's off
        // registers no listener and runs no task. Commands go in with setup.command(...).
        setup.module("api", () -> new Api(this));
    }
}
