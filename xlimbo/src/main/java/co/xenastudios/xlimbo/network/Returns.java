package co.xenastudios.xlimbo.network;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xlimbo.XLimbo;
import co.xenastudios.xlimbo.config.Settings;
import co.xenastudios.xlimbo.config.XLConfig;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Sends players back to the main server on their own: after auto-join's delay, or as
 * soon as server-check sees the main server up, a few per second so it isn't flooded.
 * Everything but the status ping runs on the main thread.
 */
public final class Returns extends ListenerModule {

    /** Arrivals while the main server is up wait this long, in case they were sent here on purpose. */
    private static final long GRACE_MILLIS = 5_000;

    private final XLimbo xlimbo;
    private final Set<UUID> queue = new LinkedHashSet<>();
    private final ReturnHistory history;
    // Null until the first answer, so turning server-check on never sends players to a
    // server nobody has asked about yet, or announces one that never went down.
    private volatile Boolean up;
    private volatile boolean cameUp;
    private String badAddress;
    private volatile Map<UUID, Integer> positions = Map.of();
    private ScheduledTask tick;
    private ScheduledTask ping;

    public Returns(XLimbo xlimbo) {
        super(xlimbo);
        this.xlimbo = xlimbo;
        this.history = xlimbo.returnHistory();
    }

    @Override
    public void enable() {
        super.enable();
        long now = System.currentTimeMillis();
        // Kept from before a reload restarted the queue; only players nobody saw arrive start now.
        for (Player player : Bukkit.getOnlinePlayers()) {
            history.arrivedIfUnknown(player.getUniqueId(), now);
        }
        history.prune(now);
        tick = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> tick(), 20, 20);
        Settings.ServerCheck check = xlimbo.config().serverCheck();
        if (check.enabled()) {
            // Only while server-check is on, at its own interval: the module is keyed by it,
            // so changing either on reload starts this again.
            ping = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> ping(), 0, check.intervalSeconds(), TimeUnit.SECONDS);
        } else {
            // Auto-join only: nothing to ask, so the main server counts as up.
            up = Boolean.TRUE;
        }
        xlimbo.returns(this);
    }

    @Override
    public void disable() {
        super.disable();
        tick.cancel();
        if (ping != null) {
            ping.cancel();
        }
        xlimbo.returns(null);
    }

    /** Each queued player's place in line, from 1, and how long the line is. */
    public Map<UUID, Integer> positions() {
        return positions;
    }

    /** A player sent themselves with /join: out of line, and not sent again while they connect. */
    public void sentByHand(UUID player) {
        queue.remove(player);
        history.tried(player, System.currentTimeMillis());
        publishPositions();
    }

    // Off the main thread: asks the main server for its status, every interval-seconds.
    private void ping() {
        Settings.ServerCheck check = xlimbo.config().serverCheck();
        InetSocketAddress address;
        try {
            address = StatusPing.parse(check.address());
            badAddress = null;
        } catch (IllegalArgumentException e) {
            if (!check.address().equals(badAddress)) {
                badAddress = check.address();
                plugin.getLogger().warning("config.yml: server-check.address " + e.getMessage() + "; nobody is sent back until it's fixed.");
            }
            up = Boolean.FALSE;
            return;
        }
        boolean answered = StatusPing.isUp(address);
        Boolean before = up;
        if (answered && Boolean.FALSE.equals(before)) {
            cameUp = true;
        }
        up = answered;
    }

    // Once a second on the main thread: fills the line, then sends the next few.
    private void tick() {
        XLConfig config = xlimbo.config();
        long now = System.currentTimeMillis();
        if (!Boolean.TRUE.equals(up)) {
            queue.clear();
            publishPositions();
            return;
        }
        boolean announce = cameUp;
        cameUp = false;
        if (announce && config.serverCheck().enabled()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                config.serverCheck().messageUp().send(player);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (queue.contains(id) || !xlimbo.limbo().isLimbo(player.getWorld()) || history.recentlySent(id, now) || history.triedTooOften(id, now)) {
                continue;
            }
            long here = history.here(id, now);
            // With server-check, everyone goes back once it's up: at once when it comes
            // back, after a moment for someone who arrives while it's up. Without, after
            // auto-join's delay.
            boolean due = config.serverCheck().enabled()
                ? announce || here >= GRACE_MILLIS
                : config.autoJoin().enabled() && here >= config.autoJoin().delaySeconds() * 1000L;
            if (due) {
                queue.add(id);
            }
        }
        int sending = config.queue().perSecond() == 0 ? queue.size() : config.queue().perSecond();
        Iterator<UUID> next = queue.iterator();
        while (sending > 0 && next.hasNext()) {
            UUID id = next.next();
            next.remove();
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;
            }
            sending--;
            history.tried(id, now);
            config.autoJoin().message().send(player);
            Proxy.connect(plugin, player, config.mainServer());
        }
        publishPositions();
        history.prune(now);
    }

    // A snapshot for the action bar, which reads it without touching the queue.
    private void publishPositions() {
        if (queue.isEmpty()) {
            positions = Map.of();
            return;
        }
        Map<UUID, Integer> snapshot = new HashMap<>(queue.size() * 2);
        int place = 1;
        for (UUID id : queue) {
            snapshot.put(id, place++);
        }
        positions = snapshot;
    }

    // Arrivals are recorded by the plugin, whether or not returns are on.
    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        queue.remove(event.getPlayer().getUniqueId());
    }
}
