package co.xenastudios.core.command;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Building blocks for {@link Completer}s. */
public final class Completions {

    private Completions() {}

    /** The options that start with what's typed, ignoring case. */
    public static List<String> matching(String typed, Collection<String> options) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    /** Names of online players the sender can see that start with what's typed. */
    public static List<String> players(CommandSender sender, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
            .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
            .map(Player::getName)
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
            .toList();
    }

    /** Player names, if the sender may act on others. */
    public static List<String> players(CommandSender sender, String typed, String othersPermission) {
        return othersPermission.isBlank() || sender.hasPermission(othersPermission) ? players(sender, typed) : List.of();
    }
}
