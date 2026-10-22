package co.xenastudios.core.command;

import co.xenastudios.core.text.Message;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/** Stops a command and sends the sender a message. Carries no stack trace: it isn't a bug. */
public final class CommandFailure extends RuntimeException {

    private final transient Message message;
    private final transient TagResolver[] placeholders;

    public CommandFailure(Message message, TagResolver... placeholders) {
        super(message.template(), null, false, false);
        this.message = message;
        this.placeholders = placeholders;
    }

    void send(CommandSender sender) {
        message.send(sender, placeholders);
    }
}
