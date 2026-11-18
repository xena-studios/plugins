package co.xenastudios.xteleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Teleport costs through Vault: taken after the warmup, never for a teleport that didn't happen. */
class MoneyTest extends ServerTest {

    final Map<UUID, Double> wallets = new ConcurrentHashMap<>();
    PlayerMock sam;

    @BeforeEach
    void economy() {
        server.getServicesManager().register(Economy.class, bank(), MockBukkit.createMockPlugin("Vault"), ServicePriority.Normal);
        // Sam waits out the warmup, which the others skip.
        sam = new TickingPlayer(server, "Sam");
        server.addPlayer(sam);
        sam.teleport(floor(-20, -20));
        wallets.put(sam.getUniqueId(), 10.0);
        wallets.put(alex.getUniqueId(), 10.0);
        set("commands.spawn.cost", 4);
    }

    double wallet(PlayerMock player) {
        return wallets.getOrDefault(player.getUniqueId(), 0.0);
    }

    @Test
    void paidOnceTheWarmupIsOver() {
        sam.performCommand("spawn");
        told(sam, "Teleporting in 3s");
        assertEquals(10, wallet(sam), "nothing taken during the warmup");
        told(sam, "Paid 4");
        told(sam, "Welcome to spawn");
        assertEquals(6, wallet(sam));
    }

    @Test
    void nothingTakenWhenTheWarmupIsCancelled() {
        sam.performCommand("spawn");
        told(sam, "Teleporting in 3s");
        sam.teleport(floor(-30, -30));
        told(sam, "you moved");
        assertEquals(10, wallet(sam));
    }

    @Test
    void refundedWhenTheTeleportFails() {
        plugin.refuseMoves = true;
        alex.performCommand("spawn");
        told(alex, "Paid 4");
        told(alex, "you got back 4");
        told(alex, "didn't work");
        assertEquals(10, wallet(alex));
    }

    @Test
    void refusedWithoutTheMoney() {
        wallets.put(alex.getUniqueId(), 3.0);
        alex.performCommand("spawn");
        told(alex, "That costs 4");
        assertTrue(at(alex, new Location(world, 50, 64, 50)));
        assertEquals(3, wallet(alex));
    }

    @Test
    void tpaherePaidByWhoeverAsked() {
        set("commands.tpahere.cost", 5);
        alex.performCommand("tpahere Steve");
        told(steve, "Alex wants you to teleport to them");
        steve.performCommand("tpaccept");
        told(steve, "Teleported to Alex");
        told(alex, "Paid 5");
        assertEquals(5, wallet(alex));
        assertEquals(0, wallet(steve), "Steve, who went, paid nothing");
    }

    /** Balances in a map, answering the few calls the core's Vault hook makes. */
    private Economy bank() {
        return (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[] {Economy.class}, (self, method, args) -> {
            String name = method.getName();
            if (name.equals("isEnabled")) {
                return true;
            }
            if (name.equals("format")) {
                double amount = (double) args[0];
                return amount == Math.rint(amount) ? Long.toString((long) amount) : Double.toString(amount);
            }
            if (args == null || !(args[0] instanceof OfflinePlayer player)) {
                throw new UnsupportedOperationException(name);
            }
            UUID id = player.getUniqueId();
            return switch (name) {
                case "getBalance" -> wallets.getOrDefault(id, 0.0);
                case "has" -> wallets.getOrDefault(id, 0.0) >= (double) args[1];
                case "withdrawPlayer" -> {
                    double left = wallets.merge(id, -(double) args[1], Double::sum);
                    yield new EconomyResponse((double) args[1], left, EconomyResponse.ResponseType.SUCCESS, null);
                }
                case "depositPlayer" -> {
                    double now = wallets.merge(id, (double) args[1], Double::sum);
                    yield new EconomyResponse((double) args[1], now, EconomyResponse.ResponseType.SUCCESS, null);
                }
                default -> throw new UnsupportedOperationException(name);
            };
        });
    }
}
