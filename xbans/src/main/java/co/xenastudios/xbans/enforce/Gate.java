package co.xenastudios.xbans.enforce;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xbans.Power;
import co.xenastudios.xbans.XBans;
import co.xenastudios.xbans.config.XBConfig;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.storage.Store;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Keeps banned players out, from memory alone: no database query on the way in.
 *
 * <p>Checked three times, each a couple of map lookups. At pre-login, off the server's
 * threads: the main check, which waits for the bans to load if the server has only just
 * started. When the connection finishes configuring, and again at join, for a ban made
 * while the player was connecting: the ban is in memory before anyone is kicked, so one
 * made at any moment is caught by one of these or by the kick.
 */
public final class Gate extends ListenerModule {

    /** How long a login waits for the bans to load just after a start. */
    static final long WAIT_SECONDS = 10;
    /** Players online on one IP at which the console is told a proxy may not be forwarding addresses. */
    static final int SHARED_IP_WARNING = 8;
    public static final String NOTIFY_BANNED_JOIN = "xbans.notify.banned-join";
    public static final String NOTIFY_ALTS = "xbans.notify.alts";

    private final XBans xbans;
    private volatile boolean warnedShared;

    public Gate(XBans xbans) {
        super(xbans);
        this.xbans = xbans;
    }

    // Highest, so a plugin that allows logins later can't let a banned player back in.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        Store.Backend backend = waitForBans();
        XBConfig config = xbans.config();
        if (backend == null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, config.screens().notReady().render());
            return;
        }
        Entry ban = banned(backend, event.getUniqueId(), event.getAddress());
        if (ban != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, xbans.screen(ban, System.currentTimeMillis()));
            tellStaff(event.getName(), ban);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void validate(PlayerConnectionValidateLoginEvent event) {
        if (!event.isAllowed() || !(event.getConnection() instanceof PlayerConfigurationConnection configuring)) {
            return;
        }
        UUID id = configuring.getProfile().getId();
        if (id == null) {
            return;
        }
        Store.Backend backend = xbans.store().backend();
        if (backend == null) {
            event.kickMessage(xbans.config().screens().notReady().render());
            return;
        }
        Entry ban = banned(backend, id, address(configuring));
        if (ban != null) {
            event.kickMessage(xbans.screen(ban, System.currentTimeMillis()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Store.Backend backend = xbans.store().backend();
        InetSocketAddress socket = player.getAddress();
        InetAddress address = socket == null ? null : socket.getAddress();
        String ip = backend == null || !xbans.config().ips().enabled() ? null : backend.keys().key(address);
        int sharing = xbans.sessions().joined(player.getUniqueId(), ip);
        if (sharing >= SHARED_IP_WARNING && !warnedShared) {
            warnedShared = true;
            xbans.getLogger().warning(sharing + " players online are on the same IP. If they come through a proxy, it isn't"
                + " forwarding their real addresses, and an IP ban would shut them all out: turn on the proxy's forwarding"
                + " (Velocity's player-info-forwarding, or BungeeCord's ip_forward).");
        }
        Entry ban = backend == null ? null : banned(backend, player.getUniqueId(), address);
        if (backend == null || ban != null) {
            Component screen = ban == null ? xbans.config().screens().notReady().render()
                : xbans.screen(ban, System.currentTimeMillis());
            event.joinMessage(null);
            xbans.sessions().quietQuit(player.getUniqueId());
            player.kick(screen);
            return;
        }
        xbans.store().seen(player.getUniqueId(), player.getName(), ip, Power.weight(player), player.hasPermission(Power.EXEMPT));
        if (ip != null && xbans.config().ips().altAlerts() && !xbans.config().broadcasts().altJoin().isEmpty()) {
            xbans.alts().alertOnJoin(player.getUniqueId(), player.getName(), ip);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        xbans.sessions().left(id);
        if (xbans.sessions().takeQuiet(id)) {
            event.quitMessage(null);
        }
    }

    private Entry banned(Store.Backend backend, UUID id, InetAddress address) {
        String ip = xbans.config().ips().enabled() ? backend.keys().key(address) : null;
        return backend.active().ban(id, ip, xbans.config().scopes().serverName(), System.currentTimeMillis());
    }

    private Store.Backend waitForBans() {
        Store.Backend backend = xbans.store().backend();
        if (backend != null) {
            return backend;
        }
        try {
            xbans.store().ready().get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException | java.util.concurrent.ExecutionException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return xbans.store().backend();
    }

    private static InetAddress address(PlayerConnection connection) {
        return connection.getAddress() instanceof InetSocketAddress socket ? socket.getAddress() : null;
    }

    private void tellStaff(String name, Entry ban) {
        var message = xbans.config().broadcasts().bannedJoin();
        if (message.isEmpty()) {
            return;
        }
        var placeholders = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] {
            xbans.texts().of(ban, System.currentTimeMillis()), Placeholders.text("player", name)};
        xbans.tasks().global(() -> {
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission(NOTIFY_BANNED_JOIN)) {
                    message.send(staff, placeholders);
                }
            }
        });
    }
}
