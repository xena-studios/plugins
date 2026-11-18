package co.xenastudios.xbans.ip;

import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xbans.XBans;
import co.xenastudios.xbans.enforce.Gate;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.storage.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** The staff alert when a player joins who shares an IP with a banned account. */
public final class Alts {

    private final XBans xbans;

    public Alts(XBans xbans) {
        this.xbans = xbans;
    }

    /** Looks for banned alts off the server's threads, after the join: never holds it up. */
    public void alertOnJoin(UUID player, String name, String ip) {
        xbans.store().alts(player, null).thenAccept(alts -> {
            Store.Backend backend = xbans.store().backend();
            if (backend == null) {
                return;
            }
            String server = xbans.config().scopes().serverName();
            long now = System.currentTimeMillis();
            List<String> banned = new ArrayList<>();
            for (Store.IpUse alt : alts) {
                Entry ban = backend.active().ban(alt.id(), null, server, now);
                if (ban != null) {
                    banned.add(alt.name());
                }
            }
            if (banned.isEmpty()) {
                return;
            }
            Message message = xbans.config().broadcasts().altJoin();
            var placeholders = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] {
                Placeholders.text("player", name), Placeholders.text("alts", String.join(", ", banned))};
            xbans.tasks().global(() -> {
                Bukkit.getConsoleSender().sendMessage(message.chat(placeholders));
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission(Gate.NOTIFY_ALTS)) {
                        message.send(staff, placeholders);
                    }
                }
            });
        }).exceptionally(e -> {
            xbans.getLogger().warning("Couldn't check " + name + "'s alts: " + e.getMessage());
            return null;
        });
    }
}
