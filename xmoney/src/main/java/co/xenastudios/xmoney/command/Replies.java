package co.xenastudios.xmoney.command;

import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xmoney.XMoney;
import co.xenastudios.xmoney.config.XMConfig;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Helpers the commands share: amounts typed, who's asking, and answers that arrive later. */
final class Replies {

    private Replies() {}

    /** The amount typed at {@code index}, in units. Stops with the right message when it isn't one. */
    static long amount(XMoney plugin, Invocation invocation, int index) {
        String typed = invocation.arg(index);
        if (typed == null) {
            throw invocation.usage();
        }
        XMConfig.AmountMessages messages = plugin.config().messages().amount();
        try {
            return plugin.bank().format().parse(typed);
        } catch (IllegalArgumentException e) {
            throw switch (e.getMessage()) {
                case "too many decimals" -> new CommandFailure(messages.tooPrecise(), Placeholders.text("decimals", plugin.bank().decimals()));
                case "too large" -> new CommandFailure(messages.tooLarge());
                default -> new CommandFailure(messages.invalid(), Placeholders.text("amount", typed));
            };
        }
    }

    /** Who did it, for the ledger: the player's name, or "Console". */
    static String source(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : "Console";
    }

    /**
     * Runs {@code then} with the future's value on the sender's own thread once it's done. A
     * failure tells the sender something went wrong and logs it.
     */
    static <T> void later(XMoney plugin, CommandSender sender, CompletableFuture<T> future, Consumer<T> then) {
        future.whenComplete((value, error) -> plugin.tasks().sender(sender, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "A command failed", error);
                plugin.config().messages().error().failed().send(sender);
                return;
            }
            then.accept(value);
        }));
    }

    static TagResolver text(String name, Object value) {
        return Placeholders.text(name, value);
    }
}
