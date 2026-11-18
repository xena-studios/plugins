package co.xenastudios.xbans.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.text.ChatPages;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xbans.Refusal;
import co.xenastudios.xbans.XBans;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.config.XBConfig;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.model.Length;
import co.xenastudios.xbans.storage.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/** /history, /staffhistory, /checkban, /checkmute, /warnings, /banlist, /mutelist and /prunehistory. */
public final class LookupCommands {

    public static final String WARNINGS_OTHERS = "xbans.warnings.others";
    private static final List<String> TYPE_WORDS = List.of("bans", "mutes", "warnings", "kicks");

    private LookupCommands() {}

    public static void register(Setup setup, XBans xbans, XBConfig config) {
        XBConfig.Commands commands = config.commands();
        Targets targets = new Targets(xbans);
        if (commands.history().enabled()) {
            setup.command(CommandSpec.named("history", commands.history())
                .description("A player's punishments, newest first.")
                .usage("/history <player> [bans|mutes|warnings|kicks] [page]")
                .completes((sender, args) -> args.length == 1 ? Completions.players(sender, args[0])
                    : Completions.matching(args[args.length - 1], TYPE_WORDS))
                .executes(invocation -> history(xbans, targets, invocation, false)));
        }
        if (commands.staffhistory().enabled()) {
            setup.command(CommandSpec.named("staffhistory", commands.staffhistory())
                .description("The punishments a staff member made, newest first.")
                .usage("/staffhistory <staff|console> [bans|mutes|warnings|kicks] [page]")
                .completes((sender, args) -> args.length == 1 ? Completions.players(sender, args[0])
                    : Completions.matching(args[args.length - 1], TYPE_WORDS))
                .executes(invocation -> history(xbans, targets, invocation, true)));
        }
        check(setup, xbans, targets, "checkban", PunishmentType.BAN, commands.checkban());
        check(setup, xbans, targets, "checkmute", PunishmentType.MUTE, commands.checkmute());
        if (commands.warnings().enabled()) {
            setup.command(CommandSpec.named("warnings", commands.warnings())
                .permissionDefault(PermissionDefault.TRUE)
                .description("Active warnings: your own, or a player's.")
                .usage("/warnings [player] [page]")
                .completes((sender, args) -> args.length == 1 && sender.hasPermission(WARNINGS_OTHERS)
                    ? Completions.players(sender, args[0]) : List.of())
                .executes(invocation -> warnings(xbans, targets, invocation)));
            setup.permission(WARNINGS_OTHERS, PermissionDefault.OP);
        }
        list(setup, xbans, "banlist", PunishmentType.BAN, commands.banlist());
        list(setup, xbans, "mutelist", PunishmentType.MUTE, commands.mutelist());
        if (commands.prunehistory().enabled()) {
            setup.command(CommandSpec.named("prunehistory", commands.prunehistory())
                .description("Delete a player's punishments that are no longer in force.")
                .usage("/prunehistory <player> [duration] [confirm]")
                .completes((sender, args) -> args.length == 1 ? Completions.players(sender, args[0])
                    : Completions.matching(args[args.length - 1], List.of("30d", "confirm")))
                .executes(invocation -> prune(xbans, targets, invocation)));
        }
    }

    // ---- /history and /staffhistory ----

    private static void history(XBans xbans, Targets targets, Invocation invocation, boolean staff) {
        String who = invocation.arg(0);
        if (who == null) {
            throw invocation.usage();
        }
        PunishmentType type = null;
        int page = 1;
        for (int i = 1; i < invocation.size(); i++) {
            String word = invocation.arg(i).toLowerCase(Locale.ROOT);
            PunishmentType named = typeWord(word);
            if (named != null) {
                type = named;
            } else if (word.chars().allMatch(Character::isDigit) && !word.isEmpty() && word.length() < 7) {
                page = Integer.parseInt(word);
            } else {
                throw invocation.usage();
            }
        }
        CommandSender sender = invocation.sender();
        PunishmentType filterType = type;
        int wanted = page;
        boolean console = staff && (who.equalsIgnoreCase("console") || who.equalsIgnoreCase("[console]"));
        CompletableFuture<Targets.Target> target = console
            ? CompletableFuture.completedFuture(new Targets.Target(null, "Console", null, null))
            : targets.resolve(who, false, false);
        String label = staff ? "staffhistory" : "history";
        target.thenCompose(found -> {
            Store.Filter filter = staff ? Store.Filter.staff(found.id(), filterType) : Store.Filter.target(found.id(), filterType);
            String command = "/xbans:" + label + " " + (console ? "console" : found.name())
                + (filterType == null ? "" : " " + typeName(filterType)) + " {page}";
            return xbans.cursors().page(viewer(sender), filter, wanted, xbans.config().lists().perPage())
                .thenAccept(result -> {
                    XBConfig.Messages messages = xbans.config().messages();
                    Message header = staff ? messages.staffHistoryHeader() : messages.historyHeader();
                    sendPage(xbans, sender, result, header, Placeholders.text("player", found.name()), command);
                });
        }).whenComplete((done, failure) -> {
            if (failure != null) {
                PunishCommands.fail(xbans, sender, failure);
            }
        });
    }

    /** Sends a page of punishments: the header, a line each, and the footer. */
    static void sendPage(XBans xbans, CommandSender sender, ChatPages.Page<Entry> page, Message header,
                         net.kyori.adventure.text.minimessage.tag.resolver.TagResolver who, String command) {
        XBConfig.Messages messages = xbans.config().messages();
        long now = System.currentTimeMillis();
        List<Component> lines = new ArrayList<>();
        if (page.items().isEmpty()) {
            lines.add(messages.historyEmpty().chat());
        } else {
            lines.add(header.chat(who, Placeholders.text("count", page.total())));
            lines.addAll(ChatPages.lines(page, entry -> messages.historyLine().chat(xbans.texts().of(entry, now)),
                messages.footer(), command));
        }
        xbans.tasks().sender(sender, () -> lines.forEach(sender::sendMessage));
    }

    static PunishmentType typeWord(String word) {
        return switch (word) {
            case "ban", "bans" -> PunishmentType.BAN;
            case "mute", "mutes" -> PunishmentType.MUTE;
            case "warn", "warns", "warning", "warnings" -> PunishmentType.WARN;
            case "kick", "kicks" -> PunishmentType.KICK;
            default -> null;
        };
    }

    private static String typeName(PunishmentType type) {
        return switch (type) {
            case BAN -> "bans";
            case MUTE -> "mutes";
            case WARN -> "warnings";
            case KICK -> "kicks";
        };
    }

    static String viewer(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : sender.getName();
    }

    // ---- /checkban and /checkmute ----

    private static void check(Setup setup, XBans xbans, Targets targets, String name, PunishmentType type, XBConfig.Cmd settings) {
        if (!settings.enabled()) {
            return;
        }
        setup.command(CommandSpec.named(name, settings)
            .description("Whether a player is " + (type == PunishmentType.BAN ? "banned" : "muted") + ", and why.")
            .usage("/" + name + " <player|ip|#id>")
            .completes((sender, args) -> args.length == 1 ? Completions.players(sender, args[0]) : List.of())
            .executes(invocation -> {
                String who = invocation.arg(0);
                if (who == null) {
                    throw invocation.usage();
                }
                CommandSender sender = invocation.sender();
                targets.resolve(who, true, true).thenCompose(target -> found(xbans, target, type)).thenAccept(found -> {
                    XBConfig.Messages messages = xbans.config().messages();
                    long now = System.currentTimeMillis();
                    List<Component> lines = new ArrayList<>();
                    if (found.isEmpty()) {
                        lines.add(messages.checkNone().chat(Placeholders.text("player", who)));
                    }
                    for (Entry entry : found) {
                        lines.add(messages.check().chat(xbans.texts().of(entry, now)));
                    }
                    xbans.tasks().sender(sender, () -> lines.forEach(sender::sendMessage));
                }).whenComplete((done, failure) -> {
                    if (failure != null) {
                        PunishCommands.fail(xbans, sender, failure);
                    }
                });
            }));
    }

    // In force now: from memory by player or IP; by id from the database, so an ended one shows too.
    private static CompletableFuture<List<Entry>> found(XBans xbans, Targets.Target target, PunishmentType type) {
        if (target.punishment() != null) {
            return xbans.store().find(target.punishment())
                .thenApply(entry -> entry != null && entry.type() == type ? List.of(entry) : List.of());
        }
        Store.Backend backend = xbans.store().backend();
        if (backend == null) {
            throw new Refusal(xbans.config().messages().notReady());
        }
        List<Entry> found = new ArrayList<>();
        for (Entry entry : backend.active().all(type, null, System.currentTimeMillis())) {
            UUID id = entry.punishment().target();
            if (target.id() != null && target.id().equals(id) || target.ip() != null && target.ip().equals(entry.ip())) {
                found.add(entry);
            }
        }
        return CompletableFuture.completedFuture(found);
    }

    // ---- /warnings ----

    private static void warnings(XBans xbans, Targets targets, Invocation invocation) {
        CommandSender sender = invocation.sender();
        String who = invocation.arg(0);
        int page = 1;
        if (who != null && who.chars().allMatch(Character::isDigit) && who.length() < 7) {
            page = Integer.parseInt(who);
            who = null;
        } else if (invocation.arg(1) != null) {
            page = invocation.integer(1, 1, 999_999);
        }
        CompletableFuture<Targets.Target> target;
        if (who == null || sender instanceof Player self && self.getName().equalsIgnoreCase(who)) {
            Player player = invocation.player();
            target = CompletableFuture.completedFuture(new Targets.Target(player.getUniqueId(), player.getName(), null, null));
        } else {
            invocation.require(WARNINGS_OTHERS);
            target = targets.resolve(who, false, false);
        }
        int wanted = page;
        target.thenCompose(found -> xbans.store().activeWarnings(found.id()).thenAccept(warnings -> {
            XBConfig.Messages messages = xbans.config().messages();
            long now = System.currentTimeMillis();
            List<Component> lines = new ArrayList<>();
            if (warnings.isEmpty()) {
                lines.add(messages.warningsNone().chat(Placeholders.text("player", found.name())));
            } else {
                ChatPages.Page<Entry> shown = ChatPages.of(warnings, wanted, xbans.config().lists().perPage());
                lines.add(messages.warningsHeader().chat(Placeholders.text("player", found.name()),
                    Placeholders.text("count", warnings.size())));
                lines.addAll(ChatPages.lines(shown, entry -> messages.historyLine().chat(xbans.texts().of(entry, now)),
                    messages.footer(), "/xbans:warnings " + found.name() + " {page}"));
            }
            xbans.tasks().sender(sender, () -> lines.forEach(sender::sendMessage));
        })).whenComplete((done, failure) -> {
            if (failure != null) {
                PunishCommands.fail(xbans, sender, failure);
            }
        });
    }

    // ---- /banlist and /mutelist, from memory ----

    private static void list(Setup setup, XBans xbans, String name, PunishmentType type, XBConfig.Cmd settings) {
        if (!settings.enabled()) {
            return;
        }
        setup.command(CommandSpec.named(name, settings)
            .description("Everyone " + (type == PunishmentType.BAN ? "banned" : "muted") + " on this server.")
            .usage("/" + name + " [page]")
            .executes(invocation -> {
                CommandSender sender = invocation.sender();
                Store.Backend backend = xbans.store().backend();
                XBConfig.Messages messages = xbans.config().messages();
                if (backend == null) {
                    messages.notReady().send(sender);
                    return;
                }
                int page = invocation.arg(0) == null ? 1 : invocation.integer(0, 1, 999_999);
                long now = System.currentTimeMillis();
                List<Entry> all = backend.active().all(type, xbans.config().scopes().serverName(), now);
                if (all.isEmpty()) {
                    messages.listEmpty().send(sender);
                    return;
                }
                ChatPages.Page<Entry> shown = ChatPages.of(all, page, xbans.config().lists().perPage());
                (type == PunishmentType.BAN ? messages.banlistHeader() : messages.mutelistHeader())
                    .send(sender, Placeholders.text("count", all.size()));
                ChatPages.lines(shown, entry -> messages.historyLine().chat(xbans.texts().of(entry, now)), messages.footer(),
                    "/xbans:" + name + " {page}").forEach(sender::sendMessage);
            }));
    }

    // ---- /prunehistory ----

    private static void prune(XBans xbans, Targets targets, Invocation invocation) {
        String who = invocation.arg(0);
        if (who == null) {
            throw invocation.usage();
        }
        long since = 0;
        boolean confirm = false;
        for (int i = 1; i < invocation.size(); i++) {
            String word = invocation.arg(i);
            if (word.equalsIgnoreCase("confirm")) {
                confirm = true;
                continue;
            }
            Length length;
            try {
                length = Length.parse(word);
            } catch (IllegalArgumentException e) {
                length = null;
            }
            if (length == null) {
                xbans.config().messages().badDuration().send(invocation.sender(), Placeholders.text("input", word));
                return;
            }
            since = length.permanent() ? 0 : System.currentTimeMillis() - length.millis();
        }
        CommandSender sender = invocation.sender();
        boolean dryRun = !confirm;
        long from = since;
        targets.resolve(who, false, false).thenCompose(target -> xbans.store().prune(target.id(), from, dryRun).thenAccept(count -> {
            XBConfig.Messages messages = xbans.config().messages();
            xbans.tasks().sender(sender, () -> (dryRun ? messages.pruneConfirm() : messages.pruned())
                .send(sender, Placeholders.text("count", count), Placeholders.text("player", target.name())));
            if (!dryRun) {
                // A delete can't be undone, so the console keeps a record of each.
                xbans.getLogger().info(sender.getName() + " pruned " + count + " inactive entries from " + target.name()
                    + "'s history (" + target.id() + ")" + (from > 0 ? " made since " + java.time.Instant.ofEpochMilli(from) : "")
                    + ".");
            }
            if (!dryRun && count > 0) {
                xbans.cursors().clear();
            }
        })).whenComplete((done, failure) -> {
            if (failure != null) {
                PunishCommands.fail(xbans, sender, failure);
            }
        });
    }
}
