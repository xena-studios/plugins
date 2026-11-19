package co.xenastudios.xbans.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completer;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xbans.Power;
import co.xenastudios.xbans.Punisher;
import co.xenastudios.xbans.Refusal;
import co.xenastudios.xbans.XBans;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.config.XBConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/** /ban, /tempban, /ipban, /tempipban, /mute, /tempmute, /ipmute, /tempipmute, /warn and /kick. */
public final class PunishCommands {

    public static final String SCOPE_PERMISSION = "xbans.scope";
    public static final String EXTRA_SILENT_PERMISSION = "xbans.extrasilent";

    private PunishCommands() {}

    /** How a command punishes: what it makes, whether it needs a length, whether it's by IP. */
    private record Kind(String name, PunishmentType type, boolean temp, boolean ip) {}

    public static void register(Setup setup, XBans xbans, XBConfig config) {
        XBConfig.Commands commands = config.commands();
        Targets targets = new Targets(xbans);
        List<Map.Entry<Kind, XBConfig.Cmd>> kinds = List.of(
            Map.entry(new Kind("ban", PunishmentType.BAN, false, false), commands.ban()),
            Map.entry(new Kind("tempban", PunishmentType.BAN, true, false), commands.tempban()),
            Map.entry(new Kind("ipban", PunishmentType.BAN, false, true), commands.ipban()),
            Map.entry(new Kind("tempipban", PunishmentType.BAN, true, true), commands.tempipban()),
            Map.entry(new Kind("mute", PunishmentType.MUTE, false, false), commands.mute()),
            Map.entry(new Kind("tempmute", PunishmentType.MUTE, true, false), commands.tempmute()),
            Map.entry(new Kind("ipmute", PunishmentType.MUTE, false, true), commands.ipmute()),
            Map.entry(new Kind("tempipmute", PunishmentType.MUTE, true, true), commands.tempipmute()),
            Map.entry(new Kind("warn", PunishmentType.WARN, false, false), commands.warn()),
            Map.entry(new Kind("kick", PunishmentType.KICK, false, false), commands.kick()));
        for (Map.Entry<Kind, XBConfig.Cmd> pair : kinds) {
            Kind kind = pair.getKey();
            XBConfig.Cmd settings = pair.getValue();
            if (!settings.enabled() || kind.ip() && !config.ips().enabled()) {
                continue;
            }
            setup.command(CommandSpec.named(kind.name(), settings)
                .description(description(kind))
                .usage(usage(kind))
                .completes(completer(xbans, kind))
                .executes(invocation -> run(xbans, targets, kind, invocation)));
        }
        setup.permission(SCOPE_PERMISSION, PermissionDefault.OP);
        setup.permission(EXTRA_SILENT_PERMISSION, PermissionDefault.OP);
        setup.permission(Punisher.NOTIFY_BROADCAST, PermissionDefault.TRUE);
        setup.permission(Punisher.NOTIFY_SILENT, PermissionDefault.OP);
        // Not for operators unless given: an operator isn't held to a helper's limits.
        setup.permission(Power.EXEMPT, PermissionDefault.FALSE);
        for (XBConfig.Limit limit : config.staffLimits().groups().values()) {
            setup.permission(limit.permission(), PermissionDefault.FALSE);
        }
        setup.permission(Power.BYPASS_TEMPLATES, PermissionDefault.FALSE);
        config.templates().forEach((name, template) -> setup.permission(template.permissionFor(name), PermissionDefault.OP));
    }

    private static String description(Kind kind) {
        String what = switch (kind.type()) {
            case BAN -> kind.ip() ? "IP-ban" : "Ban";
            case MUTE -> kind.ip() ? "IP-mute" : "Mute";
            case WARN -> "Warn";
            case KICK -> "Kick";
        };
        return what + " a player" + (kind.temp() ? " for a while." : ".");
    }

    private static String usage(Kind kind) {
        String target = kind.ip() ? "<player|ip>" : "<player>";
        return switch (kind.type()) {
            case KICK -> "/kick <player> [-s] [reason]";
            case WARN -> "/warn <player> [-s] [duration] [template] [reason]";
            default -> "/" + kind.name() + " " + target + " [-s] " + (kind.temp() ? "<duration>" : "[duration]")
                + " [server:<name>] [template] [reason]";
        };
    }

    private static void run(XBans xbans, Targets targets, Kind kind, Invocation invocation) {
        XBConfig config = xbans.config();
        XBConfig.Messages messages = config.messages();
        CommandSender sender = invocation.sender();
        String[] args = new String[invocation.size()];
        for (int i = 0; i < args.length; i++) {
            args[i] = invocation.arg(i);
        }
        boolean lasting = kind.type() == PunishmentType.BAN || kind.type() == PunishmentType.MUTE || kind.type() == PunishmentType.WARN;
        Args parsed = Args.parse(args, lasting, word -> templateFor(config, word, kind.type()) != null);
        if (parsed == null) {
            throw invocation.usage();
        }
        if (parsed.badLength() != null) {
            messages.badDuration().send(sender, Placeholders.text("input", parsed.badLength()));
            return;
        }
        if (kind.temp() && (parsed.length() == null || parsed.length().permanent()) && parsed.template() == null) {
            throw invocation.usage();
        }
        if (parsed.scope() != null && !sender.hasPermission(SCOPE_PERMISSION)) {
            messages.scopeNoPermission().send(sender);
            return;
        }
        if (parsed.extraSilent() && !sender.hasPermission(EXTRA_SILENT_PERMISSION)) {
            messages.extraSilentNoPermission().send(sender);
            return;
        }
        if (parsed.template() != null) {
            XBConfig.Template template = config.templates().get(parsed.template());
            if (!sender.hasPermission(template.permissionFor(parsed.template()))) {
                messages.templateNoPermission().send(sender, Placeholders.text("template", parsed.template()));
                return;
            }
        }
        boolean ip = kind.ip() || parsed.ip() && lasting && kind.type() != PunishmentType.WARN;
        if (ip && !config.ips().enabled()) {
            messages.ipsOff().send(sender);
            return;
        }
        if (ip && parsed.ip() && !sender.hasPermission(kind.type() == PunishmentType.BAN
            ? config.commands().ipban().permission() : config.commands().ipmute().permission())) {
            throw new co.xenastudios.core.command.CommandFailure(messages.error().noPermission());
        }
        if (kind.type() == PunishmentType.KICK) {
            // Only someone here can be kicked; the console sees vanished players too.
            Player online = invocation.target(0, "");
            args[0] = online.getUniqueId().toString();
        }
        boolean silent = parsed.silent() || config.punishing().silentByDefault() && !parsed.publicFlag();
        Power power = Power.of(sender, config.staffLimits());
        UUID staff = sender instanceof Player player ? player.getUniqueId() : null;
        String staffName = sender instanceof Player player ? player.getName() : "Console";
        // Read here, on the command's thread: the target and everyone on an IP are held to the sender's limits.
        List<Punisher.Online> online = xbans.punisher().online(power);
        targets.resolve(args[0], ip, false).thenCompose(target -> xbans.punisher().punish(new Punisher.Request(
                kind.type(), target.id(), target.name(), target.ip(), ip || target.ip() != null, parsed.length(),
                parsed.reason(), parsed.template(), parsed.scope(), silent, parsed.extraSilent(), staff, staffName, power, sender,
                false, online, kind.temp())))
            .whenComplete((entry, failure) -> {
                if (failure != null) {
                    fail(xbans, sender, failure);
                }
            });
    }

    /** Tells the sender why a command's work failed. */
    static void fail(XBans xbans, CommandSender sender, Throwable failure) {
        XBConfig.Messages messages = xbans.config().messages();
        Refusal refusal = Refusal.of(failure);
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
            ? failure.getCause() : failure;
        xbans.tasks().sender(sender, () -> {
            if (refusal != null) {
                refusal.send(sender);
            } else if (cause instanceof CancellationException) {
                messages.cancelled().send(sender);
            } else if (cause instanceof IllegalStateException && xbans.store().backend() == null) {
                messages.notReady().send(sender);
            } else {
                xbans.getLogger().log(Level.SEVERE, "A command by " + sender.getName() + " failed", cause);
                messages.failed().send(sender);
            }
        });
    }

    static XBConfig.Template templateFor(XBConfig config, String word, PunishmentType type) {
        XBConfig.Template template = config.templates().get(word.toLowerCase(Locale.ROOT));
        return template != null && template.type() == type ? template : null;
    }

    private static Completer completer(XBans xbans, Kind kind) {
        return (sender, args) -> {
            String typed = args[args.length - 1];
            if (args.length == 1) {
                return Completions.players(sender, typed);
            }
            List<String> options = new ArrayList<>(List.of("-s", "-p"));
            if (sender.hasPermission(EXTRA_SILENT_PERMISSION)) {
                options.add("-S");
            }
            if (kind.type() != PunishmentType.KICK) {
                if (args.length == 2) {
                    options.addAll(kind.temp() ? List.of("1h", "1d", "7d", "30d") : List.of("1h", "1d", "7d", "30d", "perm"));
                }
                xbans.config().templates().forEach((name, template) -> {
                    if (template.type() == kind.type() && sender.hasPermission(template.permissionFor(name))) {
                        options.add(name);
                    }
                });
                if (sender.hasPermission(SCOPE_PERMISSION) && kind.type() != PunishmentType.WARN) {
                    options.add("server:" + xbans.config().scopes().serverName());
                    options.add("server:global");
                }
            }
            return Completions.matching(typed, options);
        };
    }
}
