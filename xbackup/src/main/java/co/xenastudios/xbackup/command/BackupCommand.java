package co.xenastudios.xbackup.command;

import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completer;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.command.Subcommands;
import co.xenastudios.core.text.Durations;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xbackup.config.XBConfig;
import co.xenastudios.xbackup.restore.Restore;
import co.xenastudios.xbackup.server.Engine;
import co.xenastudios.xbackup.store.Maintenance;
import co.xenastudios.xbackup.store.Sizes;
import co.xenastudios.xbackup.store.Snapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.PermissionDefault;

/**
 * {@code /backup}: every subcommand only queues a job or reads the catalogue, so none of
 * them waits on the disk; replies arrive when the job is done.
 */
public final class BackupCommand {

    private static final int PAGE = 8;
    private static final Message LINE = Message.of("<prefix><text>");

    private BackupCommand() {}

    public static CommandSpec spec(Engine engine, XBConfig.Command settings) {
        Completer none = Completer.NONE;
        List<CommandSpec> subcommands = List.of(
            sub("now", "/backup now [reason]", "Back up now.", none, invocation -> now(engine, invocation)),
            sub("list", "/backup list [page]", "List backups, newest first.", none, invocation -> list(engine, invocation)),
            sub("status", "/backup status", "What xBackup is doing, and how the last backup went.", none,
                invocation -> status(engine, invocation)),
            sub("verify", "/backup verify [full]", "Read back stored data and check it.", fixed(List.of("full")),
                invocation -> verify(engine, invocation)),
            sub("restore", "/backup restore <id|latest|cancel> [path] [confirm]", "Restore a backup on the next start.",
                restoreCompleter(engine), invocation -> restore(engine, invocation)),
            sub("prune", "/backup prune", "Remove backups retention doesn't keep, and give back their space.", none,
                invocation -> prune(engine, invocation)),
            sub("pin", "/backup pin <id>", "Keep a backup forever.", ids(engine), invocation -> pin(engine, invocation, true)),
            sub("unpin", "/backup unpin <id>", "Let retention remove a backup again.", ids(engine),
                invocation -> pin(engine, invocation, false)),
            sub("forget", "/backup forget <id> confirm", "Remove one backup now.", ids(engine),
                invocation -> forget(engine, invocation)),
            sub("copy", "/backup copy <remote>", "Copy the backups to a remote now.",
                (sender, args) -> args.length <= 1
                    ? Completions.matching(args.length == 0 ? "" : args[0], List.copyOf(engine.remotes().keySet()))
                    : List.of(),
                invocation -> copy(engine, invocation)),
            sub("repair", "/backup repair", "Rebuild the index from the stored data.", none,
                invocation -> repair(engine, invocation)));
        return CommandSpec.named("backup", settings)
            .description("Run, list, check and restore backups.")
            .usage("/backup " + Subcommands.names(subcommands))
            .completes(Subcommands.completer(subcommands))
            .executes(Subcommands.executor(subcommands));
    }

    /** Every subcommand's permission, for the core to declare. */
    public static List<String> permissions() {
        return List.of("now", "list", "status", "verify", "restore", "prune", "pin", "unpin", "forget", "copy", "repair")
            .stream().map(name -> "xbackup." + name).toList();
    }

    public static PermissionDefault permissionDefault() {
        return PermissionDefault.OP;
    }

    private static CommandSpec sub(String name, String usage, String description, Completer completer,
                                   co.xenastudios.core.command.Executor run) {
        return CommandSpec.named(name).permission("xbackup." + name).usage(usage).description(description)
            .completes(completer).executes(run);
    }

    // ---------------------------------------------------------------- replies

    private static void say(CommandSender sender, String miniMessage) {
        sender.sendMessage(LINE.chat(Placeholders.component("text", Message.of(miniMessage).render())));
    }

    private static String escape(String text) {
        return MiniMessage.miniMessage().escapeTags(text);
    }

    private static <T> void reply(CommandSender sender, CompletableFuture<T> job, Consumer<T> done) {
        job.whenComplete((value, error) -> {
            if (error != null) {
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                    ? error.getCause() : error;
                if (cause instanceof Engine.Skipped skipped) {
                    say(sender, "Skipped: " + escape(skipped.getMessage()) + ".");
                } else {
                    say(sender, "<error>" + escape(Engine.describe(cause)) + "</error>");
                }
                return;
            }
            try {
                done.accept(value);
            } catch (RuntimeException e) {
                say(sender, "<error>" + escape(Engine.describe(e)) + "</error>");
            }
        });
    }

    static String ago(Instant time) {
        Duration since = Duration.between(time, Instant.now());
        return since.isNegative() ? "just now" : since.toSeconds() < 60 ? "just now" : Durations.format(since) + " ago";
    }

    // "no chunks changed", "1 changed chunk", "12 changed chunks".
    private static String chunks(Engine engine) {
        long read = engine.lastResult() == null ? 0 : engine.lastResult().chunksRead();
        return read == 0 ? "no chunks changed" : read + (read == 1 ? " changed chunk" : " changed chunks");
    }

    private static String millis(long ms) {
        return ms < 1000 ? ms + " ms" : String.format("%.1fs", ms / 1000.0);
    }

    // ---------------------------------------------------------------- subcommands

    private static void now(Engine engine, Invocation invocation) {
        String reason = invocation.rest(0);
        CommandSender sender = invocation.sender();
        boolean busy = engine.activity() != null;
        say(sender, busy ? "Backing up after the job that's running (" + escape(engine.activity()) + ")."
            : "<gray>Backing up...</gray>");
        reply(sender, engine.backup(reason == null ? "manual" : reason, false), snapshot -> say(sender,
            "<success>Backed up</success> <highlight>" + snapshot.id().shortHex() + "</highlight>: " + snapshot.files()
                + " files, " + Sizes.format(snapshot.bytes()) + ", <highlight>" + Sizes.format(snapshot.added())
                + "</highlight> new (" + chunks(engine) + "), in " + millis(snapshot.durationMillis())
                + ". The server paused <highlight>"
                + snapshot.pauseMillis() + " ms</highlight>."
                + (snapshot.warnings().isEmpty() ? "" : " <error>" + snapshot.warnings().size()
                + " warnings</error>; the console lists them.")));
    }

    private static void list(Engine engine, Invocation invocation) {
        List<Snapshot> snapshots = engine.snapshots();
        CommandSender sender = invocation.sender();
        if (snapshots.isEmpty()) {
            say(sender, "No backups yet. <highlight>/backup now</highlight> makes one.");
            return;
        }
        int pages = (snapshots.size() + PAGE - 1) / PAGE;
        int page = invocation.arg(0) == null ? 1 : invocation.integer(0, 1, pages);
        say(sender, "<highlight>" + snapshots.size() + "</highlight> backups, " + Sizes.format(engine.repositoryBytes())
            + " in all. Click one to restore it.");
        int from = snapshots.size() - 1 - (page - 1) * PAGE;
        for (int i = from; i > from - PAGE && i >= 0; i--) {
            Snapshot snapshot = snapshots.get(i);
            String id = snapshot.id().shortHex();
            sender.sendMessage(Message.of("<click:suggest_command:'/backup restore " + id + " '><hover:show_text:'"
                + escape(snapshot.time().toString()) + "'><highlight>" + id + "</highlight></hover></click> <gray>"
                + ago(snapshot.time()) + "</gray> " + escape(snapshot.reason()) + " <gray>·</gray> " + Sizes.format(snapshot.bytes())
                + " <gray>+" + Sizes.format(snapshot.added()) + "</gray>" + (engine.pinned(snapshot) ? " <success>pinned</success>" : "")
                + (snapshot.warnings().isEmpty() ? "" : " <error>!</error>")).render());
        }
        if (page < pages) {
            sender.sendMessage(Message.of("<gray>Page " + page + " of " + pages + "</gray> <click:run_command:'/backup list "
                + (page + 1) + "'><highlight>Next page »</highlight></click>").render());
        }
    }

    private static void status(Engine engine, Invocation invocation) {
        CommandSender sender = invocation.sender();
        List<Snapshot> snapshots = engine.snapshots();
        List<String> lines = new ArrayList<>();
        lines.add("<highlight>Repository</highlight>: " + escape(engine.settings().repository().toString()) + ", "
            + snapshots.size() + " backups, " + Sizes.format(engine.repositoryBytes()));
        if (snapshots.isEmpty()) {
            lines.add("<highlight>Last backup</highlight>: none yet");
        } else {
            Snapshot last = snapshots.get(snapshots.size() - 1);
            lines.add("<highlight>Last backup</highlight>: " + ago(last.time()) + ", " + last.files() + " files, +"
                + Sizes.format(last.added()) + " in " + millis(last.durationMillis()));
            lines.add("<highlight>Server pause</highlight>: " + last.pauseMillis() + " ms saving, autosave off for "
                + millis(last.autosaveOffMillis()));
        }
        Instant next = engine.nextBackup();
        lines.add("<highlight>Next backup</highlight>: " + (next == null ? "off (/backup now only)"
            : "in " + Durations.format(Duration.between(Instant.now(), next).plusSeconds(1))));
        String activity = engine.activity();
        lines.add("<highlight>Running</highlight>: " + (activity == null ? "nothing" : escape(activity)));
        lines.add("<highlight>Last check</highlight>: " + escape(engine.lastCheck()));
        Map<String, XBConfig.Remote> remotes = engine.remotes();
        if (!remotes.isEmpty()) {
            for (Map.Entry<String, XBConfig.Remote> remote : remotes.entrySet()) {
                String state = !remote.getValue().enabled() ? "off"
                    : engine.remoteStatus().getOrDefault(remote.getKey(), "not copied since the server started");
                lines.add("<highlight>Remote " + escape(remote.getKey()) + "</highlight> (" + escape(remote.getValue().type())
                    + "): " + escape(state));
            }
        }
        String problem = engine.problem();
        lines.add("<highlight>Problem</highlight>: " + (problem == null ? "none" : "<error>" + escape(problem) + "</error>"));
        say(sender, String.join("\n", lines));
        reply(sender, engine.pendingRestore(), pending -> {
            if (pending != null) {
                say(sender, "<highlight>Restore waiting</highlight>: backup " + pending.snapshot().substring(0, 8) + " ("
                    + pending.entries().size() + " paths), on the next start. <highlight>/backup restore cancel</highlight>"
                    + " to call it off.");
            }
        });
    }

    private static void verify(Engine engine, Invocation invocation) {
        boolean full = "full".equalsIgnoreCase(invocation.arg(0));
        CommandSender sender = invocation.sender();
        say(sender, "<gray>Checking " + (full ? "everything" : "a sample") + "...</gray>");
        reply(sender, engine.verify(full), (Maintenance.Report report) -> {
            if (report.ok()) {
                say(sender, "<success>All good.</success> Read back " + report.packsChecked() + " of " + report.packsTotal()
                    + " packs (" + Sizes.format(report.bytesChecked()) + ") across " + report.snapshots() + " backups.");
            } else {
                say(sender, "<error>Found " + report.problems().size() + " problems</error>, the first: "
                    + escape(report.problems().get(0)) + " The console lists them all; <highlight>/backup repair</highlight>"
                    + " rebuilds the index if that's what's damaged.");
            }
        });
    }

    private static void restore(Engine engine, Invocation invocation) {
        CommandSender sender = invocation.sender();
        String first = invocation.arg(0);
        if (first == null) {
            throw invocation.usage();
        }
        if (first.equalsIgnoreCase("cancel")) {
            reply(sender, engine.cancelRestore(), cancelled -> say(sender, cancelled
                ? "<success>Cancelled</success> the restore that was waiting for the next start."
                : "No restore is waiting."));
            return;
        }
        Snapshot snapshot = find(engine, first);
        List<String> paths = new ArrayList<>();
        boolean confirm = false;
        for (int i = 1; i < invocation.size(); i++) {
            String arg = invocation.arg(i);
            if (arg.equalsIgnoreCase("confirm")) {
                confirm = true;
            } else {
                try {
                    paths.add(Restore.normalise(arg));
                } catch (IllegalArgumentException e) {
                    throw new CommandFailure(Message.of("<prefix><error>" + escape(e.getMessage())));
                }
            }
        }
        if (paths.isEmpty()) {
            paths.add("");
        }
        String what = paths.equals(List.of("")) ? "everything" : String.join(", ", paths);
        String id = snapshot.id().shortHex();
        if (!confirm) {
            reply(sender, engine.plan(snapshot, paths), plan -> {
                StringBuilder text = new StringBuilder("Restoring <highlight>" + escape(what) + "</highlight> from backup <highlight>"
                    + id + "</highlight> (" + ago(snapshot.time()) + ") would change " + plan.changed() + " files"
                    + (plan.changedChunks() > 0 ? " (" + plan.changedChunks() + " region chunks)" : "") + ", bring back "
                    + plan.added() + " and set aside " + plan.removed() + ". Confirm to stage it: it's swapped in on the next"
                    + " start, before any world loads, and what's there now is kept in plugins/xBackup/restore/replaced/.");
                for (String example : plan.examples()) {
                    text.append("\n<gray>").append(escape(example)).append("</gray>");
                }
                String command = "/backup restore " + id + " " + String.join(" ", paths).strip() + " confirm";
                text.append("\n<click:run_command:'").append(command.replace("  ", " ")).append("'><hover:show_text:'")
                    .append(command.replace("  ", " ")).append("'><success>[Restore it]</success></hover></click>");
                say(sender, text.toString());
            });
            return;
        }
        say(sender, "<gray>Staging " + escape(what) + " from backup " + id + "...</gray>");
        reply(sender, engine.stage(snapshot, paths), written -> say(sender, "<success>Staged</success> " + written.files()
            + " files (" + Sizes.format(written.bytes()) + "), each checked against its backup. <highlight>Restart the"
            + " server</highlight> to put them in place. <highlight>/backup restore cancel</highlight> calls it off."));
    }

    private static void prune(Engine engine, Invocation invocation) {
        CommandSender sender = invocation.sender();
        say(sender, "<gray>Cleaning up...</gray>");
        reply(sender, engine.prune(), pruned -> say(sender, "<success>Removed</success> " + pruned.removed()
            + " backups retention doesn't keep (" + pruned.kept() + " kept), and gave back " + Sizes.format(pruned.freed()) + "."));
    }

    private static void pin(Engine engine, Invocation invocation, boolean pinned) {
        if (invocation.arg(0) == null) {
            throw invocation.usage();
        }
        Snapshot snapshot = find(engine, invocation.arg(0));
        CommandSender sender = invocation.sender();
        reply(sender, engine.pin(snapshot, pinned), done -> say(sender, pinned
            ? "<success>Pinned</success> " + snapshot.id().shortHex() + ": retention will always keep it."
            : "Unpinned " + snapshot.id().shortHex() + ": retention can remove it again."));
    }

    private static void forget(Engine engine, Invocation invocation) {
        if (invocation.arg(0) == null) {
            throw invocation.usage();
        }
        Snapshot snapshot = find(engine, invocation.arg(0));
        CommandSender sender = invocation.sender();
        if (!"confirm".equalsIgnoreCase(invocation.arg(1))) {
            String command = "/backup forget " + snapshot.id().shortHex() + " confirm";
            say(sender, "This removes backup <highlight>" + snapshot.id().shortHex() + "</highlight> (" + ago(snapshot.time())
                + ") for good. <click:run_command:'" + command + "'><hover:show_text:'" + command + "'><error>[Remove it]</error></hover></click>");
            return;
        }
        reply(sender, engine.forget(snapshot), done -> say(sender, "Removed backup " + snapshot.id().shortHex()
            + ". Its space comes back at the next clean-up (<highlight>/backup prune</highlight>)."));
    }

    private static void copy(Engine engine, Invocation invocation) {
        String name = invocation.arg(0);
        if (name == null) {
            throw invocation.usage();
        }
        CommandSender sender = invocation.sender();
        say(sender, "<gray>Copying to " + escape(name) + "...</gray>");
        reply(sender, engine.push(name), result -> say(sender, "<success>Copied</success> to " + escape(name) + ": "
            + result.filesSent() + " files (" + Sizes.format(result.bytesSent()) + ") sent, " + result.filesDeleted()
            + " removed."));
    }

    private static void repair(Engine engine, Invocation invocation) {
        CommandSender sender = invocation.sender();
        say(sender, "<gray>Rebuilding the index from the packs...</gray>");
        reply(sender, engine.repair(), rebuilt -> say(sender, "<success>Rebuilt</success> the index: " + rebuilt.blobs()
            + " pieces in " + rebuilt.packs() + " packs." + (rebuilt.damaged().isEmpty() ? ""
            : " <error>" + rebuilt.damaged().size() + " packs are damaged</error>; the console lists them. Run"
            + " <highlight>/backup verify full</highlight> to see which backups that touches.")));
    }

    // ---------------------------------------------------------------- helpers

    private static Snapshot find(Engine engine, String id) {
        try {
            return engine.find(id);
        } catch (IllegalArgumentException e) {
            throw new CommandFailure(Message.of("<prefix><error>" + escape(e.getMessage())));
        }
    }

    private static Completer fixed(List<String> options) {
        return (sender, args) -> args.length <= 1 ? Completions.matching(args.length == 0 ? "" : args[0], options) : List.of();
    }

    private static List<String> idList(Engine engine) {
        List<String> ids = new ArrayList<>();
        ids.add("latest");
        List<Snapshot> snapshots = engine.snapshots();
        for (int i = snapshots.size() - 1; i >= 0 && ids.size() < 30; i--) {
            ids.add(snapshots.get(i).id().shortHex());
        }
        return ids;
    }

    private static Completer ids(Engine engine) {
        return (sender, args) -> args.length <= 1 ? Completions.matching(args.length == 0 ? "" : args[0], idList(engine))
            : List.of();
    }

    private static Completer restoreCompleter(Engine engine) {
        return (sender, args) -> {
            if (args.length <= 1) {
                List<String> options = new ArrayList<>(idList(engine));
                options.add("cancel");
                return Completions.matching(args.length == 0 ? "" : args[0], options);
            }
            List<String> options = new ArrayList<>(engine.topLevel());
            options.add("confirm");
            return Completions.matching(args[args.length - 1], options);
        };
    }
}
