package co.xenastudios.xlimbo.world;

import co.xenastudios.core.Module;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xlimbo.XLimbo;
import co.xenastudios.xlimbo.config.XLConfig;
import co.xenastudios.xlimbo.network.Returns;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Repeats a hint on every player's action bar, or their place in line while they wait to go back. */
public final class ActionBar implements Module {

    /** Stands in for each player's place in line until it's swapped in. */
    private static final String POSITION = "\uE000";

    private final XLimbo xlimbo;
    private final int interval;
    private ScheduledTask task;

    public ActionBar(XLimbo xlimbo, int interval) {
        this.xlimbo = xlimbo;
        this.interval = interval;
    }

    @Override
    public void enable() {
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(xlimbo, scheduled -> show(), interval, interval);
    }

    @Override
    public void disable() {
        task.cancel();
    }

    private void show() {
        XLConfig config = xlimbo.config();
        // Parsed once per message, not per player.
        Component hint = config.actionBar().enabled() ? config.actionBar().message().chat() : null;
        Returns returns = xlimbo.returns();
        Map<UUID, Integer> positions = returns == null || config.queue().actionBar().isEmpty() ? Map.of() : returns.positions();
        // The line template is parsed once; each player's number is swapped in after.
        Component line = positions.isEmpty() ? null : config.queue().actionBar().chat(
            Placeholders.text("position", POSITION), Placeholders.text("total", positions.size()));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!xlimbo.limbo().isLimbo(player.getWorld())) {
                continue;
            }
            Integer position = positions.get(player.getUniqueId());
            if (position != null) {
                player.sendActionBar(line.replaceText(TextReplacementConfig.builder()
                    .matchLiteral(POSITION).replacement(String.valueOf(position)).build()));
            } else if (hint != null) {
                player.sendActionBar(hint);
            }
        }
    }
}
