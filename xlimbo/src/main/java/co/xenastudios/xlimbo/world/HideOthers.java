package co.xenastudios.xlimbo.world;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xlimbo.XLimbo;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Players can't see each other. Hundreds of players in one spot each tracking every
 * other is a limbo's biggest load; hidden, nobody is tracked or sent anyone's movement.
 */
public final class HideOthers extends ListenerModule {

    public HideOthers(XLimbo xlimbo) {
        super(xlimbo);
    }

    @Override
    public void enable() {
        super.enable();
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (player != other) {
                    player.hidePlayer(plugin, other);
                }
            }
        }
    }

    @Override
    public void disable() {
        super.disable();
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (player != other) {
                    player.showPlayer(plugin, other);
                }
            }
        }
    }

    // Before the player is sent to anyone, so they're never tracked at all.
    @EventHandler(priority = EventPriority.LOWEST)
    void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != joined) {
                other.hidePlayer(plugin, joined);
                joined.hidePlayer(plugin, other);
            }
        }
    }
}
