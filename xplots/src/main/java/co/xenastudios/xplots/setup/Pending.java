package co.xenastudios.xplots.setup;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Changes waiting on {@code /xplots confirm}: a previewed field, or deleting one. One per
 * sender; a new one replaces the old, and each lapses after two minutes. Main thread.
 */
public final class Pending {

    /** A change waiting to be confirmed. */
    public interface Action {
        void confirm();

        /** Called when it's cancelled, replaced or lapses: stop any preview. */
        default void dismiss() {}
    }

    private static final long LAPSE_TICKS = 2 * 60 * 20;

    private final Plugin plugin;
    private final Map<String, Action> actions = new HashMap<>();
    private final Map<String, BukkitTask> lapses = new HashMap<>();

    public Pending(Plugin plugin) {
        this.plugin = plugin;
    }

    public void put(String sender, Action action) {
        cancel(sender);
        actions.put(sender, action);
        lapses.put(sender, Bukkit.getScheduler().runTaskLater(plugin, () -> cancel(sender), LAPSE_TICKS));
    }

    /** Runs the sender's waiting change. False if there was none. */
    public boolean confirm(String sender) {
        Action action = take(sender);
        if (action == null) {
            return false;
        }
        action.dismiss();
        action.confirm();
        return true;
    }

    /** Drops the sender's waiting change. False if there was none. */
    public boolean cancel(String sender) {
        Action action = take(sender);
        if (action == null) {
            return false;
        }
        action.dismiss();
        return true;
    }

    private Action take(String sender) {
        BukkitTask lapse = lapses.remove(sender);
        if (lapse != null) {
            lapse.cancel();
        }
        return actions.remove(sender);
    }

    public void clear() {
        for (String sender : Map.copyOf(actions).keySet()) {
            cancel(sender);
        }
    }
}
