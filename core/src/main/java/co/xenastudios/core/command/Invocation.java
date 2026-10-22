package co.xenastudios.core.command;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.text.Placeholders;
import java.util.Arrays;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** One run of a command: who ran it, with what arguments, and helpers that stop it with the right error. */
public final class Invocation {

    private final CommandSender sender;
    private final String label;
    private final String[] args;
    private final String usage;
    private final ErrorMessages errors;

    Invocation(CommandSender sender, String label, String[] args, String usage, ErrorMessages errors) {
        this.sender = sender;
        this.label = label;
        this.args = args;
        this.usage = usage;
        this.errors = errors;
    }

    public CommandSender sender() {
        return sender;
    }

    /** The name or alias it was run as. */
    public String label() {
        return label;
    }

    public int size() {
        return args.length;
    }

    /** The argument at {@code index}, or null if there isn't one. */
    public String arg(int index) {
        return index < args.length ? args[index] : null;
    }

    /** The arguments from {@code index} on, joined with spaces, or null if there are none. */
    public String rest(int index) {
        return index < args.length ? String.join(" ", Arrays.copyOfRange(args, index, args.length)) : null;
    }

    /** The sender as a player. Stops with the players-only error for the console. */
    public Player player() {
        if (sender instanceof Player player) {
            return player;
        }
        throw new CommandFailure(errors.playerOnly());
    }

    /**
     * The player named at {@code index}, or the sender if there's no name there.
     * Naming someone else needs {@code othersPermission} (blank means anyone may).
     * Players the sender can't see count as offline, so hidden staff stay hidden.
     */
    public Player target(int index, String othersPermission) {
        String name = arg(index);
        if (name == null) {
            if (sender instanceof Player player) {
                return player;
            }
            throw usage();
        }
        if (sender instanceof Player player && player.getName().equalsIgnoreCase(name)) {
            return player;
        }
        require(othersPermission);
        Player found = Bukkit.getPlayerExact(name);
        if (found == null || (sender instanceof Player viewer && !viewer.canSee(found))) {
            throw new CommandFailure(errors.playerNotFound(), Placeholders.text("player", name));
        }
        return found;
    }

    /** Whether the sender is acting on someone else. */
    public boolean isOther(Player target) {
        return target != sender;
    }

    /** Stops with the no-permission error unless the sender has the permission (blank means anyone has it). */
    public void require(String permission) {
        if (!permission.isBlank() && !sender.hasPermission(permission)) {
            throw new CommandFailure(errors.noPermission());
        }
    }

    /** The whole number at {@code index}, from {@code min} to {@code max}. Stops with the usage error otherwise. */
    public int integer(int index, int min, int max) {
        String raw = arg(index);
        if (raw == null) {
            throw usage();
        }
        try {
            int value = Integer.parseInt(raw);
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Falls through to the usage error.
        }
        throw usage();
    }

    /** The number at {@code index}, from {@code min} to {@code max}. Stops with the usage error otherwise. */
    public double decimal(int index, double min, double max) {
        String raw = arg(index);
        if (raw == null) {
            throw usage();
        }
        try {
            double value = Double.parseDouble(raw);
            if (value >= min && value <= max && Double.isFinite(value)) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Falls through to the usage error.
        }
        throw usage();
    }

    /** The usage error, to throw: {@code throw invocation.usage();}. */
    public CommandFailure usage() {
        return new CommandFailure(errors.usage(), Placeholders.text("usage", usage));
    }
}
