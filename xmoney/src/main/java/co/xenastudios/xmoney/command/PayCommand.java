package co.xenastudios.xmoney.command;

import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xmoney.Bank;
import co.xenastudios.xmoney.XMoney;
import co.xenastudios.xmoney.api.TransactionKind;
import co.xenastudios.xmoney.config.XMConfig;
import co.xenastudios.xmoney.ledger.Account;
import co.xenastudios.xmoney.ledger.Cause;
import co.xenastudios.xmoney.ledger.Outcome;
import co.xenastudios.xmoney.money.Money;
import java.math.BigDecimal;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * {@code /pay <player> <amount>}: sends money to another player, online or not, as long as
 * they've played here. Large payments ask first: a click runs the same command with
 * {@code confirm} on the end, so there's nothing to remember and nothing to expire.
 */
public final class PayCommand {

    private PayCommand() {}

    public static CommandSpec spec(XMoney plugin, XMConfig.Pay settings) {
        return CommandSpec.named("pay", settings)
            .description("Send money to another player.")
            .usage("/pay <player> <amount>")
            .completes((sender, args) -> args.length == 1 ? Completions.players(sender, args[0]) : List.of())
            .executes(invocation -> run(plugin, settings, invocation));
    }

    private static void run(XMoney plugin, XMConfig.Pay settings, Invocation invocation) {
        Player payer = invocation.player();
        String name = invocation.arg(0);
        if (name == null) {
            throw invocation.usage();
        }
        Bank bank = plugin.bank();
        XMConfig.PayMessages messages = plugin.config().messages().pay();
        long amount = Replies.amount(plugin, invocation, 1);
        boolean confirmed = "confirm".equalsIgnoreCase(invocation.arg(2));
        if (name.equalsIgnoreCase(payer.getName())) {
            throw new CommandFailure(messages.self());
        }
        long minimum = Math.max(1, Money.units(BigDecimal.valueOf(settings.minimum()), bank.decimals()));
        if (amount < minimum) {
            throw new CommandFailure(messages.tooLittle(), Replies.text("minimum", bank.format(minimum)));
        }
        Account own = bank.accounts().get(payer.getUniqueId());
        if (own == null || own.balance() < amount) {
            throw new CommandFailure(messages.notEnough(), Replies.text("amount", bank.format(amount)),
                Replies.text("balance", bank.format(bank.balance(own))));
        }
        Replies.later(plugin, payer, plugin.players().find(name, true), found -> {
            if (found.isEmpty()) {
                plugin.config().messages().balance().unknown().send(payer, Replies.text("player", name));
                return;
            }
            Account payee = found.get();
            if (payee.id().equals(payer.getUniqueId())) {
                messages.self().send(payer);
                return;
            }
            long confirmAbove = Money.units(BigDecimal.valueOf(settings.confirmAbove()), bank.decimals());
            if (confirmAbove > 0 && amount >= confirmAbove && !confirmed) {
                String command = "/" + invocation.label() + " " + payee.name() + " " + bank.format().typed(amount) + " confirm";
                messages.confirm().send(payer, Replies.text("amount", bank.format(amount)), Replies.text("player", payee.name()),
                    Placeholders.click("command", command));
                return;
            }
            Cause cause = new Cause(TransactionKind.PAY, payer.getName(), "");
            Account from = bank.accounts().get(payer.getUniqueId());
            Replies.later(plugin, payer, bank.ledger().transfer(from, payee, amount, cause), outcome -> {
                reply(plugin, payer, payee, amount, outcome);
            });
        });
    }

    private static void reply(XMoney plugin, Player payer, Account payee, long amount, Outcome outcome) {
        Bank bank = plugin.bank();
        XMConfig.PayMessages messages = plugin.config().messages().pay();
        switch (outcome.status()) {
            case SUCCESS -> {
                messages.sent().send(payer, Replies.text("amount", bank.format(amount)), Replies.text("player", payee.name()),
                    Replies.text("balance", bank.format(outcome.balance())));
                Player online = Bukkit.getPlayer(payee.id());
                if (online != null) {
                    plugin.tasks().entity(online, () -> messages.received().send(online,
                        Replies.text("amount", bank.format(amount)), Replies.text("player", payer.getName())));
                }
            }
            case INSUFFICIENT_FUNDS -> messages.notEnough().send(payer, Replies.text("amount", bank.format(amount)),
                Replies.text("balance", bank.format(outcome.balance())));
            case BALANCE_LIMIT -> messages.limit().send(payer, Replies.text("player", payee.name()));
            case SAME_ACCOUNT -> messages.self().send(payer);
            case CANCELLED -> messages.cancelled().send(payer);
            case UNAVAILABLE -> messages.unavailable().send(payer);
            case INVALID_AMOUNT -> plugin.config().messages().amount().invalid().send(payer, Replies.text("amount", bank.format(amount)));
        }
    }
}
