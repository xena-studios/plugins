package co.xenastudios.xmoney.command;

import co.xenastudios.core.player.Profiles;
import co.xenastudios.xmoney.XMoney;
import co.xenastudios.xmoney.ledger.Account;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * Finds the account a command names, online or not, without blocking: xMoney's own accounts
 * by name first (every one is in memory), then the core's {@link Profiles} for a player who
 * changed their name or who played before xMoney was installed. Such a player gets their
 * account then, with the starting balance, as if they'd joined. A name nobody has played
 * under finds nothing.
 */
public final class Players {

    private final XMoney plugin;
    private final Profiles profiles;

    public Players(XMoney plugin, Profiles profiles) {
        this.plugin = plugin;
        this.profiles = profiles;
    }

    /**
     * The account by this name.
     *
     * @param playersOnly whether to skip accounts that aren't players' (a town's bank)
     * @return a future, complete at once when the account is in memory, otherwise completed
     *     off the server's threads
     */
    public CompletableFuture<Optional<Account>> find(String name, boolean playersOnly) {
        Account known = plugin.bank().accounts().named(name);
        if (known != null && (known.player() || !playersOnly)) {
            return CompletableFuture.completedFuture(Optional.of(known));
        }
        if (!Profiles.validName(name)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return profiles.byName(name).thenApplyAsync(found -> found.flatMap(this::account), plugin.tasks()::async);
    }

    // Off the server's threads: hasPlayedBefore can read the player's file.
    private Optional<Account> account(Profiles.Profile profile) {
        Account account = plugin.bank().accounts().get(profile.id());
        if (account != null) {
            plugin.ledger().rename(account, profile.name());
            return Optional.of(account);
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(profile.id());
        if (!player.isOnline() && !player.hasPlayedBefore()) {
            return Optional.empty();
        }
        return Optional.of(plugin.ledger().open(profile.id(), profile.name(), plugin.bank().start()));
    }

    /** A name for a uuid from memory only (online players and recent lookups), or null. Any thread. */
    public String cachedName(UUID id) {
        return profiles.cached(id).map(Profiles.Profile::name).orElse(null);
    }
}
