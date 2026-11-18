package co.xenastudios.xstats.board;

import co.xenastudios.core.Module;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xstats.XStats;
import co.xenastudios.xstats.api.LeaderboardEntry;
import co.xenastudios.xstats.api.Period;
import co.xenastudios.xstats.config.XSConfig;
import co.xenastudios.xstats.data.Board;
import co.xenastudios.xstats.stat.Format;
import co.xenastudios.xstats.stat.StatDef;
import co.xenastudios.xstats.storage.Store;
import co.xenastudios.xstats.web.Pages;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Leaderboards in the world: a Paper text display each, no hologram plugin. The text is
 * set after each save, on the thread of the region it stands in.
 *
 * <p>Displays aren't saved with the world: each is made when its chunk loads and goes
 * when it unloads, so a board removed while its chunk was unloaded never leaves one
 * behind. The chunk listener is registered only while there's a board.
 */
public final class WorldBoards implements Module, Listener {

    /** One board, and the text it shows now. */
    private record Placed(Store.BoardRow row, Component text) {}

    /** Boards with an update waiting for their region: one is enough, as while an empty server is paused. */
    private final java.util.Set<Integer> waiting = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final XStats plugin;
    private final NamespacedKey key;
    private volatile List<Placed> placed = List.of();
    private boolean listening;
    private volatile boolean on;

    public WorldBoards(XStats plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "board");
    }

    @Override
    public void enable() {
        on = true;
        listen();
    }

    @Override
    public void disable() {
        on = false;
        listen();
    }

    /**
     * Registers the chunk listener while there are boards, and not otherwise. Done right
     * here, from any thread: registering is synchronized, and on Folia a plugin that's
     * stopping can't schedule anything.
     */
    private synchronized void listen() {
        boolean wanted = on && !placed.isEmpty();
        if (wanted && !listening) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
        } else if (!wanted && listening) {
            HandlerList.unregisterAll(this);
        }
        listening = wanted;
    }

    public List<Store.BoardRow> rows() {
        return placed.stream().map(Placed::row).toList();
    }

    /** Reads the boards from the database. On xStats' thread. */
    public void load() {
        try {
            List<Placed> loaded = new ArrayList<>();
            for (Store.BoardRow row : plugin.engine().store().boards()) {
                loaded.add(new Placed(row, Component.empty()));
            }
            placed = List.copyOf(loaded);
        } catch (SQLException e) {
            plugin.getLogger().warning("Couldn't read the boards placed in the world: " + e.getMessage());
        }
        listen();
    }

    /** Adds a board. On xStats' thread. */
    public int add(Store.BoardRow row) throws SQLException {
        int id = plugin.engine().store().addBoard(row);
        List<Placed> next = new ArrayList<>(placed);
        next.add(new Placed(new Store.BoardRow(id, row.world(), row.x(), row.y(), row.z(), row.yaw(), row.stat(), row.period()),
            Component.empty()));
        placed = List.copyOf(next);
        listen();
        update();
        return id;
    }

    /** Removes a board and its display. On xStats' thread. */
    public boolean remove(int id) throws SQLException {
        Optional<Placed> found = placed.stream().filter(board -> board.row().id() == id).findFirst();
        if (found.isEmpty() || !plugin.engine().store().removeBoard(id)) {
            return false;
        }
        placed = placed.stream().filter(board -> board.row().id() != id).toList();
        listen();
        Location location = location(found.get().row());
        if (location != null) {
            Bukkit.getRegionScheduler().execute(plugin, location, () -> displays(location, id).forEach(TextDisplay::remove));
        }
        return true;
    }

    /** Sets every board's text from the leaderboards just worked out. On xStats' thread, after a save. */
    public void update() {
        if (!on || placed.isEmpty() || !plugin.isEnabled()) {
            return;
        }
        List<Placed> next = new ArrayList<>();
        for (Placed board : placed) {
            Placed updated = new Placed(board.row(), text(board.row()));
            next.add(updated);
            Location location = location(board.row());
            if (location != null && waiting.add(board.row().id())) {
                Bukkit.getRegionScheduler().execute(plugin, location, () -> {
                    waiting.remove(board.row().id());
                    // The newest text by the time the region runs it.
                    placed.stream().filter(current -> current.row().id() == board.row().id()).findFirst()
                        .ifPresent(current -> show(current, location));
                });
            }
        }
        placed = List.copyOf(next);
    }

    private Component text(Store.BoardRow row) {
        XSConfig config = plugin.config();
        XSConfig.Boards settings = config.leaderboards().boards();
        Optional<StatDef> stat = plugin.catalog().snapshot().get(row.stat());
        Period period = Period.parse(row.period()).orElse(Period.ALL);
        if (stat.isEmpty()) {
            return Component.text("No stat called " + row.stat());
        }
        Component text = settings.title().render(Placeholders.text("stat", stat.get().name()),
            Placeholders.text("period", Pages.periodName(config, period)));
        Board board = plugin.engine().leaderboards().get(row.stat(), period);
        List<LeaderboardEntry> entries = board == null ? List.of() : board.top(settings.lines(), false);
        if (entries.isEmpty()) {
            return text.appendNewline().append(settings.empty().render());
        }
        for (LeaderboardEntry entry : entries) {
            text = text.appendNewline().append(settings.line().render(Placeholders.text("rank", entry.rank()),
                Placeholders.text("player", entry.name()), Placeholders.text("value", Format.value(stat.get(), entry.value()))));
        }
        return text;
    }

    private static Location location(Store.BoardRow row) {
        World world = Bukkit.getWorld(row.world());
        return world == null ? null : new Location(world, row.x(), row.y(), row.z(), row.yaw(), 0);
    }

    /** On the region's thread: sets the text of the board's display, making it if it isn't there. */
    private void show(Placed board, Location location) {
        if (!location.isChunkLoaded()) {
            return;
        }
        List<TextDisplay> found = displays(location, board.row().id());
        if (found.isEmpty()) {
            location.getWorld().spawn(location, TextDisplay.class, display -> {
                display.setPersistent(false);
                display.setBillboard(Display.Billboard.VERTICAL);
                display.setAlignment(TextDisplay.TextAlignment.CENTER);
                display.setLineWidth(300);
                display.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, board.row().id());
                display.text(board.text());
            });
            return;
        }
        found.getFirst().text(board.text());
        // A second one, as from a reload: only one stays.
        found.subList(1, found.size()).forEach(TextDisplay::remove);
    }

    private List<TextDisplay> displays(Location location, int id) {
        List<TextDisplay> found = new ArrayList<>();
        for (TextDisplay display : location.getNearbyEntitiesByType(TextDisplay.class, 0.5)) {
            Integer board = display.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
            if (board != null && board == id) {
                found.add(display);
            }
        }
        return found;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Placed board : placed) {
            Store.BoardRow row = board.row();
            if (row.world().equals(event.getWorld().getName()) && ((int) Math.floor(row.x())) >> 4 == event.getChunk().getX()
                && ((int) Math.floor(row.z())) >> 4 == event.getChunk().getZ()) {
                Location location = location(row);
                // Next tick: entities are better not spawned while the chunk is still loading.
                Bukkit.getRegionScheduler().runDelayed(plugin, location, task -> show(board, location), 1);
            }
        }
    }

    /** While the plugin stops: displays go with their chunks anyway, since they're never saved. */
    public void clear() {
        placed = List.of();
    }
}
