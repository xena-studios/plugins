package co.xenastudios.xmoney.listen;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xmoney.XMoney;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * A joining player's account: made with the starting balance the first time, and their name
 * kept up to date after a name change. Early, so other plugins' join handlers find it. The
 * only listener xMoney has: balances are all in memory already, so there's nothing to load
 * on join or let go of on quit.
 */
public final class Joins extends ListenerModule {

    private final XMoney money;

    public Joins(XMoney plugin) {
        super(plugin);
        this.money = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        money.ledger().open(player.getUniqueId(), player.getName(), money.bank().start());
    }
}
