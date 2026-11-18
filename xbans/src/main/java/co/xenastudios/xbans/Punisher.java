package co.xenastudios.xbans;

import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.api.PunishRequest;
import co.xenastudios.xbans.api.Removal;
import co.xenastudios.xbans.api.event.PunishEvent;
import co.xenastudios.xbans.api.event.PunishedEvent;
import co.xenastudios.xbans.api.event.RevokedEvent;
import co.xenastudios.xbans.config.XBConfig;
import co.xenastudios.xbans.model.Active;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.model.Length;
import co.xenastudios.xbans.storage.Store;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Makes and revokes punishments, for commands, the API and the other servers of a network
 * alike: checks them, stores them, then makes them felt here (the in-memory lists, kicks,
 * the player told, the broadcast, the events).
 *
 * <p>Each step that needs the database runs off the server's threads; the in-memory lists
 * change as soon as the database has committed, before anyone is kicked, so a player
 * joining at that moment is caught by the join check.
 */
public final class Punisher {

    public static final String NOTIFY_BROADCAST = "xbans.notify.broadcast";
    public static final String NOTIFY_SILENT = "xbans.notify.silent";

    /**
     * A punishment to make.
     *
     * @param target the player, or null for an IP no account is known for
     * @param targetName the player's name, or the IP as shown
     * @param ip the IP key to punish, when it's given rather than the player's own
     * @param length null when none was given: permanent, or the template's, or the warnings default
     * @param scope null for the config's default
     * @param power the sender's limits, or null for none
     * @param sender who asked, to tell them the outcome, or null for the API
     * @param templateLength whether the length is a template's own, which isn't held to staff caps
     *     (weights and exemptions still apply)
     */
    public record Request(
        PunishmentType type, UUID target, String targetName, String ip, boolean ipBased, Length length, String reason,
        String template, String scope, boolean silent, boolean extraSilent, UUID staff, String staffName, Power power,
        CommandSender sender, boolean templateLength) {}

    /**
     * Revoking: a player's (all their scopes, unless {@code scope} names one), an IP's, or one
     * punishment by id.
     */
    public record Revoke(
        PunishmentType type, UUID target, String targetName, String ip, Long id, String scope, String reason,
        boolean silent, boolean extraSilent, UUID staff, String staffName, CommandSender sender) {}

    private final XBans plugin;

    public Punisher(XBans plugin) {
        this.plugin = plugin;
    }

    private XBConfig config() {
        return plugin.config();
    }

    private Store.Backend backend() {
        Store.Backend backend = plugin.store().backend();
        if (backend == null) {
            throw new Refusal(config().messages().notReady());
        }
        return backend;
    }

    /** The scope a punishment gets: what was typed, else the config's default. */
    public String scope(String typed) {
        XBConfig.Scopes scopes = config().scopes();
        if (typed == null) {
            return scopes.defaultScope() == XBConfig.Scopes.DefaultScope.GLOBAL ? Punishment.GLOBAL : scopes.serverName();
        }
        if (co.xenastudios.xbans.command.Args.isGlobal(typed)) {
            return Punishment.GLOBAL;
        }
        if (co.xenastudios.xbans.command.Args.isLocal(typed)) {
            return scopes.serverName();
        }
        return typed;
    }

    // ---- Punishing ----

    /** Checks, stores and applies a punishment. Fails with a {@link Refusal} when it can't be made. */
    public CompletableFuture<Entry> punish(Request request) {
        try {
            backend();
        } catch (Refusal refusal) {
            return CompletableFuture.failedFuture(refusal);
        }
        Player online = request.target() == null ? null : Bukkit.getPlayer(request.target());
        CompletableFuture<Store.Known> known;
        if (request.target() == null) {
            known = CompletableFuture.completedFuture(new Store.Known(null, 0, false, null));
        } else if (online != null) {
            known = CompletableFuture.completedFuture(new Store.Known(online.getName(), Power.weight(online),
                online.hasPermission(Power.EXEMPT), plugin.sessions().ip(online.getUniqueId())));
        } else {
            known = plugin.store().known(request.target());
        }
        return known.thenCompose(target -> withTemplate(request).thenCompose(resolved -> store(resolved, target)));
    }

    // The template's step, length, reason and flags, if one was named.
    private CompletableFuture<Request> withTemplate(Request request) {
        if (request.template() == null) {
            return CompletableFuture.completedFuture(request);
        }
        XBConfig.Template template = config().templates().get(request.template());
        if (template == null || request.target() == null) {
            return CompletableFuture.completedFuture(request);
        }
        long since = System.currentTimeMillis() - template.expireLadder().millis();
        return plugin.store().ladderStep(request.target(), request.template(), template.expireLadder().permanent() ? 0 : since)
            .thenApply(step -> {
                List<Length> ladder = template.ladder().isEmpty() ? List.of(Length.PERMANENT) : template.ladder();
                Length length = request.length() != null ? request.length() : ladder.get(Math.min(step, ladder.size() - 1));
                String reason = request.reason() != null ? request.reason() : template.reason();
                return new Request(request.type(), request.target(), request.targetName(), request.ip(),
                    request.ipBased() || template.ip(), length, reason, request.template(), request.scope(),
                    request.silent() || template.silent(), request.extraSilent(), request.staff(), request.staffName(),
                    // A template's own length isn't held to staff caps; one typed with it is.
                    request.power(), request.sender(), request.length() == null);
            });
    }

    private CompletableFuture<Entry> store(Request request, Store.Known target) {
        XBConfig current = config();
        XBConfig.Messages messages = current.messages();
        Store.Backend backend = backend();
        PunishmentType type = request.type();
        Power power = request.power();
        String name = target.name() != null && request.target() != null ? target.name() : request.targetName();
        if (power != null && target.exempt()) {
            throw new Refusal(messages.exempt(), Placeholders.text("player", name));
        }
        if (power != null && current.staffLimits().weights() && target.weight() > 0 && power.weight() <= target.weight()) {
            throw new Refusal(messages.outranks(), Placeholders.text("player", name));
        }
        Length length = request.length();
        if (type == PunishmentType.WARN && length == null) {
            length = current.warnings().defaultDuration();
        }
        if (length == null || type == PunishmentType.KICK) {
            length = Length.PERMANENT;
        }
        if (power != null && !request.templateLength()) {
            Length cap = power.cap(type);
            if (cap != null && length.compareTo(cap) > 0) {
                throw new Refusal(messages.overLimit(), Placeholders.text("max", cap.shown()));
            }
        }
        String ip = null;
        if (request.ipBased()) {
            ip = request.ip() != null ? request.ip() : target.lastIp();
            if (ip == null) {
                throw new Refusal(messages.noIp(), Placeholders.text("player", name));
            }
        }
        long now = System.currentTimeMillis();
        String scope = type == PunishmentType.KICK ? Punishment.GLOBAL : scope(request.scope());
        Instant expires = length.permanent() ? null : Instant.ofEpochMilli(now + length.millis());
        Entry replaces = null;
        if (type == PunishmentType.BAN || type == PunishmentType.MUTE) {
            replaces = backend.active().onScope(type, request.target(), request.ipBased() ? ip : null, scope, now);
            if (replaces != null && !lastsAsLong(expires, replaces.punishment().expires())) {
                throw new Refusal(type == PunishmentType.BAN ? messages.alreadyBanned() : messages.alreadyMuted(),
                    plugin.texts().of(replaces, now));
            }
        }
        String reason = request.reason() == null || request.reason().isBlank() ? current.punishing().defaultReason() : request.reason();
        Punishment draft = new Punishment(0, type, request.target(), name, request.ipBased(), reason, request.staff(),
            request.staffName(), Instant.ofEpochMilli(now), expires, scope, request.silent() || request.extraSilent(),
            request.template(), null);
        Entry entry = new Entry(draft, ip);
        Entry replaced = replaces;
        Removal replacement = replaced == null ? null : new Removal(request.staff(), request.staffName(), Instant.ofEpochMilli(now),
            Store.REPLACED_REASON);
        return plugin.store().insert(entry, replaced, replacement, () -> firePunish(request, entry))
            .thenApply(stored -> {
                applyMade(stored, replaced == null ? null : replaced.id(), true, request.extraSilent(), request.sender());
                return stored;
            });
    }

    private static boolean lastsAsLong(Instant fresh, Instant existing) {
        return fresh == null || existing != null && !fresh.isBefore(existing);
    }

    private void firePunish(Request request, Entry entry) {
        if (PunishEvent.getHandlerList().getRegisteredListeners().length == 0) {
            return;
        }
        Punishment p = entry.punishment();
        PunishRequest api = new PunishRequest(p.type(), p.target() == null ? new UUID(0, 0) : p.target(), p.reason(),
            p.expires() == null ? null : java.time.Duration.between(p.created(), p.expires()), p.staff(), p.staffName(),
            p.silent(), p.ipBased(), p.scope());
        PunishEvent event = new PunishEvent(api);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            throw new CancellationException("Cancelled by a PunishEvent listener");
        }
    }

    /**
     * Makes a stored punishment felt on this server: the lists in memory, kicks, telling the
     * player, the broadcast and the event. For this server's punishments and the network's.
     *
     * @param replaced the id of a ban or mute it replaces, or null
     */
    public void applyMade(Entry entry, Long replaced, boolean local, boolean extraSilent, CommandSender sender) {
        Store.Backend backend = plugin.store().backend();
        if (backend == null) {
            return;
        }
        Active active = backend.active();
        if (replaced != null) {
            active.remove(replaced);
        }
        // In memory before anyone is kicked: a player joining now is caught by the join check.
        active.add(entry);
        XBConfig current = config();
        Punishment p = entry.punishment();
        long now = System.currentTimeMillis();
        TagResolver placeholders = plugin.texts().of(entry, now);
        String server = current.scopes().serverName();
        if (entry.appliesOn(server)) {
            for (Player player : affected(entry)) {
                switch (p.type()) {
                    case BAN -> kick(player, plugin.screen(entry, now));
                    case KICK -> kick(player, current.screens().kick().render(placeholders));
                    case MUTE -> current.screens().youWereMuted().send(player, placeholders);
                    case WARN -> current.screens().youWereWarned().send(player, placeholders);
                }
            }
        }
        XBConfig.Broadcasts words = current.broadcasts();
        Message message = switch (p.type()) {
            case BAN -> p.ipBased() ? words.ipBan() : words.ban();
            case MUTE -> p.ipBased() ? words.ipMute() : words.mute();
            case WARN -> words.warn();
            case KICK -> words.kick();
        };
        broadcast(message, placeholders, p.silent(), extraSilent, sender);
        if (local && p.type() == PunishmentType.WARN && p.target() != null) {
            warnActions(p.target(), p.targetName());
        }
        if (PunishedEvent.getHandlerList().getRegisteredListeners().length > 0) {
            callAsync(new PunishedEvent(p, !local));
        }
    }

    // The online players a punishment is for: its account, and with an IP punishment everyone on that IP.
    private List<Player> affected(Entry entry) {
        List<Player> found = new ArrayList<>();
        UUID target = entry.punishment().target();
        boolean byIp = entry.punishment().ipBased() && entry.ip() != null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getUniqueId().equals(target) || byIp && entry.ip().equals(plugin.sessions().ip(player.getUniqueId()))) {
                found.add(player);
            }
        }
        return found;
    }

    private void kick(Player player, Component screen) {
        plugin.tasks().entity(player, () -> {
            if (player.isOnline()) {
                plugin.sessions().quietQuit(player.getUniqueId());
                player.kick(screen);
            }
        });
    }

    private void warnActions(UUID target, String name) {
        plugin.store().activeWarnings(target).thenAccept(warnings -> {
            XBConfig.WarnAction action = config().warnings().actions().get(Integer.toString(warnings.size()));
            if (action == null || action.commands().isEmpty()) {
                return;
            }
            // On the next tick on the global thread, outside any command that's running.
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                for (String command : action.commands()) {
                    String line = command.replace("<player>", name).strip();
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line);
                }
            });
        }).exceptionally(e -> {
            plugin.getLogger().log(Level.WARNING, "Couldn't count " + name + "'s warnings for warnings.actions", e);
            return null;
        });
    }

    // ---- Revoking ----

    /** Revokes what the request names. Fails with a {@link Refusal} when there's nothing to revoke. */
    public CompletableFuture<List<Entry>> revoke(Revoke request) {
        Store.Backend backend;
        try {
            backend = backend();
        } catch (Refusal refusal) {
            return CompletableFuture.failedFuture(refusal);
        }
        return candidates(request, backend).thenCompose(found -> {
            if (found.isEmpty()) {
                XBConfig.Messages messages = config().messages();
                if (request.id() != null) {
                    throw new Refusal(messages.noSuchId(), Placeholders.text("id", request.id()));
                }
                throw new Refusal(switch (request.type()) {
                    case BAN -> messages.notBanned();
                    case MUTE -> messages.notMuted();
                    default -> messages.noWarnings();
                }, Placeholders.text("player", request.targetName()));
            }
            Removal removal = new Removal(request.staff(), request.staffName(), Instant.now(),
                request.reason() == null ? "" : request.reason());
            List<CompletableFuture<Entry>> each = new ArrayList<>();
            for (Entry entry : found) {
                each.add(plugin.store().revoke(entry.id(), removal, request.silent() || request.extraSilent())
                    .thenApply(done -> done ? new Entry(entry.punishment().withRemoval(removal), entry.ip()) : null));
            }
            return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
                List<Entry> revoked = each.stream().map(CompletableFuture::join).filter(java.util.Objects::nonNull).toList();
                for (int i = 0; i < revoked.size(); i++) {
                    applyRevoked(revoked.get(i), true, request.silent(), request.extraSilent(), i == 0, request.sender());
                }
                return revoked;
            });
        });
    }

    private CompletableFuture<List<Entry>> candidates(Revoke request, Store.Backend backend) {
        long now = System.currentTimeMillis();
        PunishmentType type = request.type();
        if (request.id() != null) {
            return plugin.store().find(request.id()).thenApply(entry ->
                entry != null && entry.type() == type && entry.active(now) ? List.of(entry) : List.of());
        }
        if (type == PunishmentType.WARN) {
            return plugin.store().activeWarnings(request.target())
                .thenApply(warnings -> warnings.isEmpty() ? List.of() : List.of(warnings.get(0)));
        }
        String scope = request.scope() == null ? null : scope(request.scope());
        List<Entry> found = new ArrayList<>();
        for (Entry entry : backend.active().all(type, null, now)) {
            boolean mine = request.target() != null && request.target().equals(entry.punishment().target())
                || request.ip() != null && request.ip().equals(entry.ip());
            if (mine && (scope == null || entry.punishment().scope().equalsIgnoreCase(scope))) {
                found.add(entry);
            }
        }
        return CompletableFuture.completedFuture(found);
    }

    /** Makes a revocation felt on this server. Only the first of several revoked at once is broadcast. */
    public void applyRevoked(Entry entry, boolean local, boolean silent, boolean extraSilent, boolean announce,
                             CommandSender sender) {
        Store.Backend backend = plugin.store().backend();
        if (backend != null) {
            backend.active().remove(entry.id());
        }
        XBConfig current = config();
        Punishment p = entry.punishment();
        if (p.type() == PunishmentType.MUTE && p.target() != null) {
            Player player = Bukkit.getPlayer(p.target());
            if (player != null) {
                current.screens().youWereUnmuted().send(player);
            }
        }
        if (announce) {
            XBConfig.Broadcasts words = current.broadcasts();
            Message message = switch (p.type()) {
                case BAN -> words.unban();
                case MUTE -> words.unmute();
                default -> words.unwarn();
            };
            TagResolver placeholders = TagResolver.resolver(plugin.texts().of(entry, System.currentTimeMillis()),
                Placeholders.text("staff", p.removal() == null ? "?" : p.removal().staffName()));
            broadcast(message, placeholders, silent, extraSilent, sender);
        }
        if (RevokedEvent.getHandlerList().getRegisteredListeners().length > 0) {
            callAsync(new RevokedEvent(p, !local));
        }
    }

    // ---- Telling people ----

    /**
     * Sends a broadcast on the global thread: to everyone with {@value #NOTIFY_BROADCAST}, or
     * when silent to staff with {@value #NOTIFY_SILENT}, or when extra silent to the console
     * only. The staff member who did it always sees it.
     */
    public void broadcast(Message message, TagResolver placeholders, boolean silent, boolean extraSilent, CommandSender sender) {
        if (message.isEmpty()) {
            return;
        }
        String prefix = silent || extraSilent ? config().broadcasts().silentPrefix() : "";
        String template = message.template();
        // After the theme's <prefix>, so it reads "» [Silent] Console banned Steve".
        Message line = prefix.isEmpty() ? message : template.startsWith("<prefix>")
            ? Message.of("<prefix>" + prefix + template.substring("<prefix>".length())) : Message.of(prefix + template);
        plugin.tasks().global(() -> {
            Component text = line.chat(placeholders);
            Bukkit.getConsoleSender().sendMessage(text);
            boolean seen = !(sender instanceof Player);
            if (!extraSilent) {
                String permission = silent ? NOTIFY_SILENT : NOTIFY_BROADCAST;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.hasPermission(permission)) {
                        player.sendMessage(text);
                        seen |= player == sender;
                    }
                }
            }
            if (!seen && sender instanceof Player player && player.isOnline()) {
                player.sendMessage(text);
            }
        });
    }

    private void callAsync(org.bukkit.event.Event event) {
        if (Bukkit.isPrimaryThread()) {
            plugin.tasks().async(() -> Bukkit.getPluginManager().callEvent(event));
        } else {
            Bukkit.getPluginManager().callEvent(event);
        }
    }
}
