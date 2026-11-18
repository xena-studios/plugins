package co.xenastudios.xmoney.command;

import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.xmoney.XMoney;
import co.xenastudios.xmoney.config.XMConfig;
import co.xenastudios.xmoney.imports.CmiBalances;
import co.xenastudios.xmoney.imports.EssentialsBalances;
import co.xenastudios.xmoney.imports.ImportException;
import co.xenastudios.xmoney.imports.Importer;
import co.xenastudios.xmoney.imports.Source;
import co.xenastudios.xmoney.imports.VaultBalances;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.command.CommandSender;

/**
 * {@code /xmoney import <essentials|cmi|vault> [confirm]}: without {@code confirm}, a dry run
 * that changes nothing and says what would be imported; with it, the import. One at a time.
 */
public final class ImportCommand {

    private static final List<String> SOURCES = List.of("essentials", "cmi", "vault");
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private ImportCommand() {}

    public static CommandSpec spec(XMoney plugin) {
        return CommandSpec.named("import")
            .permission(XMoney.ID + ".import")
            .description("Import balances from another economy plugin.")
            .usage("/" + XMoney.ID + " import <essentials|cmi|vault> [confirm]")
            .completes((sender, args) -> switch (args.length) {
                case 1 -> Completions.matching(args[0], SOURCES);
                case 2 -> Completions.matching(args[1], List.of("confirm"));
                default -> List.of();
            })
            .executes(invocation -> {
                String which = invocation.arg(0);
                if (which == null || !SOURCES.contains(which.toLowerCase(Locale.ROOT))) {
                    throw invocation.usage();
                }
                boolean confirm = "confirm".equalsIgnoreCase(invocation.arg(1));
                run(plugin, invocation.sender(), which.toLowerCase(Locale.ROOT), confirm);
            });
    }

    private static void run(XMoney plugin, CommandSender sender, String which, boolean confirm) {
        XMConfig.ImportMessages messages = plugin.config().messages().imports();
        if (!RUNNING.compareAndSet(false, true)) {
            messages.running().send(sender);
            return;
        }
        Path plugins = plugin.getDataFolder().toPath().getParent();
        Source source = switch (which) {
            case "essentials" -> new EssentialsBalances(plugins, plugin.tasks()::async);
            case "cmi" -> new CmiBalances(plugins, plugin.tasks()::async);
            default -> new VaultBalances(plugin);
        };
        messages.reading().send(sender, Replies.text("source", source.name()));
        var bank = plugin.bank();
        source.read()
            .thenApply(found -> Importer.plan(source.name(), found, bank.decimals(), bank.max()))
            .thenCompose(plan -> {
                if (plan.lines().isEmpty()) {
                    throw new ImportException("it has no balances that can be imported (" + plan.skipped() + " skipped).", true);
                }
                if (!confirm) {
                    plugin.tasks().sender(sender, () -> messages.dryRun().send(sender,
                        Replies.text("source", plan.source()),
                        Replies.text("count", String.format("%,d", plan.lines().size())),
                        Replies.text("total", bank.format(plan.total())),
                        Replies.text("skipped", String.format("%,d", plan.skipped())),
                        Replies.text("command", "/" + XMoney.ID + " import " + which + " confirm")));
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }
                return Importer.apply(bank, plan).thenAccept(set -> {
                    plugin.getLogger().info("Imported " + set + " balances from " + plan.source() + ", " + plan.skipped() + " skipped.");
                    plugin.tasks().sender(sender, () -> messages.done().send(sender,
                        Replies.text("source", plan.source()),
                        Replies.text("count", String.format("%,d", set)),
                        Replies.text("skipped", String.format("%,d", plan.skipped()))));
                });
            })
            .whenComplete((ignored, error) -> {
                RUNNING.set(false);
                if (error == null) {
                    return;
                }
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                plugin.tasks().sender(sender, () -> {
                    if (cause instanceof ImportException problem) {
                        (problem.missing() ? messages.nothing() : messages.failed()).send(sender,
                            Replies.text("source", source.name()), Replies.text("reason", problem.getMessage()));
                    } else {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE, "Importing from " + source.name() + " failed", cause);
                        plugin.config().messages().error().failed().send(sender);
                    }
                });
            });
    }
}
