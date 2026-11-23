package co.xenastudios.xvote.command;

import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.xvote.XVote;
import co.xenastudios.xvote.receiver.Keys;
import co.xenastudios.xvote.receiver.NuVotifierImport;
import co.xenastudios.xvote.receiver.Protocol;
import co.xenastudios.xvote.receiver.Receiver;
import co.xenastudios.xvote.receiver.Secrets;
import co.xenastudios.xvote.receiver.Tokens;
import co.xenastudios.xvote.storage.Imports;
import co.xenastudios.xvote.storage.Ledger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

/** {@code /xvote status|key|token|import|party}: the receiver and the ledger, for admins. */
public final class AdminCommands {

    private final XVote plugin;

    private AdminCommands(XVote plugin) {
        this.plugin = plugin;
    }

    public static List<CommandSpec> specs(XVote plugin) {
        AdminCommands commands = new AdminCommands(plugin);
        String permission = plugin.adminPermission();
        return List.of(
            CommandSpec.named("status").permission(permission).usage("/xvote status").executes(commands::status),
            CommandSpec.named("key").permission(permission).usage("/xvote key").executes(commands::key),
            CommandSpec.named("token").permission(permission).usage("/xvote token [service]").executes(commands::token),
            CommandSpec.named("import").permission(permission).usage("/xvote import <nuvotifier|votingplugin|superbvote>")
                .completes((sender, args) -> args.length <= 1
                    ? Completions.matching(args.length == 0 ? "" : args[0], List.of("nuvotifier", "votingplugin", "superbvote")) : List.of())
                .executes(commands::importFrom),
            CommandSpec.named("party").permission(permission).usage("/xvote party [start]")
                .completes((sender, args) -> args.length <= 1 ? Completions.matching(args.length == 0 ? "" : args[0], List.of("start")) : List.of())
                .executes(commands::party));
    }

    private void reply(CommandSender sender, String text) {
        Component message = plugin.staffMessage(text);
        plugin.tasks().sender(sender, () -> sender.sendMessage(message));
    }

    // ---- /xvote status ----

    private void status(Invocation inv) {
        CommandSender sender = inv.sender();
        Receiver receiver = plugin.receiver().receiver();
        var settings = plugin.config().receiver();
        String listening = receiver != null && !receiver.listening()
            ? "<error>stopped</error> on port " + receiver.port() + ": the console says why; /xvote reload starts it again"
            : receiver != null
            ? "<success>listening</success> on port <highlight>" + receiver.port() + "</highlight>, Votifier "
                + (receiver.settings().v1() ? "v1 and v2" : "v2 only")
            : settings.enabled() ? "<error>not listening</error>: the console says why" : "off";
        int tokens = receiver == null || receiver.credentials() == null ? 0 : receiver.credentials().tokens().all().size();
        var party = plugin.config().party();
        String partyLine = party.enabled() ? plugin.votes().party().votes() + " of " + party.votes() + " votes" : "off";
        String head = "<highlight>xVote status</highlight>"
            + "\n<highlight>Receiver</highlight>: " + listening + (receiver != null ? " (" + tokens + (tokens == 1 ? " token" : " tokens") + ")" : "")
            + "\n<highlight>Database</highlight>: " + VoteCommand.escape(plugin.store().description())
            + (plugin.feed().on() ? ", network mode on" : "")
            + "\n<highlight>Voters</highlight>: " + plugin.voters().size()
            + "\n<highlight>Vote party</highlight>: " + partyLine;
        plugin.store().submit(c -> new Object[] {plugin.ledger().waiting(c), plugin.ledger().recent(c, 5)}).whenComplete((found, error) -> {
            if (error != null) {
                reply(sender, head + "\n<error>Couldn't read the database:</error> " + VoteCommand.escape(error.getMessage()));
                return;
            }
            long[] waiting = (long[]) found[0];
            @SuppressWarnings("unchecked")
            List<Ledger.Recent> recent = (List<Ledger.Recent>) found[1];
            StringBuilder text = new StringBuilder(head)
                .append("\n<highlight>Waiting</highlight>: ").append(waiting[0]).append(" votes until their player is online, ")
                .append(waiting[1]).append(" for names that haven't joined");
            if (!recent.isEmpty()) {
                text.append("\n<highlight>Last votes</highlight> (the site is the service name to put in sites):");
                long now = System.currentTimeMillis();
                for (Ledger.Recent vote : recent) {
                    text.append("\n <gray>").append(co.xenastudios.core.text.Durations.format(Duration.ofMillis(now - vote.at())))
                        .append(" ago</gray> ").append(VoteCommand.escape(vote.name())).append(" from <highlight>")
                        .append(VoteCommand.escape(vote.site())).append("</highlight> <gray>(")
                        .append(vote.source().toLowerCase(Locale.ROOT).replace('_', ' '))
                        .append(vote.paid() ? ", paid" : vote.matched() ? ", waiting" : ", name unknown").append(")</gray>");
                }
            }
            reply(sender, text.toString());
        });
    }

    // ---- /xvote key ----

    private void key(Invocation inv) {
        CommandSender sender = inv.sender();
        var credentials = plugin.receiver().credentials();
        if (credentials == null || credentials.keys() == null) {
            reply(sender, "<error>There's no key yet.</error> The console says why.");
            return;
        }
        String key = Keys.publicText(credentials.keys());
        if (sender instanceof org.bukkit.entity.Player) {
            reply(sender, "The public key for lists that use Votifier v1: <click:copy_to_clipboard:'" + key
                + "'><hover:show_text:'Click to copy'><highlight>[Click to copy]</highlight></hover></click>"
                + " <gray>It's also in plugins/xVote/rsa/public.key.");
        } else {
            reply(sender, "The public key for lists that use Votifier v1 (also in plugins/xVote/rsa/public.key):\n" + key);
        }
    }

    // ---- /xvote token [service] ----

    private void token(Invocation inv) {
        CommandSender sender = inv.sender();
        var credentials = plugin.receiver().credentials();
        if (credentials == null) {
            reply(sender, "<error>The tokens couldn't be read.</error> The console says why.");
            return;
        }
        String wanted = inv.arg(0);
        Tokens tokens = credentials.tokens();
        if (wanted == null) {
            StringBuilder text = new StringBuilder("Votifier v2 tokens, by the service name lists send. Keep them secret:");
            for (Map.Entry<String, String> entry : tokens.all().entrySet()) {
                text.append("\n <highlight>").append(VoteCommand.escape(entry.getKey())).append("</highlight>: ")
                    .append(copyable(sender, entry.getValue()));
            }
            text.append("\n<gray>/xvote token <service> makes one for a list that wants its own.");
            reply(sender, text.toString());
            return;
        }
        String existing = tokens.forService(wanted);
        if (existing != null) {
            reply(sender, "The token for <highlight>" + VoteCommand.escape(wanted) + "</highlight>: " + copyable(sender, existing));
            return;
        }
        if (!wanted.matches("[A-Za-z0-9 ._:-]{1,64}")) {
            reply(sender, "<error>A service name is up to 64 letters, digits, spaces and . _ : -</error>");
            return;
        }
        String token = Protocol.newToken();
        plugin.tasks().async(() -> {
            try {
                plugin.receiver().saveTokens(tokens.with(wanted, token));
                reply(sender, "<success>Made a token</success> for <highlight>" + VoteCommand.escape(wanted) + "</highlight>: "
                    + copyable(sender, token) + " <gray>Saved in tokens.yml.");
            } catch (IOException e) {
                reply(sender, "<error>Couldn't save tokens.yml:</error> " + VoteCommand.escape(e.getMessage()));
            }
        });
    }

    private static String copyable(CommandSender sender, String token) {
        if (!(sender instanceof org.bukkit.entity.Player)) {
            return token;
        }
        return "<click:copy_to_clipboard:'" + token + "'><hover:show_text:'Click to copy'><highlight>" + token + "</highlight></hover></click>";
    }

    // ---- /xvote import nuvotifier ----

    private void importFrom(Invocation inv) {
        String source = inv.arg(0);
        if (source == null) {
            throw inv.usage();
        }
        switch (source.toLowerCase(Locale.ROOT)) {
            case "nuvotifier" -> nuVotifier(inv);
            case "votingplugin", "superbvote" -> totals(inv, source.toLowerCase(Locale.ROOT));
            default -> throw inv.usage();
        }
    }

    private void totals(Invocation inv, String source) {
        CommandSender sender = inv.sender();
        boolean again = "again".equalsIgnoreCase(inv.arg(1));
        Path plugins = plugin.getDataFolder().toPath().getParent();
        plugin.tasks().async(() -> {
            Imports.Found found;
            try {
                found = source.equals("superbvote") ? Imports.superbVote(plugins) : Imports.votingPlugin(plugins);
            } catch (IOException e) {
                reply(sender, "<error>Couldn't import from " + source + ":</error> " + VoteCommand.escape(e.getMessage()));
                return;
            }
            // Old records may have no name: the server's user cache usually knows it.
            List<Imports.Imported> players = new java.util.ArrayList<>(found.players().size());
            for (Imports.Imported player : found.players()) {
                String name = player.name();
                if (name.isBlank()) {
                    String known = org.bukkit.Bukkit.getOfflinePlayer(player.id()).getName();
                    name = known == null ? "" : known;
                }
                players.add(new Imports.Imported(player.id(), name, player.total(), player.lastVote()));
            }
            long now = System.currentTimeMillis();
            var periods = plugin.periods();
            plugin.store().submit(c -> {
                long before = plugin.ledger().imported(c, source);
                if (before > 0 && !again) {
                    return -before;
                }
                return (long) plugin.ledger().addImported(c, source, players, periods, now);
            }).whenComplete((result, error) -> {
                if (error == null && result >= 0) {
                    try {
                        plugin.votes().load(now);
                    } catch (java.sql.SQLException e) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING, "Couldn't read the imported totals back", e);
                    }
                }
                if (error != null) {
                    reply(sender, "<error>Couldn't store the imported totals:</error> " + VoteCommand.escape(error.getMessage()));
                } else if (result < 0) {
                    reply(sender, "<error>" + found.source() + "'s totals were imported before</error> ("
                        + co.xenastudios.core.text.Durations.format(Duration.ofMillis(now + result)) + " ago). <highlight>/xvote import "
                        + source + " again</highlight> adds them a second time.");
                } else {
                    reply(sender, "<success>Imported</success> the all-time totals of <highlight>" + result + "</highlight> players from "
                        + VoteCommand.escape(found.from()) + ". Day, week and month totals start now."
                        + (plugin.feed().on() ? " Other servers on the network show them after a restart." : ""));
                }
            });
        });
    }

    private void nuVotifier(Invocation inv) {
        CommandSender sender = inv.sender();
        Path folder = plugin.getDataFolder().toPath();
        plugin.tasks().async(() -> {
            Path found = NuVotifierImport.find(folder.getParent());
            if (found == null) {
                reply(sender, "<error>There's no NuVotifier folder</error> (plugins/Votifier) with keys or a config to take over.");
                return;
            }
            try {
                NuVotifierImport.Found read = NuVotifierImport.read(found);
                String stamp = Long.toString(System.currentTimeMillis());
                // Only what NuVotifier has is replaced, so only that is moved aside.
                if (read.keys() != null) {
                    backUp(folder.resolve("rsa"), folder.resolve("rsa.before-import-" + stamp));
                }
                if (read.tokens() != null) {
                    backUp(folder.resolve(Tokens.FILE), folder.resolve(Tokens.FILE + ".before-import-" + stamp));
                }
                NuVotifierImport.write(read, folder);
                plugin.useNuVotifierPort(read);
                // Reads the keys and tokens again, and moves to NuVotifier's port if it differs.
                plugin.tasks().global(() -> sender.sendMessage(plugin.reload()));
                reply(sender, "<success>Took over NuVotifier's setup</success> from plugins/" + found.getFileName() + ": "
                    + (read.keys() != null ? "its keys" : "no keys") + ", " + (read.tokens() != null ? read.tokens().all().size() + " tokens" : "no tokens")
                    + (read.port() > 0 ? ", port " + read.port() : "") + ". xVote's own " + (read.keys() != null && read.tokens() != null ? "were" : "was") + " kept as .before-import files."
                    + " Remove NuVotifier before the next restart.");
            } catch (IOException e) {
                reply(sender, "<error>Couldn't take over NuVotifier's setup:</error> " + VoteCommand.escape(e.getMessage()));
            }
        });
    }

    // Moved aside, and kept owner-only: they hold the private key or the tokens.
    private static void backUp(Path from, Path to) throws IOException {
        if (Files.exists(from)) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            if (Files.isDirectory(to)) {
                Secrets.ownerOnly(to.resolve("private.key"));
            } else {
                Secrets.ownerOnly(to);
            }
        }
    }

    // ---- /xvote party [start] ----

    private void party(Invocation inv) {
        CommandSender sender = inv.sender();
        var settings = plugin.config().party();
        String sub = inv.arg(0);
        if (sub == null) {
            reply(sender, settings.enabled()
                ? "Vote party: <highlight>" + plugin.votes().party().votes() + "</highlight> of " + settings.votes() + " votes."
                : "The vote party is off. Turn on party.enabled to use it.");
            return;
        }
        if (!sub.equalsIgnoreCase("start")) {
            throw inv.usage();
        }
        plugin.votes().startParty().whenComplete((done, error) -> reply(sender, error == null
            ? "<success>Vote party started.</success>"
            : "<error>Couldn't start the party:</error> " + VoteCommand.escape(error.getMessage())));
    }
}
