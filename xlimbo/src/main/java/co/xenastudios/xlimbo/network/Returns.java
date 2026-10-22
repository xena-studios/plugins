package co.xenastudios.xlimbo.network;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xlimbo.XLimbo;
import co.xenastudios.xlimbo.config.Settings;
import co.xenastudios.xlimbo.config.XLConfig;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Sends players back to the main server on their own: after auto-join's delay, or as
 * soon as server-check sees the main server up, a few per second so it isn't flooded.
 * Everything but the status ping runs on the main thread.
 */
public final class Returns extends ListenerModule {

    /** A player sent back who's still here this long after has been bounced back. */
    private static final long BOUNCE_MILLIS = 15_000;
    /** Arrivals while the main server is up wait this long, in case they were sent here on purpose. */
    private static final long GRACE_MILLIS = 5_000;
    /** Automatic tries per player in {@link #TRIES_WINDOW_MILLIS}, so a player the main server keeps refusing isn't bounced forever. */
    private static final int MAX_TRIES = 3;
    private static final long TRIES_WINDOW_MILLIS = 300_000;

    private final XLimbo xlimbo;
    private final Set<UUID> queue = new LinkedHashSet<>();
    private final Map<UUID, Long> arrived = new HashMap<>();
    private final Map<UUID, Deque<Long>> tries = new HashMap<>();
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
    }

    @Override
    public void enable() {
        super.enable();
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            arrived.put(player.getUniqueId(), now);
        }
        tick = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> tick(), 20, 20);
        ping = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> ping(), 0, 1, TimeUnit.SECONDS);
        xlimbo.returns(this);
    }

    @Override
    public void disable() {
        super.disable();
        tick.cancel();
        ping.cancel();
        xlimbo.returns(null);
    }

    /** Each queued player's place in line, from 1, and how long the line is. */
    public Map<UUID, Integer> positions() {
        return positions;
    }

    /** A player sent themselves with /join: out of line, and not sent again while they connect. */
    public void sentByHand(UUID player) {
        queue.remove(player);
        tries.computeIfAbsent(player, key -> new ArrayDeque<>()).addLast(System.currentTimeMillis());
        publishPositions();
    }

    private long lastPing;

    // Off the main thread: asks the main server for its status every interval.
    private void ping() {
        Settings.ServerCheck check = xlimbo.config().serverCheck();
        if (!check.enabled()) {
            up = Boolean.TRUE;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPing < check.intervalSeconds() * 1000L - 500) {
            return;
        }
        lastPing = now;
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
            if (queue.contains(id) || !xlimbo.limbo().isLimbo(player.getWorld()) || recentlySent(id, now) || triedTooOften(id, now)) {
                continue;
            }
            long here = now - arrived.getOrDefault(id, now);
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
            tries.computeIfAbsent(id, key -> new ArrayDeque<>()).addLast(now);
            config.autoJoin().message().send(player);
            Proxy.connect(plugin, player, config.mainServer());
        }
        publishPositions();
        tries.values().removeIf(times -> {
            while (!times.isEmpty() && now - times.peekFirst() > TRIES_WINDOW_MILLIS) {
                times.pollFirst();
            }
            return times.isEmpty();
        });
    }

    private boolean recentlySent(UUID id, long now) {
        Deque<Long> times = tries.get(id);
        return times != null && !times.isEmpty() && now - times.peekLast() < BOUNCE_MILLIS;
    }

    private boolean triedTooOften(UUID id, long now) {
        Deque<Long> times = tries.get(id);
        if (times == null) {
            return false;
        }
        int recent = 0;
        for (long time : times) {
            if (now - time <= TRIES_WINDOW_MILLIS) {
                recent++;
            }
        }
        return recent >= MAX_TRIES;
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

    @EventHandler
    void onJoin(PlayerJoinEvent event) {
        arrived.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        arrived.remove(id);
        queue.remove(id);
    }
}
