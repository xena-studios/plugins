package co.xenastudios.xteleport;

import co.xenastudios.core.Module;
import co.xenastudios.core.Setup;
import co.xenastudios.core.XenaPlugin;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.player.Profiles;
import co.xenastudios.xteleport.command.AdminCommands;
import co.xenastudios.xteleport.command.HomeCommands;
import co.xenastudios.xteleport.command.RtpCommand;
import co.xenastudios.xteleport.command.TpaCommands;
import co.xenastudios.xteleport.command.WarpCommands;
import co.xenastudios.xteleport.config.XTConfig;
import co.xenastudios.xteleport.home.Homes;
import co.xenastudios.xteleport.rtp.Rtp;
import co.xenastudios.xteleport.storage.Store;
import co.xenastudios.xteleport.teleport.Teleporter;
import co.xenastudios.xteleport.tpa.Requests;
import co.xenastudios.xteleport.warp.Places;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.permissions.PermissionDefault;

/** xTeleport: every teleport your server needs. Not final: MockBukkit subclasses it in tests. */
public class XTeleport extends XenaPlugin<XTConfig> {

    /** Any number of homes. */
    public static final String UNLIMITED_HOMES = "xteleport.homes.unlimited";
    /** Send requests to players who turned them off. */
    public static final String TPTOGGLE_BYPASS = "xteleport.tptoggle.bypass";

    private Store store;
    private Homes homes;
    private Places places;
    private Requests requests;
    private Teleporter teleporter;
    private Rtp rtp;
    private Profiles profiles;
    private Api api;

    @Override
    protected ConfigSpec<XTConfig> configSpec() {
        return XTConfig.SPEC;
    }

    @Override
    protected List<String> adminAliases() {
        return List.of("xtp");
    }

    @Override
    protected void setup(Setup setup, XTConfig config) {
        if (store == null) {
            start(config);
        }
        rtp.configure(config.rtp(), getLogger());
        setup.module("players", () -> new PlayerListener(this));
        XTConfig.Spawn spawn = config.spawn();
        if (spawn.onFirstJoin() || spawn.onEveryJoin() || spawn.onRespawn()) {
            setup.module("spawn", () -> new SpawnListener(this));
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            setup.module("placeholders", PlaceholderModule::new);
        }
        setup.module("api", () -> api);
        HomeCommands.register(setup, this, config.commands());
        TpaCommands.register(setup, this, config.commands());
        WarpCommands.register(setup, this, config.commands());
        RtpCommand.register(setup, this, config.commands());
        AdminCommands.specs(this).forEach(setup::subcommand);
        for (String permission : List.of(Teleporter.WARMUP_BYPASS, Teleporter.COOLDOWN_BYPASS, Teleporter.COST_BYPASS,
            UNLIMITED_HOMES, TPTOGGLE_BYPASS)) {
            setup.permission(permission, PermissionDefault.OP);
        }
    }

    // Once. The database opens on its own thread, so a slow MySQL can't hold up startup;
    // commands say "not available" for the moment until it's open.
    private void start(XTConfig config) {
        store = Store.open(config.storage(), getDataFolder().toPath(), getLogger());
        homes = new Homes(store, id -> Bukkit.getPlayer(id) != null);
        places = new Places(store);
        requests = new Requests();
        teleporter = new Teleporter(this);
        rtp = new Rtp(this);
        profiles = new Profiles(tasks());
        api = new Api(this);
        store.opened()
            .thenCompose(database -> store.loadServer())
            .whenComplete((data, error) -> {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    getLogger().severe(cause.getMessage());
                    getLogger().severe("xTeleport can't keep homes or warps without its database. Fix storage in config.yml and restart.");
                    return;
                }
                places.load(data);
                getLogger().info("Loaded " + data.warps().size() + (data.warps().size() == 1 ? " warp" : " warps")
                    + (data.spawn() == null ? "" : " and the spawn") + " from " + store.description() + ".");
                // Players who joined while it opened (a reload with players online).
                Bukkit.getOnlinePlayers().forEach(player -> homes.of(player.getUniqueId()));
            });
    }

    private final class PlaceholderModule implements Module {
        private co.xenastudios.xteleport.hook.TeleportPlaceholders expansion;

        @Override
        public void enable() {
            expansion = new co.xenastudios.xteleport.hook.TeleportPlaceholders(XTeleport.this);
            expansion.register();
        }

        @Override
        public void disable() {
            expansion.unregister();
        }
    }

    @Override
    protected void shutdown() {
        if (store == null) {
            return;
        }
        teleporter.clear();
        requests.clear();
        store.close(10, TimeUnit.SECONDS);
    }

    /**
     * Loads a chunk in the background: every chunk xTeleport reads goes through here.
     * Overridden in tests, where MockBukkit can't load chunks asynchronously.
     *
     * @param generate false completes with null for a chunk that was never generated
     */
    public CompletableFuture<Chunk> loadChunk(World world, int chunkX, int chunkZ, boolean generate) {
        return world.getChunkAtAsync(chunkX, chunkZ, generate);
    }

    /**
     * Moves a player, with the {@code COMMAND} cause that xUtilities' {@code /back} records.
     * Every teleport xTeleport makes goes through here. Overridden in tests, where MockBukkit
     * can't {@code teleportAsync}.
     */
    public CompletableFuture<Boolean> move(Player player, Location to) {
        return player.teleportAsync(to, PlayerTeleportEvent.TeleportCause.COMMAND);
    }

    /** Whether homes and warps can be used: the database is open and the warps read. */
    public boolean ready() {
        return store != null && store.ready() && places.loaded();
    }

    public Store store() {
        return store;
    }

    public Homes homes() {
        return homes;
    }

    public Places places() {
        return places;
    }

    public Requests requests() {
        return requests;
    }

    public Teleporter teleporter() {
        return teleporter;
    }

    public Rtp rtp() {
        return rtp;
    }

    public Profiles profiles() {
        return profiles;
    }
}
