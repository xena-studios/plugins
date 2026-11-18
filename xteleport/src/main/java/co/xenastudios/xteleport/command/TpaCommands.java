package co.xenastudios.xteleport.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xteleport.XTeleport;
import co.xenastudios.xteleport.api.TeleportType;
import co.xenastudios.xteleport.api.event.TpaRequestEvent;
import co.xenastudios.xteleport.config.Settings;
import co.xenastudios.xteleport.config.XTConfig;
import co.xenastudios.xteleport.teleport.Teleporter;
import co.xenastudios.xteleport.tpa.Requests;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/**
 * {@code /tpa}, {@code /tpahere}, {@code /tpaccept}, {@code /tpdeny}, {@code /tpacancel}
 * and {@code /tptoggle}. Requests run out lazily (see {@link Requests}), and players the
 * sender can't see, such as vanished staff, can't be asked.
 */
public final class TpaCommands {

    private final XTeleport plugin;
    private final XTConfig.Commands commands;

    private TpaCommands(XTeleport plugin, XTConfig.Commands commands) {
        this.plugin = plugin;
        this.commands = commands;
    }

    public static void register(Setup setup, XTeleport plugin, XTConfig.Commands commands) {
        TpaCommands tpa = new TpaCommands(plugin, commands);
        if (commands.tpa().enabled()) {
            setup.command(CommandSpec.named("tpa", commands.tpa())
                .permissionDefault(PermissionDefault.TRUE)
                .description("Ask to teleport to a player.")
                .usage("/tpa <player>")
                .completes(TpaCommands::completePlayers)
                .executes(invocation -> tpa.request(invocation, false)));
        }
        if (commands.tpahere().enabled()) {
            setup.command(CommandSpec.named("tpahere", commands.tpahere())
                .permissionDefault(PermissionDefault.TRUE)
                .description("Ask a player to teleport to you.")
                .usage("/tpahere <player>")
                .completes(TpaCommands::completePlayers)
                .executes(invocation -> tpa.request(invocation, true)));
        }
        if (commands.tpaccept().enabled()) {
            setup.command(CommandSpec.named("tpaccept", commands.tpaccept())
                .permissionDefault(PermissionDefault.TRUE)
                .description("Accept a teleport request.")
                .usage("/tpaccept [player]")
                .completes(tpa::completeRequests)
                .executes(invocation -> tpa.answer(invocation, true)));
        }
        if (commands.tpdeny().enabled()) {
            setup.command(CommandSpec.named("tpdeny", commands.tpdeny())
                .permissionDefault(PermissionDefault.TRUE)
                .description("Deny a teleport request.")
                .usage("/tpdeny [player]")
                .completes(tpa::completeRequests)
                .executes(invocation -> tpa.answer(invocation, false)));
        }
        if (commands.tpacancel().enabled()) {
            setup.command(CommandSpec.named("tpacancel", commands.tpacancel())
                .permissionDefault(PermissionDefault.TRUE)
                .description("Take back teleport requests you sent.")
                .usage("/tpacancel [player]")
                .completes(TpaCommands::completePlayers)
                .executes(tpa::cancel));
        }
        if (commands.tptoggle().enabled()) {
            setup.command(CommandSpec.named("tptoggle", commands.tptoggle())
                .permissionDefault(PermissionDefault.TRUE)
                .description("Stop or start getting teleport requests.")
                .executes(tpa::toggle));
        }
    }

    private XTConfig.TpaMessages messages() {
        return plugin.config().messages().tpa();
    }

    private long expireMillis() {
        return plugin.config().tpa().expireAfter() * 1000L;
    }

    /** An online player the sender can see, by name, or the player-not-found error. */
    private Player visible(Invocation invocation, String name) {
        Player found = Bukkit.getPlayerExact(name);
        if (found == null || (invocation.sender() instanceof Player viewer && !viewer.canSee(found))) {
            throw new CommandFailure(plugin.config().messages().error().playerNotFound(), Placeholders.text("player", name));
        }
        return found;
    }

    private void request(Invocation invocation, boolean here) {
        Player sender = invocation.player();
        if (invocation.arg(0) == null) {
            throw invocation.usage();
        }
        Player target = visible(invocation, invocation.arg(0));
        if (target == sender) {
            throw new CommandFailure(messages().notYourself());
        }
        if (plugin.homes().tpaOff(target.getUniqueId()) && !sender.hasPermission(XTeleport.TPTOGGLE_BYPASS)) {
            throw new CommandFailure(messages().notTaking(), Placeholders.text("player", target.getName()));
        }
        if (plugin.requests().asked(target.getUniqueId(), sender.getUniqueId(), expireMillis())) {
            throw new CommandFailure(messages().alreadyAsked(), Placeholders.text("player", target.getName()));
        }
        // Whoever asks pays and has the cooldown, so check now that they could.
        if (plugin.teleporter().precheck(sender, trip(here, sender).paidBy(sender)) != null) {
            return;
        }
        if (TpaRequestEvent.getHandlerList().getRegisteredListeners().length > 0) {
            TpaRequestEvent event = new TpaRequestEvent(sender, target, here);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return;
            }
        }
        if (!plugin.requests().add(target.getUniqueId(),
            new Requests.Request(sender.getUniqueId(), sender.getName(), here, plugin.requests().now()), expireMillis())) {
            throw new CommandFailure(messages().alreadyAsked(), Placeholders.text("player", target.getName()));
        }
        int seconds = plugin.config().tpa().expireAfter();
        (here ? messages().sentHere() : messages().sent()).send(sender,
            Placeholders.text("player", target.getName()), Placeholders.text("seconds", seconds));
        (here ? messages().receivedHere() : messages().received()).send(target,
            Placeholders.text("player", sender.getName()), Placeholders.text("seconds", seconds),
            Replies.click("accept", "/tpaccept " + sender.getName()),
            Replies.click("deny", "/tpdeny " + sender.getName()));
    }

    // Checked for safety like a home: the player gone to may be over lava or the End's void.
    // Their spot is read once the warmup is over, on their own thread, and checked on the
    // thread that owns it.
    private Teleporter.Trip trip(boolean here, Player arrivingAt) {
        Settings.TeleportCommand settings = here ? commands.tpahere() : commands.tpa();
        return Teleporter.Trip.of(here ? TeleportType.TPAHERE : TeleportType.TPA, here ? "tpahere" : "tpa", settings, true,
            messages().teleported(), Placeholders.text("player", arrivingAt.getName()));
    }

    private void answer(Invocation invocation, boolean accept) {
        Player player = invocation.player();
        UUID from = null;
        if (invocation.arg(0) != null) {
            Player named = Bukkit.getPlayerExact(invocation.arg(0));
            if (named == null) {
                throw new CommandFailure(messages().noRequestFrom(), Placeholders.text("player", invocation.arg(0)));
            }
            from = named.getUniqueId();
        }
        Optional<Requests.Request> taken = takeOnline(player.getUniqueId(), from);
        if (taken.isEmpty()) {
            if (invocation.arg(0) != null) {
                throw new CommandFailure(messages().noRequestFrom(), Placeholders.text("player", invocation.arg(0)));
            }
            throw new CommandFailure(messages().noRequests());
        }
        Requests.Request request = taken.get();
        Player asker = Bukkit.getPlayer(request.from());
        if (asker == null) {
            // They left in the moment since.
            throw new CommandFailure(messages().noRequestFrom(), Placeholders.text("player", request.fromName()));
        }
        if (!accept) {
            messages().youDenied().send(player, Placeholders.text("player", request.fromName()));
            messages().denied().send(asker, Placeholders.text("player", player.getName()));
            return;
        }
        messages().youAccepted().send(player, Placeholders.text("player", request.fromName()));
        messages().accepted().send(asker, Placeholders.text("player", player.getName()));
        // /tpa: the asker comes here. /tpahere: this player goes to the asker. The asker pays.
        Player moving = request.here() ? player : asker;
        Player destination = request.here() ? asker : player;
        Teleporter.Trip trip = trip(request.here(), destination).paidBy(asker);
        Teleporter teleporter = plugin.teleporter();
        teleporter.onPlayer(moving, () -> teleporter.teleport(moving, trip, () -> teleporter.whereIs(destination)), () -> {});
    }

    // The newest request still pending whose sender is online, or the named one's.
    private Optional<Requests.Request> takeOnline(UUID to, UUID from) {
        while (true) {
            Optional<Requests.Request> taken = plugin.requests().take(to, from, expireMillis());
            if (taken.isEmpty() || Bukkit.getPlayer(taken.get().from()) != null) {
                return taken;
            }
            // The sender left; that request is gone, so look at the next one.
        }
    }

    private void cancel(Invocation invocation) {
        Player player = invocation.player();
        UUID to = invocation.arg(0) == null ? null : visible(invocation, invocation.arg(0)).getUniqueId();
        List<UUID> targets = plugin.requests().cancel(player.getUniqueId(), to, expireMillis());
        if (targets.isEmpty()) {
            throw new CommandFailure(messages().nothingToCancel());
        }
        for (UUID target : targets) {
            Player online = Bukkit.getPlayer(target);
            if (online != null) {
                messages().cancelled().send(online, Placeholders.text("player", player.getName()));
            }
        }
        messages().youCancelled().send(player, Placeholders.text("count", targets.size()));
    }

    private void toggle(Invocation invocation) {
        Player player = invocation.player();
        Replies.requireReady(plugin);
        if (plugin.homes().cached(player.getUniqueId()).isEmpty()) {
            // Their settings are still being read; this is rare, just after joining.
            throw new CommandFailure(plugin.config().messages().teleport().unavailable());
        }
        boolean off = !plugin.homes().tpaOff(player.getUniqueId());
        Replies.whenSaved(plugin, player, plugin.homes().tpaOff(player.getUniqueId(), off),
            () -> (off ? messages().toggledOff() : messages().toggledOn()).send(player));
    }

    private static List<String> completePlayers(CommandSender sender, String[] args) {
        return args.length == 1 ? Completions.players(sender, args[0]) : List.of();
    }

    private List<String> completeRequests(CommandSender sender, String[] args) {
        if (args.length != 1 || !(sender instanceof Player player)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Requests.Request request : plugin.requests().pending(player.getUniqueId(), expireMillis())) {
            names.add(request.fromName());
        }
        return Completions.matching(args[0], names);
    }
}
