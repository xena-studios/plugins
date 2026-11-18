package co.xenastudios.xlimbo;

import co.xenastudios.core.Module;
import co.xenastudios.xlimbo.api.XLimboApi;
import co.xenastudios.xlimbo.api.event.PlayerReturnEvent;
import co.xenastudios.xlimbo.network.Proxy;
import co.xenastudios.xlimbo.network.Returns;
import java.util.OptionalInt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

/**
 * xLimbo's developer API, in the services manager while xLimbo is enabled. It reads the
 * queue's snapshot and the last status answer, which already exist, so it adds nothing
 * to run. Paper only, so the main thread is the only game thread.
 */
final class Api implements XLimboApi, Module {

    private final XLimbo xlimbo;

    Api(XLimbo xlimbo) {
        this.xlimbo = xlimbo;
    }

    @Override
    public void enable() {
        xlimbo.getServer().getServicesManager().register(XLimboApi.class, this, xlimbo, ServicePriority.Normal);
    }

    @Override
    public void disable() {
        xlimbo.getServer().getServicesManager().unregister(XLimboApi.class, this);
    }

    @Override
    public boolean isServerCheckEnabled() {
        return xlimbo.config().serverCheck().enabled();
    }

    @Override
    public boolean isMainServerUp() {
        Returns returns = xlimbo.returns();
        return returns != null && isServerCheckEnabled() && Boolean.TRUE.equals(returns.up());
    }

    @Override
    public OptionalInt getQueuePosition(Player player) {
        Returns returns = xlimbo.returns();
        Integer position = returns == null ? null : returns.positions().get(player.getUniqueId());
        return position == null ? OptionalInt.empty() : OptionalInt.of(position);
    }

    @Override
    public int getQueueSize() {
        Returns returns = xlimbo.returns();
        return returns == null ? 0 : returns.positions().size();
    }

    @Override
    public void sendBack(Player player) {
        onMainThread(() -> send(player));
    }

    @Override
    public void sendBackAll() {
        onMainThread(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (xlimbo.limbo().isLimbo(player.getWorld())) {
                    send(player);
                }
            }
        });
    }

    // Main thread: the queue and the return history are main-thread state.
    private void send(Player player) {
        if (!player.isOnline()) {
            return;
        }
        Returns returns = xlimbo.returns();
        if (returns != null) {
            returns.enqueue(player.getUniqueId());
        } else if (xlimbo.mayReturn(player, PlayerReturnEvent.Cause.PLUGIN)) {
            xlimbo.returnHistory().sent(player.getUniqueId(), System.currentTimeMillis());
            xlimbo.config().autoJoin().message().send(player);
            Proxy.connect(xlimbo, player, xlimbo.config().mainServer());
        }
    }

    private void onMainThread(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getGlobalRegionScheduler().execute(xlimbo, action);
        }
    }
}
