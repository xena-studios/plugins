package co.xenastudios.xrewind.query;

import co.xenastudios.core.db.Database;
import co.xenastudios.core.text.ChatPages;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xrewind.XRewind;
import co.xenastudios.xrewind.config.XRConfig;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Runs lookups off the server's threads and sends the results back on the sender's.
 * Remembers each sender's last lookup for {@code /xrewind page}.
 */
public final class Lookups {

    public static final int PER_PAGE = 7;
    /** The furthest page: deeper ones would read thousands of rows to show seven. */
    public static final int MAX_PAGE = 500;

    private record Last(Query query, Lookup.Center center, boolean inspect) {}

    private final XRewind plugin;
    // Weak, so a player who left doesn't keep theirs.
    private final Map<CommandSender, Last> last = Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<CommandSender> busy = ConcurrentHashMap.newKeySet();

    public Lookups(XRewind plugin) {
        this.plugin = plugin;
    }

    /**
     * The player a command is about: the sender, or the player a command block or
     * {@code /execute as} ran it as. Null for the console.
     */
    public static Player playerOf(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        return sender instanceof org.bukkit.command.ProxiedCommandSender proxied && proxied.getCallee() instanceof Player player
            ? player : null;
    }

    /** Where a player is, as a lookup's centre; null for the console. */
    public static Lookup.Center centerOf(CommandSender sender) {
        if (playerOf(sender) instanceof Player player) {
            Location at = player.getLocation();
            return new Lookup.Center(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
        }
        return null;
    }

    /** A block's whole history, for the inspector. */
    public static Query at() {
        return new Query(List.of(), List.of(), 0, 0, null, Set.of(), List.of(), List.of(), false, false, false);
    }

    /**
     * Looks up a page and sends it. A sender's second lookup while their first is still
     * running is ignored, so clicking fast with the inspector can't pile them up.
     */
    public void run(CommandSender sender, Query query, Lookup.Center center, int page, boolean inspect) {
        XRConfig.LookupMessages messages = plugin.config().messages().lookup();
        if (center == null && query.world() == null && query.radius() != Query.GLOBAL) {
            messages.invalid().send(sender, Placeholders.text("reason", "From the console, add w:<world> or r:#global."));
            return;
        }
        Database database = plugin.storage().database();
        if (database == null) {
            messages.notReady().send(sender);
            return;
        }
        if (!busy.add(sender)) {
            return;
        }
        last.put(sender, new Last(query, center, inspect));
        plugin.tasks().async(() -> {
            try {
                plugin.writer().flush(1_000);
                Lookup.Result result = Lookup.page(database, query, center, page, PER_PAGE);
                reply(sender, render(messages, query, center, page, inspect, result));
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "A lookup for " + sender.getName() + " failed", e);
                reply(sender, List.of(plugin.config().errorMessages().failed().chat()));
            } finally {
                busy.remove(sender);
            }
        });
    }

    /** Another page of the sender's last lookup. */
    public void page(CommandSender sender, int page) {
        Last previous = last.get(sender);
        if (previous == null) {
            plugin.config().messages().lookup().noPrevious().send(sender);
            return;
        }
        run(sender, previous.query(), previous.center(), page, previous.inspect());
    }

    private static List<Component> render(XRConfig.LookupMessages messages, Query query, Lookup.Center center,
                                          int page, boolean inspect, Lookup.Result result) {
        List<Component> lines = new ArrayList<>();
        if (result.total() == 0) {
            lines.add(messages.none().chat());
            return lines;
        }
        lines.add(inspect && center != null
            ? messages.headerAt().chat(Placeholders.text("x", center.x()), Placeholders.text("y", center.y()),
                Placeholders.text("z", center.z()), Placeholders.text("world", center.world()))
            : messages.header().chat(Placeholders.text("count", String.format("%,d", result.total()))));
        if (query.count()) {
            return lines;
        }
        lines.addAll(ChatPages.lines(ChatPages.of(result.rows(), result.total(), page, PER_PAGE),
            row -> Formats.line(messages, row), messages.footer(), "/xrewind page {page}"));
        return lines;
    }

    private void reply(CommandSender sender, List<Component> lines) {
        plugin.tasks().sender(sender, () -> lines.forEach(sender::sendMessage));
    }
}
