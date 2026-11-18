package co.xenastudios.xutilities.command;

import co.xenastudios.core.Setup;
import co.xenastudios.xutilities.XUtilities;
import co.xenastudios.xutilities.config.XConfig;

/** Sets up every xUtilities command the config turns on. */
public final class Commands {

    private Commands() {}

    public static void register(Setup setup, XUtilities plugin, XConfig config, BackTracker tracker) {
        GamemodeCommands.register(setup, plugin.tasks(), config.commands());
        PlayerCommands.register(setup, plugin.tasks(), config);
        TravelCommands.register(setup, plugin.tasks(), config.commands(), tracker);
        MenuCommands.register(setup, plugin.tasks(), config.commands());

        // Includes /xutilities and its aliases, which the core keeps for itself.
        LinkCommands.register(setup, config.infoCommands(), setup.taken(), plugin.getLogger());
    }
}
