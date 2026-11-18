package co.xenastudios.core;

import co.xenastudios.core.command.CommandSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.permissions.PermissionDefault;

/**
 * What a plugin wants running for the current config: its commands, permissions and
 * modules. The core makes the server match it on startup and on every reload.
 */
public final class Setup {

    final List<CommandSpec> commands = new ArrayList<>();
    final Map<String, PermissionDefault> permissions = new LinkedHashMap<>();
    final Map<String, Supplier<Module>> modules = new LinkedHashMap<>();
    final List<CommandSpec> subcommands = new ArrayList<>();

    final java.util.Set<String> reserved = new java.util.HashSet<>();

    Setup() {}

    /** Labels the core keeps for itself: the plugin's own command and its aliases. */
    void reserve(java.util.Collection<String> labels) {
        labels.forEach(label -> reserved.add(label.toLowerCase(java.util.Locale.ROOT)));
    }

    /**
     * Every name and alias in use so far, including the plugin's own command, which the
     * core adds once setup is done: for a plugin's own commands to avoid.
     */
    public java.util.Set<String> taken() {
        java.util.Set<String> taken = new java.util.HashSet<>(reserved);
        for (CommandSpec spec : commands) {
            taken.add(spec.name());
            taken.addAll(spec.aliases());
        }
        return taken;
    }

    /** Registers the command and declares its permission. */
    public void command(CommandSpec spec) {
        commands.add(spec);
        permission(spec.permission(), spec.permissionDefault());
    }

    /**
     * Adds a subcommand to the plugin's own command, beside {@code reload} and
     * {@code info}: {@code /<plugin> <name>}. Its spec's usage should be the whole
     * command, such as {@code /xrewind status}. Declares its permission.
     */
    public void subcommand(CommandSpec spec) {
        subcommands.add(spec);
        permission(spec.permission(), spec.permissionDefault());
    }

    /** The commands set up so far, such as to avoid their names. */
    public List<CommandSpec> commands() {
        return List.copyOf(commands);
    }

    /** Declares a permission with its default. A blank name is ignored. */
    public void permission(String name, PermissionDefault permissionDefault) {
        if (!name.isBlank()) {
            permissions.putIfAbsent(name, permissionDefault);
        }
    }

    /**
     * Runs a module while this key is set up. A module already on under the same key
     * stays on through a reload, so it should read settings from the live config.
     */
    public void module(String key, Supplier<Module> factory) {
        modules.put(key, factory);
    }
}
