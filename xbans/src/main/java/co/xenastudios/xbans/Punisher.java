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
import java.util.concurrent.Executor;
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
     * @param templateLength whether the length is a template's own: held to staff caps like
     *     any other, unless the sender has {@value Power#BYPASS_TEMPLATES}
     * @param online who was online when the command ran, read on its thread ({@link #online}),
     *     to hold the target and everyone on an IP to the sender's limits; empty without a power
     * @param temporary from /tempban or /tempmute: a template step that's permanent is refused
     */
    public record Request(
        PunishmentType type, UUID target, String targetName, String ip, boolean ipBased, Length length, String reason,
        String template, String scope, boolean silent, boolean extraSilent, UUID staff, String staffName, Power power,
        CommandSender sender, boolean templateLength, List<Online> online, boolean temporary) {}

    /** An online player as a staff limit sees them: their IP key, weight and exemption. */
    public record Online(UUID id, String name, String ip, int weight, boolean exempt) {}

    /**
     * Revoking: a player's (all their scopes, unless {@code scope} names one), an IP's, or one
     * punishment by id.
     *
     * @param power the sender's weight, or null for none (the console, the API): who may lift what
     */
    public record Revoke(
        PunishmentType type, UUID target, String targetName, String ip, Long id, String scope, String reason,
        boolean silent, boolean extraSilent, UUID staff, String staffName, Power power, CommandSender sender) {}

    /** Lifting punishments made by staff with a higher weight (or by the console). */
    public static final String OVERRIDE = "xbans.unban.override";

    private final XBans plugin;
    private final Executor async;

    public Punisher(XBans plugin) {
        this.plugin = plugin;
        this.async = work -> plugin.tasks().async(work);
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

    /**
     * Everyone online now, with their weight and exemption, for a {@link Request} from a sender
     * with limits. On the command's thread, where their permissions may be read; empty for a
     * sender without limits, who isn't held to anyone's.
     */
    public List<Online> online(Power power) {
        if (power == null) {
            return List.of();
        }
        List<Online> found = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            found.add(new Online(player.getUniqueId(), player.getName(), plugin.sessions().ip(player.getUniqueId()),
                Power.weight(player), player.hasPermission(Power.EXEMPT)));
        }
        return found;
    }

    /** Checks, stores and applies a punishment. Fails with a {@link Refusal} when it can't be made. */
    public CompletableFuture<Entry> punish(Request request) {
        return punish(request, true);
    }

    // first: false when trying again after a conflict, which has already asked PunishEvent's listeners.
    private CompletableFuture<Entry> punish(Request request, boolean first) {
        try {
            backend();
        } catch (Refusal refusal) {
            return CompletableFuture.failedFuture(refusal);
        }
        Online seen = request.target() == null ? null
            : request.online().stream().filter(player -> player.id().equals(request.target())).findFirst().orElse(null);
        Player online = seen != null || request.target() == null ? null : Bukkit.getPlayer(request.target());
        CompletableFuture<Store.Known> known;
        if (request.target() == null) {
            known = CompletableFuture.completedFuture(new Store.Known(null, 0, false, null));
        } else if (seen != null) {
            known = CompletableFuture.completedFuture(new Store.Known(seen.name(), seen.weight(), seen.exempt(), seen.ip()));
        } else if (online != null && request.power() == null) {
            // No limits to hold them to, so their permissions aren't needed.
            known = CompletableFuture.completedFuture(new Store.Known(online.getName(), 0, false,
                plugin.sessions().ip(online.getUniqueId())));
        } else {
            known = plugin.store().known(request.target());
        }
        return known.thenCompose(target -> withTemplate(request).thenCompose(resolved -> {
            String ip = resolved.ipBased() ? resolved.ip() != null ? resolved.ip() : target.lastIp() : null;
            // An IP punishment reaches every account on the IP, so each is held to the sender's limits.
            CompletableFuture<List<Store.Account>> sharing = ip == null || resolved.power() == null
                ? CompletableFuture.completedFuture(List.of()) : plugin.store().accountsOn(ip);
            // Off the server's threads, and outside any transaction: PunishEvent's listeners may call the API.
            return sharing.thenApplyAsync(accounts -> prepare(resolved, target, ip, accounts, first), async);
        })).thenCompose(prepared -> plugin.store().insert(prepared.entry(), prepared.replaces(), prepared.replacement(),
            request.extraSilent())
            .thenApply(stored -> {
                applyMade(stored.entry(), prepared.replaces().stream().map(Entry::id).toList(), true,
                    request.extraSilent(), request.sender(), stored.warnings());
                return stored.entry();
            })).exceptionallyCompose(failure -> {
                if (first && cause(failure) instanceof Store.Conflict conflict) {
                    // What it replaced was revoked meanwhile, maybe on a server whose change hasn't
                    // reached this one yet: forget it, and decide again from what's in force now.
                    Store.Backend backend = plugin.store().backend();
                    if (backend != null) {
                        backend.active().remove(conflict.id());
                    }
                    return punish(request, false);
                }
                if (cause(failure) instanceof Store.Conflict) {
                    XBConfig.Messages messages = config().messages();
                    return CompletableFuture.failedFuture(new Refusal(request.type() == PunishmentType.BAN
                        ? messages.alreadyBanned() : messages.alreadyMuted(), Placeholders.text("player", request.targetName()),
                        Placeholders.text("expires", "?")));
                }
                return CompletableFuture.failedFuture(failure);
            });
    }

    private static Throwable cause(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    // The template's step, length, reason and flags, if one was named.
    private CompletableFuture<Request> withTemplate(Request request) {
        if (request.template() == null) {
            return CompletableFuture.completedFuture(request);
        }
        XBConfig.Template template = config().templates().get(request.template());
        if (template == null) {
            return CompletableFuture.completedFuture(request);
        }
        long since = System.currentTimeMillis() - template.expireLadder().millis();
        // A bare IP has no player whose history counts the step, so it starts the ladder.
        CompletableFuture<Integer> steps = request.target() == null ? CompletableFuture.completedFuture(0)
            : plugin.store().ladderStep(request.target(), request.template(), template.expireLadder().permanent() ? 0 : since);
        return steps.thenApply(step -> {
            List<Length> ladder = template.ladder().isEmpty() ? List.of(Length.PERMANENT) : template.ladder();
            Length length = request.length() != null ? request.length() : ladder.get(Math.min(step, ladder.size() - 1));
            String reason = request.reason() != null ? request.reason() : template.reason();
            return new Request(request.type(), request.target(), request.targetName(), request.ip(),
                request.ipBased() || template.ip(), length, reason, request.template(), request.scope(),
                request.silent() || template.silent(), request.extraSilent(), request.staff(), request.staffName(),
                request.power(), request.sender(), request.length() == null, request.online(), request.temporary());
        });
    }

    /** A punishment checked and ready to store, with the bans or mutes it replaces. */
    private record Prepared(Entry entry, List<Entry> replaces, Removal replacement) {}

    private Prepared prepare(Request request, Store.Known target, String ip, List<Store.Account> sharing, boolean ask) {
        XBConfig current = config();
        XBConfig.Messages messages = current.messages();
        Store.Backend backend = backend();
        PunishmentType type = request.type();
        Power power = request.power();
        String name = target.name() != null && request.target() != null ? target.name() : request.targetName();
        if (power != null) {
            if (target.exempt()) {
                throw new Refusal(messages.exempt(), Placeholders.text("player", name));
            }
            if (outranks(current, power, target.weight())) {
                throw new Refusal(messages.outranks(), Placeholders.text("player", name));
            }
            if (ip != null) {
                limitsOnIp(current, power, ip, sharing, request.online());
            }
        }
        Length length = request.length();
        if (type == PunishmentType.WARN && length == null) {
            length = current.warnings().defaultDuration();
        }
        if (length == null || type == PunishmentType.KICK) {
            length = Length.PERMANENT;
        }
        if (request.temporary() && length.permanent()) {
            // /tempban with a template: its ladder's step here is permanent.
            throw new Refusal(messages.tempPermanent(), Placeholders.text("template", String.valueOf(request.template())));
        }
        if (power != null && !(request.templateLength() && power.bypassTemplates())) {
            Length cap = power.cap(type);
            if (cap != null && length.compareTo(cap) > 0) {
                throw new Refusal(messages.overLimit(), Placeholders.text("max", cap.shown()));
            }
        }
        if (request.ipBased() && ip == null) {
            throw new Refusal(messages.noIp(), Placeholders.text("player", name));
        }
        long now = System.currentTimeMillis();
        String scope = type == PunishmentType.KICK ? Punishment.GLOBAL : scope(request.scope());
        Instant expires = length.permanent() ? null : Instant.ofEpochMilli(now + length.millis());
        List<Entry> replaces = new ArrayList<>();
        if (type == PunishmentType.BAN || type == PunishmentType.MUTE) {
            String newIp = request.ipBased() ? ip : null;
            for (Entry old : backend.active().onScope(type, request.target(), newIp, scope, now)) {
                switch (replacing(old, request.target(), newIp, expires, mayLift(current, power, request.staff(), old))) {
                    case REPLACE -> replaces.add(old);
                    case REFUSE_LONGER -> throw new Refusal(type == PunishmentType.BAN ? messages.alreadyBanned()
                        : messages.alreadyMuted(), plugin.texts().of(old, now));
                    case REFUSE_OUTRANKED -> throw new Refusal(messages.cantLift(), plugin.texts().of(old, now));
                    case BOTH -> { }
                }
            }
        }
        String reason = request.reason() == null || request.reason().isBlank() ? current.punishing().defaultReason() : request.reason();
        Punishment draft = new Punishment(0, type, request.target(), name, request.ipBased(), reason, request.staff(),
            request.staffName(), Instant.ofEpochMilli(now), expires, scope, request.silent() || request.extraSilent(),
            request.template(), null);
        Entry entry = new Entry(draft, ip, power == null ? Entry.CONSOLE : power.weight());
        if (ask) {
            firePunish(entry);
        }
        Removal replacement = replaces.isEmpty() ? null : new Removal(request.staff(), request.staffName(),
            Instant.ofEpochMilli(now), Store.REPLACED_REASON);
        return new Prepared(entry, List.copyOf(replaces), replacement);
    }

    /** What a new ban or mute does to one already in force on the same scope. */
    enum Replacing {
        /** The new one takes its place: the old one is revoked as replaced. */
        REPLACE,
        /** Refused: the old one already reaches everyone the new one would, for at least as long. */
        REFUSE_LONGER,
        /** Refused: as {@link #REFUSE_LONGER}, but the old one was given by someone who outranks the sender. */
        REFUSE_OUTRANKED,
        /** Neither covers the other: both stay in force. */
        BOTH
    }

    /**
     * The replacement rule. A new one replaces an old one only if it reaches everyone the old
     * one did (an account ban never lifts an IP ban), lasts at least as long (a permanent one
     * as long as a permanent one, so re-banning changes the reason), and the sender could lift
     * the old one themselves: otherwise a junior could swap a senior's ban for their own, then
     * unban it. Failing that, if the old one already reaches everyone for at least as long,
     * the new one is refused; if not, both stay in force.
     *
     * @param newIp the new one's IP key if it's by IP, else null
     * @param expires when the new one ends, null for never
     * @param mayLift whether the sender may lift the old one
     */
    static Replacing replacing(Entry old, UUID newAccount, String newIp, Instant expires, boolean mayLift) {
        UUID oldAccount = old.punishment().target();
        String oldIp = old.punishment().ipBased() ? old.ip() : null;
        Instant oldExpires = old.punishment().expires();
        boolean replaces = covers(newAccount, newIp, oldAccount, oldIp) && lastsAsLong(expires, oldExpires);
        if (replaces && mayLift) {
            return Replacing.REPLACE;
        }
        if (covers(oldAccount, oldIp, newAccount, newIp) && lastsAsLong(oldExpires, expires)) {
            return mayLift ? Replacing.REFUSE_LONGER : Replacing.REFUSE_OUTRANKED;
        }
        return Replacing.BOTH;
    }

    private static boolean outranks(XBConfig current, Power power, int weight) {
        return current.staffLimits().weights() && weight > 0 && power.weight() <= weight;
    }

    // Every account seen on the IP, and everyone online on it now (their permissions as they
    // are), held to exemptions and weights as the target is.
    private static void limitsOnIp(XBConfig current, Power power, String ip, List<Store.Account> sharing,
                                   List<Online> online) {
        XBConfig.Messages messages = current.messages();
        java.util.Map<UUID, Store.Account> accounts = new java.util.LinkedHashMap<>();
        for (Store.Account account : sharing) {
            accounts.put(account.id(), account);
        }
        for (Online player : online) {
            if (ip.equals(player.ip())) {
                accounts.put(player.id(), new Store.Account(player.id(), player.name(), player.weight(), player.exempt()));
            }
        }
        for (Store.Account account : accounts.values()) {
            if (account.exempt()) {
                throw new Refusal(messages.exempt(), Placeholders.text("player", account.name()));
            }
            if (outranks(current, power, account.weight())) {
                throw new Refusal(messages.outranks(), Placeholders.text("player", account.name()));
            }
        }
    }

    // Whether a punishment on (account a, IP key a) reaches everyone one on (b, b) does. A null
    // IP is an account punishment; a null account, an IP's alone.
    private static boolean covers(UUID accountA, String ipA, UUID accountB, String ipB) {
        return (accountB == null || accountB.equals(accountA)) && (ipB == null || ipB.equals(ipA));
    }

    private static boolean lastsAsLong(Instant fresh, Instant existing) {
        return fresh == null || existing != null && !fresh.isBefore(existing);
    }

    private void firePunish(Entry entry) {
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

    /** A punishment made on another server, as {@link #applyMade(Entry, List, boolean, boolean, CommandSender, int)}. */
    public void applyMade(Entry entry, List<Long> replaced, boolean local, boolean extraSilent, CommandSender sender) {
        applyMade(entry, replaced, local, extraSilent, sender, 0);
    }

    /**
     * Enforces a ban this server didn't hear of when it was made (an import, or one caught up
     * on from the network): its players here are kicked. Nothing is broadcast.
     */
    public void enforce(Entry entry) {
        if (entry.type() != PunishmentType.BAN || !entry.active(System.currentTimeMillis())
            || !entry.appliesOn(config().scopes().serverName())) {
            return;
        }
        for (Player player : affected(entry)) {
            kick(player, plugin.screen(entry, System.currentTimeMillis()));
        }
    }

    /**
     * Makes a stored punishment felt on this server: the lists in memory, kicks, telling the
     * player, the broadcast and the event. For this server's punishments and the network's.
     *
     * @param replaced the ids of bans or mutes it replaces
     * @param warnings for a warning made here, how many the player now has in force
     */
    public void applyMade(Entry entry, List<Long> replaced, boolean local, boolean extraSilent, CommandSender sender,
                          int warnings) {
        Store.Backend backend = plugin.store().backend();
        if (backend == null) {
            return;
        }
        Active active = backend.active();
        replaced.forEach(active::remove);
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
            warnActions(p.targetName(), warnings);
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

    // The action for the count of warnings the player has now, counted when this one was stored.
    private void warnActions(String name, int warnings) {
        XBConfig.WarnAction action = config().warnings().actions().get(Integer.toString(warnings));
        if (action == null || action.commands().isEmpty()) {
            return;
        }
        // On the next tick on the global thread, outside any command that's running. Through
        // the core's Tasks, which keeps running while an empty Paper server is paused (#67).
        plugin.tasks().nextTick(() -> {
            for (String command : action.commands()) {
                String line = command.replace("<player>", name).strip();
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line);
            }
        });
    }

    // ---- Revoking ----

    /**
     * Revokes what the request names. Fails with a {@link Refusal} when there's nothing to
     * revoke, or nothing the sender may lift.
     */
    public CompletableFuture<List<Entry>> revoke(Revoke request) {
        try {
            backend();
        } catch (Refusal refusal) {
            return CompletableFuture.failedFuture(refusal);
        }
        return candidates(request).thenCompose(found -> {
            XBConfig.Messages messages = config().messages();
            if (found.isEmpty()) {
                if (request.id() != null) {
                    throw new Refusal(messages.noSuchId(), Placeholders.text("id", request.id()));
                }
                throw new Refusal(switch (request.type()) {
                    case BAN -> messages.notBanned();
                    case MUTE -> messages.notMuted();
                    default -> messages.noWarnings();
                }, Placeholders.text("player", request.targetName()));
            }
            XBConfig current = config();
            List<Entry> allowed = found.stream()
                .filter(entry -> mayLift(current, request.power(), request.staff(), entry)).toList();
            if (allowed.isEmpty()) {
                throw new Refusal(messages.cantLift(), plugin.texts().of(found.get(0), System.currentTimeMillis()));
            }
            if (request.type() == PunishmentType.WARN && request.id() == null) {
                allowed = List.of(allowed.get(0)); // The newest warning they may lift.
            }
            Removal removal = new Removal(request.staff(), request.staffName(), Instant.now(),
                request.reason() == null ? "" : request.reason());
            List<CompletableFuture<Entry>> each = new ArrayList<>();
            for (Entry entry : allowed) {
                each.add(plugin.store().revoke(entry.id(), removal, request.silent(), request.extraSilent())
                    .thenApply(done -> done ? entry.revoked(removal) : null));
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

    // Staff lift their own punishments, and those made by staff whose weight was no higher than
    // theirs; the console's is highest. xbans.unban.override, the console and the API lift anything.
    // The same decides whether a new punishment may replace an old one.
    private static boolean mayLift(XBConfig current, Power power, UUID staff, Entry entry) {
        if (power == null || power.override() || !current.staffLimits().weights()) {
            return true;
        }
        UUID madeBy = entry.punishment().staff();
        return madeBy != null && madeBy.equals(staff) || power.weight() >= entry.staffWeight();
    }

    // From the database, not memory: what's in force there is what can be revoked, even on a
    // server that missed a change.
    private CompletableFuture<List<Entry>> candidates(Revoke request) {
        long now = System.currentTimeMillis();
        PunishmentType type = request.type();
        if (request.id() != null) {
            return plugin.store().find(request.id()).thenApply(entry ->
                entry != null && entry.type() == type && entry.active(now) ? List.of(entry) : List.of());
        }
        if (type == PunishmentType.WARN) {
            return plugin.store().activeWarnings(request.target());
        }
        String scope = request.scope() == null ? null : scope(request.scope());
        return plugin.store().inForce(type, request.target(), request.ip()).thenApply(found -> found.stream()
            .filter(entry -> scope == null || entry.punishment().scope().equalsIgnoreCase(scope))
            .toList());
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
