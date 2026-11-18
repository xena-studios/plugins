package co.xenastudios.xbans.enforce;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.core.Module;
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
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.RegisteredListener;

/**
 * Keeps muted players quiet: chat, the configured commands (by any alias or namespace),
 * and, while they're on, signs, books and anvil renames. Each check is a map lookup for the
 * player and their IP; only a muted player's command is looked up further.
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

        // Lowest, so the message is stopped before any chat plugin formats or sends it. Already
        // cancelled means the older chat events' guard stopped it and told them.
        @EventHandler(priority = EventPriority.LOWEST)
        public void chat(AsyncChatEvent event) {
            Entry mute = muteOf(xbans, event.getPlayer());
            if (mute != null) {
                if (!event.isCancelled()) {
                    tell(xbans, event.getPlayer(), mute);
                }
                event.setCancelled(true);
            }
        }

        // And again last, so a plugin that un-cancels chat in between can't let it through.
        @EventHandler(priority = EventPriority.HIGHEST)
        public void chatAgain(AsyncChatEvent event) {
            if (!event.isCancelled() && muteOf(xbans, event.getPlayer()) != null) {
                event.setCancelled(true);
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

    /**
     * Chat on the older events, {@link AsyncPlayerChatEvent} and {@link PlayerChatEvent}.
     * Paper fires those in full before {@link AsyncChatEvent}, so a plugin relaying them
     * (a Discord bridge, a script) would relay a muted player. Each guard cancels at the
     * lowest priority and again at the highest.
     *
     * <p>The asynchronous one is always guarded: Paper then fires it for every message, off
     * the server's threads, which costs about one event per message. The synchronous one
     * can't be: a listener on it moves every chat message onto the main thread. So it's
     * guarded only while another plugin listens to it, and each message's asynchronous event
     * checks that first. Paper fires the synchronous one after the asynchronous one, and
     * reads its listeners when it does, so a plugin that started listening a moment ago
     * (a script loaded late, a bridge that just connected) is guarded from its first message,
     * and the guard goes once it stops.
     */
    public static final class LegacyChat implements Module {

        private final XBans xbans;
        private final Guard async = new Guard(AsyncPlayerChatEvent.class, true);
        private final Guard sync = new Guard(PlayerChatEvent.class, false);

        public LegacyChat(XBans xbans) {
            this.xbans = xbans;
        }

        @Override
        public void enable() {
            async.on();
        }

        @Override
        public void disable() {
            async.off();
            sync.off();
        }

        /** Puts the synchronous guard on while another plugin listens to its event, and takes it off once none does. */
        void refresh() {
            sync.refresh();
        }

        boolean guarding(Class<? extends Event> type) {
            return (type == AsyncPlayerChatEvent.class ? async : sync).on;
        }

        private final class Guard implements Listener {

            private final Class<? extends PlayerEvent> type;
            // The asynchronous guard: each message checks the synchronous one is on when it's needed.
            private final boolean checks;
            private volatile boolean on;

            Guard(Class<? extends PlayerEvent> type, boolean checks) {
                this.type = type;
                this.checks = checks;
            }

            synchronized void refresh() {
                boolean others = false;
                for (RegisteredListener listener : handlers().getRegisteredListeners()) {
                    if (listener.getPlugin() != xbans) {
                        others = true;
                        break;
                    }
                }
                if (others) {
                    on();
                } else {
                    off();
                }
            }

            synchronized void on() {
                if (!on) {
                    EventExecutor first = (listener, event) -> stop(event, true);
                    EventExecutor last = (listener, event) -> stop(event, false);
                    Bukkit.getPluginManager().registerEvent(type, this, EventPriority.LOWEST, first, xbans, false);
                    Bukkit.getPluginManager().registerEvent(type, this, EventPriority.HIGHEST, last, xbans, false);
                    on = true;
                }
            }

            synchronized void off() {
                if (on) {
                    handlers().unregister(this);
                    on = false;
                }
            }

            private HandlerList handlers() {
                return type == AsyncPlayerChatEvent.class ? AsyncPlayerChatEvent.getHandlerList() : PlayerChatEvent.getHandlerList();
            }

            private void stop(Event event, boolean first) {
                if (!type.isInstance(event) || !(event instanceof org.bukkit.event.Cancellable cancellable)) {
                    return;
                }
                if (first && checks) {
                    sync.refresh();
                }
                Player player = ((PlayerEvent) event).getPlayer();
                if (first || !cancellable.isCancelled()) {
                    Entry mute = muteOf(xbans, player);
                    if (mute != null) {
                        if (first && !cancellable.isCancelled()) {
                            tell(xbans, player, mute);
                        }
                        cancellable.setCancelled(true);
                    }
                }
            }
        }
    }

    /** Renaming items on an anvil, while mutes.block-anvils is on: a name is something others read. */
    public static final class Anvils extends ListenerModule {

        private final XBans xbans;

        public Anvils(XBans xbans) {
            super(xbans);
            this.xbans = xbans;
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void rename(PrepareAnvilEvent event) {
            String text = event.getView().getRenameText();
            if (text == null || text.isBlank() || !(event.getView().getPlayer() instanceof Player player)) {
                return;
            }
            ItemStack item = event.getInventory().getFirstItem();
            if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                && text.equals(PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()))) {
                return; // Its name as it is: a repair, not a rename.
            }
            if (muteOf(xbans, player) != null) {
                event.setResult(null);
            }
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
