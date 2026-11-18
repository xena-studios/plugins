package co.xenastudios.xteleport.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xteleport.XTeleport;
import co.xenastudios.xteleport.api.TeleportType;
import co.xenastudios.xteleport.config.Settings.RtpWorld;
import co.xenastudios.xteleport.config.XTConfig;
import co.xenastudios.xteleport.rtp.Search;
import co.xenastudios.xteleport.teleport.Teleporter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/**
 * {@code /rtp [world]}: the cooldown and cost are checked before searching, so nobody waits
 * for a spot they can't use; then the search, then the usual warmup and teleport.
 */
public final class RtpCommand {

    private final XTeleport plugin;
    private final XTConfig.Commands commands;

    private RtpCommand(XTeleport plugin, XTConfig.Commands commands) {
        this.plugin = plugin;
        this.commands = commands;
    }

    public static void register(Setup setup, XTeleport plugin, XTConfig.Commands commands) {
        if (!commands.rtp().enabled()) {
            return;
        }
        RtpCommand rtp = new RtpCommand(plugin, commands);
        setup.command(CommandSpec.named("rtp", commands.rtp())
            .permissionDefault(PermissionDefault.TRUE)
            .description("Teleport to a random safe spot.")
            .usage("/rtp [world]")
            .completes(rtp::completeWorlds)
            .executes(rtp::rtp));
    }

    private XTConfig.RtpMessages messages() {
        return plugin.config().messages().rtp();
    }

    private void rtp(Invocation invocation) {
        Player player = invocation.player();
        String typed = invocation.arg(0);
        if (typed == null && !plugin.config().rtp().defaultWorld().isBlank()) {
            typed = plugin.config().rtp().defaultWorld();
        }
        World world = typed == null ? player.getWorld() : Bukkit.getWorld(typed);
        String worldName = typed == null ? player.getWorld().getName() : typed;
        RtpWorld rules = world == null ? null : plugin.rtp().allowed(player, world);
        if (rules == null) {
            throw new CommandFailure(messages().worldNotAllowed(), Placeholders.text("world", worldName));
        }
        Teleporter.Trip trip = Teleporter.Trip.of(TeleportType.RTP, "rtp", commands.rtp(), false, messages().teleported());
        if (plugin.teleporter().precheck(player, trip) != null) {
            return;
        }
        UUID id = player.getUniqueId();
        switch (plugin.rtp().begin(id)) {
            case ALREADY -> throw new CommandFailure(messages().alreadySearching());
            case BUSY -> throw new CommandFailure(messages().busy());
            case STARTED -> { }
        }
        messages().searching().send(player);
        CompletableFuture<Search.Found> search;
        try {
            search = plugin.rtp().find(world, rules);
        } catch (RuntimeException e) {
            plugin.rtp().end(id);
            throw e;
        }
        search.whenComplete((found, error) -> {
            plugin.rtp().end(id);
            plugin.teleporter().onPlayer(player, () -> {
                if (error != null || found == null) {
                    messages().notFound().send(player);
                    return;
                }
                Location to = found.location();
                plugin.teleporter().teleport(player, Teleporter.Trip.of(TeleportType.RTP, "rtp", commands.rtp(), false,
                    messages().teleported(), Replies.where(world.getName(), to.getX(), to.getY(), to.getZ())),
                    () -> CompletableFuture.completedFuture(to));
            }, () -> {});
        });
    }

    private List<String> completeWorlds(CommandSender sender, String[] args) {
        if (args.length != 1 || !(sender instanceof Player player)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (plugin.rtp().allowed(player, world) != null) {
                names.add(world.getName());
            }
        }
        return Completions.matching(args[0], names);
    }
}
