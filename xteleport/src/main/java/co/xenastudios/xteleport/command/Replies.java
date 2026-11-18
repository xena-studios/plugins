package co.xenastudios.xteleport.command;

import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xteleport.XTeleport;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

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
     * A placeholder for a command to click, such as {@code <command>} in
     * {@code <click:run_command:'<command>'>}. Inside a click only pre-processed tags are
     * filled in; escaped, so nothing in it can add formatting.
     */
    static TagResolver click(String name, String command) {
        return TagResolver.resolver(name, Tag.preProcessParsed(MiniMessage.miniMessage().escapeTags(command)));
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
