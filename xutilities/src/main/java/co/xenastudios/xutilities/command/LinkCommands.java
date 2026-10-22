package co.xenastudios.xutilities.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.xutilities.config.Settings.LinkCommand;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.permissions.PermissionDefault;

/** The commands defined under info-commands. */
final class LinkCommands {

    private LinkCommands() {}

    /**
     * @param taken names and aliases xUtilities' own commands use; a link command that
     *     clashes with one is skipped
     */
    static void register(Setup setup, Map<String, LinkCommand> links, Set<String> taken, Logger logger) {
        Set<String> used = new HashSet<>(taken);
        links.forEach((name, link) -> {
            if (!link.enabled()) {
                return;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            List<String> labels = new ArrayList<>(link.aliases());
            labels.add(0, lower);
            for (String label : labels) {
                if (used.contains(label.toLowerCase(Locale.ROOT))) {
                    logger.warning("info-commands." + name + ": /" + label + " is already one of xUtilities' commands, so this link command is skipped.");
                    return;
                }
            }
            labels.forEach(label -> used.add(label.toLowerCase(Locale.ROOT)));
            String permission = link.permission() == null ? "xutilities.command." + lower : link.permission();
            setup.command(CommandSpec.named(lower)
                .aliases(link.aliases())
                .permission(permission)
                .permissionDefault(PermissionDefault.TRUE)
                .description("Show the " + lower + " link.")
                .executes(invocation -> link.message().send(invocation.sender())));
        });
    }
}
