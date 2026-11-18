package co.xenastudios.xbans;

import co.xenastudios.core.text.Message;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/** A punishment or lookup that can't go ahead, with what to tell the sender. No stack trace: it isn't a bug. */
public final class Refusal extends RuntimeException {

    private final transient Message message;
    private final transient TagResolver[] placeholders;

    public Refusal(Message message, TagResolver... placeholders) {
        super(message.template(), null, false, false);
        this.message = message;
        this.placeholders = placeholders;
    }

    /** What the sender would be told, for an API caller. */
    public net.kyori.adventure.text.Component render() {
        return message.render(placeholders);
    }

    public void send(CommandSender sender) {
        message.send(sender, placeholders);
    }

    /** The refusal behind a failed future, if that's what failed it. */
    public static Refusal of(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof Refusal refusal ? refusal : null;
    }
}
