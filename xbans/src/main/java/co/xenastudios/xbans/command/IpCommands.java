package co.xenastudios.xbans.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.text.ChatPages;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xbans.XBans;
import co.xenastudios.xbans.config.XBConfig;
import co.xenastudios.xbans.ip.IpKeys;
import co.xenastudios.xbans.storage.Store;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/** /alts (LiteBans' /dupeip) and /iphistory. Off with ips.enabled. */
public final class IpCommands {

    private IpCommands() {}

    public static void register(Setup setup, XBans xbans, XBConfig config) {
        if (!config.ips().enabled()) {
            return;
        }
        Targets targets = new Targets(xbans);
        if (config.commands().alts().enabled()) {
            setup.command(CommandSpec.named("alts", config.commands().alts())
                .description("Accounts that share an IP with a player.")
                .usage("/alts <player|ip> [page]")
                .completes((sender, args) -> args.length == 1 ? Completions.players(sender, args[0]) : List.of())
                .executes(invocation -> {
                    String who = invocation.arg(0);
                    if (who == null) {
                        throw invocation.usage();
                    }
                    int page = invocation.arg(1) == null ? 1 : invocation.integer(1, 1, 999_999);
                    CommandSender sender = invocation.sender();
                    targets.resolve(who, true, false)
                        .thenCompose(target -> xbans.store().alts(target.id(), target.ip()).thenAccept(alts -> {
                            List<Store.IpUse> others = alts.stream().filter(use -> !use.id().equals(target.id())).toList();
                            altsReply(xbans, sender, target.name(), who, others, page);
                        }))
                        .whenComplete((done, failure) -> {
                            if (failure != null) {
                                PunishCommands.fail(xbans, sender, failure);
                            }
                        });
                }));
        }
        if (config.commands().iphistory().enabled()) {
            setup.command(CommandSpec.named("iphistory", config.commands().iphistory())
                .description("The IPs a player has joined from.")
                .usage("/iphistory <player> [page]")
                .completes((sender, args) -> args.length == 1 ? Completions.players(sender, args[0]) : List.of())
                .executes(invocation -> {
                    String who = invocation.arg(0);
                    if (who == null) {
                        throw invocation.usage();
                    }
                    int page = invocation.arg(1) == null ? 1 : invocation.integer(1, 1, 999_999);
                    CommandSender sender = invocation.sender();
                    targets.resolve(who, false, false)
                        .thenCompose(target -> xbans.store().ipHistory(target.id()).thenAccept(uses -> {
                            XBConfig.Messages messages = xbans.config().messages();
                            List<Component> lines = new ArrayList<>();
                            if (uses.isEmpty()) {
                                lines.add(messages.ipHistoryNone().chat(Placeholders.text("player", target.name())));
                            } else {
                                ChatPages.Page<Store.IpUse> shown = ChatPages.of(uses, page, xbans.config().lists().perPage());
                                lines.add(messages.ipHistoryHeader().chat(Placeholders.text("player", target.name()),
                                    Placeholders.text("count", uses.size())));
                                lines.addAll(ChatPages.lines(shown, use -> messages.ipHistoryLine().chat(
                                        Placeholders.text("ip", IpKeys.shown(use.ip())),
                                        Placeholders.text("first", xbans.texts().date(Instant.ofEpochMilli(use.first()))),
                                        Placeholders.text("last", xbans.texts().date(Instant.ofEpochMilli(use.last())))),
                                    messages.footer(), "/xbans:iphistory " + target.name() + " {page}"));
                            }
                            xbans.tasks().sender(sender, () -> lines.forEach(sender::sendMessage));
                        }))
                        .whenComplete((done, failure) -> {
                            if (failure != null) {
                                PunishCommands.fail(xbans, sender, failure);
                            }
                        });
                }));
        }
        setup.permission(co.xenastudios.xbans.enforce.Gate.NOTIFY_ALTS, org.bukkit.permissions.PermissionDefault.OP);
    }

    // typed: what the sender typed, for the next page's command: an IP's name (its masked form,
    // or the start of its hash) wouldn't find it again.
    private static void altsReply(XBans xbans, CommandSender sender, String name, String typed, List<Store.IpUse> alts,
                                  int page) {
        XBConfig.Messages messages = xbans.config().messages();
        Store.Backend backend = xbans.store().backend();
        String server = xbans.config().scopes().serverName();
        long now = System.currentTimeMillis();
        List<Component> lines = new ArrayList<>();
        if (alts.isEmpty()) {
            lines.add(messages.altsNone().chat(Placeholders.text("player", name)));
        } else {
            XBConfig.AltStates states = messages.altStates();
            ChatPages.Page<Store.IpUse> shown = ChatPages.of(alts, page, xbans.config().lists().perPage());
            lines.add(messages.altsHeader().chat(Placeholders.text("player", name), Placeholders.text("count", alts.size())));
            lines.addAll(ChatPages.lines(shown, alt -> {
                String state;
                if (backend != null && backend.active().ban(alt.id(), null, server, now) != null) {
                    state = states.banned();
                } else if (backend != null && backend.active().mute(alt.id(), null, server, now) != null) {
                    state = states.muted();
                } else if (Bukkit.getPlayer(alt.id()) != null) {
                    state = states.online();
                } else {
                    state = states.offline();
                }
                return messages.altsLine().chat(Placeholders.text("name", alt.name()),
                    net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver("state",
                        net.kyori.adventure.text.minimessage.tag.Tag.selfClosingInserting(
                            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(state))));
            }, messages.footer(), "/xbans:alts " + typed + " {page}"));
        }
        xbans.tasks().sender(sender, () -> lines.forEach(sender::sendMessage));
    }
}
