package co.xenastudios.xmoney.command;

import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.xmoney.Bank;
import co.xenastudios.xmoney.XMoney;
import co.xenastudios.xmoney.config.XMConfig;
import co.xenastudios.xmoney.ledger.Account;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /balance [player]}: your balance, or someone else's, online or not. */
public final class BalanceCommand {

    private BalanceCommand() {}

    public static CommandSpec spec(XMoney plugin, XMConfig.Balance settings) {
        return CommandSpec.named("balance", settings)
            .description("Your balance, or another player's.")
            .usage("/balance [player]")
            .completes((sender, args) -> args.length == 1
                ? Completions.players(sender, args[0], settings.permissionOthers()) : List.of())
            .executes(invocation -> run(plugin, settings, invocation));
    }

    private static void run(XMoney plugin, XMConfig.Balance settings, Invocation invocation) {
        Bank bank = plugin.bank();
        XMConfig.BalanceMessages messages = plugin.config().messages().balance();
        CommandSender sender = invocation.sender();
        String name = invocation.arg(0);
        if (name == null || sender instanceof Player self && self.getName().equalsIgnoreCase(name)) {
            Player player = invocation.player();
            messages.own().send(player, Replies.text("balance", bank.format(bank.balance(bank.accounts().get(player.getUniqueId())))));
            return;
        }
        invocation.require(settings.permissionOthers());
        Replies.later(plugin, sender, plugin.players().find(name, false), found -> {
            if (found.isEmpty()) {
                messages.unknown().send(sender, Replies.text("player", name));
                return;
            }
            Account account = found.get();
            messages.other().send(sender, Replies.text("player", account.name()), Replies.text("balance", bank.format(account.balance())));
        });
    }
}
