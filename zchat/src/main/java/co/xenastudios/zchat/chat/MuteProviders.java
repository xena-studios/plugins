package co.xenastudios.zchat.chat;

import co.xenastudios.zchat.api.MessageKind;
import co.xenastudios.zchat.api.MuteProvider;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The mute providers other plugins registered through the API. Asked on every message,
 * on many threads at once, so they're kept in an array that's replaced whole on each
 * change: asking reads it once and takes no lock, and with none registered costs one
 * read.
 */
public final class MuteProviders {

    private record Registered(Plugin plugin, MuteProvider provider) {}

    private static final Registered[] NONE = new Registered[0];

    private final Logger logger;
    private volatile Registered[] registered = NONE;

    public MuteProviders(Logger logger) {
        this.logger = logger;
    }

    public synchronized void add(Plugin plugin, MuteProvider provider) {
        for (Registered entry : registered) {
            if (entry.provider == provider) {
                return;
            }
        }
        Registered[] more = Arrays.copyOf(registered, registered.length + 1);
        more[registered.length] = new Registered(plugin, provider);
        registered = more;
    }

    public synchronized void remove(MuteProvider provider) {
        registered = Arrays.stream(registered).filter(entry -> entry.provider != provider).toArray(Registered[]::new);
    }

    /**
     * What the first provider with a reason says, or null if the player may send it (or
     * there are no providers).
     */
    public Component reason(Player player, MessageKind kind) {
        Registered[] all = registered;
        for (Registered entry : all) {
            if (!entry.plugin.isEnabled()) {
                remove(entry.provider);
                continue;
            }
            try {
                Component reason = entry.provider.muteReason(player, kind);
                if (reason != null) {
                    return reason;
                }
            } catch (RuntimeException | LinkageError e) {
                // A broken provider mustn't break chat: it's dropped, and said once.
                remove(entry.provider);
                logger.log(Level.SEVERE, entry.plugin.getName() + "'s mute provider failed, so zChat stopped asking it", e);
            }
        }
        return null;
    }

    public int size() {
        return registered.length;
    }
}
