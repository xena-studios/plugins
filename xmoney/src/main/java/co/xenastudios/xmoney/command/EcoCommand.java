package co.xenastudios.xmoney.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.command.Subcommands;
import co.xenastudios.core.text.ChatPages;
import co.xenastudios.core.text.Durations;
import co.xenastudios.core.text.Message;
import co.xenastudios.xmoney.Bank;
import co.xenastudios.xmoney.XMoney;
import co.xenastudios.xmoney.api.TransactionKind;
import co.xenastudios.xmoney.config.XMConfig;
import co.xenastudios.xmoney.ledger.Account;
import co.xenastudios.xmoney.ledger.Cause;
import co.xenastudios.xmoney.ledger.Outcome;
import co.xenastudios.xmoney.ledger.Store;
import co.xenastudios.xmoney.hook.MoneyService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.PermissionDefault;

/**
 * {@code /eco give|take|set|reset|history}: staff changing and reading balances, online or
 * offline. Each part has its own permission. Every change is in the ledger under the staff
 * member's name.
 */
public final class EcoCommand {

    private EcoCommand() {}

    private static String permission(String part) {
        return XMConfig.ECO + "." + part;
    }

    public static void declare(Setup setup, XMoney plugin, XMConfig.Eco settings) {
        List<CommandSpec> parts = List.of(
            change(plugin, "give", "<player> <amount>", TransactionKind.GIVE,
                (account, amount) -> amount == 0 ? null : (cause -> plugin.ledger().deposit(account, amount, cause))),
            change(plugin, "take", "<player> <amount>", TransactionKind.TAKE,
                (account, amount) -> amount == 0 ? null : (cause -> plugin.ledger().withdraw(account, amount, cause))),
            change(plugin, "set", "<player> <amount>", TransactionKind.SET,
                (account, amount) -> cause -> plugin.ledger().set(account, amount, cause)),
            reset(plugin),
            history(plugin));
        for (CommandSpec part : parts) {
            setup.permission(part.permission(), PermissionDefault.OP);
        }
        setup.command(CommandSpec.named("eco", settings)
            .description("Give, take, set and look up players' money.")
            .usage("/eco " + Subcommands.names(parts))
            .completes(Subcommands.completer(parts))
            .executes(Subcommands.executor(parts)));
    }

    private interface Change {
        CompletableFuture<Outcome> apply(Cause cause);
    }

    private static CommandSpec change(XMoney plugin, String name, String args, TransactionKind kind,
                                      BiFunction<Account, Long, Change> change) {
        return CommandSpec.named(name)
            .permission(permission(name))
            .usage("/eco " + name + " " + args)
            .completes((sender, typed) -> typed.length == 1 ? Completions.players(sender, typed[0]) : List.of())
            .executes(invocation -> {
                String player = invocation.arg(0);
                if (player == null) {
                    throw invocation.usage();
                }
                long amount = Replies.amount(plugin, invocation, 1);
                if (amount == 0 && kind != TransactionKind.SET) {
                    throw invocation.usage();
                }
                apply(plugin, invocation.sender(), player, kind, account -> change.apply(account, amount), name);
            });
    }

    private static CommandSpec reset(XMoney plugin) {
        return CommandSpec.named("reset")
            .permission(permission("reset"))
            .usage("/eco reset <player>")
            .completes((sender, typed) -> typed.length == 1 ? Completions.players(sender, typed[0]) : List.of())
            .executes(invocation -> {
                String player = invocation.arg(0);
                if (player == null) {
                    throw invocation.usage();
                }
                apply(plugin, invocation.sender(), player, TransactionKind.SET,
                    account -> cause -> plugin.ledger().set(account, plugin.bank().start(), cause), "reset");
            });
    }

    private static void apply(XMoney plugin, CommandSender sender, String name, TransactionKind kind,
                              java.util.function.Function<Account, Change> change, String part) {
        XMConfig.EcoMessages messages = plugin.config().messages().eco();
        Bank bank = plugin.bank();
        Cause cause = new Cause(kind, Replies.source(sender), "");
        Replies.later(plugin, sender, plugin.players().find(name, false), found -> {
            if (found.isEmpty()) {
                plugin.config().messages().balance().unknown().send(sender, Replies.text("player", name));
                return;
            }
            Account account = found.get();
            Replies.later(plugin, sender, change.apply(account).apply(cause), outcome -> {
                var player = Replies.text("player", account.name());
                var balance = Replies.text("balance", bank.format(outcome.balance()));
                switch (outcome.status()) {
                    case SUCCESS -> {
                        Message done = switch (part) {
                            case "give" -> messages.given();
                            case "take" -> messages.taken();
                            case "set" -> messages.set();
                            default -> messages.reset();
                        };
                        done.send(sender, player, balance, Replies.text("amount", bank.format(outcome.amount())));
                    }
                    case INSUFFICIENT_FUNDS -> messages.notEnough().send(sender, player, balance);
                    case BALANCE_LIMIT -> messages.limit().send(sender, player);
                    case CANCELLED -> messages.cancelled().send(sender);
                    case UNAVAILABLE -> messages.unavailable().send(sender);
                    default -> plugin.config().messages().error().failed().send(sender);
                }
            });
        });
    }

    private static CommandSpec history(XMoney plugin) {
        return CommandSpec.named("history")
            .permission(permission("history"))
            .usage("/eco history <player> [page]")
            .completes((sender, typed) -> typed.length == 1 ? Completions.players(sender, typed[0]) : List.of())
            .executes(invocation -> history(plugin, invocation));
    }

    private record Page(Account account, long total, int number, List<Store.Row> rows) {}

    private static void history(XMoney plugin, Invocation invocation) {
        String name = invocation.arg(0);
        if (name == null) {
            throw invocation.usage();
        }
        int page = invocation.arg(1) == null ? 1 : invocation.integer(1, 1, Integer.MAX_VALUE);
        CommandSender sender = invocation.sender();
        XMConfig.EcoMessages messages = plugin.config().messages().eco();
        Store store = plugin.store();
        messages.working().send(sender);
        // Read once what's been changed so far is saved, so the history has it.
        CompletableFuture<Optional<Account>> named = plugin.players().find(name, false).thenCombine(plugin.ledger().saved(), (found, saved) -> found);
        CompletableFuture<Page> read = named.thenApplyAsync(found -> {
            if (found.isEmpty()) {
                return null;
            }
            Account account = found.get();
            try {
                long total = store.count(account.id());
                int number = Math.clamp(page, 1, ChatPages.pages(total, MoneyService.HISTORY_PAGE));
                return new Page(account, total, number, store.history(account.id(), ChatPages.offset(number, MoneyService.HISTORY_PAGE),
                    MoneyService.HISTORY_PAGE));
            } catch (java.sql.SQLException e) {
                throw new CompletionException(e);
            }
        }, plugin.tasks()::async);
        Replies.later(plugin, sender, read, found -> {
            if (found == null) {
                plugin.config().messages().balance().unknown().send(sender, Replies.text("player", name));
                return;
            }
            var player = Replies.text("player", found.account().name());
            if (found.total() == 0) {
                messages.historyEmpty().send(sender, player);
                return;
            }
            messages.historyHeader().send(sender, player, Replies.text("count", String.format("%,d", found.total())));
            ChatPages.Page<Store.Row> shown = ChatPages.of(found.rows(), found.total(), found.number(), MoneyService.HISTORY_PAGE);
            long now = System.currentTimeMillis();
            ChatPages.lines(shown, row -> line(plugin, messages, row, now), messages.historyFooter(),
                "/" + invocation.label() + " history " + found.account().name() + " {page}").forEach(sender::sendMessage);
        });
    }

    private static Component line(XMoney plugin, XMConfig.EcoMessages messages, Store.Row row, long now) {
        Bank bank = plugin.bank();
        Message change = row.amount() >= 0 ? messages.changeIn() : messages.changeOut();
        Component amount = change.render(Replies.text("amount", bank.format(Math.abs(row.amount()))));
        return messages.historyLine().chat(
            Replies.text("ago", Durations.format(Duration.ofMillis(Math.max(0, now - row.time())))),
            co.xenastudios.core.text.Placeholders.component("change", amount),
            Replies.text("what", what(plugin, messages.words(), row)),
            Replies.text("balance", bank.format(row.balance())));
    }

    /** The words for what a row was: "paid Alex", "ChestShop", "xVote: vote reward". */
    static String what(XMoney plugin, XMConfig.Words words, Store.Row row) {
        String template = switch (row.kind()) {
            case START -> words.start();
            case PAY -> row.amount() < 0 ? words.paidTo() : words.paidBy();
            case GIVE -> words.given();
            case TAKE -> words.taken();
            case SET -> words.set();
            case PLUGIN -> row.reason().isEmpty() ? words.plugin() : words.pluginReason();
            case IMPORT -> words.imported();
        };
        return template.replace("<other>", name(plugin, row.other())).replace("<source>", row.source()).replace("<reason>", row.reason());
    }

    private static String name(XMoney plugin, UUID id) {
        if (id == null) {
            return "?";
        }
        Account account = plugin.bank().accounts().get(id);
        return account != null ? account.name() : id.toString().substring(0, 8);
    }
}
