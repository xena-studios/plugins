package co.xenastudios.core.command;

import co.xenastudios.core.ErrorMessages;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;

/** A command registered while the server runs. Its spec can be swapped on reload without re-registering. */
final class RuntimeCommand extends Command implements PluginIdentifiableCommand {

    private final Plugin plugin;
    private final Supplier<ErrorMessages> errors;
    private volatile CommandSpec spec;

    RuntimeCommand(Plugin plugin, CommandSpec spec, Supplier<ErrorMessages> errors) {
        super(spec.name(), spec.description(), spec.usage(), spec.aliases());
        this.plugin = plugin;
        this.errors = errors;
        update(spec);
    }

    CommandSpec spec() {
        return spec;
    }

    void update(CommandSpec spec) {
        this.spec = spec;
        setDescription(spec.description());
        setUsage(spec.usage());
        // Paper hides commands a player lacks the permission for from their client.
        setPermission(spec.permission().isBlank() ? null : spec.permission());
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        CommandSpec current = spec;
        ErrorMessages messages = errors.get();
        if (!current.permission().isBlank() && !sender.hasPermission(current.permission())) {
            messages.noPermission().send(sender);
            return true;
        }
        try {
            current.executor().execute(new Invocation(sender, label, args, current.usage(), messages));
        } catch (CommandFailure failure) {
            failure.send(sender);
        } catch (RuntimeException | LinkageError e) {
            // One broken command never takes anything else down with it.
            plugin.getLogger().log(Level.SEVERE, "/" + label + " failed for " + sender.getName(), e);
            messages.failed().send(sender);
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        CommandSpec current = spec;
        if (!current.permission().isBlank() && !sender.hasPermission(current.permission())) {
            return List.of();
        }
        try {
            return current.completer().complete(sender, args);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Tab completion for /" + alias + " failed", e);
            return List.of();
        }
    }

    @Override
    public Plugin getPlugin() {
        return plugin;
    }
}
