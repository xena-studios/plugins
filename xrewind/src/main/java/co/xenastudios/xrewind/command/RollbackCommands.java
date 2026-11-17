package co.xenastudios.xrewind.command;

import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.xrewind.XRewind;
import co.xenastudios.xrewind.rollback.Rollbacks;
import java.util.Arrays;
import java.util.List;

/** {@code /xrewind rollback}, {@code restore}, {@code apply}, {@code cancel} and {@code undo}. */
public final class RollbackCommands {

    private RollbackCommands() {}

    public static List<CommandSpec> specs(XRewind plugin, Rollbacks rollbacks) {
        return List.of(
            CommandSpec.named("rollback")
                .aliases(List.of("rb"))
                .permission("xrewind.rollback")
                .description("Undo matching changes.")
                .usage("/xrewind rollback t:<time> [u:<player>] [r:<radius>] [a:<action>] [i:<blocks>] [e:<exclude>] [#preview]")
                .completes(LookupCommands.params(true))
                .executes(invocation -> rollbacks.run(invocation.sender(), LookupCommands.parse(plugin, args(invocation.rest(0))), false)),
            CommandSpec.named("restore")
                .aliases(List.of("rs"))
                .permission("xrewind.restore")
                .description("Put back what a rollback undid.")
                .usage("/xrewind restore t:<time> [u:<player>] [r:<radius>] [a:<action>] [i:<blocks>] [e:<exclude>] [#preview]")
                .completes(LookupCommands.params(true))
                .executes(invocation -> rollbacks.run(invocation.sender(), LookupCommands.parse(plugin, args(invocation.rest(0))), true)),
            CommandSpec.named("apply")
                .permission("xrewind.rollback")
                .description("Apply your preview.")
                .usage("/xrewind apply")
                .executes(invocation -> rollbacks.apply(invocation.player())),
            CommandSpec.named("cancel")
                .permission("xrewind.rollback")
                .description("Drop your preview.")
                .usage("/xrewind cancel")
                .executes(invocation -> rollbacks.cancel(invocation.player())),
            CommandSpec.named("undo")
                .permission("xrewind.rollback")
                .description("Revert your last rollback or restore.")
                .usage("/xrewind undo")
                .executes(invocation -> rollbacks.undo(invocation.sender())));
    }

    private static List<String> args(String rest) {
        return rest == null ? List.of() : Arrays.asList(rest.split(" "));
    }
}
