package co.xenastudios.zchat.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandFailure;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.zchat.ZChat;
import co.xenastudios.zchat.chat.ChatState;
import co.xenastudios.zchat.chat.Screen;
import co.xenastudios.zchat.config.Settings;
import co.xenastudios.zchat.config.ZConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.persistence.PersistentDataType;

/** /msg, /reply and /socialspy. */
public final class MessageCommands {

    private MessageCommands() {}

    public static void register(Setup setup, ZChat zchat, ZConfig config) {
        Settings.PrivateMessages settings = config.privateMessages();
        ChatState state = zchat.state();

        Settings.Msg msg = settings.msg();
        if (msg.enabled()) {
            setup.command(ChatCommands.named("msg", msg)
                .permissionDefault(PermissionDefault.TRUE)
                .description("Send a player a private message.")
                .usage("/msg <player> <message>")
                .completes((sender, args) -> args.length == 1 ? Completions.players(sender, args[0], "") : List.of())
                .executes(invocation -> {
                    if (invocation.arg(0) == null || invocation.arg(1) == null) {
                        throw invocation.usage();
                    }
                    String text = invocation.rest(1);
                    for (Player target : targets(zchat, invocation)) {
                        send(zchat, invocation.sender(), target, text);
                    }
                }));
        }

        Settings.Reply reply = settings.reply();
        if (reply.enabled()) {
            setup.command(ChatCommands.named("reply", reply)
                .permissionDefault(PermissionDefault.TRUE)
                .description("Answer your last private message.")
                .usage("/reply <message>")
                .executes(invocation -> {
                    String text = invocation.rest(0);
                    if (text == null) {
                        throw invocation.usage();
                    }
                    UUID self = id(invocation.sender());
                    ChatState.Partner partner = self == null ? null : state.partner(self);
                    if (partner == null) {
                        throw new CommandFailure(reply.messageNobody());
                    }
                    send(zchat, invocation.sender(), recipient(zchat, invocation, partner), text);
                }));
        }

        Settings.SocialSpy spy = settings.socialspy();
        if (spy.enabled()) {
            setup.command(ChatCommands.named("socialspy", spy)
                .description("See every private message.")
                .executes(invocation -> {
                    Player player = invocation.player();
                    boolean on = !state.spies(player.getUniqueId());
                    state.spy(player.getUniqueId(), on);
                    if (spy.remember()) {
                        zchat.tasks().entity(player, () -> {
                            if (on) {
                                player.getPersistentDataContainer().set(zchat.socialSpyKey(), PersistentDataType.BOOLEAN, true);
                            } else {
                                player.getPersistentDataContainer().remove(zchat.socialSpyKey());
                            }
                        });
                    }
                    (on ? spy.messageOn() : spy.messageOff()).send(player);
                }));
        }
    }

    // A name, or a selector like Minecraft's /msg takes (@p, @a[distance=..10]).
    private static List<Player> targets(ZChat zchat, Invocation invocation) {
        String name = invocation.arg(0);
        // Folia can't safely gather entities from every region, so selectors are Paper only.
        if (!name.startsWith("@") || zchat.isFolia()) {
            return List.of(invocation.target(0, ""));
        }
        List<Player> found = new ArrayList<>();
        try {
            for (Entity entity : Bukkit.selectEntities(invocation.sender(), name)) {
                if (entity instanceof Player player
                    && (!(invocation.sender() instanceof Player viewer) || viewer.canSee(player))) {
                    found.add(player);
                }
            }
        } catch (IllegalArgumentException e) {
            throw invocation.usage();
        }
        if (found.isEmpty()) {
            throw new CommandFailure(zchat.config().messages().error().playerNotFound(), Placeholders.text("player", name));
        }
        return found;
    }

    // Whoever /reply answers: the console, or a player who's still online and visible.
    private static CommandSender recipient(ZChat zchat, Invocation invocation, ChatState.Partner partner) {
        if (partner.id().equals(ChatState.CONSOLE)) {
            return Bukkit.getConsoleSender();
        }
        Player player = Bukkit.getPlayer(partner.id());
        if (player == null || (invocation.sender() instanceof Player viewer && !viewer.canSee(player))) {
            throw new CommandFailure(zchat.config().messages().error().playerNotFound(), Placeholders.text("player", partner.name()));
        }
        return player;
    }

    /** Who can be replied to: a player, the console, or null for command blocks and the like. */
    private static UUID id(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUniqueId();
        }
        return sender instanceof ConsoleCommandSender ? ChatState.CONSOLE : null;
    }

    /** Delivers a private message: filtered, to both sides, to spies, and remembered for /reply. */
    static void send(ZChat zchat, CommandSender from, CommandSender to, String text) {
        ZConfig config = zchat.config();
        Settings.PrivateMessages settings = config.privateMessages();
        Component message = Component.text(text);
        if (from instanceof Player player) {
            Screen screen = Screen.of(zchat, player, text);
            if (screen.blocked()) {
                throw new CommandFailure(config.filter().messageBlocked());
            }
            message = screen.message();
        }
        var said = Placeholders.component("message", message);

        settings.msg().formatSent().send(from, Placeholders.text("player", to.getName()), said);
        settings.msg().formatReceived().send(to, Placeholders.text("player", from.getName()), said);
        if (to == from) {
            // Talking to yourself: no ping, nothing to reply to, nothing for spies.
            return;
        }
        if (to instanceof Player receiver) {
            zchat.tasks().entity(receiver, () -> settings.msg().sound().play(receiver, Sound.Source.PLAYER));
        }

        ChatState state = zchat.state();
        UUID fromId = id(from);
        UUID toId = id(to);
        if (fromId != null && toId != null) {
            state.partner(fromId, new ChatState.Partner(toId, to.getName()));
            state.partner(toId, new ChatState.Partner(fromId, from.getName()));
        }

        Settings.SocialSpy spy = settings.socialspy();
        if (spy.enabled()) {
            Component line = spy.format().chat(
                Placeholders.text("sender", from.getName()), Placeholders.text("receiver", to.getName()), said);
            for (UUID id : state.spies()) {
                Player watcher = Bukkit.getPlayer(id);
                if (watcher != null && watcher != from && watcher != to
                    && (spy.permission().isBlank() || watcher.hasPermission(spy.permission()))) {
                    watcher.sendMessage(line);
                }
            }
            Audience console = Bukkit.getConsoleSender();
            if (spy.logToConsole() && from != console && to != console) {
                console.sendMessage(line);
            }
        }
    }
}
