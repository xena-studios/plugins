package co.xenastudios.sandbox;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.command.CommandRegistry;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.task.Tasks;
import co.xenastudios.sandbox.SandboxPlugin.Check;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;

/** The live-server checks `/sandbox selftest` runs. */
final class Checks {

    private Checks() {}

    static List<Check> all(SandboxPlugin plugin) {
        return List.of(
            check("command registered at runtime runs, by name, alias and namespace", Checks::runtimeRegister),
            check("command unregistered at runtime is gone from the client tree", Checks::runtimeUnregister),
            check("command re-registered with new aliases drops the old ones", Checks::runtimeRealias),
            check("core registry replaces a vanilla command and gives it back", () -> replacesVanilla(plugin)));
    }

    // ---- The core's CommandRegistry against a real server ----

    private static void replacesVanilla(SandboxPlugin plugin) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        CommandRegistry registry = new CommandRegistry(plugin, () -> ErrorMessages.DEFAULTS, new Tasks(plugin));
        Command vanilla = Bukkit.getCommandMap().getKnownCommands().get("seed");
        require(vanilla != null, "no vanilla /seed to replace");
        CommandSpec seed = CommandSpec.named("seed").executes(invocation -> hits.incrementAndGet());
        List<String> warnings = registry.sync(List.of(seed));
        try {
            require(warnings.isEmpty(), "warnings: " + warnings);
            dispatch("seed");
            require(hits.get() == 1, "ours didn't run");
            require(node("seed") != null, "no client tree node");
        } finally {
            registry.clear();
        }
        // Paper wraps vanilla commands afresh on every lookup, so compare by type.
        Command back = Bukkit.getCommandMap().getKnownCommands().get("seed");
        require(back != null && back.getClass() == vanilla.getClass(), "vanilla /seed wasn't given back: " + back);
        dispatch("seed");
        require(hits.get() == 1, "ours still ran after clear()");
    }

    // ---- Spike: live command (re)registration through the Bukkit CommandMap ----
    // If these pass, turning commands on or off and changing their aliases can
    // apply on reload, followed by Player#updateCommands, instead of a restart.

    private static void runtimeRegister() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        Command command = counting("spikea", List.of("spikea-alias"), hits);
        register(command);
        try {
            require(node("spikea") != null, "no client tree node for spikea");
            require(node("spikea-alias") != null, "no client tree node for the alias");
            require(node("sandbox:spikea") != null, "no client tree node for the namespaced label");
            dispatch("spikea");
            dispatch("spikea-alias");
            dispatch("sandbox:spikea");
            require(hits.get() == 3, "ran " + hits.get() + " of 3 times");
            List<String> completions = Bukkit.getCommandMap().tabComplete(Bukkit.getConsoleSender(), "spikea ");
            require(completions != null && completions.contains("alpha"), "tab completion was " + completions);
        } finally {
            unregister(command);
        }
    }

    private static void runtimeUnregister() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        Command command = counting("spikeb", List.of("spikeb-alias"), hits);
        register(command);
        unregister(command);
        for (String label : List.of("spikeb", "spikeb-alias", "sandbox:spikeb", "sandbox:spikeb-alias")) {
            require(node(label) == null, "client tree still has " + label);
            require(Bukkit.getCommandMap().getCommand(label) == null, "command map still has " + label);
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "spikeb");
        require(hits.get() == 0, "an unregistered command still ran");
    }

    private static void runtimeRealias() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        Command first = counting("spikec", List.of("spikec-old"), hits);
        register(first);
        unregister(first);
        Command second = counting("spikec", List.of("spikec-new"), hits);
        register(second);
        try {
            require(node("spikec-old") == null, "old alias still in the client tree");
            require(node("spikec-new") != null, "new alias missing from the client tree");
            dispatch("spikec-new");
            require(hits.get() == 1, "new alias didn't run");
        } finally {
            unregister(second);
        }
    }

    // ---- helpers ----

    private static Command counting(String name, List<String> aliases, AtomicInteger hits) {
        return new Command(name, "spike", "/" + name, aliases) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                hits.incrementAndGet();
                return true;
            }

            @Override
            public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                return List.of("alpha", "beta");
            }
        };
    }

    private static void register(Command command) {
        require(Bukkit.getCommandMap().register("sandbox", command), "label taken: " + command.getName());
    }

    private static void unregister(Command command) {
        CommandMap map = Bukkit.getCommandMap();
        Map<String, Command> known = map.getKnownCommands();
        known.entrySet().stream().filter(e -> e.getValue() == command).map(Map.Entry::getKey).toList().forEach(known::remove);
        command.unregister(map);
    }

    private static void dispatch(String line) {
        require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line), "dispatch failed: " + line);
    }

    /** The node the server would send clients for this label, or null. Reflection: there's no API for it. */
    private static CommandNode<?> node(String label) throws Exception {
        Object craftServer = Bukkit.getServer();
        Object server = craftServer.getClass().getMethod("getServer").invoke(craftServer);
        Object commands = server.getClass().getMethod("getCommands").invoke(server);
        CommandDispatcher<?> dispatcher = (CommandDispatcher<?>) commands.getClass().getMethod("getDispatcher").invoke(commands);
        return dispatcher.getRoot().getChild(label);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static Check check(String name, ThrowingRunnable body) {
        return new Check() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void run() throws Exception {
                body.run();
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
