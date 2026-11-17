package co.xenastudios.xrewind.listen;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.log.Actor;
import co.xenastudios.xrewind.log.Entry;
import co.xenastudios.xrewind.log.LogQueue;
import co.xenastudios.xrewind.storage.Clock;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

/** Text players write: on signs, in chat, and as commands. One module per kind, each switched on its own. */
public final class Words {

    private Words() {}

    /** Sign text as it's written. */
    public static final class Signs extends ListenerModule {
        private final LogQueue queue;

        public Signs(Plugin plugin, LogQueue queue) {
            super(plugin);
            this.queue = queue;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        void onSign(SignChangeEvent event) {
            List<String> lines = new ArrayList<>(4);
            for (Component line : event.lines()) {
                String text = PlainTextComponentSerializer.plainText().serialize(line).strip();
                if (!text.isEmpty()) {
                    lines.add(text);
                }
            }
            if (lines.isEmpty()) {
                return;
            }
            Block block = event.getBlock();
            queue.add(new Entry.Text(Clock.now(), Actor.of(event.getPlayer()), block.getWorld().getName(), block.getX(),
                block.getY(), block.getZ(), Actions.SIGN, String.join(" | ", lines)));
        }
    }

    /** Chat messages, as sent. Off by default. */
    public static final class Chat extends ListenerModule {
        private final LogQueue queue;

        public Chat(Plugin plugin, LogQueue queue) {
            super(plugin);
            this.queue = queue;
        }

        // Off the main thread: only the player's world, not their position, is read.
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        void onChat(AsyncChatEvent event) {
            Player player = event.getPlayer();
            queue.add(new Entry.Text(Clock.now(), Actor.of(player), player.getWorld().getName(), 0, 0, 0, Actions.CHAT,
                PlainTextComponentSerializer.plainText().serialize(event.originalMessage())));
        }
    }

    /** Commands players run. Off by default. */
    public static final class Commands extends ListenerModule {
        private final LogQueue queue;

        public Commands(Plugin plugin, LogQueue queue) {
            super(plugin);
            this.queue = queue;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        void onCommand(PlayerCommandPreprocessEvent event) {
            Player player = event.getPlayer();
            var at = player.getLocation();
            queue.add(new Entry.Text(Clock.now(), Actor.of(player), at.getWorld().getName(), at.getBlockX(), at.getBlockY(),
                at.getBlockZ(), Actions.COMMAND, event.getMessage()));
        }
    }
}
