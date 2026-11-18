package co.xenastudios.xstats.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.text.ChatPages;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xstats.XStats;
import co.xenastudios.xstats.api.LeaderboardEntry;
import co.xenastudios.xstats.api.Period;
import co.xenastudios.xstats.config.XSConfig;
import co.xenastudios.xstats.data.Board;
import co.xenastudios.xstats.stat.Format;
import co.xenastudios.xstats.stat.StatDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/** /top <stat> [daily|weekly|monthly|all] [page]: a leaderboard from memory, a page at a time. */
public final class TopCommand {

    private TopCommand() {}

    public static void register(Setup setup, XStats plugin, XSConfig config) {
        XSConfig.Command settings = config.commands().top();
        if (!settings.enabled()) {
            return;
        }
        setup.command(CommandSpec.named("top", settings)
            .permissionDefault(PermissionDefault.TRUE)
            .description("See a leaderboard.")
            .usage("/top <stat> [daily|weekly|monthly|all] [page]")
            .completes((sender, args) -> switch (args.length) {
                case 1 -> Completions.matching(args[0], shown(plugin).stream().map(StatDef::id).toList());
                case 2 -> {
                    Optional<StatDef> stat = find(plugin, args[0]);
                    yield stat.isPresent() && stat.get().periods()
                        ? Completions.matching(args[1], List.of("daily", "weekly", "monthly", "all")) : List.of();
                }
                default -> List.of();
            })
            .executes(invocation -> {
                XSConfig current = plugin.config();
                XSConfig.Messages messages = current.messages();
                if (!plugin.engine().ready()) {
                    throw new CommandFailure(messages.notReady());
                }
                if (invocation.size() == 0) {
                    throw invocation.usage();
                }
                StatDef stat = find(plugin, invocation.arg(0)).orElseThrow(() -> new CommandFailure(messages.unknownStat(),
                    Placeholders.text("stat", invocation.arg(0)),
                    Placeholders.text("stats", String.join(", ", shown(plugin).stream().map(StatDef::id).toList()))));
                Period period = Period.ALL;
                int pageArg = 1;
                if (invocation.size() >= 2) {
                    Optional<Period> parsed = Period.parse(invocation.arg(1));
                    if (parsed.isPresent()) {
                        period = parsed.get();
                        pageArg = 2;
                    }
                }
                if (period != Period.ALL && !stat.periods()) {
                    throw new CommandFailure(messages.noPeriods(), Placeholders.text("stat", stat.name()));
                }
                int page = invocation.size() > pageArg ? invocation.integer(pageArg, 1, Integer.MAX_VALUE) : 1;
                send(plugin, invocation.sender(), stat, period, page);
            }));
    }

    static List<StatDef> shown(XStats plugin) {
        return plugin.catalog().snapshot().stats().stream().filter(StatDef::inGame).toList();
    }

    /** A stat by id, or by name ignoring case and spaces: "mob_kills", "Mobs killed". */
    static Optional<StatDef> find(XStats plugin, String typed) {
        String wanted = typed.toLowerCase(Locale.ROOT);
        for (StatDef stat : shown(plugin)) {
            if (stat.id().equals(wanted) || stat.name().toLowerCase(Locale.ROOT).replace(' ', '_').equals(wanted)) {
                return Optional.of(stat);
            }
        }
        return Optional.empty();
    }

    private static void send(XStats plugin, CommandSender sender, StatDef stat, Period period, int page) {
        XSConfig config = plugin.config();
        XSConfig.Messages messages = config.messages();
        Board board = plugin.engine().leaderboards().get(stat.id(), period);
        int kept = Math.min(board == null ? 0 : board.size(), config.leaderboards().size());
        if (kept == 0) {
            messages.topEmpty().send(sender);
            return;
        }
        int perPage = config.leaderboards().perPage();
        int pages = ChatPages.pages(kept, perPage);
        int number = Math.max(1, Math.min(page, pages));
        int from = (number - 1) * perPage;
        ChatPages.Page<LeaderboardEntry> shown = ChatPages.of(board.slice(from, Math.min(perPage, kept - from)), kept, number, perPage);
        List<Component> lines = new ArrayList<>();
        lines.add(messages.topHeader().chat(Placeholders.text("stat", stat.name()), Placeholders.text("period", co.xenastudios.xstats.web.Pages.periodName(config, period))));
        java.util.UUID self = sender instanceof Player player ? player.getUniqueId() : null;
        lines.addAll(ChatPages.lines(shown, entry -> (entry.player().equals(self) ? messages.topLineSelf() : messages.topLine()).render(
                Placeholders.text("rank", entry.rank()),
                Placeholders.text("player", entry.name()),
                Placeholders.text("value", Format.value(stat, entry.value()))),
            messages.pageFooter(), "/top " + stat.id() + " " + period.id() + " {page}"));
        lines.forEach(sender::sendMessage);
    }
}
