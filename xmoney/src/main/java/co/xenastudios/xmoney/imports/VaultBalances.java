package co.xenastudios.xmoney.imports;

import co.xenastudios.xmoney.XMoney;
import co.xenastudios.xmoney.vault.VaultServices;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Balances from whatever other economy is registered with Vault: EssentialsX's, CMI's, or
 * any other, while it's still installed. That covers every economy plugin at once.
 *
 * <p>Vault can't list accounts, so a VaultUnlocked economy is asked for its own list, and
 * otherwise every player who has played here is asked about (that list is read from the
 * worlds' player files off the server's threads). Economy plugins expect to be called on
 * the main thread, so balances are read there, a few milliseconds' worth a tick
 * ({@link #BUDGET_NANOS}), so even a big server, or a slow economy, doesn't stall. Asking a
 * VaultUnlocked economy for its list is one call, so it gets a tick of its own.
 *
 * <p>Scheduled through the core's {@code Tasks}, which runs on an empty Paper server
 * while it's paused, so an import typed in the console doesn't wait for a player to join.
 */
@SuppressWarnings("deprecation") // Vault 1's Economy, which most economy plugins still register.
public final class VaultBalances implements Source {

    /** How long each tick reads balances for: a tenth of a tick. */
    static final long BUDGET_NANOS = 5_000_000;

    private final XMoney plugin;
    private String provider = "the economy registered with Vault";

    public VaultBalances(XMoney plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return provider;
    }

    @Override
    public CompletableFuture<List<Found>> read() {
        CompletableFuture<List<Found>> done = new CompletableFuture<>();
        plugin.tasks().global(() -> {
            try {
                start(done);
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    private void start(CompletableFuture<List<Found>> done) {
        net.milkbowl.vault2.economy.Economy unlocked = other(net.milkbowl.vault2.economy.Economy.class);
        net.milkbowl.vault.economy.Economy legacy = other(net.milkbowl.vault.economy.Economy.class);
        if (unlocked == null && legacy == null) {
            throw new ImportException("no other economy plugin is registered with Vault. Install the old one beside "
                + XMoney.NAME + " for the import, then remove it.", true);
        }
        List<Found> found = new ArrayList<>();
        if (unlocked != null) {
            provider = unlocked.getName();
            // Copied, since it may change while it's read; a provider may leave names out (null).
            Iterator<Map.Entry<UUID, String>> accounts = new ArrayList<>(new java.util.HashMap<>(unlocked.getUUIDNameMap()).entrySet()).iterator();
            later(() -> step(done, found, () -> {
                Map.Entry<UUID, String> account = accounts.next();
                if (account.getKey() == null) {
                    return;
                }
                BigDecimal balance = unlocked.getBalance(XMoney.NAME, account.getKey());
                found.add(new Found(account.getKey(), account.getValue(), true, balance == null ? "0" : balance.toPlainString()));
            }, accounts), done);
        } else {
            provider = legacy.getName();
            // Every player file in the worlds: disk work, so off the server's threads.
            plugin.tasks().async(() -> {
                Iterator<OfflinePlayer> players;
                try {
                    players = List.of(Bukkit.getOfflinePlayers()).iterator();
                } catch (RuntimeException e) {
                    done.completeExceptionally(new ImportException("couldn't list the players who have played here: " + e, false));
                    return;
                }
                later(() -> step(done, found, () -> {
                    OfflinePlayer player = players.next();
                    if (legacy.hasAccount(player)) {
                        found.add(new Found(player.getUniqueId(), player.getName(), true, BigDecimal.valueOf(legacy.getBalance(player)).toPlainString()));
                    }
                }, players), done);
            });
        }
    }

    // On the main thread's next tick, never right away.
    private void later(Runnable work, CompletableFuture<List<Found>> done) {
        plugin.tasks().nextTick(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
    }

    // A slice now, the rest on later ticks.
    private void step(CompletableFuture<List<Found>> done, List<Found> found, Runnable one, Iterator<?> left) {
        try {
            long until = System.nanoTime() + BUDGET_NANOS;
            while (left.hasNext() && System.nanoTime() < until) {
                one.run();
            }
        } catch (RuntimeException e) {
            done.completeExceptionally(new ImportException(provider + " failed while being read: " + e, false));
            return;
        }
        if (!left.hasNext()) {
            done.complete(found);
            return;
        }
        later(() -> step(done, found, one, left), done);
    }

    private static <T> T other(Class<T> type) {
        for (RegisteredServiceProvider<T> registration : Bukkit.getServicesManager().getRegistrations(type)) {
            if (!VaultServices.ours(registration.getProvider())) {
                return registration.getProvider();
            }
        }
        return null;
    }
}
