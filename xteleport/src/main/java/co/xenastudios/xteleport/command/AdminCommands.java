package co.xenastudios.xteleport.command;

import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xteleport.XTeleport;
import co.xenastudios.xteleport.config.Settings.RtpWorld;
import co.xenastudios.xteleport.imports.EssentialsImport;
import co.xenastudios.xteleport.rtp.Search;
import co.xenastudios.xteleport.storage.Store;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/** {@code /xteleport import} and {@code /xteleport rtpcheck}, beside reload and info. */
public final class AdminCommands {

    private static final Message NO_DATA = Message.of("<prefix><error>No EssentialsX data found in <highlight><folder></highlight>.");
    private static final Message FOUND = Message.of("<prefix>EssentialsX has <highlight><homes></highlight> homes of"
        + " <highlight><players></highlight> players, <highlight><warps></highlight> warps and <spawn>.");
    private static final Message DRY_RUN = Message.of("<prefix>Nothing was changed. Homes and warps you already have with"
        + " the same name, and a spawn already set, are kept. To import, run <highlight>/xteleport import essentials confirm</highlight>.");
    private static final Message IMPORTED = Message.of("<prefix><success>Imported</success> <highlight><homes></highlight> homes,"
        + " <highlight><warps></highlight> warps<spawn>. Skipped <highlight><skipped></highlight> already here.");
    private static final Message PROBLEM = Message.of("<prefix><error><problem>");
    private static final Message FAILED = Message.of("<prefix><error>The import failed, and nothing was changed: <reason>");
    private static final Message UNKNOWN = Message.of("<prefix><error>Only EssentialsX can be imported in this version: "
        + "<highlight>/xteleport import essentials</highlight>.");
    private static final Message NOT_RTP_WORLD = Message.of("<prefix><error>/rtp can't send anyone to <highlight><world></highlight>."
        + " Add it under rtp.worlds.");
    private static final Message BUSY = Message.of("<prefix><error>The most /rtp searches are already running. Try again in a moment.");
    private static final Message SPOT = Message.of("<prefix><success>Found a safe spot</success> in <found> of <runs>"
        + " searches in <world>, after <tries> tries each on average, in <ms> ms each. The last was <highlight><x> <y> <z></highlight>.");
    private static final Message NO_SPOT = Message.of("<prefix><error>No safe spot in <world> in <runs> searches of <tries> tries.</error>"
        + " Check its radii and blocked biomes.");

    private final XTeleport plugin;

    private AdminCommands(XTeleport plugin) {
        this.plugin = plugin;
    }

    public static List<CommandSpec> specs(XTeleport plugin) {
        AdminCommands admin = new AdminCommands(plugin);
        return List.of(
            CommandSpec.named("import")
                .permission(plugin.adminPermission())
                .usage("/xteleport import essentials [confirm]")
                .completes((sender, args) -> switch (args.length) {
                    case 1 -> Completions.matching(args[0], List.of("essentials"));
                    case 2 -> Completions.matching(args[1], List.of("confirm"));
                    default -> List.of();
                })
                .executes(admin::importFrom),
            CommandSpec.named("rtpcheck")
                .permission(plugin.adminPermission())
                .usage("/xteleport rtpcheck <world> [searches]")
                .completes((sender, args) -> args.length == 1
                    ? Completions.matching(args[0], Bukkit.getWorlds().stream().map(World::getName).toList()) : List.of())
                .executes(admin::rtpCheck));
    }

    private void importFrom(Invocation invocation) {
        CommandSender sender = invocation.sender();
        String from = invocation.arg(0);
        if (from == null) {
            throw invocation.usage();
        }
        if (!from.toLowerCase(Locale.ROOT).startsWith("essentials")) {
            throw new CommandFailure(UNKNOWN);
        }
        Replies.requireReady(plugin);
        boolean confirm = "confirm".equalsIgnoreCase(invocation.arg(1));
        Path folder = plugin.getDataFolder().toPath().getParent().resolve("Essentials");
        plugin.tasks().async(() -> {
            EssentialsImport.Found found = EssentialsImport.read(folder, id -> {
                World world = Bukkit.getWorld(id);
                return world == null ? null : world.getName();
            });
            plugin.tasks().sender(sender, () -> report(sender, folder, found, confirm));
        });
    }

    private void report(CommandSender sender, Path folder, EssentialsImport.Found found, boolean confirm) {
        found.problems().forEach(problem -> PROBLEM.send(sender, Placeholders.text("problem", problem)));
        if (found.homes().isEmpty() && found.warps().isEmpty() && found.spawn() == null) {
            NO_DATA.send(sender, Placeholders.text("folder", plugin.getDataFolder().getParentFile().getName() + "/" + folder.getFileName()));
            return;
        }
        FOUND.send(sender,
            Placeholders.text("homes", found.homes().size()),
            Placeholders.text("players", found.players()),
            Placeholders.text("warps", found.warps().size()),
            Placeholders.text("spawn", found.spawn() == null ? "no spawn" : "a spawn"));
        if (!confirm) {
            DRY_RUN.send(sender);
            return;
        }
        Store.ServerData before = new Store.ServerData(plugin.places().warps(), plugin.places().setSpawn());
        plugin.store().importAll(found.homes(), found.warps(), found.spawn())
            .thenCompose(imported -> plugin.store().loadServer().thenApply(server -> {
                plugin.places().load(server);
                // Online players' homes are read again, with what was added.
                plugin.homes().forgetAll();
                Bukkit.getOnlinePlayers().forEach(player -> plugin.homes().of(player.getUniqueId()));
                return imported;
            }))
            .whenComplete((imported, error) -> plugin.tasks().sender(sender, () -> {
                if (error != null) {
                    plugin.places().load(before);
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    FAILED.send(sender, Placeholders.text("reason", String.valueOf(cause.getMessage())));
                    return;
                }
                plugin.getLogger().info("Imported " + imported.homes() + " homes and " + imported.warps() + " warps from EssentialsX.");
                IMPORTED.send(sender,
                    Placeholders.text("homes", imported.homes()),
                    Placeholders.text("warps", imported.warps()),
                    Placeholders.text("spawn", imported.spawn() ? " and the spawn" : ""),
                    Placeholders.text("skipped", imported.skipped()));
            }));
    }

    // Searches like /rtp does and says where it would land, without teleporting anyone: for
    // checking a world's settings (several searches show how often they find a spot), and
    // for the boot checks to run real searches on Paper and Folia.
    private void rtpCheck(Invocation invocation) {
        CommandSender sender = invocation.sender();
        String name = invocation.arg(0);
        if (name == null) {
            throw invocation.usage();
        }
        int runs = invocation.arg(1) == null ? 1 : invocation.integer(1, 1, 20);
        World world = Bukkit.getWorld(name);
        RtpWorld rules = world == null ? null : plugin.rtp().rules(world);
        if (rules == null) {
            throw new CommandFailure(NOT_RTP_WORLD, Placeholders.text("world", name));
        }
        if (plugin.rtp().begin(null) != Search.Start.STARTED) {
            throw new CommandFailure(BUSY);
        }
        check(sender, world, rules, runs, new Tally(System.nanoTime()));
    }

    /** What the searches so far found. */
    private static final class Tally {
        final long start;
        int runs;
        int found;
        int tries;
        Location last;

        Tally(long start) {
            this.start = start;
        }
    }

    // One search after another, so a check costs no more than one player's /rtp at a time.
    // The place among the searches is given back however the last one ends, even if
    // starting it threw.
    private void check(CommandSender sender, World world, RtpWorld rules, int runs, Tally tally) {
        CompletableFuture<Search.Found> search;
        try {
            search = plugin.rtp().find(world, rules);
        } catch (RuntimeException e) {
            search = CompletableFuture.failedFuture(e);
        }
        search.whenComplete((found, error) -> {
            tally.runs++;
            if (found != null) {
                tally.found++;
                tally.tries += found.tries();
                tally.last = found.location();
            }
            if (error == null && tally.runs < runs && plugin.isEnabled()) {
                check(sender, world, rules, runs, tally);
                return;
            }
            plugin.rtp().end(null);
            long ms = (System.nanoTime() - tally.start) / 1_000_000 / tally.runs;
            plugin.tasks().sender(sender, () -> {
                if (error != null) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "The RTP search failed", error);
                    plugin.config().messages().error().failed().send(sender);
                } else if (tally.found == 0) {
                    NO_SPOT.send(sender, Placeholders.text("world", world.getName()), Placeholders.text("runs", tally.runs),
                        Placeholders.text("tries", plugin.rtp().settings().attempts()));
                } else {
                    Location at = tally.last;
                    SPOT.send(sender, Replies.where(world.getName(), at.getX(), at.getY(), at.getZ()),
                        Placeholders.text("found", tally.found), Placeholders.text("runs", tally.runs),
                        Placeholders.text("tries", String.format(Locale.ROOT, "%.1f", tally.tries / (double) tally.found)),
                        Placeholders.text("ms", ms));
                }
            });
        });
    }
}
