package co.xenastudios.xvote.command;

import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.text.ChatPages;
import co.xenastudios.core.text.Durations;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xvote.XVote;
import co.xenastudios.xvote.api.Period;
import co.xenastudios.xvote.api.VoteResult;
import co.xenastudios.xvote.api.VoteSource;
import co.xenastudios.xvote.config.XVConfig;
import co.xenastudios.xvote.receiver.Receiver;
import co.xenastudios.xvote.receiver.Tokens;
import co.xenastudios.xvote.receiver.VoteClient;
import co.xenastudios.xvote.reward.Rewards;
import co.xenastudios.xvote.vote.IncomingVote;
import co.xenastudios.xvote.vote.Voter;
import co.xenastudios.xvote.vote.Voters;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/** {@code /vote}: where to vote, the leaderboards, held items, and {@code /vote test} for staff. */
public final class VoteCommand {

    static final int PER_PAGE = 10;
    private static final List<String> PERIODS = List.of("day", "week", "month", "all");

    private final XVote plugin;

    private VoteCommand(XVote plugin) {
        this.plugin = plugin;
    }

    public static CommandSpec spec(XVote plugin) {
        VoteCommand command = new VoteCommand(plugin);
        return CommandSpec.named("vote")
            .permission(XVote.USE)
            .permissionDefault(PermissionDefault.TRUE)
            .description("Where to vote, and the top voters.")
            .usage("/vote [top|claim]")
            .completes(command::complete)
            .executes(command::run);
    }

    private void run(Invocation inv) {
        String sub = inv.arg(0);
        if (sub == null) {
            list(inv.sender());
            return;
        }
        switch (sub.toLowerCase(Locale.ROOT)) {
            case "top" -> top(inv);
            case "claim" -> claim(inv.player());
            case "test" -> {
                inv.require(XVote.TEST);
                test(inv);
            }
            default -> throw inv.usage();
        }
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            List<String> subs = new ArrayList<>(List.of("top", "claim"));
            if (sender.hasPermission(XVote.TEST)) {
                subs.add("test");
            }
            return Completions.matching(args.length == 0 ? "" : args[0], subs);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("top") && args.length == 2) {
            return Completions.matching(args[1], PERIODS);
        }
        if (sub.equals("test") && sender.hasPermission(XVote.TEST)) {
            if (args.length == 2) {
                return Completions.players(sender, args[1]);
            }
            if (args.length == 3) {
                return Completions.matching(args[2], plugin.rewards().sites().stream().map(Rewards.Site::key).toList());
            }
        }
        return List.of();
    }

    // ---- /vote ----

    private void list(CommandSender sender) {
        XVConfig.VoteList messages = plugin.config().messages().list();
        Rewards rewards = plugin.rewards();
        if (rewards.sites().isEmpty()) {
            messages.none().send(sender);
            return;
        }
        long now = System.currentTimeMillis();
        Voter voter = sender instanceof Player player ? plugin.voters().get(player.getUniqueId()) : null;
        List<Component> lines = new ArrayList<>();
        int open = 0;
        for (Rewards.Site site : rewards.sites()) {
            long next = voter == null ? 0 : voter.lastOn(site.service()) + site.cooldownMs();
            TagResolver placeholders = TagResolver.resolver(Placeholders.text("site", site.name()), link(site.link()));
            if (next <= now) {
                open++;
                lines.add(messages.available().chat(placeholders));
            } else {
                lines.add(messages.cooldown().chat(placeholders,
                    Placeholders.text("time", Durations.format(Duration.ofMillis(next - now)))));
            }
        }
        messages.header().send(sender, Placeholders.text("available", open), Placeholders.text("sites", rewards.sites().size()));
        lines.forEach(sender::sendMessage);
        if (sender instanceof Player player) {
            int total = voter == null ? 0 : voter.total();
            int streak = voter == null ? 0 : voter.streakNow(plugin.periods(), now);
            messages.footer().send(player, Placeholders.text("total", total), Placeholders.text("streak", streak));
        }
        XVConfig.Party party = plugin.config().party();
        if (party.enabled()) {
            long votes = plugin.votes().party().votes();
            messages.party().send(sender, Placeholders.text("votes", votes), Placeholders.text("needed", party.votes()),
                Placeholders.text("left", Math.max(0, party.votes() - votes)));
        }
    }

    /**
     * A site's link as a placeholder that also works inside a click or hover: those only
     * take pre-processed tags. Escaped so a link can't add formatting, and without
     * {@code '}, which would end the click's argument.
     */
    static TagResolver link(String link) {
        String safe = MiniMessage.miniMessage().escapeTags(link.replace("'", "%27"));
        return TagResolver.resolver("link", Tag.preProcessParsed(safe));
    }

    // ---- /vote top [period] [page] ----

    private void top(Invocation inv) {
        Period period = Period.MONTH;
        int pageArg = 1;
        int index = 1;
        String first = inv.arg(1);
        if (first != null && !first.matches("\\d+")) {
            period = switch (first.toLowerCase(Locale.ROOT)) {
                case "day", "today" -> Period.DAY;
                case "week" -> Period.WEEK;
                case "month" -> Period.MONTH;
                case "all", "alltime", "total" -> Period.ALL;
                default -> throw inv.usage();
            };
            index = 2;
        }
        if (inv.arg(index) != null) {
            pageArg = inv.integer(index, 1, 100_000);
        }
        XVConfig.Top messages = plugin.config().messages().top();
        String periodName = periodName(messages.periods(), period);
        List<Voters.Ranked> ranked = plugin.voters().top(period, System.currentTimeMillis());
        CommandSender sender = inv.sender();
        if (ranked.isEmpty()) {
            messages.empty().send(sender, Placeholders.text("period", periodName));
            return;
        }
        ChatPages.Page<Voters.Ranked> page = ChatPages.of(ranked, pageArg, PER_PAGE);
        messages.header().send(sender, Placeholders.text("period", periodName));
        ChatPages.lines(page, entry -> messages.line().chat(
                Placeholders.text("rank", entry.rank()),
                Placeholders.text("player", entry.voter().name()),
                Placeholders.text("votes", entry.votes())),
            messages.footer(), "/vote top " + period.name().toLowerCase(Locale.ROOT) + " {page}")
            .forEach(sender::sendMessage);
    }

    static String periodName(XVConfig.Periods periods, Period period) {
        return switch (period) {
            case DAY -> periods.day();
            case WEEK -> periods.week();
            case MONTH -> periods.month();
            case ALL -> periods.all();
        };
    }

    // ---- /vote claim ----

    private void claim(Player player) {
        XVConfig.Claim messages = plugin.config().messages().claim();
        java.util.UUID id = player.getUniqueId();
        plugin.store().submit(c -> plugin.ledger().takeHeld(c, id)).whenComplete((items, error) -> {
            if (error != null) {
                plugin.tasks().entity(player, () -> player.sendMessage(plugin.config().messages().error().failed().chat()));
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Couldn't read " + player.getName() + "'s held items", error);
                return;
            }
            if (items.isEmpty()) {
                plugin.tasks().entity(player, () -> messages.none().send(player));
                return;
            }
            // Taken out of the database already: if the player has gone, they go back.
            var task = player.getScheduler().run(plugin, scheduled -> plugin.handOut(player, items), () -> putBack(id, items));
            if (task == null) {
                putBack(id, items);
            }
        });
    }

    private void putBack(java.util.UUID id, List<byte[]> items) {
        plugin.store().submit(c -> {
            plugin.ledger().hold(c, id, items, System.currentTimeMillis());
            return null;
        });
    }

    // ---- /vote test <player> [site] ----

    private void test(Invocation inv) {
        String name = inv.arg(1);
        if (name == null) {
            throw new CommandFailure(Message.of("<prefix><error>Usage:</error> /vote test <player> [site]"));
        }
        String siteArg = inv.arg(2);
        Rewards.Site site = siteArg == null ? null : plugin.rewards().siteByKey(siteArg);
        String service = site != null ? site.service() : siteArg != null ? siteArg : "test";
        CommandSender sender = inv.sender();
        long now = System.currentTimeMillis();
        String timestamp = Long.toString(now);
        Receiver receiver = plugin.receiver().receiver();
        plugin.tasks().async(() -> {
            CompletableFuture<VoteResult> result;
            String via;
            if (receiver != null) {
                Tokens tokens = receiver.credentials().tokens();
                String token = tokens.forService(service);
                if (token == null) {
                    token = tokens.forService(Tokens.DEFAULT);
                }
                String host = receiver.settings().host().isBlank() || receiver.settings().host().equals("0.0.0.0")
                    ? "127.0.0.1" : receiver.settings().host();
                result = plugin.votes().expectTest(service, name, timestamp);
                via = " through the receiver on port " + receiver.port();
                try {
                    String refused = VoteClient.sendV2(host, receiver.port(), token == null ? "" : token,
                        new VoteClient.Vote(service, name, "127.0.0.1", timestamp), 5000);
                    if (refused != null) {
                        reply(sender, "<error>The receiver refused the test vote:</error> " + escape(refused));
                        return;
                    }
                } catch (IOException e) {
                    reply(sender, "<error>Couldn't reach the receiver on port " + receiver.port() + ":</error> " + escape(e.getMessage())
                        + " Sending the test straight to xVote instead.");
                    result = plugin.votes().accept(new IncomingVote(service, name, "127.0.0.1", timestamp, VoteSource.TEST, now));
                    via = " (not through the receiver)";
                }
            } else {
                result = plugin.votes().accept(new IncomingVote(service, name, "127.0.0.1", timestamp, VoteSource.TEST, now));
                via = " (the receiver is off)";
            }
            String how = via;
            result.orTimeout(30, TimeUnit.SECONDS).whenComplete((outcome, error) ->
                reply(sender, describe(error != null ? VoteResult.FAILED : outcome, name, service, how)));
        });
    }

    private String describe(VoteResult result, String name, String service, String via) {
        String who = "<highlight>" + escape(name) + "</highlight>";
        String where = "<highlight>" + escape(service) + "</highlight>";
        return switch (result) {
            case COUNTED -> "<success>Test vote for " + escape(name) + " counted</success> from " + where + via + "."
                + (plugin.votes().online().byName(name) != null ? " It's being paid now." : " It's paid when they're next online.");
            case WAITING -> "Test vote for " + who + " from " + where + " stored" + via + ". Nobody by that name has joined,"
                + " so it waits until they first do.";
            case DUPLICATE -> "<error>Test vote for " + escape(name) + " dropped as a repeat:</error> they voted on " + where
                + " within its cooldown. Try another site, or leave the site off.";
            case INVALID -> "<error>\"" + escape(name) + "\" can't be a Minecraft name.</error>";
            case CANCELLED -> "<error>Another plugin cancelled the test vote for " + escape(name) + ".</error>";
            case FAILED -> "<error>The test vote for " + escape(name) + " couldn't be stored.</error> The console says why.";
        };
    }

    private void reply(CommandSender sender, String text) {
        Component message = plugin.staffMessage(text);
        plugin.tasks().sender(sender, () -> sender.sendMessage(message));
    }

    static String escape(String text) {
        return MiniMessage.miniMessage().escapeTags(text == null ? "" : text);
    }
}
