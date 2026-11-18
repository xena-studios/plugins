package co.xenastudios.xteleport.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.player.Profiles;
import co.xenastudios.core.text.ChatPages;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xteleport.XTeleport;
import co.xenastudios.xteleport.api.Home;
import co.xenastudios.xteleport.api.TeleportType;
import co.xenastudios.xteleport.api.event.HomeSetEvent;
import co.xenastudios.xteleport.config.Settings;
import co.xenastudios.xteleport.config.XTConfig;
import co.xenastudios.xteleport.home.HomeLimits;
import co.xenastudios.xteleport.home.Homes;
import co.xenastudios.xteleport.teleport.Teleporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/** {@code /sethome}, {@code /home}, {@code /delhome} and {@code /homes}. */
public final class HomeCommands {

    private final XTeleport plugin;

    private HomeCommands(XTeleport plugin) {
        this.plugin = plugin;
    }

    public static void register(Setup setup, XTeleport plugin, XTConfig.Commands commands) {
        HomeCommands homes = new HomeCommands(plugin);
        if (commands.sethome().enabled()) {
            setup.command(CommandSpec.named("sethome", commands.sethome())
                .permissionDefault(PermissionDefault.TRUE)
                .description("Set a home where you stand.")
                .usage("/sethome [name]")
                .executes(homes::sethome));
        }
        Settings.HomeCommand home = commands.home();
        if (home.enabled()) {
            setup.command(CommandSpec.named("home", home)
                .permissionDefault(PermissionDefault.TRUE)
                .description("Teleport to one of your homes.")
                .usage("/home [name]")
                .completes((sender, args) -> args.length == 1 ? homes.completeHomes(sender, args[0], home.permissionOthers(), true) : List.of())
                .executes(invocation -> homes.home(invocation, home)));
            setup.permission(home.permissionOthers(), PermissionDefault.OP);
        }
        Settings.OthersCommand delhome = commands.delhome();
        if (delhome.enabled()) {
            setup.command(CommandSpec.named("delhome", delhome)
                .permissionDefault(PermissionDefault.TRUE)
                .description("Delete one of your homes.")
                .usage("/delhome <name>")
                .completes((sender, args) -> args.length == 1 ? homes.completeHomes(sender, args[0], delhome.permissionOthers(), false) : List.of())
                .executes(invocation -> homes.delhome(invocation, delhome)));
            setup.permission(delhome.permissionOthers(), PermissionDefault.OP);
        }
        Settings.OthersCommand list = commands.homes();
        if (list.enabled()) {
            setup.command(CommandSpec.named("homes", list)
                .permissionDefault(PermissionDefault.TRUE)
                .description("List your homes.")
                .usage("/homes [page]")
                .completes((sender, args) -> args.length == 1 ? Completions.players(sender, args[0], list.permissionOthers()) : List.of())
                .executes(invocation -> homes.homes(invocation, list)));
            setup.permission(list.permissionOthers(), PermissionDefault.OP);
        }
    }

    private XTConfig.HomeMessages messages() {
        return plugin.config().messages().homes();
    }

    private Teleporter teleporter() {
        return plugin.teleporter();
    }

    // The player's own homes, then on their thread; "not available" if they can't be read.
    private void withHomes(Player player, UUID owner, Consumer<Homes.Loaded> then) {
        plugin.homes().of(owner).whenComplete((loaded, error) -> teleporter().onPlayer(player, () -> {
            if (error != null) {
                plugin.config().messages().teleport().unavailable().send(player);
            } else {
                guarded(player, () -> then.accept(loaded));
            }
        }, () -> {}));
    }

    // A player named by staff, online or not, then their homes, then on the sender's thread.
    private void withOthersHomes(CommandSender sender, String name, java.util.function.BiConsumer<Profiles.Profile, Homes.Loaded> then) {
        plugin.profiles().byName(name).whenComplete((profile, error) -> {
            if (error != null || profile.isEmpty()) {
                plugin.tasks().sender(sender, () -> messages().neverJoined().send(sender, Placeholders.text("player", name)));
                return;
            }
            plugin.homes().of(profile.get().id()).whenComplete((loaded, failed) -> plugin.tasks().sender(sender, () -> {
                if (failed != null) {
                    plugin.config().messages().teleport().unavailable().send(sender);
                } else {
                    guarded(sender, () -> then.accept(profile.get(), loaded));
                }
            }));
        });
    }

    // Work that runs after the command returned, so the core can't catch what it throws:
    // the same "something went wrong", with the trace in the console.
    private void guarded(CommandSender sender, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "A home command failed", e);
            plugin.config().messages().error().failed().send(sender);
        }
    }

    // Another player's home, typed as player:home, then on the staff member's thread.
    private void othersHome(Player staff, String typed, java.util.function.BiConsumer<Profiles.Profile, Home> then) {
        String owner = typed.substring(0, typed.indexOf(':'));
        String name = typed.substring(typed.indexOf(':') + 1);
        withOthersHomes(staff, owner, (profile, loaded) -> {
            Optional<Home> home = loaded.home(name);
            if (home.isEmpty()) {
                messages().notFoundOthers().send(staff, Placeholders.text("player", profile.name()), Placeholders.text("home", name));
            } else {
                then.accept(profile, home.get());
            }
        });
    }

    private void sethome(Invocation invocation) {
        Player player = invocation.player();
        Replies.requireReady(plugin);
        XTConfig.Homes settings = plugin.config().homes();
        String name = invocation.arg(0) == null ? settings.defaultName() : invocation.arg(0);
        if (!Homes.validName(name)) {
            throw new CommandFailure(messages().badName());
        }
        if (settings.bedHome() && name.equalsIgnoreCase(settings.bedName())) {
            throw new CommandFailure(messages().reservedName(), Placeholders.text("home", name));
        }
        withHomes(player, player.getUniqueId(), loaded -> {
            boolean replacing = loaded.home(name).isPresent();
            int limit = HomeLimits.of(player, settings);
            if (!replacing && loaded.homes().size() >= limit) {
                messages().limitReached().send(player, Placeholders.text("limit", limit));
                return;
            }
            Location at = player.getLocation();
            if (HomeSetEvent.getHandlerList().getRegisteredListeners().length > 0) {
                HomeSetEvent event = new HomeSetEvent(player, name, at.clone(), replacing);
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) {
                    return;
                }
            }
            plugin.homes().set(new Home(player.getUniqueId(), name, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                at.getYaw(), at.getPitch()));
            (replacing ? messages().moved() : messages().set()).send(player, Placeholders.text("home", name));
        });
    }

    private void home(Invocation invocation, Settings.HomeCommand settings) {
        Player player = invocation.player();
        Replies.requireReady(plugin);
        String typed = invocation.arg(0);
        if (typed != null && typed.indexOf(':') > 0) {
            invocation.require(settings.permissionOthers());
            othersHome(player, typed, (profile, home) -> go(player, home, settings));
            return;
        }
        XTConfig.Homes homes = plugin.config().homes();
        withHomes(player, player.getUniqueId(), loaded -> {
            if (typed == null) {
                Optional<Home> only = loaded.homes().size() == 1 ? Optional.of(loaded.list().getFirst()) : loaded.home(homes.defaultName());
                if (only.isPresent()) {
                    go(player, only.get(), settings);
                } else if (loaded.homes().isEmpty() && !(homes.bedHome() && bedOf(player) != null)) {
                    messages().noHomes().send(player);
                } else {
                    list(player, player.getUniqueId(), player.getName(), loaded, 1, false);
                }
                return;
            }
            Optional<Home> home = loaded.home(typed);
            if (home.isPresent()) {
                go(player, home.get(), settings);
            } else if (homes.bedHome() && typed.equalsIgnoreCase(homes.bedName())) {
                bed(player, settings, homes.bedName());
            } else {
                messages().notFound().send(player, Placeholders.text("home", typed));
            }
        });
    }

    /**
     * The block of the player's bed or respawn anchor, or null: where it was set, without
     * loading its chunk to check it's still there (the teleport checks, in the background).
     * Deprecated since 1.21.5 for a method that loads the chunk on this thread, which is
     * exactly what mustn't happen here; not marked for removal.
     */
    @SuppressWarnings("deprecation")
    private static Location bedOf(Player player) {
        return player.getPotentialRespawnLocation();
    }

    private void go(Player player, Home home, Settings.HomeCommand settings) {
        Location to = home.location();
        if (to == null) {
            plugin.config().messages().teleport().worldMissing().send(player, Placeholders.text("world", home.world()));
            return;
        }
        teleporter().teleport(player, Teleporter.Trip.of(TeleportType.HOME, "home", settings, true, messages().teleported(),
            Placeholders.text("home", home.name())), () -> CompletableFuture.completedFuture(to));
    }

    // The bed or respawn anchor, worked out once the warmup is over: its chunk loads in the
    // background, and the block is read on its own thread to stand on top of it.
    private void bed(Player player, Settings.HomeCommand settings, String bedName) {
        Location spawnBlock = bedOf(player);
        if (spawnBlock == null || !spawnBlock.isWorldLoaded()) {
            messages().noBed().send(player);
            return;
        }
        teleporter().teleport(player, Teleporter.Trip.of(TeleportType.HOME, "home", settings, true, messages().teleported(),
            Placeholders.text("home", bedName)), () -> standOn(spawnBlock));
    }

    private CompletableFuture<Location> standOn(Location block) {
        World world = block.getWorld();
        CompletableFuture<Location> spot = new CompletableFuture<>();
        co.xenastudios.xteleport.teleport.Safety.ChunkLoader.safely(plugin::loadChunk, world, block.getBlockX() >> 4,
            block.getBlockZ() >> 4, true).whenComplete((chunk, error) -> {
            if (error != null || chunk == null) {
                spot.complete(null);
                return;
            }
            Runnable read = () -> {
                Block at = block.getBlock();
                // A bed is just over half a block high; an anchor is a full block.
                double height = Tag.BEDS.isTagged(at.getType()) ? 0.5625 : 1;
                spot.complete(new Location(world, at.getX() + 0.5, at.getY() + height, at.getZ() + 0.5));
            };
            if (Bukkit.isOwnedByCurrentRegion(block)) {
                read.run();
            } else {
                Bukkit.getRegionScheduler().execute(plugin, block, read);
            }
        });
        return spot;
    }

    private void delhome(Invocation invocation, Settings.OthersCommand settings) {
        Player player = invocation.player();
        Replies.requireReady(plugin);
        String typed = invocation.arg(0);
        if (typed == null) {
            throw invocation.usage();
        }
        if (typed.indexOf(':') > 0) {
            invocation.require(settings.permissionOthers());
            othersHome(player, typed, (profile, home) -> {
                plugin.homes().delete(profile.id(), home.name());
                messages().deletedOthers().send(player, Placeholders.text("player", profile.name()),
                    Placeholders.text("home", home.name()));
            });
            return;
        }
        withHomes(player, player.getUniqueId(), loaded -> {
            Optional<Home> home = loaded.home(typed);
            if (home.isEmpty()) {
                messages().notFound().send(player, Placeholders.text("home", typed));
                return;
            }
            plugin.homes().delete(player.getUniqueId(), typed);
            messages().deleted().send(player, Placeholders.text("home", home.get().name()));
        });
    }

    private void homes(Invocation invocation, Settings.OthersCommand settings) {
        CommandSender sender = invocation.sender();
        Replies.requireReady(plugin);
        String first = invocation.arg(0);
        boolean others = first != null && !Replies.isNumber(first)
            && !(sender instanceof Player self && self.getName().equalsIgnoreCase(first));
        if (others) {
            invocation.require(settings.permissionOthers());
            int page = Replies.page(invocation.arg(1));
            withOthersHomes(sender, first, (profile, loaded) -> list(sender, profile.id(), profile.name(), loaded, page, true));
            return;
        }
        Player player = invocation.player();
        int page = Replies.page(Replies.isNumber(first) ? first : invocation.arg(1));
        withHomes(player, player.getUniqueId(), loaded -> list(player, player.getUniqueId(), player.getName(), loaded, page, false));
    }

    /** One home in a list, or the bed. */
    private record Entry(String name, String world, double x, double y, double z) {}

    private void list(CommandSender viewer, UUID owner, String ownerName, Homes.Loaded loaded, int page, boolean others) {
        XTConfig.Homes settings = plugin.config().homes();
        List<Entry> entries = new ArrayList<>();
        // A bed is only offered for one's own list: someone else's bed is theirs to find.
        if (!others && settings.bedHome() && viewer instanceof Player player) {
            Location bed = bedOf(player);
            if (bed != null && bed.isWorldLoaded()) {
                entries.add(new Entry(settings.bedName(), bed.getWorld().getName(), bed.getX(), bed.getY(), bed.getZ()));
            }
        }
        for (Home home : loaded.list()) {
            entries.add(new Entry(home.name(), home.world(), home.x(), home.y(), home.z()));
        }
        if (entries.isEmpty()) {
            if (others) {
                messages().noHomesOthers().send(viewer, Placeholders.text("player", ownerName));
            } else {
                messages().noHomes().send(viewer);
            }
            return;
        }
        if (others) {
            messages().headerOthers().send(viewer, Placeholders.text("player", ownerName), Placeholders.text("count", loaded.homes().size()));
        } else {
            int limit = viewer instanceof Player player ? HomeLimits.of(player, settings) : 0;
            messages().header().send(viewer, Placeholders.text("count", loaded.homes().size()),
                Placeholders.text("limit", limit == Integer.MAX_VALUE ? "∞" : Integer.toString(limit)));
        }
        String prefix = others ? "/home " + ownerName + ":" : "/home ";
        ChatPages.Page<Entry> shown = ChatPages.of(entries, page, settings.perPage());
        String pageCommand = others ? "/homes " + ownerName + " {page}" : "/homes {page}";
        for (Component line : ChatPages.lines(shown, entry -> messages().entry().chat(
            Placeholders.text("home", entry.name()),
            Replies.where(entry.world(), entry.x(), entry.y(), entry.z()),
            Replies.click("command", prefix + entry.name())), messages().footer(), pageCommand)) {
            viewer.sendMessage(line);
        }
    }

    private List<String> completeHomes(CommandSender sender, String typed, String othersPermission, boolean withBed) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        int colon = typed.indexOf(':');
        if (colon > 0 && (othersPermission.isBlank() || sender.hasPermission(othersPermission))) {
            Player owner = Bukkit.getPlayerExact(typed.substring(0, colon));
            if (owner != null && player.canSee(owner)) {
                plugin.homes().cached(owner.getUniqueId()).ifPresent(loaded ->
                    loaded.list().forEach(home -> names.add(owner.getName() + ":" + home.name())));
            }
            return Completions.matching(typed, names);
        }
        plugin.homes().cached(player.getUniqueId()).ifPresent(loaded -> loaded.list().forEach(home -> names.add(home.name())));
        XTConfig.Homes settings = plugin.config().homes();
        if (withBed && settings.bedHome()) {
            names.add(settings.bedName());
        }
        return Completions.matching(typed, names);
    }
}
