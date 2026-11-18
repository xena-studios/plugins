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
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.command.CommandSender;

/**
 * {@code /xmoney import <essentials|cmi|vault> [confirm [overwrite]]}: without {@code confirm},
 * a dry run that changes nothing and says what would be imported; with it, the import. If
 * some of the accounts have changed in xMoney since (players paid each other here, say), the
 * import would set them back, so it also needs {@code overwrite}. One at a time.
 */
public final class ImportCommand {

    private static final List<String> SOURCES = List.of("essentials", "cmi", "vault");
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private ImportCommand() {}

    public static CommandSpec spec(XMoney plugin) {
        return CommandSpec.named("import")
            .permission(XMoney.ID + ".import")
            .description("Import balances from another economy plugin.")
            .usage("/" + XMoney.ID + " import <essentials|cmi|vault> [confirm [overwrite]]")
            .completes((sender, args) -> switch (args.length) {
                case 1 -> Completions.matching(args[0], SOURCES);
                case 2 -> Completions.matching(args[1], List.of("confirm"));
                case 3 -> Completions.matching(args[2], List.of("overwrite"));
                default -> List.of();
            })
            .executes(invocation -> {
                String which = invocation.arg(0);
                if (which == null || !SOURCES.contains(which.toLowerCase(Locale.ROOT))) {
                    throw invocation.usage();
                }
                boolean confirm = "confirm".equalsIgnoreCase(invocation.arg(1));
                boolean overwrite = confirm && "overwrite".equalsIgnoreCase(invocation.arg(2));
                run(plugin, invocation.sender(), which.toLowerCase(Locale.ROOT), confirm, overwrite);
            });
    }

    private record Checked(Importer.Plan plan, int overwrites) {}

    private static void run(XMoney plugin, CommandSender sender, String which, boolean confirm, boolean overwrite) {
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
        // The accounts changed here are read once every change so far is saved: one made just
        // before would otherwise be missed, and set back without asking.
        CompletableFuture<Void> saved = plugin.ledger().saved();
        source.read()
            .thenCombine(saved, (found, ignored) -> found)
            .thenApplyAsync(found -> {
                Importer.Plan plan = Importer.plan(source.name(), found, bank.max());
                if (plan.lines().isEmpty()) {
                    throw new ImportException("it has no balances that can be imported (" + plan.skipped() + " skipped).", true);
                }
                try {
                    return new Checked(plan, Importer.overwrites(plan, plugin.store().changedHere()));
                } catch (SQLException e) {
                    throw new CompletionException(e);
                }
            }, plugin.tasks()::async)
            .thenCompose(checked -> {
                Importer.Plan plan = checked.plan();
                String command = "/" + XMoney.ID + " import " + which + " confirm";
                if (checked.overwrites() > 0 && (!confirm || !overwrite)) {
                    plugin.tasks().sender(sender, () -> {
                        if (!confirm) {
                            dryRun(messages, sender, bank, plan, command);
                        }
                        messages.overwrite().send(sender, Replies.text("source", plan.source()),
                            Replies.text("count", String.format("%,d", checked.overwrites())),
                            Replies.text("command", command + " overwrite"));
                    });
                    return CompletableFuture.completedFuture(null);
                }
                if (!confirm) {
                    plugin.tasks().sender(sender, () -> dryRun(messages, sender, bank, plan, command));
                    return CompletableFuture.completedFuture(null);
                }
                return Importer.apply(bank, plan).thenAccept(set -> {
                    int failed = plan.lines().size() - set;
                    if (failed > 0) {
                        plugin.getLogger().warning(failed + " balances from " + plan.source() + " couldn't be set; "
                            + "/eco history shows what each account has.");
                    }
                    int skipped = plan.skipped() + failed;
                    plugin.getLogger().info("Imported " + set + " balances from " + plan.source() + ", " + skipped + " skipped.");
                    plugin.tasks().sender(sender, () -> messages.done().send(sender,
                        Replies.text("source", plan.source()),
                        Replies.text("count", String.format("%,d", set)),
                        Replies.text("skipped", String.format("%,d", skipped))));
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

    private static void dryRun(XMConfig.ImportMessages messages, CommandSender sender, co.xenastudios.xmoney.Bank bank,
                               Importer.Plan plan, String command) {
        messages.dryRun().send(sender,
            Replies.text("source", plan.source()),
            Replies.text("count", String.format("%,d", plan.lines().size())),
            Replies.text("total", bank.format(plan.total())),
            Replies.text("skipped", String.format("%,d", plan.skipped())),
            Replies.text("command", command));
    }
}
