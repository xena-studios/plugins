package co.xenastudios.xrewind.command;

import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.xrewind.XRewind;
import co.xenastudios.xrewind.maintain.Upkeep;
import co.xenastudios.xrewind.query.Query;
import java.util.Arrays;
import java.util.List;

/** {@code /xrewind purge} and {@code /xrewind import}. */
public final class UpkeepCommands {

    private UpkeepCommands() {}

    public static List<CommandSpec> specs(XRewind plugin, Upkeep upkeep) {
        return List.of(
            CommandSpec.named("purge")
                .permission("xrewind.purge")
                .description("Delete history older than a time.")
                .usage("/xrewind purge t:<time>")
                .completes((sender, args) -> Completions.matching(args.length == 0 ? "" : args[args.length - 1], List.of("t:30d", "t:90d")))
                .executes(invocation -> {
                    Query query = LookupCommands.parse(plugin, invocation.size() == 0 ? List.of() : Arrays.asList(invocation.rest(0).split(" ")));
                    if (query.seconds() == 0) {
                        throw invocation.usage();
                    }
                    upkeep.purge(invocation.sender(), query.seconds());
                }),
            CommandSpec.named("import")
                .permission("xrewind.admin")
                .description("Import CoreProtect's history.")
                .usage("/xrewind import coreprotect [#again]")
                .completes((sender, args) -> args.length <= 1 ? Completions.matching(args.length == 0 ? "" : args[0], List.of("coreprotect"))
                    : Completions.matching(args[args.length - 1], List.of("#again")))
                .executes(invocation -> {
                    if (!"coreprotect".equalsIgnoreCase(invocation.arg(0))) {
                        throw invocation.usage();
                    }
                    upkeep.importCoreProtect(invocation.sender(), "#again".equalsIgnoreCase(invocation.arg(1)));
                }));
    }
}
