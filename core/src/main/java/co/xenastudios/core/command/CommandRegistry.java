package co.xenastudios.core.command;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.task.Tasks;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * A plugin's commands, registered while the server runs. {@link #sync} makes the
 * registered commands match a list of specs, so turning a command on or off, or
 * changing its aliases or permission, applies on reload. A command that's off isn't
 * registered at all, leaving its name free for other plugins.
 *
 * <p>Our commands replace the server's own of the same name, such as vanilla
 * {@code /clear}, and give them back when they're turned off. They never replace
 * another plugin's: that clash is reported, and ours stays reachable as
 * {@code /<plugin>:<name>}.
 */
public final class CommandRegistry {

    private final Plugin plugin;
    private final String namespace;
    private final Supplier<ErrorMessages> errors;
    private final Tasks tasks;
    private final Map<String, RuntimeCommand> active = new LinkedHashMap<>();
    /** Server commands we took a label from, to give back. */
    private final Map<String, Command> displaced = new HashMap<>();
    /** The registered names, for readers on other threads (/info on Folia). */
    private volatile List<String> names = List.of();

    public CommandRegistry(Plugin plugin, Supplier<ErrorMessages> errors, Tasks tasks) {
        this.plugin = plugin;
        this.namespace = plugin.getName().toLowerCase(Locale.ROOT);
        this.errors = errors;
        this.tasks = tasks;
    }

    /**
     * Registers, updates and unregisters commands to match {@code specs}, then
     * refreshes online players' command lists. Call on the global thread (the main
     * thread on Paper); only {@link #names} is safe from other threads.
     *
     * @return warnings about labels another plugin already has
     */
    public List<String> sync(Collection<CommandSpec> specs) {
        Map<String, CommandSpec> wanted = new LinkedHashMap<>();
        for (CommandSpec spec : specs) {
            wanted.put(spec.name(), spec);
        }
        CommandMap map = Bukkit.getCommandMap();
        List<String> warnings = new ArrayList<>();
        boolean changed = false;

        for (RuntimeCommand command : List.copyOf(active.values())) {
            CommandSpec spec = wanted.get(command.getName());
            if (spec == null || !spec.aliases().equals(command.spec().aliases())) {
                unregister(map, command);
                changed = true;
            }
        }
        for (CommandSpec spec : wanted.values()) {
            RuntimeCommand existing = active.get(spec.name());
            if (existing != null) {
                changed |= !spec.permission().equals(existing.spec().permission());
                existing.update(spec);
            } else {
                warnings.addAll(register(map, new RuntimeCommand(plugin, spec, errors)));
                changed = true;
            }
        }
        names = List.copyOf(active.keySet());
        if (changed) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                tasks.entity(player, player::updateCommands);
            }
        }
        return warnings;
    }

    /** Unregisters every command, giving back any server commands they replaced. */
    public void clear() {
        CommandMap map = Bukkit.getCommandMap();
        for (RuntimeCommand command : List.copyOf(active.values())) {
            unregister(map, command);
        }
        names = List.of();
    }

    /** The names of the registered commands. Safe from any thread. */
    public List<String> names() {
        return names;
    }

    private List<String> register(CommandMap map, RuntimeCommand command) {
        Map<String, Command> known = map.getKnownCommands();
        List<String> labels = labels(command);
        for (String label : labels) {
            Command existing = known.get(label);
            // Paper would let us overwrite a server command anyway; keep it to give back.
            if (existing != null && !(existing instanceof PluginIdentifiableCommand)) {
                displaced.put(label, existing);
                known.remove(label);
            }
        }
        map.register(namespace, command);
        active.put(command.getName(), command);

        List<String> warnings = new ArrayList<>();
        for (String label : labels) {
            Command owner = known.get(label);
            if (owner != command) {
                warnings.add("/%s already belongs to %s, so %s's is /%s:%s. Turn one of them off, or give ours another alias."
                    .formatted(label, ownerName(owner), plugin.getName(), namespace, label));
            }
        }
        return warnings;
    }

    private void unregister(CommandMap map, RuntimeCommand command) {
        Map<String, Command> known = map.getKnownCommands();
        // By key: Paper's map forwards key removal to the client command tree.
        List<String> owned = known.entrySet().stream()
            .filter(entry -> entry.getValue() == command)
            .map(Map.Entry::getKey)
            .toList();
        owned.forEach(known::remove);
        command.unregister(map);
        active.remove(command.getName());
        for (String label : labels(command)) {
            Command server = displaced.remove(label);
            if (server != null && !known.containsKey(label)) {
                known.put(label, server);
            }
        }
    }

    private static List<String> labels(RuntimeCommand command) {
        List<String> labels = new ArrayList<>();
        labels.add(command.getName());
        labels.addAll(command.spec().aliases());
        return labels;
    }

    private static String ownerName(Command owner) {
        if (owner instanceof PluginIdentifiableCommand pluginCommand) {
            return pluginCommand.getPlugin().getName();
        }
        return owner == null ? "nothing" : "the server";
    }
}
