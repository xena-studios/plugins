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
 * otherwise every player who has played here is asked about. Economy plugins expect to be
 * called on the main thread, so balances are read there, 500 a tick, so even a big server
 * doesn't stall.
 */
@SuppressWarnings("deprecation") // Vault 1's Economy, which most economy plugins still register.
public final class VaultBalances implements Source {

    static final int PER_TICK = 500;

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
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
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
            Iterator<Map.Entry<UUID, String>> accounts = Map.copyOf(unlocked.getUUIDNameMap()).entrySet().iterator();
            step(done, found, () -> {
                Map.Entry<UUID, String> account = accounts.next();
                BigDecimal balance = unlocked.getBalance(XMoney.NAME, account.getKey());
                found.add(new Found(account.getKey(), account.getValue(), true, balance == null ? "0" : balance.toPlainString()));
            }, accounts);
        } else {
            provider = legacy.getName();
            Iterator<OfflinePlayer> players = List.of(Bukkit.getOfflinePlayers()).iterator();
            step(done, found, () -> {
                OfflinePlayer player = players.next();
                if (legacy.hasAccount(player)) {
                    found.add(new Found(player.getUniqueId(), player.getName(), true, BigDecimal.valueOf(legacy.getBalance(player)).toPlainString()));
                }
            }, players);
        }
    }

    // A slice now, the rest on later ticks.
    private void step(CompletableFuture<List<Found>> done, List<Found> found, Runnable one, Iterator<?> left) {
        try {
            for (int i = 0; i < PER_TICK && left.hasNext(); i++) {
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
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> step(done, found, one, left), 1);
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
