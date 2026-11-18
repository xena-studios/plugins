package co.xenastudios.xbans.command;

import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.xbans.XBans;
import co.xenastudios.xbans.importer.Imports;
import java.util.List;

/** /xbans import &lt;source&gt; [confirm]: a dry run with counts, then the import. */
public final class ImportCommand {

    private ImportCommand() {}

    public static CommandSpec spec(XBans xbans) {
        return CommandSpec.named("import")
            .permission(xbans.adminPermission())
            .description("Import punishments from vanilla, EssentialsX or LiteBans.")
            .usage("/xbans import <" + String.join("|", Imports.sources()) + "> [confirm]")
            .completes((sender, args) -> args.length == 1 ? Completions.matching(args[0], Imports.sources())
                : args.length == 2 ? Completions.matching(args[1], List.of("confirm")) : List.of())
            .executes(invocation -> {
                if (invocation.arg(0) == null) {
                    throw invocation.usage();
                }
                boolean confirm = "confirm".equalsIgnoreCase(invocation.arg(1));
                xbans.imports().start(invocation.arg(0), confirm, invocation.sender());
            });
    }
}
