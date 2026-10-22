package co.xenastudios.xutilities.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.xutilities.XUtilities;
import co.xenastudios.xutilities.config.XConfig;
import java.util.HashSet;
import java.util.Set;

/** Sets up every xUtilities command the config turns on. */
public final class Commands {

    private Commands() {}

    public static void register(Setup setup, XUtilities plugin, XConfig config, BackTracker tracker) {
        GamemodeCommands.register(setup, plugin.tasks(), config.commands());
        PlayerCommands.register(setup, plugin.tasks(), config);
        TravelCommands.register(setup, plugin.tasks(), config.commands(), tracker);
        MenuCommands.register(setup, plugin.tasks(), config.commands());

        // Includes /xutilities itself, which the core sets up first.
        Set<String> taken = new HashSet<>();
        for (CommandSpec spec : setup.commands()) {
            taken.add(spec.name());
            taken.addAll(spec.aliases());
        }
        LinkCommands.register(setup, config.infoCommands(), taken, plugin.getLogger());
    }
}
