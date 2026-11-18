package co.xenastudios.xbans.command;

import co.xenastudios.core.player.Profiles;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xbans.Refusal;
import co.xenastudios.xbans.XBans;
import co.xenastudios.xbans.config.XBConfig;
import co.xenastudios.xbans.ip.IpKeys;
import co.xenastudios.xbans.storage.Store;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Who a command names: a player by name or uuid (online or not), an IP address, or a
 * punishment's {@code #id}. Never blocks: names in memory answer at once, others are looked
 * up off the server's threads.
 */
final class Targets {

    /**
     * @param id the player, or null for an IP or an id
     * @param ip the IP key, when an address was given
     * @param punishment the punishment id, when {@code #id} was given
     */
    record Target(UUID id, String name, String ip, Long punishment) {}

    private final XBans xbans;

    Targets(XBans xbans) {
        this.xbans = xbans;
    }

    /**
     * @param ips whether an IP address may be given
     * @param ids whether {@code #id} may be given
     */
    CompletableFuture<Target> resolve(String token, boolean ips, boolean ids) {
        XBConfig.Messages messages = xbans.config().messages();
        if (ids && token.startsWith("#") && token.length() > 1 && token.substring(1).chars().allMatch(Character::isDigit)) {
            try {
                return CompletableFuture.completedFuture(new Target(null, token, null, Long.parseLong(token.substring(1))));
            } catch (NumberFormatException e) {
                return CompletableFuture.failedFuture(new Refusal(messages.noSuchId(), Placeholders.text("id", token.substring(1))));
            }
        }
        if (IpKeys.looksLikeAddress(token)) {
            if (!ips) {
                return CompletableFuture.failedFuture(new Refusal(messages.neverJoined(), Placeholders.text("player", token)));
            }
            if (!xbans.config().ips().enabled()) {
                return CompletableFuture.failedFuture(new Refusal(messages.ipsOff()));
            }
            Store.Backend backend = xbans.store().backend();
            if (backend == null) {
                return CompletableFuture.failedFuture(new Refusal(messages.notReady()));
            }
            if (!IpKeys.punishable(token)) {
                return CompletableFuture.failedFuture(new Refusal(messages.badIp()));
            }
            String key = backend.keys().key(token);
            return CompletableFuture.completedFuture(new Target(null, IpKeys.label(key), key, null));
        }
        UUID id = uuid(token);
        if (id != null) {
            return xbans.profiles().byId(id).thenApply(found -> new Target(id, found.map(Profiles.Profile::name).orElse(token), null, null));
        }
        if (!Profiles.validName(token)) {
            return CompletableFuture.failedFuture(new Refusal(messages.neverJoined(), Placeholders.text("player", token)));
        }
        return xbans.profiles().byName(token).thenCompose(found -> {
            if (found.isPresent()) {
                return CompletableFuture.completedFuture(new Target(found.get().id(), found.get().name(), null, null));
            }
            return fromOwnTable(token);
        });
    }

    // A player who joined once, whom neither Paper nor Mojang knows by that name any more.
    private CompletableFuture<Target> fromOwnTable(String token) {
        XBConfig.Messages messages = xbans.config().messages();
        if (xbans.store().backend() == null) {
            return CompletableFuture.failedFuture(new Refusal(messages.neverJoined(), Placeholders.text("player", token)));
        }
        return xbans.store().idByName(token).thenApply(id -> {
            if (id == null) {
                throw new Refusal(messages.neverJoined(), Placeholders.text("player", token));
            }
            return new Target(id, token, null, null);
        });
    }

    static UUID uuid(String token) {
        String text = token;
        if (text.length() == 32 && text.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            text = text.substring(0, 8) + "-" + text.substring(8, 12) + "-" + text.substring(12, 16) + "-"
                + text.substring(16, 20) + "-" + text.substring(20);
        }
        if (text.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Optional<Long> id(String token) {
        if (token.startsWith("#") && token.length() > 1 && token.substring(1).chars().allMatch(Character::isDigit)) {
            try {
                return Optional.of(Long.parseLong(token.substring(1)));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
