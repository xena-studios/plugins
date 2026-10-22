package co.xenastudios.core.command;

import java.util.List;
import org.bukkit.command.CommandSender;

/** Tab completions for the argument being typed, the last of {@code args}. */
@FunctionalInterface
public interface Completer {

    Completer NONE = (sender, args) -> List.of();

    List<String> complete(CommandSender sender, String[] args);
}
