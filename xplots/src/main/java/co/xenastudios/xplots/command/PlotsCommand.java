package co.xenastudios.xplots.command;

import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xplots.XPlots;
import co.xenastudios.xplots.model.Field;
import co.xenastudios.xplots.model.Places;
import co.xenastudios.xplots.model.Plot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/** {@code /plots}: a player's own plots. */
public final class PlotsCommand {

    private static final List<String> SUBCOMMANDS = List.of("add", "remove", "home", "warp");

    private final XPlots plugin;

    private PlotsCommand(XPlots plugin) {
        this.plugin = plugin;
    }

    public static CommandSpec spec(XPlots plugin) {
        PlotsCommand command = new PlotsCommand(plugin);
        return CommandSpec.named("plots")
            .aliases(List.of("plot"))
            .permission(XPlots.USE)
            .permissionDefault(PermissionDefault.TRUE)
            .description("Your plots: rent, members, teleport.")
            .usage("/plots [add|remove|home|warp]")
            .completes(command::complete)
            .executes(command::run);
    }

    private void run(Invocation inv) {
        Player player = inv.player();
        String sub = inv.arg(0);
        if (sub == null) {
            plugin.menus().mine(player);
            return;
        }
        switch (sub.toLowerCase(Locale.ROOT)) {
            case "add" -> add(inv, player);
            case "remove" -> remove(inv, player);
            case "home" -> home(inv, player);
            case "warp" -> warp(inv, player);
            default -> throw inv.usage();
        }
    }

    /** The plot the player means: the one of theirs they stand in, or their only one. */
    private Plot chosen(Player player) {
        List<Plot> owned = plugin.plots().owned(player.getUniqueId());
        if (owned.isEmpty()) {
            throw new CommandFailure(plugin.config().messages().noPlots());
        }
        if (owned.size() == 1) {
            return owned.get(0);
        }
        Plot here = plugin.plots().at(player.getLocation().getBlock());
        if (here != null && owned.contains(here)) {
            return here;
        }
        throw new CommandFailure(plugin.config().messages().whichPlot());
    }

    private void add(Invocation inv, Player player) {
        String name = inv.arg(1);
        if (name == null) {
            throw inv.usage();
        }
        Plot plot = chosen(player);
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(name);
        if (target == null) {
            throw new CommandFailure(plugin.config().messages().neverJoined(), Placeholders.text("player", name));
        }
        plugin.rentals().addMember(player, plot, target, false);
    }

    private void remove(Invocation inv, Player player) {
        String name = inv.arg(1);
        if (name == null) {
            throw inv.usage();
        }
        Plot plot = chosen(player);
        UUID member = null;
        for (var entry : plot.lease().members().entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) {
                member = entry.getKey();
            }
        }
        if (member == null) {
            throw new CommandFailure(plugin.config().messages().notMember(), Placeholders.text("player", name));
        }
        plugin.rentals().removeMember(player, plot, member);
    }

    private void home(Invocation inv, Player player) {
        String id = inv.arg(1);
        Plot plot;
        if (id == null) {
            List<Plot> owned = plugin.plots().owned(player.getUniqueId());
            if (owned.isEmpty()) {
                throw new CommandFailure(plugin.config().messages().noPlots());
            }
            plot = owned.get(0);
        } else {
            plot = plugin.plots().plot(id.toLowerCase(Locale.ROOT));
            if (plot == null || !plugin.rentals().owns(player, plot)) {
                throw new CommandFailure(plugin.config().messages().notYours());
            }
        }
        plugin.menus().home(player, plot);
    }

    private void warp(Invocation inv, Player player) {
        String name = inv.arg(1);
        Field field;
        if (name == null) {
            List<Field> warps = withWarps();
            if (warps.size() != 1) {
                throw new CommandFailure(plugin.config().messages().error().usage(), Placeholders.text("usage",
                    "/plots warp <" + String.join("|", warps.stream().map(Field::name).toList()) + ">"));
            }
            field = warps.get(0);
        } else {
            field = plugin.plots().field(name);
            if (field == null) {
                throw new CommandFailure(plugin.config().messages().noWarp(), Placeholders.text("field", name));
            }
        }
        World world = Bukkit.getWorld(field.world());
        Location to = world == null ? null : Places.decode(world, field.warp());
        if (to == null) {
            throw new CommandFailure(plugin.config().messages().noWarp(), Placeholders.text("field", field.name()));
        }
        plugin.warmups().teleport(player, to);
    }

    private List<Field> withWarps() {
        List<Field> warps = new ArrayList<>();
        for (Field field : plugin.plots().fields()) {
            if (field.warp() != null) {
                warps.add(field);
            }
        }
        warps.sort((a, b) -> a.name().compareTo(b.name()));
        return warps;
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return Completions.matching(args.length == 0 ? "" : args[0], SUBCOMMANDS);
        }
        if (args.length != 2 || !(sender instanceof Player player)) {
            return List.of();
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> Completions.players(sender, args[1]);
            case "remove" -> {
                List<String> names = new ArrayList<>();
                for (Plot plot : plugin.plots().owned(player.getUniqueId())) {
                    names.addAll(plot.lease().members().values());
                }
                yield Completions.matching(args[1], names);
            }
            case "home" -> Completions.matching(args[1], plugin.plots().owned(player.getUniqueId()).stream().map(Plot::id).toList());
            case "warp" -> Completions.matching(args[1], withWarps().stream().map(Field::name).toList());
            default -> List.of();
        };
    }
}
