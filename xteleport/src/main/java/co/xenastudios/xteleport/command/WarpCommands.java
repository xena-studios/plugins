package co.xenastudios.xteleport.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.text.ChatPages;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xteleport.XTeleport;
import co.xenastudios.xteleport.api.TeleportType;
import co.xenastudios.xteleport.api.Warp;
import co.xenastudios.xteleport.config.XTConfig;
import co.xenastudios.xteleport.home.Homes;
import co.xenastudios.xteleport.teleport.Teleporter;
import co.xenastudios.xteleport.warp.Places;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permissible;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/** {@code /setwarp}, {@code /warp}, {@code /delwarp}, {@code /warps}, {@code /setspawn} and {@code /spawn}. */
public final class WarpCommands {

    /** With per-warp permissions on, {@code xteleport.warp.<name>} is needed for each warp. */
    public static final String WARP_PERMISSION = "xteleport.warp.";

    private final XTeleport plugin;
    private final XTConfig.Commands commands;

    private WarpCommands(XTeleport plugin, XTConfig.Commands commands) {
        this.plugin = plugin;
        this.commands = commands;
    }

    public static void register(Setup setup, XTeleport plugin, XTConfig.Commands commands) {
        WarpCommands warps = new WarpCommands(plugin, commands);
        if (commands.setwarp().enabled()) {
            setup.command(CommandSpec.named("setwarp", commands.setwarp())
                .description("Set a warp where you stand.")
                .usage("/setwarp <name>")
                .completes(warps::completeWarps)
                .executes(warps::setwarp));
        }
        if (commands.warp().enabled()) {
            setup.command(CommandSpec.named("warp", commands.warp())
                .permissionDefault(PermissionDefault.TRUE)
                .description("Teleport to a warp.")
                .usage("/warp [name]")
                .completes(warps::completeWarps)
                .executes(warps::warp));
        }
        if (commands.delwarp().enabled()) {
            setup.command(CommandSpec.named("delwarp", commands.delwarp())
                .description("Delete a warp.")
                .usage("/delwarp <name>")
                .completes(warps::completeWarps)
                .executes(warps::delwarp));
        }
        if (commands.warps().enabled()) {
            setup.command(CommandSpec.named("warps", commands.warps())
                .permissionDefault(PermissionDefault.TRUE)
                .description("List the warps.")
                .usage("/warps [page]")
                .executes(invocation -> warps.list(invocation.sender(), Replies.page(invocation.arg(0)))));
        }
        if (commands.setspawn().enabled()) {
            setup.command(CommandSpec.named("setspawn", commands.setspawn())
                .description("Set the spawn where you stand.")
                .executes(warps::setspawn));
        }
        if (commands.spawn().enabled()) {
            setup.command(CommandSpec.named("spawn", commands.spawn())
                .permissionDefault(PermissionDefault.TRUE)
                .description("Teleport to the spawn.")
                .executes(warps::spawn));
        }
    }

    private XTConfig.WarpMessages messages() {
        return plugin.config().messages().warps();
    }

    /** Whether someone may use a warp: always, unless per-warp permissions are on. */
    public static boolean mayUse(Permissible who, String warp, XTConfig.Warps settings) {
        return !settings.perWarpPermission() || who.hasPermission(WARP_PERMISSION + warp.toLowerCase(Locale.ROOT));
    }

    private void setwarp(Invocation invocation) {
        Player player = invocation.player();
        Replies.requireReady(plugin);
        String name = invocation.arg(0);
        if (name == null) {
            throw invocation.usage();
        }
        if (!Homes.validName(name)) {
            throw new CommandFailure(messages().badName());
        }
        boolean replacing = plugin.places().warp(name).isPresent();
        Replies.whenSaved(plugin, player, plugin.places().setWarp(Places.of(name, player.getLocation())),
            () -> (replacing ? messages().moved() : messages().set()).send(player, Placeholders.text("warp", name)));
    }

    private void warp(Invocation invocation) {
        Replies.requireReady(plugin);
        String name = invocation.arg(0);
        if (name == null) {
            list(invocation.sender(), 1);
            return;
        }
        Player player = invocation.player();
        Optional<Warp> warp = plugin.places().warp(name);
        if (warp.isEmpty() || !mayUse(player, warp.get().name(), plugin.config().warps())) {
            // A warp the player may not use is one they don't need to know about.
            throw new CommandFailure(messages().notFound(), Placeholders.text("warp", name));
        }
        Location to = warp.get().location();
        if (to == null) {
            throw new CommandFailure(plugin.config().messages().teleport().worldMissing(), Placeholders.text("world", warp.get().world()));
        }
        plugin.teleporter().teleport(player, Teleporter.Trip.of(TeleportType.WARP, "warp", commands.warp(), true,
            messages().teleported(), Placeholders.text("warp", warp.get().name())), () -> CompletableFuture.completedFuture(to));
    }

    private void delwarp(Invocation invocation) {
        Replies.requireReady(plugin);
        String name = invocation.arg(0);
        if (name == null) {
            throw invocation.usage();
        }
        Optional<Warp> warp = plugin.places().warp(name);
        if (warp.isEmpty()) {
            throw new CommandFailure(messages().notFound(), Placeholders.text("warp", name));
        }
        CommandSender sender = invocation.sender();
        Replies.whenSaved(plugin, sender, plugin.places().deleteWarp(name),
            () -> messages().deleted().send(sender, Placeholders.text("warp", warp.get().name())));
    }

    private void list(CommandSender viewer, int page) {
        Replies.requireReady(plugin);
        XTConfig.Warps settings = plugin.config().warps();
        List<Warp> visible = plugin.places().warps().stream().filter(warp -> mayUse(viewer, warp.name(), settings)).toList();
        if (visible.isEmpty()) {
            messages().noWarps().send(viewer);
            return;
        }
        messages().header().send(viewer, Placeholders.text("count", visible.size()));
        ChatPages.Page<Warp> shown = ChatPages.of(visible, page, settings.perPage());
        for (Component line : ChatPages.lines(shown, warp -> messages().entry().chat(
            Placeholders.text("warp", warp.name()),
            Replies.where(warp.world(), warp.x(), warp.y(), warp.z()),
            Replies.click("command", "/warp " + warp.name())), messages().footer(), "/warps {page}")) {
            viewer.sendMessage(line);
        }
    }

    private void setspawn(Invocation invocation) {
        Player player = invocation.player();
        Replies.requireReady(plugin);
        Replies.whenSaved(plugin, player, plugin.places().setSpawn(Places.of("spawn", player.getLocation())),
            () -> plugin.config().messages().spawn().set().send(player));
    }

    private void spawn(Invocation invocation) {
        Player player = invocation.player();
        Replies.requireReady(plugin);
        Location to = plugin.places().spawn();
        if (to == null) {
            throw new CommandFailure(plugin.config().messages().teleport().failed());
        }
        plugin.teleporter().teleport(player, Teleporter.Trip.of(TeleportType.SPAWN, "spawn", commands.spawn(), true,
            plugin.config().messages().spawn().teleported()), () -> CompletableFuture.completedFuture(to));
    }

    private List<String> completeWarps(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        XTConfig.Warps settings = plugin.config().warps();
        return Completions.matching(args[0], plugin.places().warps().stream()
            .filter(warp -> mayUse(sender, warp.name(), settings)).map(Warp::name).toList());
    }
}
