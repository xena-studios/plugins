package co.xenastudios.xmoney.command;

import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.text.ChatPages;
import co.xenastudios.xmoney.Bank;
import co.xenastudios.xmoney.XMoney;
import co.xenastudios.xmoney.config.XMConfig;
import co.xenastudios.xmoney.ledger.Accounts;
import java.util.List;
import org.bukkit.command.CommandSender;

/**
 * {@code /baltop [page]}: the richest players, from the sorted list kept in memory as
 * balances change. Never a database query.
 */
public final class BaltopCommand {

    private BaltopCommand() {}

    public static CommandSpec spec(XMoney plugin, XMConfig.Baltop settings) {
        return CommandSpec.named("baltop", settings)
            .description("The richest players.")
            .usage("/baltop [page]")
            .executes(invocation -> {
                int page = invocation.arg(0) == null ? 1 : invocation.integer(0, 1, Integer.MAX_VALUE);
                show(plugin, invocation.sender(), invocation.label(), page, plugin.config().commands().baltop().perPage());
            });
    }

    static void show(XMoney plugin, CommandSender sender, String label, int page, int perPage) {
        Bank bank = plugin.bank();
        Accounts accounts = bank.accounts();
        XMConfig.BaltopMessages messages = plugin.config().messages().baltop();
        int ranked = accounts.ranked();
        if (ranked == 0) {
            messages.empty().send(sender);
            return;
        }
        int pages = ChatPages.pages(ranked, perPage);
        int number = Math.clamp(page, 1, pages);
        List<Accounts.Ranked> rows = accounts.top((number - 1) * perPage + 1, perPage);
        messages.header().send(sender, Replies.text("total", bank.format(accounts.total())), Replies.text("count", String.format("%,d", ranked)));
        ChatPages.Page<Accounts.Ranked> shown = ChatPages.of(rows, ranked, number, perPage);
        ChatPages.lines(shown, row -> messages.line().chat(Replies.text("rank", row.rank()), Replies.text("player", row.account().name()),
            Replies.text("balance", bank.format(row.balance()))), messages.footer(), "/" + label + " {page}").forEach(sender::sendMessage);
    }
}
