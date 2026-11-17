package co.xenastudios.xrewind.command;

import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completer;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xrewind.XRewind;
import co.xenastudios.xrewind.query.Inspector;
import co.xenastudios.xrewind.query.Lookups;
import co.xenastudios.xrewind.query.Params;
import co.xenastudios.xrewind.query.Query;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** {@code /xrewind inspect}, {@code lookup}, {@code page} and {@code near}. */
public final class LookupCommands {

    /** Seeing chat and commands, which are more private than blocks. */
    public static final String CHAT_PERMISSION = "xrewind.lookup.chat";

    private static final List<String> KEYS = List.of("u:", "t:", "r:", "a:", "i:", "e:", "w:");
    private static final List<String> FLAGS = List.of("#preview", "#count", "#silent");
    private static final List<String> ACTIONS = List.of("block", "+block", "-block", "container", "+container", "-container",
        "kill", "click", "sign", "chat", "command", "session", "+session", "-session");

    private LookupCommands() {}

    public static List<CommandSpec> specs(XRewind plugin, Lookups lookups, Inspector inspector) {
        return List.of(
            CommandSpec.named("inspect")
                .aliases(List.of("i"))
                .permission("xrewind.inspect")
                .description("Click blocks to see their history.")
                .usage("/xrewind inspect")
                .executes(invocation -> {
                    Player player = invocation.player();
                    (inspector.toggle(player) ? plugin.config().messages().lookup().inspectorOn()
                        : plugin.config().messages().lookup().inspectorOff()).send(player);
                }),
            CommandSpec.named("lookup")
                .aliases(List.of("l"))
                .permission("xrewind.lookup")
                .description("Search the history.")
                .usage("/xrewind lookup [u:<player>] [t:<time>] [r:<radius>] [a:<action>] [i:<blocks>] [e:<exclude>]")
                .completes(params(true))
                .executes(invocation -> {
                    Query query = parse(plugin, invocation.size() == 0 ? List.of() : Arrays.asList(invocation.rest(0).split(" ")));
                    if (query.wantsPrivate()) {
                        invocation.require(CHAT_PERMISSION);
                    }
                    lookups.run(invocation.sender(), query, Lookups.centerOf(invocation.sender()), 1, false);
                }),
            CommandSpec.named("page")
                .permission("xrewind.lookup")
                .description("Another page of your last lookup.")
                .usage("/xrewind page <number>")
                .executes(invocation -> lookups.page(invocation.sender(), invocation.integer(0, 1, 1_000_000))),
            CommandSpec.named("near")
                .permission("xrewind.lookup")
                .description("What changed within 5 blocks in the last 3 days.")
                .usage("/xrewind near")
                .executes(invocation -> {
                    Player player = invocation.player();
                    lookups.run(player, Params.parse(List.of("r:5", "t:3d")), Lookups.centerOf(player), 1, false);
                }));
    }

    static Query parse(XRewind plugin, List<String> args) {
        try {
            return Params.parse(args.stream().filter(arg -> !arg.isBlank()).toList());
        } catch (IllegalArgumentException e) {
            throw new CommandFailure(plugin.config().messages().lookup().invalid(), Placeholders.text("reason", e.getMessage()));
        }
    }

    /** Completes parameter names, players after u:, actions after a:, worlds after w:. */
    static Completer params(boolean flags) {
        return (sender, args) -> {
            String typed = args.length == 0 ? "" : args[args.length - 1];
            String lower = typed.toLowerCase(java.util.Locale.ROOT);
            List<String> options = new ArrayList<>();
            if (lower.startsWith("u:")) {
                Completions.players(sender, typed.substring(2)).forEach(name -> options.add("u:" + name));
                return options;
            }
            if (lower.startsWith("a:")) {
                ACTIONS.forEach(action -> options.add("a:" + action));
            } else if (lower.startsWith("w:")) {
                Bukkit.getWorlds().stream().map(World::getName).forEach(name -> options.add("w:" + name));
            } else if (lower.startsWith("r:")) {
                options.addAll(List.of("r:10", "r:#world", "r:#global"));
            } else if (lower.startsWith("t:")) {
                options.addAll(List.of("t:1h", "t:1d", "t:1w"));
            } else {
                options.addAll(KEYS);
                if (flags) {
                    options.addAll(FLAGS);
                }
            }
            return Completions.matching(typed, options);
        };
    }
}
