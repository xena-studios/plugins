package co.xenastudios.xbans.enforce;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xbans.XBans;
import co.xenastudios.xbans.config.XBConfig;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.storage.Store;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;

/**
 * Keeps muted players quiet: chat, the configured commands (by any alias or namespace),
 * and, while they're on, signs and books. Each check is a map lookup for the player and
 * their IP; only a muted player's command is looked up further.
 */
public final class MuteGuard {

    private MuteGuard() {}

    /** The mute on this online player now, by account or IP, or null. */
    static Entry muteOf(XBans xbans, Player player) {
        Store.Backend backend = xbans.store().backend();
        if (backend == null) {
            return null;
        }
        return backend.active().mute(player.getUniqueId(), xbans.sessions().ip(player.getUniqueId()),
            xbans.config().scopes().serverName(), System.currentTimeMillis());
    }

    static void tell(XBans xbans, Player player, Entry mute) {
        XBConfig.Screens screens = xbans.config().screens();
        long now = System.currentTimeMillis();
        (mute.punishment().isPermanent() ? screens.muted() : screens.tempMuted()).send(player, xbans.texts().of(mute, now));
    }

    /** Chat and commands, always on. */
    public static final class Chat extends ListenerModule {

        private final XBans xbans;
        private volatile List<String> source;
        private volatile Set<String> blocked = Set.of();

        public Chat(XBans xbans) {
            super(xbans);
            this.xbans = xbans;
        }

        // Lowest, so the message is stopped before any chat plugin formats or sends it.
        @EventHandler(priority = EventPriority.LOWEST)
        public void chat(AsyncChatEvent event) {
            Entry mute = muteOf(xbans, event.getPlayer());
            if (mute != null) {
                event.setCancelled(true);
                tell(xbans, event.getPlayer(), mute);
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void command(PlayerCommandPreprocessEvent event) {
            Entry mute = muteOf(xbans, event.getPlayer());
            if (mute == null) {
                return;
            }
            if (blocks(event.getMessage())) {
                event.setCancelled(true);
                tell(xbans, event.getPlayer(), mute);
            }
        }

        /** Whether a command line ("/msg Bob hi") is one a muted player can't use. */
        boolean blocks(String line) {
            String text = line.startsWith("/") ? line.substring(1) : line;
            int space = text.indexOf(' ');
            String label = (space < 0 ? text : text.substring(0, space)).toLowerCase(Locale.ROOT);
            if (label.isEmpty()) {
                return false;
            }
            Set<String> names = blocked();
            if (names.contains(strip(label))) {
                return true;
            }
            // An alias of a blocked command, such as /emsg for EssentialsX's /msg, or a namespaced one.
            Command command = Bukkit.getCommandMap().getCommand(label);
            if (command == null) {
                return false;
            }
            if (names.contains(strip(command.getName().toLowerCase(Locale.ROOT)))
                || names.contains(strip(command.getLabel().toLowerCase(Locale.ROOT)))) {
                return true;
            }
            for (String alias : command.getAliases()) {
                if (names.contains(strip(alias.toLowerCase(Locale.ROOT)))) {
                    return true;
                }
            }
            return false;
        }

        private Set<String> blocked() {
            List<String> current = xbans.config().mutes().blockedCommands();
            if (current != source) {
                Set<String> names = new HashSet<>();
                for (String name : current) {
                    String clean = name.strip().toLowerCase(Locale.ROOT);
                    names.add(strip(clean.startsWith("/") ? clean.substring(1) : clean));
                }
                blocked = Set.copyOf(names);
                source = current;
            }
            return blocked;
        }

        private static String strip(String label) {
            int colon = label.lastIndexOf(':');
            return colon < 0 ? label : label.substring(colon + 1);
        }
    }

    /** Sign writing, while mutes.block-signs is on. */
    public static final class Signs extends ListenerModule {

        private final XBans xbans;

        public Signs(XBans xbans) {
            super(xbans);
            this.xbans = xbans;
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void sign(SignChangeEvent event) {
            boolean written = false;
            for (Component line : event.lines()) {
                if (line != null && !PlainTextComponentSerializer.plainText().serialize(line).isBlank()) {
                    written = true;
                    break;
                }
            }
            if (!written) {
                return;
            }
            Entry mute = muteOf(xbans, event.getPlayer());
            if (mute != null) {
                event.setCancelled(true);
                tell(xbans, event.getPlayer(), mute);
            }
        }
    }

    /** Book writing and signing, while mutes.block-books is on. */
    public static final class Books extends ListenerModule {

        private final XBans xbans;

        public Books(XBans xbans) {
            super(xbans);
            this.xbans = xbans;
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void book(PlayerEditBookEvent event) {
            Entry mute = muteOf(xbans, event.getPlayer());
            if (mute != null) {
                event.setCancelled(true);
                tell(xbans, event.getPlayer(), mute);
            }
        }
    }
}
