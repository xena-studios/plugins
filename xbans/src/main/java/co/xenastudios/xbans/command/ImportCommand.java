package co.xenastudios.xbans.command;

import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.xbans.XBans;

/** /xbans import. */
public final class ImportCommand {

    private ImportCommand() {}

    public static CommandSpec spec(XBans xbans) {
        return CommandSpec.named("import")
            .permission(xbans.adminPermission())
            .usage("/xbans import <source> [confirm]")
            .executes(invocation -> {});
    }
}
