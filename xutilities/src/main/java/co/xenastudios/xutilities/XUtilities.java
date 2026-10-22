package co.xenastudios.xutilities;

import co.xenastudios.core.Setup;
import co.xenastudios.core.XenaPlugin;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.xutilities.command.BackTracker;
import co.xenastudios.xutilities.command.Commands;
import co.xenastudios.xutilities.config.XConfig;

/** xUtilities: every command you need. None you don't. Not final: MockBukkit subclasses it in tests. */
public class XUtilities extends XenaPlugin<XConfig> {

    private BackTracker backTracker;

    @Override
    protected ConfigSpec<XConfig> configSpec() {
        return XConfig.SPEC;
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
    }
}
