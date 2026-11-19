package co.xenastudios.xteleport.command;

import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xteleport.XTeleport;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/** What the command classes share. */
final class Replies {

    private Replies() {}

    /** Stops with "not available right now" until the database is open and the warps read. */
    static void requireReady(XTeleport plugin) {
        if (!plugin.ready()) {
            throw new CommandFailure(plugin.config().messages().teleport().unavailable());
        }
    }

    /**
     * Replies once a change has been written to the database, on the sender's thread:
     * {@code saved} if it was, otherwise "couldn't save, try again" (the change was already
     * undone in memory, and the console says why).
     */
    static void whenSaved(XTeleport plugin, CommandSender sender, CompletableFuture<?> write, Runnable saved) {
        write.whenComplete((done, error) -> plugin.tasks().sender(sender, () -> {
            if (error != null) {
                plugin.config().messages().teleport().notSaved().send(sender);
            } else {
                saved.run();
            }
        }));
    }

    /** A command to click: see {@link Placeholders#click}. */
    static TagResolver click(String name, String command) {
        return Placeholders.click(name, command);
    }

    /** {@code <world>}, {@code <x>}, {@code <y>} and {@code <z>}, in whole blocks. */
    static TagResolver where(String world, double x, double y, double z) {
        return TagResolver.resolver(
            Placeholders.text("world", world),
            Placeholders.text("x", (long) Math.floor(x)),
            Placeholders.text("y", (long) Math.floor(y)),
            Placeholders.text("z", (long) Math.floor(z)));
    }

    /** A page number typed, or 1. */
    static int page(String typed) {
        if (typed == null) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(typed));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    static boolean isNumber(String typed) {
        return typed != null && !typed.isEmpty() && typed.chars().allMatch(Character::isDigit) && typed.length() < 7;
    }
}
