package co.xenastudios.xstats;

import co.xenastudios.core.Module;
import co.xenastudios.core.Setup;
import co.xenastudios.core.XenaPlugin;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.core.db.DatabaseException;
import co.xenastudios.core.player.Profiles;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xstats.board.WorldBoards;
import co.xenastudios.xstats.collect.Collector;
import co.xenastudios.xstats.collect.Reader;
import co.xenastudios.xstats.collect.Sessions;
import co.xenastudios.xstats.collect.Worker;
import co.xenastudios.xstats.command.AdminCommands;
import co.xenastudios.xstats.command.StatsCommand;
import co.xenastudios.xstats.command.TopCommand;
import co.xenastudios.xstats.config.XSConfig;
import co.xenastudios.xstats.data.Calendar;
import co.xenastudios.xstats.data.Engine;
import co.xenastudios.xstats.hook.StatPlaceholders;
import co.xenastudios.xstats.profile.Sections;
import co.xenastudios.xstats.stat.Catalog;
import co.xenastudios.xstats.stat.Definitions;
import co.xenastudios.xstats.stat.StatDef;
import co.xenastudios.xstats.storage.Store;
import co.xenastudios.xstats.web.Pages;
import co.xenastudios.xstats.web.WebModule;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/** xStats: player stats, leaderboards and a website. Not final: MockBukkit subclasses it in tests. */
public class XStats extends XenaPlugin<XSConfig> {

    /** Staff see more on /stats: sections other plugins only show staff. */
    public static final String STAFF = "xstats.staff";

    private static final Message STAFF_MESSAGE = Message.of("<prefix><text>");

    private final Catalog catalog = new Catalog();
    private final List<Definitions.Registered> registered = new CopyOnWriteArrayList<>();
    private final AtomicBoolean saving = new AtomicBoolean();
    private Sections sections;
    private boolean started;
    private Worker worker;
    private Engine engine;
    private Reader reader;
    private Profiles profiles;
    private Pages pages;
    private WorldBoards boards;
    private Api api;
    private volatile Collector collector;
    private volatile WebModule web;
    private volatile String startupProblem;
    private Path world;

    @Override
    protected ConfigSpec<XSConfig> configSpec() {
        return XSConfig.SPEC;
    }

    @Override
    protected void setup(Setup setup, XSConfig config) {
        stats(config);
        Engine.Settings settings = settings(config);
        if (!started) {
            started = true;
            start(config, settings);
        }
        engine.settings(settings);
        reader.reset();
        worker.execute(() -> {
            engine.recomputeBoards();
            afterSave();
        });

        setup.module("sessions", () -> new Sessions(this, engine, reader));
        // Restarted when its timing changes; the save in progress, if any, finishes first.
        XSConfig.Collecting collecting = config.collecting();
        setup.module("collector:" + collecting.saveEveryMinutes() + ":" + config.leaderboards().excludeBanned(), () -> {
            Collector made = new Collector(this, tasks(), worker, engine, reader, Duration.ofMinutes(collecting.saveEveryMinutes()),
                () -> config().collecting().readsPerTick(), config.leaderboards().excludeBanned(), saving, this::afterSave);
            collector = made;
            return made;
        });
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            setup.module("placeholders", () -> new PlaceholderModule());
        }
        setup.module("api", () -> api);
        setup.module("boards", () -> boards);
        if (config.web().enabled()) {
            // Restarted whenever its settings change, so a new port or address applies on reload.
            setup.module("web:" + config.web().hashCode(), () -> {
                WebModule made = new WebModule(this, config.web());
                web = made;
                return made;
            });
        }

        StatsCommand.register(setup, this, config);
        TopCommand.register(setup, this, config);
        AdminCommands.specs(this).forEach(setup::subcommand);
        setup.permission(StatsCommand.PRIVATE, PermissionDefault.TRUE);
        setup.permission(STAFF, PermissionDefault.OP);
        setup.permission(Reader.EXCLUDE, PermissionDefault.FALSE);
    }

    private void stats(XSConfig config) {
        List<StatDef> stats = Definitions.build(config.stats(), registered, catalog,
            warning -> getLogger().warning("config.yml: " + warning));
        catalog.set(stats);
    }

    private Engine.Settings settings(XSConfig config) {
        ZoneId zone = ZoneId.systemDefault();
        if (!config.periods().timezone().isBlank()) {
            try {
                zone = ZoneId.of(config.periods().timezone().strip());
            } catch (DateTimeException e) {
                getLogger().warning("config.yml: periods.timezone \"" + config.periods().timezone()
                    + "\" isn't a time zone, such as Europe/London. Using the server's own: " + zone + ".");
            }
        }
        return new Engine.Settings(new Calendar(zone, config.periods().weekStarts()), config.collecting().afkAware(),
            Duration.ofMinutes(config.collecting().saveEveryMinutes()).toMillis(), config.periods().keepDailyDays(),
            config.network().enabled());
    }

    // Opens the database on xStats' own thread, so a slow or unreachable MySQL never holds up
    // startup. Until it's open, commands say stats are still loading. Storage and network
    // settings apply on restart: stats aren't copied between databases.
    private void start(XSConfig config, Engine.Settings settings) {
        worker = new Worker(getLogger());
        sections = new Sections(getLogger());
        engine = new Engine(catalog, settings, getLogger());
        reader = new Reader(catalog, getLogger());
        profiles = new Profiles(tasks());
        pages = new Pages(this);
        boards = new WorldBoards(this);
        api = new Api(this);
        engine.onNewLeader(api::newLeader);
        world = mainWorld();
        String server = config.serverName().matches("[A-Za-z0-9_-]{1,64}") ? config.serverName() : "main";
        if (!server.equals(config.serverName())) {
            getLogger().warning("config.yml: server-name can only use letters, digits, - and _. Using \"main\".");
        }
        worker.execute(() -> {
            Store store;
            ChangeFeed feed;
            try {
                store = Store.open(config.storage(), getDataFolder().toPath(), server);
                feed = ChangeFeed.open(store.database(), config.network(), getLogger());
                feed.subscribe("saved", change -> engine.networkChanged());
                feed.subscribe("player", change -> worker.execute(() -> engine.refreshHidden(UUID.fromString(change.payload()))));
                // Changes may have been missed (the database was out of reach): read the network's numbers again.
                feed.onResync(engine::networkChanged);
                engine.open(store, feed);
            } catch (DatabaseException | SQLException e) {
                startupProblem = e.getMessage();
                getLogger().severe(e.getMessage());
                getLogger().severe("xStats can't keep stats without its database. Fix storage in config.yml and restart.");
                return;
            }
            feed.start(tasks());
            getLogger().info("Stats ready: " + engine.playerCount() + " players in " + store.description() + ".");
            boards.load();
            afterSave();
            if (config.collecting().importOnFirstStart() && world != null) {
                try {
                    if (!store.flag("imported")) {
                        getLogger().info("First start: reading players' history from the world's stats files.");
                        runImport(message -> getLogger().info(message));
                    }
                } catch (SQLException e) {
                    getLogger().warning("Couldn't check whether the history was imported: " + e.getMessage());
                }
            }
        });
    }

    /** The main world's folder, where Minecraft keeps every player's stats file; null if there's none. */
    private Path mainWorld() {
        try {
            return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst().getWorldFolder().toPath();
        } catch (RuntimeException e) {
            getLogger().fine("No world folder to import from: " + e);
            return null;
        }
    }

    /** After each save, on xStats' thread: the website's pages, boards in the world, then events. */
    public void afterSave() {
        try {
            pages.render();
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Couldn't make the website's pages", e);
        }
        boards.update();
        api.saved();
    }

    /**
     * Reads the world's stats files off the server's threads, then adds what's new on xStats'
     * thread. {@code done} hears how it went, off the server's threads.
     */
    public void runImport(java.util.function.Consumer<String> done) {
        if (world == null || engine.store() == null) {
            done.accept("There's no world to import from yet.");
            return;
        }
        co.xenastudios.xstats.importer.Import.run(this, world, done);
    }

    @Override
    protected void shutdown() {
        if (worker == null) {
            return;
        }
        // Players still online: one last reading each, without placeholders (other plugins may be gone).
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                engine.queue(reader.read(player, true, false));
            } catch (RuntimeException e) {
                getLogger().fine("Couldn't read " + player.getName() + " while stopping: " + e);
            }
        }
        try {
            worker.execute(() -> {
                engine.save(System.currentTimeMillis());
                if (engine.feed() != null) {
                    engine.feed().close();
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            getLogger().warning("The last save didn't finish in 10 seconds: " + e);
        }
        boards.clear();
        worker.stop(5);
    }

    /** A reply for staff, in the theme. */
    public Component staffMessage(String miniMessage) {
        return STAFF_MESSAGE.chat(Placeholders.component("text", Message.of(miniMessage).render()));
    }

    /** Registers a stat for the API, and puts it in use. */
    void register(Definitions.Registered stat) {
        registered.removeIf(old -> old.id().equals(stat.id()));
        registered.add(stat);
        stats(config());
    }

    /** Forgets a plugin's stats when it's disabled. */
    void unregister(String plugin) {
        if (registered.removeIf(stat -> stat.plugin().equals(plugin))) {
            stats(config());
        }
    }

    public Catalog catalog() {
        return catalog;
    }

    public Engine engine() {
        return engine;
    }

    public Worker worker() {
        return worker;
    }

    public Reader reader() {
        return reader;
    }

    public Profiles profiles() {
        return profiles;
    }

    public Pages pages() {
        return pages;
    }

    public Sections sections() {
        return sections;
    }

    public WorldBoards boards() {
        return boards;
    }

    /** The website's port while it runs, or 0. */
    public int webPort() {
        WebModule current = web;
        return current == null || !config().web().enabled() ? 0 : current.port();
    }

    public Collector collector() {
        return collector;
    }

    public List<Definitions.Registered> registered() {
        return registered;
    }

    /** Why the database couldn't be opened, or null. */
    public String startupProblem() {
        return startupProblem;
    }

    private final class PlaceholderModule implements Module {
        private StatPlaceholders expansion;

        @Override
        public void enable() {
            expansion = new StatPlaceholders(XStats.this);
            expansion.register();
        }

        @Override
        public void disable() {
            expansion.unregister();
        }
    }
}
