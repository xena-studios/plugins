package co.xenastudios.core.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;

/**
 * A command made of subcommands, such as {@code /xrewind status}: each a
 * {@link CommandSpec} named for its first word, with its own aliases, permission,
 * usage and completion. The sender sees and completes only those they may use.
 */
public final class Subcommands {

    private Subcommands() {}

    /**
     * The executor that runs whichever subcommand the first argument names, with the
     * arguments after it. Unknown or missing names get {@code usage}.
     */
    public static Executor executor(List<CommandSpec> subcommands) {
        return invocation -> {
            String name = invocation.arg(0);
            CommandSpec sub = name == null ? null : find(subcommands, name);
            if (sub == null) {
                throw invocation.usage();
            }
            invocation.require(sub.permission());
            sub.executor().execute(invocation.shift(sub.usage()));
        };
    }

    /** Completes subcommand names the sender may use, then each subcommand's own arguments. */
    public static Completer completer(List<CommandSpec> subcommands) {
        return (sender, args) -> {
            if (args.length <= 1) {
                List<String> names = new ArrayList<>();
                for (CommandSpec sub : subcommands) {
                    if (allowed(sender, sub)) {
                        names.add(sub.name());
                    }
                }
                return Completions.matching(args.length == 0 ? "" : args[0], names);
            }
            CommandSpec sub = find(subcommands, args[0]);
            if (sub == null || !allowed(sender, sub)) {
                return List.of();
            }
            return sub.completer().complete(sender, Arrays.copyOfRange(args, 1, args.length));
        };
    }

    /** {@code <a|b|c>}: the subcommands' names, for the parent's usage. */
    public static String names(List<CommandSpec> subcommands) {
        return "<" + String.join("|", subcommands.stream().map(CommandSpec::name).toList()) + ">";
    }

    static CommandSpec find(List<CommandSpec> subcommands, String typed) {
        String name = typed.toLowerCase(Locale.ROOT);
        for (CommandSpec sub : subcommands) {
            if (sub.name().equals(name) || sub.aliases().contains(name)) {
                return sub;
            }
        }
        return null;
    }

    private static boolean allowed(CommandSender sender, CommandSpec sub) {
        return sub.permission().isBlank() || sender.hasPermission(sub.permission());
    }
}
