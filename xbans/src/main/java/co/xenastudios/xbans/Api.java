package co.xenastudios.xbans;

import co.xenastudios.core.Module;
import co.xenastudios.core.text.ChatPages;
import co.xenastudios.xbans.api.Alt;
import co.xenastudios.xbans.api.HistoryPage;
import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.api.PunishRequest;
import co.xenastudios.xbans.api.XBansApi;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.model.Length;
import co.xenastudios.xbans.storage.Store;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;

/**
 * xBans' developer API, offered through the services manager while xBans is enabled. The
 * checks answer from the bans and mutes already in memory, so it adds nothing to run.
 */
final class Api implements XBansApi, Module {

    private final XBans xbans;

    Api(XBans xbans) {
        this.xbans = xbans;
    }

    @Override
    public void enable() {
        Bukkit.getServicesManager().register(XBansApi.class, this, xbans, ServicePriority.Normal);
    }

    @Override
    public void disable() {
        Bukkit.getServicesManager().unregister(XBansApi.class, this);
    }

    private String server() {
        return xbans.config().scopes().serverName();
    }

    @Override
    public boolean isBanned(UUID player) {
        return getActiveBan(player).isPresent();
    }

    @Override
    public boolean isMuted(UUID player) {
        return getActiveMute(player).isPresent();
    }

    @Override
    public Optional<Punishment> getActiveBan(UUID player) {
        Objects.requireNonNull(player, "player");
        Store.Backend backend = xbans.store().backend();
        if (backend == null) {
            return Optional.empty();
        }
        Entry ban = backend.active().ban(player, null, server(), System.currentTimeMillis());
        return Optional.ofNullable(ban).map(Entry::punishment);
    }

    @Override
    public Optional<Punishment> getActiveMute(UUID player) {
        Objects.requireNonNull(player, "player");
        Store.Backend backend = xbans.store().backend();
        if (backend == null) {
            return Optional.empty();
        }
        Entry mute = backend.active().mute(player, xbans.sessions().ip(player), server(), System.currentTimeMillis());
        return Optional.ofNullable(mute).map(Entry::punishment);
    }

    @Override
    public CompletableFuture<Punishment> punish(PunishRequest request) {
        Objects.requireNonNull(request, "request");
        String name = xbans.profiles().cached(request.target()).map(profile -> profile.name()).orElse(request.target().toString());
        Punisher.Request made = new Punisher.Request(request.type(), request.target(), name, null, request.ipBased(),
            Length.of(request.duration()), request.reason(), null, request.scope(), request.silent(), false,
            request.staff(), request.staffName(), null, null, false);
        return xbans.punisher().punish(made).handle((entry, failure) -> {
            if (failure != null) {
                Refusal refusal = Refusal.of(failure);
                Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
                if (refusal != null) {
                    throw new IllegalStateException(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(refusal.render()));
                }
                throw cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause);
            }
            return entry.punishment();
        });
    }

    @Override
    public CompletableFuture<Optional<Punishment>> revoke(UUID player, PunishmentType type, UUID staff, String staffName,
                                                          String reason) {
        Objects.requireNonNull(player, "player");
        if (type == PunishmentType.KICK) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Punisher.Revoke request = new Punisher.Revoke(type, player, player.toString(), null, null, null, reason, false, false,
            staff, staffName, null);
        return xbans.punisher().revoke(request).handle((revoked, failure) -> {
            if (failure != null) {
                if (Refusal.of(failure) != null) {
                    return Optional.empty();
                }
                throw new IllegalStateException(failure.getCause() != null ? failure.getCause() : failure);
            }
            return revoked.isEmpty() ? Optional.empty() : Optional.of(revoked.get(0).punishment());
        });
    }

    @Override
    public CompletableFuture<HistoryPage> history(UUID player, int page, int perPage) {
        return page(Store.Filter.target(Objects.requireNonNull(player, "player"), null), page, perPage);
    }

    @Override
    public CompletableFuture<HistoryPage> staffHistory(UUID staff, int page, int perPage) {
        return page(Store.Filter.staff(staff, null), page, perPage);
    }

    private CompletableFuture<HistoryPage> page(Store.Filter filter, int page, int perPage) {
        int size = Math.max(1, Math.min(100, perPage));
        int number = Math.max(1, page);
        return xbans.store().read((c, b) -> {
            long total = Store.count(c, b, filter);
            long start = Store.boundary(c, b, filter, ChatPages.offset(number, size));
            List<Entry> rows = start == Long.MIN_VALUE ? List.of() : Store.page(c, b, filter, start, size);
            return new HistoryPage(rows.stream().map(Entry::punishment).toList(), number, ChatPages.pages(total, size), total);
        });
    }

    @Override
    public CompletableFuture<List<Alt>> alts(UUID player) {
        return xbans.store().alts(Objects.requireNonNull(player, "player"), null).thenApply(uses -> uses.stream()
            .filter(use -> !use.id().equals(player))
            .map(use -> new Alt(use.id(), use.name(), Instant.ofEpochMilli(use.last())))
            .toList());
    }
}
