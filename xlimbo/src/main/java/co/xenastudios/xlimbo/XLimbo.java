package co.xenastudios.xlimbo;

import co.xenastudios.core.Setup;
import co.xenastudios.core.XenaPlugin;
import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.config.ConfigLoader;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xlimbo.api.event.PlayerReturnEvent;
import co.xenastudios.xlimbo.config.Settings;
import co.xenastudios.xlimbo.config.XLConfig;
import co.xenastudios.xlimbo.network.Proxy;
import co.xenastudios.xlimbo.network.ReturnHistory;
import co.xenastudios.xlimbo.network.Returns;
import co.xenastudios.xlimbo.protect.Protections;
import co.xenastudios.xlimbo.world.ActionBar;
import co.xenastudios.xlimbo.world.Arrivals;
import co.xenastudios.xlimbo.world.HideOthers;
import co.xenastudios.xlimbo.world.LimboWorld;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.permissions.PermissionDefault;

/** xLimbo: never drop a player again. Not final: MockBukkit subclasses it in tests. */
public class XLimbo extends XenaPlugin<XLConfig> {

    private LimboWorld limbo;
    private final Map<UUID, Long> joinCooldowns = new ConcurrentHashMap<>();
    private final ReturnHistory returnHistory = new ReturnHistory();
    private volatile Returns returns;
    private boolean worldPending;
    private Api api;

    @Override
    protected ConfigSpec<XLConfig> configSpec() {
        return XLConfig.SPEC;
    }

    @Override
    protected int bStatsId() {
        return 34867;
    }

    @Override
    protected void setup(Setup setup, XLConfig config) {
        // The developer API, first so it's there even if a later feature fails to set up.
        if (api == null) {
            api = new Api(this);
        }
        setup.module("api", () -> api);
        Proxy.open(this);
        // xLimbo 1.x made its world without a marker; the one its config names is ours.
        limbo().adoptUnmarked(configCarriedFrom() == 1);
        setup.module("arrivals", () -> new Arrivals(this));
        if (config.player().hideOthers()) {
            setup.module("hide-others", () -> new HideOthers(this));
        }
        if (config.autoJoin().enabled() || config.serverCheck().enabled()) {
            // Keyed by server-check's interval, so changing it on reload restarts the ping.
            String key = config.serverCheck().enabled() ? "returns.check." + config.serverCheck().intervalSeconds() : "returns.auto";
            setup.module(key, () -> new Returns(this));
        }
        boolean queueBar = !config.queue().actionBar().isEmpty() && (config.autoJoin().enabled() || config.serverCheck().enabled());
        if (config.actionBar().enabled() || queueBar) {
            int interval = config.actionBar().intervalTicks();
            setup.module("action-bar." + interval, () -> new ActionBar(this, interval));
        }
        Protections.register(setup, this, config.protections());
        joinCommand(setup, config.joinCommand());
        syncWorld(config.world());
        // Every setting applies on reload: players already waiting get creative and flight
        // too, if they've just been turned on.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (limbo().isLimbo(player.getWorld())) {
                Arrivals.equip(player, config);
            }
        }
    }

    // xLimbo loads before the server's worlds, so its generator is ready for one named in
    // bukkit.yml; its own world can only be made once they're loaded.
    private void syncWorld(Settings.World settings) {
        if (Bukkit.getWorlds().isEmpty()) {
            if (!worldPending) {
                worldPending = true;
                Bukkit.getGlobalRegionScheduler().run(this, task -> limbo().sync(config().world()));
            }
            return;
        }
        limbo().sync(settings);
    }

    private void joinCommand(Setup setup, Settings.JoinCommand settings) {
        if (!settings.enabled()) {
            return;
        }
        setup.command(CommandSpec.named("join", settings)
            .permissionDefault(PermissionDefault.TRUE)
            .description("Go back to the main server.")
            .executes(invocation -> {
                Player player = invocation.player();
                Settings.JoinCommand current = config().joinCommand();
                long now = System.currentTimeMillis();
                Long last = joinCooldowns.get(player.getUniqueId());
                long left = last == null ? 0 : last + current.cooldownSeconds() * 1000L - now;
                if (left > 0) {
                    throw new CommandFailure(current.messageCooldown(), Placeholders.text("seconds", (left + 999) / 1000));
                }
                // A plugin keeping them here doesn't use up the cooldown.
                if (!mayReturn(player, PlayerReturnEvent.Cause.JOIN_COMMAND)) {
                    return;
                }
                joinCooldowns.put(player.getUniqueId(), now);
                Returns waiting = returns;
                // Commands run on the main thread, as the queue does.
                if (waiting != null) {
                    waiting.sentByHand(player.getUniqueId());
                } else {
                    returnHistory.sent(player.getUniqueId(), now);
                }
                current.messageSending().send(player, Placeholders.text("server", config().mainServer()));
                Proxy.connect(this, player, config().mainServer());
            }));
    }

    // Reloaded in place (/reload confirm, a plugin manager): a world made with this
    // instance's generator would keep it, and its class loader, after the new one starts.
    // A server stopping saves and unloads its worlds itself.
    @Override
    protected void shutdown() {
        if (limbo != null && !Bukkit.isStopping()) {
            limbo.letGo();
        }
    }

    /** The generator for a world named in bukkit.yml with "generator: xLimbo". */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        XLConfig current = config();
        if (current == null) {
            // Asked before enabling: read the floor from the file, or use the defaults.
            try {
                // Read only: carrying it forward and saving it is the real load's job, which
                // also needs to see that it was carried, to take over 1.x's world.
                current = ConfigLoader.read(XLConfig.SPEC, getDataFolder().toPath().resolve("config.yml")).config();
            } catch (Exception e) {
                current = XLConfig.DEFAULTS;
            }
        }
        return limbo().generatorFor(current.world());
    }

    /**
     * Whether another plugin lets the player go back to the main server, asked just before
     * they're sent. Main thread. The event is only built when something listens for it.
     */
    public boolean mayReturn(Player player, PlayerReturnEvent.Cause cause) {
        return PlayerReturnEvent.getHandlerList().getRegisteredListeners().length == 0
            || new PlayerReturnEvent(player, config().mainServer(), cause).callEvent();
    }

    /** Forgets a player who left, but not when they were sent back: a refused player comes back. */
    public void forget(UUID player) {
        joinCooldowns.remove(player);
        returnHistory.left(player);
    }

    /** When players arrived and were sent back, kept across reloads. Main thread only. */
    public ReturnHistory returnHistory() {
        return returnHistory;
    }

    public LimboWorld limbo() {
        if (limbo == null) {
            limbo = new LimboWorld(this);
        }
        return limbo;
    }

    /** The return queue, while automatic returns are on; otherwise null. */
    public Returns returns() {
        return returns;
    }

    public void returns(Returns returns) {
        this.returns = returns;
    }
}
