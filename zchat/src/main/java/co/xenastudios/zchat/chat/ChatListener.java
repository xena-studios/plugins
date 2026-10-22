package co.xenastudios.zchat.chat;

import co.xenastudios.core.ListenerModule;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.zchat.ZChat;
import co.xenastudios.zchat.config.Settings;
import co.xenastudios.zchat.config.ZConfig;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Every chat message comes through here. The cheapest checks run first (mute, cooldown,
 * filter, anti-repeat), so a message that's stopped costs almost nothing; one that gets
 * through is drawn once and that line goes to everyone.
 */
public final class ChatListener extends ListenerModule {

    public static final String BYPASS_MUTE = "zchat.bypass.mute";
    public static final String BYPASS_COOLDOWN = "zchat.bypass.cooldown";
    public static final String BYPASS_FILTER = "zchat.bypass.filter";
    public static final String BYPASS_ANTI_REPEAT = "zchat.bypass.anti-repeat";

    private final ZChat zchat;

    public ChatListener(ZChat zchat) {
        super(zchat);
        this.zchat = zchat;
    }

    // What onChat worked out, for onChatSent to act on once no other plugin has
    // cancelled the message. Events don't override equals, so this is by identity.
    private final Map<AsyncChatEvent, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(ChatState.Recent recent, Mentions.Applied mentions) {}

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        ZConfig config = zchat.config();
        ZChat.Compiled compiled = zchat.compiled();
        ChatState state = zchat.state();
        long now = System.nanoTime();

        if (state.muted() && !player.hasPermission(BYPASS_MUTE)) {
            refuse(event, config.mutechat().messageBlocked());
            return;
        }

        ChatState.Recent recent = state.recent(player.getUniqueId());
        Settings.Cooldown cooldown = config.cooldown();
        if (cooldown.enabled() && cooldown.seconds() > 0 && recent != null && !player.hasPermission(BYPASS_COOLDOWN)) {
            long left = recent.nanos() + (long) (cooldown.seconds() * 1e9) - now;
            if (left > 0) {
                refuse(event, cooldown.message(), Placeholders.text("seconds", (long) Math.ceil(left / 1e9)));
                return;
            }
        }

        Screen screen = Screen.of(zchat, player, PlainTextComponentSerializer.plainText().serialize(event.message()));
        if (screen.blocked()) {
            refuse(event, config.filter().messageBlocked());
            return;
        }

        Settings.AntiRepeat antiRepeat = config.antiRepeat();
        String key = null;
        if (antiRepeat.enabled() && !player.hasPermission(BYPASS_ANTI_REPEAT)) {
            key = Repeats.key(screen.plain());
            if (key.length() < Repeats.SHORTEST) {
                key = null;
            } else if (recent != null && recent.key() != null
                && now - recent.keyNanos() < antiRepeat.seconds() * 1_000_000_000L
                && Repeats.similarity(key, recent.key()) >= antiRepeat.similarity()) {
                refuse(event, antiRepeat.message());
                return;
            }
        }
        ChatState.Recent sent = key != null
            ? new ChatState.Recent(now, key, now)
            : new ChatState.Recent(now, recent == null ? null : recent.key(), recent == null ? 0 : recent.keyNanos());

        Component message = screen.message();
        Mentions.Applied mentions = null;
        if (config.mentions().enabled()) {
            mentions = Mentions.apply(message, player, config.mentions(), state);
            message = mentions.message();
        }
        event.message(message);

        // Players who hid chat don't see others' messages, but still see their own.
        if (config.togglechat().enabled()) {
            event.viewers().removeIf(viewer -> viewer instanceof Player other && other != player && state.hidesChat(other.getUniqueId()));
        }

        if (compiled.formats().enabled()) {
            Formats formats = compiled.formats();
            // Drawn once and the same line sent to every viewer.
            event.renderer(ChatRenderer.viewerUnaware((source, displayName, said) -> formats.render(source, displayName, said)));
        }
        pending.put(event, new Pending(sent, mentions));
    }

    // Last, after every other plugin: only a message that's really sent counts toward
    // the cooldown and anti-repeat, and pings only the players who'll see it.
    @EventHandler(priority = EventPriority.MONITOR)
    void onChatSent(AsyncChatEvent event) {
        Pending done = pending.remove(event);
        if (done == null || event.isCancelled()) {
            return;
        }
        Player sender = event.getPlayer();
        zchat.state().recent(sender.getUniqueId(), done.recent());
        Mentions.Applied mentions = done.mentions();
        Settings.Mentions settings = zchat.config().mentions();
        if (mentions == null || settings.sound().isNone() || (!mentions.everyone() && mentions.pinged().isEmpty())) {
            return;
        }
        for (Audience viewer : event.viewers()) {
            if (viewer instanceof Player player && player != sender && (mentions.everyone() || mentions.pinged().contains(player))) {
                zchat.tasks().entity(player, () -> settings.sound().play(player, Sound.Source.PLAYER));
            }
        }
    }

    private static void refuse(AsyncChatEvent event, Message message, TagResolver... placeholders) {
        event.setCancelled(true);
        message.send(event.getPlayer(), placeholders);
    }

    @EventHandler
    void onJoin(PlayerJoinEvent event) {
        zchat.state().joined(event.getPlayer());
        restore(zchat, event.getPlayer());
    }

    /** Gives a player back their remembered hidden chat and social spy. Call on their thread. */
    public static void restore(ZChat zchat, Player player) {
        ZConfig config = zchat.config();
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (config.togglechat().enabled() && config.togglechat().remember() && data.has(zchat.hiddenChatKey(), PersistentDataType.BOOLEAN)) {
            zchat.state().hideChat(player.getUniqueId(), true);
        }
        Settings.SocialSpy spy = config.privateMessages().socialspy();
        if (spy.enabled() && spy.remember() && data.has(zchat.socialSpyKey(), PersistentDataType.BOOLEAN)
            && (spy.permission().isBlank() || player.hasPermission(spy.permission()))) {
            zchat.state().spy(player.getUniqueId(), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onQuit(PlayerQuitEvent event) {
        zchat.state().forget(event.getPlayer());
    }
}
