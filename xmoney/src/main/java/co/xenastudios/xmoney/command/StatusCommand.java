package co.xenastudios.xmoney.command;

import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.text.Message;
import co.xenastudios.xmoney.XMoney;
import co.xenastudios.xmoney.ledger.Accounts;
import co.xenastudios.xmoney.ledger.Writer;

/** {@code /xmoney status}: where balances are kept, and whether saving is keeping up. */
public final class StatusCommand {

    private static final Message STATUS = Message.of("""
        <prefix><highlight>Storage</highlight>: <storage><network>
        <highlight>Accounts</highlight>: <accounts>. Players hold <total>
        <highlight>Saved</highlight> since startup: <written> changes. Waiting: <waiting>.
        <highlight>Last save</highlight>: <batch>
        <highlight>Problem</highlight>: <problem>""");

    private StatusCommand() {}

    public static CommandSpec spec(XMoney plugin) {
        return CommandSpec.named("status")
            .permission(XMoney.ID + ".status")
            .description("Whether balances are being saved.")
            .usage("/" + XMoney.ID + " status")
            .executes(invocation -> {
                Writer writer = plugin.ledger().writer();
                Accounts accounts = plugin.bank().accounts();
                int size = writer.lastBatchSize();
                invocation.sender().sendMessage(STATUS.chat(
                    Replies.text("storage", plugin.store().description()),
                    Replies.text("network", plugin.feed().on() ? ", shared with other servers (experimental)" : ""),
                    Replies.text("accounts", String.format("%,d", accounts.size())),
                    Replies.text("total", plugin.bank().format(accounts.total())),
                    Replies.text("written", String.format("%,d", writer.written())),
                    Replies.text("waiting", String.format("%,d", writer.waiting())),
                    Replies.text("batch", size == 0 ? "none yet"
                        : String.format("%,d changes in %.1f ms", size, writer.lastBatchNanos() / 1e6)),
                    Replies.text("problem", writer.problem() == null ? "none" : writer.problem())));
            });
    }
}
