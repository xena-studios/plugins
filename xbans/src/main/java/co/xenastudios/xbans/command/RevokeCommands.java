package co.xenastudios.xbans.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.xbans.Punisher;
import co.xenastudios.xbans.XBans;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.config.XBConfig;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.storage.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /unban, /unmute and /unwarn: by player (every server's, unless {@code server:} names
 * one), by IP, or by {@code #id}. The punishment stays in the history, revoked.
 */
public final class RevokeCommands {

    private RevokeCommands() {}

    public static void register(Setup setup, XBans xbans, XBConfig config) {
        Targets targets = new Targets(xbans);
        command(setup, xbans, targets, "unban", PunishmentType.BAN, config.commands().unban());
        command(setup, xbans, targets, "unmute", PunishmentType.MUTE, config.commands().unmute());
        command(setup, xbans, targets, "unwarn", PunishmentType.WARN, config.commands().unwarn());
    }

    private static void command(Setup setup, XBans xbans, Targets targets, String name, PunishmentType type, XBConfig.Cmd settings) {
        if (!settings.enabled()) {
            return;
        }
        String what = switch (type) {
            case BAN -> "ban";
            case MUTE -> "mute";
            default -> "newest warning";
        };
        setup.command(CommandSpec.named(name, settings)
            .description("Revoke a player's " + what + ".")
            .usage("/" + name + " <player" + (type == PunishmentType.WARN ? "" : "|ip") + "|#id> [-s] [server:<name>] [reason]")
            .completes((sender, args) -> args.length == 1 ? completeTargets(xbans, sender, type, args[0])
                : Completions.matching(args[args.length - 1], List.of("-s")))
            .executes(invocation -> run(xbans, targets, type, invocation)));
    }

    private static void run(XBans xbans, Targets targets, PunishmentType type, Invocation invocation) {
        XBConfig config = xbans.config();
        CommandSender sender = invocation.sender();
        String[] args = new String[invocation.size()];
        for (int i = 0; i < args.length; i++) {
            args[i] = invocation.arg(i);
        }
        Args parsed = Args.parse(args, false, word -> false);
        if (parsed == null) {
            throw invocation.usage();
        }
        if (parsed.scope() != null && !sender.hasPermission(PunishCommands.SCOPE_PERMISSION)) {
            config.messages().scopeNoPermission().send(sender);
            return;
        }
        boolean silent = parsed.silent() || config.punishing().silentByDefault() && !parsed.publicFlag();
        UUID staff = sender instanceof Player player ? player.getUniqueId() : null;
        String staffName = sender instanceof Player player ? player.getName() : "Console";
        targets.resolve(parsed.target(), type != PunishmentType.WARN, true)
            .thenCompose(target -> xbans.punisher().revoke(new Punisher.Revoke(type, target.id(), target.name(), target.ip(),
                target.punishment(), parsed.scope(), parsed.reason(), silent, parsed.extraSilent(), staff, staffName, sender)))
            .whenComplete((revoked, failure) -> {
                if (failure != null) {
                    PunishCommands.fail(xbans, sender, failure);
                }
            });
    }

    // The names of players banned or muted here, from memory; anyone online for /unwarn.
    private static List<String> completeTargets(XBans xbans, CommandSender sender, PunishmentType type, String typed) {
        Store.Backend backend = xbans.store().backend();
        if (type == PunishmentType.WARN || backend == null) {
            return Completions.players(sender, typed);
        }
        List<String> names = new ArrayList<>();
        for (Entry entry : backend.active().all(type, null, System.currentTimeMillis())) {
            if (entry.punishment().target() != null && names.size() < 50) {
                names.add(entry.punishment().targetName());
            }
        }
        return Completions.matching(typed, names.stream().distinct().toList());
    }
}
