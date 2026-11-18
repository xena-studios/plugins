package co.xenastudios.xstats.command;

import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completer;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.command.Subcommands;
import co.xenastudios.xstats.XStats;
import co.xenastudios.xstats.api.Period;
import co.xenastudios.xstats.collect.Collector;
import co.xenastudios.xstats.config.XSConfig;
import co.xenastudios.xstats.data.Engine;
import co.xenastudios.xstats.stat.Format;
import co.xenastudios.xstats.stat.StatDef;
import co.xenastudios.xstats.storage.Store;
import co.xenastudios.xstats.web.Export;
import co.xenastudios.xstats.web.SelfTest;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /xstats}'s subcommands beside reload and info: status, save, import, board and web. */
public final class AdminCommands {

    private final XStats plugin;

    private AdminCommands(XStats plugin) {
        this.plugin = plugin;
    }

    public static List<CommandSpec> specs(XStats plugin) {
        return new AdminCommands(plugin).all();
    }

    private List<CommandSpec> all() {
        String admin = plugin.adminPermission();
        List<CommandSpec> board = List.of(
            sub("add", "/xstats board add <stat> [daily|weekly|monthly|all] [<world> <x> <y> <z>]", this::boardAdd,
                (sender, args) -> switch (args.length) {
                    case 1 -> Completions.matching(args[0], plugin.catalog().snapshot().stats().stream().map(StatDef::id).toList());
                    case 2 -> Completions.matching(args[1], List.of("daily", "weekly", "monthly", "all"));
                    case 3 -> Completions.matching(args[2], Bukkit.getWorlds().stream().map(World::getName).toList());
                    default -> List.of();
                }),
            sub("list", "/xstats board list", this::boardList, Completer.NONE),
            sub("remove", "/xstats board remove <id>", this::boardRemove, Completer.NONE));
        List<CommandSpec> web = List.of(
            sub("test", "/xstats web test", this::webTest, Completer.NONE),
            sub("export", "/xstats web export", this::webExport, Completer.NONE));
        return List.of(
            CommandSpec.named("status").permission(admin).usage("/xstats status").executes(this::status),
            CommandSpec.named("save").permission(admin).usage("/xstats save").executes(this::save),
            CommandSpec.named("import").permission(admin).usage("/xstats import").executes(this::importHistory),
            CommandSpec.named("board").permission(admin).usage("/xstats board " + Subcommands.names(board))
                .completes(Subcommands.completer(board)).executes(Subcommands.executor(board)),
            CommandSpec.named("web").permission(admin).usage("/xstats web " + Subcommands.names(web))
                .completes(Subcommands.completer(web)).executes(Subcommands.executor(web)));
    }

    private CommandSpec sub(String name, String usage, co.xenastudios.core.command.Executor executor, Completer completer) {
        return CommandSpec.named(name).permission(plugin.adminPermission()).usage(usage).completes(completer).executes(executor);
    }

    private void reply(CommandSender sender, String miniMessage) {
        plugin.tasks().sender(sender, () -> sender.sendMessage(plugin.staffMessage(miniMessage)));
    }

    private Engine ready() {
        Engine engine = plugin.engine();
        if (!engine.ready()) {
            throw new CommandFailure(plugin.config().messages().notReady());
        }
        return engine;
    }

    private void status(Invocation invocation) {
        CommandSender sender = invocation.sender();
        Engine engine = plugin.engine();
        if (!engine.ready()) {
            String problem = plugin.startupProblem();
            reply(sender, problem == null ? "Stats are still loading." : "<error>The database couldn't be opened: " + escape(problem));
            return;
        }
        XSConfig config = plugin.config();
        Engine.LastSave last = engine.lastSave();
        // Checked here, on the command's thread, against the server's own names for its stats.
        List<String> wrong = co.xenastudios.xstats.stat.Vanilla.verify();
        String table = wrong.isEmpty() ? "all " + co.xenastudios.xstats.stat.Vanilla.untyped() + " match this server"
            : "<error>" + wrong.size() + " don't match: " + escape(String.join(", ", wrong));
        plugin.worker().execute(() -> {
            String rows;
            try {
                long[] counts = engine.store().rowCounts();
                rows = counts[0] + " all-time, " + counts[1] + " daily, " + counts[2] + " monthly";
            } catch (SQLException e) {
                rows = "couldn't count: " + e.getMessage();
            }
            String saved = last.at() == 0 ? "not yet"
                : Format.time((System.currentTimeMillis() - last.at()) / 1000) + " ago, " + last.players() + " players, " + last.millis() + " ms";
            int port = plugin.webPort();
            String web = !config.web().enabled() ? "off" : port == 0 ? "<error>not running</error> (the console says why)"
                : "on " + config.web().bind() + ":" + port;
            reply(sender, "<highlight>xStats</highlight> on " + escape(engine.store().serverName())
                + "\n<highlight>Storage</highlight>: " + escape(engine.store().description())
                + (engine.feed().on() ? ", network mode on" : "")
                + "\n<highlight>Players</highlight>: " + engine.playerCount() + " known, " + Bukkit.getOnlinePlayers().size() + " online"
                + "\n<highlight>Stats</highlight>: " + plugin.catalog().snapshot().stats().size() + " kept; Minecraft's own: " + table
                + "\n<highlight>Last save</highlight>: " + saved
                + "\n<highlight>Rows</highlight>: " + rows
                + "\n<highlight>Website</highlight>: " + web
                + "\n<highlight>Problem</highlight>: " + (last.problem() == null ? "none" : "<error>" + escape(last.problem())));
        });
    }

    private static String escape(String text) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(text);
    }

    private void save(Invocation invocation) {
        ready();
        Collector collector = plugin.collector();
        if (collector == null) {
            throw new CommandFailure(plugin.config().messages().notReady());
        }
        CommandSender sender = invocation.sender();
        if (!collector.begin(() -> {
            Engine.LastSave last = plugin.engine().lastSave();
            reply(sender, last.problem() == null
                ? "<success>Saved</success> " + last.players() + " players in " + last.millis() + " ms."
                : "<error>The save didn't reach the database: " + escape(last.problem()));
        })) {
            reply(sender, "A save is already running.");
        }
    }

    private void importHistory(Invocation invocation) {
        ready();
        CommandSender sender = invocation.sender();
        reply(sender, "Reading the world's stats files...");
        plugin.runImport(message -> reply(sender, escape(message)));
    }

    private void boardAdd(Invocation invocation) {
        ready();
        if (invocation.size() < 1) {
            throw invocation.usage();
        }
        Optional<StatDef> stat = plugin.catalog().snapshot().get(invocation.arg(0).toLowerCase(java.util.Locale.ROOT));
        if (stat.isEmpty()) {
            reply(invocation.sender(), "<error>There's no stat called " + escape(invocation.arg(0)) + ".");
            return;
        }
        Period period = invocation.size() >= 2 ? Period.parse(invocation.arg(1)).orElseThrow(invocation::usage) : Period.ALL;
        if (period != Period.ALL && !stat.get().periods()) {
            reply(invocation.sender(), "<error>" + escape(stat.get().name()) + " is only counted all time.");
            return;
        }
        Location at;
        if (invocation.size() >= 6) {
            World world = Bukkit.getWorld(invocation.arg(2));
            if (world == null) {
                reply(invocation.sender(), "<error>There's no world called " + escape(invocation.arg(2)) + ".");
                return;
            }
            at = new Location(world, invocation.decimal(3, -3.0e7, 3.0e7), invocation.decimal(4, -2048, 4096),
                invocation.decimal(5, -3.0e7, 3.0e7));
        } else if (invocation.size() <= 2) {
            Player player = invocation.player();
            // Just above where they stand, centred on the block.
            at = player.getLocation().getBlock().getLocation().add(0.5, 2.5, 0.5);
        } else {
            throw invocation.usage();
        }
        Store.BoardRow row = new Store.BoardRow(0, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), 0,
            stat.get().id(), period.id());
        CommandSender sender = invocation.sender();
        plugin.worker().execute(() -> {
            try {
                int id = plugin.boards().add(row);
                reply(sender, "<success>Placed board " + id + "</success>: " + escape(stat.get().name()) + ", " + period.id()
                    + ". Remove it with <highlight>/xstats board remove " + id + "</highlight>.");
            } catch (SQLException e) {
                reply(sender, "<error>Couldn't save the board: " + escape(e.getMessage()));
            }
        });
    }

    private void boardList(Invocation invocation) {
        ready();
        List<Store.BoardRow> rows = plugin.boards().rows();
        if (rows.isEmpty()) {
            reply(invocation.sender(), "No boards yet. Place one with <highlight>/xstats board add <stat></highlight>.");
            return;
        }
        StringBuilder text = new StringBuilder("<highlight>Boards</highlight> (" + rows.size() + "):");
        for (Store.BoardRow row : rows) {
            text.append("\n  <highlight>").append(row.id()).append("</highlight> ").append(escape(row.stat())).append(", ")
                .append(row.period()).append(" at ").append(escape(row.world())).append(' ')
                .append((int) Math.floor(row.x())).append(' ').append((int) Math.floor(row.y())).append(' ')
                .append((int) Math.floor(row.z()));
        }
        reply(invocation.sender(), text.toString());
    }

    private void boardRemove(Invocation invocation) {
        ready();
        int id = invocation.integer(0, 1, Integer.MAX_VALUE);
        CommandSender sender = invocation.sender();
        plugin.worker().execute(() -> {
            try {
                reply(sender, plugin.boards().remove(id) ? "<success>Removed board " + id + "."
                    : "<error>There's no board " + id + ". <highlight>/xstats board list</highlight> shows them.");
            } catch (SQLException e) {
                reply(sender, "<error>Couldn't remove the board: " + escape(e.getMessage()));
            }
        });
    }

    private void webTest(Invocation invocation) {
        ready();
        CommandSender sender = invocation.sender();
        plugin.tasks().async(() -> {
            List<String> failures = SelfTest.run(plugin);
            if (failures.isEmpty()) {
                reply(sender, "<success>Web test passed</success>: " + SelfTest.count() + " checks against a test server on this machine.");
            } else {
                reply(sender, "<error>Web test failed</error>: " + escape(String.join("; ", failures)));
            }
        });
    }

    private void webExport(Invocation invocation) {
        CommandSender sender = invocation.sender();
        plugin.tasks().async(() -> {
            try {
                Path folder = Export.run(plugin);
                reply(sender, "<success>Exported the website</success> to " + escape(plugin.getDataFolder().getName() + "/" + folder.getFileName())
                    + (plugin.config().web().publicUrl().isBlank() ? ". Set web.public-url, or edit its config.json, so it finds the API."
                        : ". Its config.json points at " + escape(plugin.config().web().publicUrl()) + "."));
            } catch (IOException e) {
                reply(sender, "<error>Couldn't export the website: " + escape(e.getMessage()));
            }
        });
    }
}
