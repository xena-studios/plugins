package co.xenastudios.core.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class VaultEconomyTest {

    ServerMock server;
    PlayerMock steve;
    final Map<UUID, Double> balances = new HashMap<>();

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        steve = server.addPlayer("Steve");
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void withoutVaultNothingCanBeTaken() {
        VaultEconomy economy = new VaultEconomy();
        assertFalse(economy.installed());
        assertFalse(economy.ready());
        assertFalse(economy.take(steve, 0), "not even nothing");
        assertFalse(economy.has(steve, 0));
        assertFalse(economy.give(steve, 5));
        assertEquals(0, economy.balance(steve));
        assertNull(economy.format(5));
    }

    @Test
    void theEconomyIsLookedUpWhenUsedSoOneThatRegistersLateCounts() {
        Plugin vault = MockBukkit.createMockPlugin("Vault");
        VaultEconomy economy = new VaultEconomy();
        assertTrue(economy.installed());
        assertFalse(economy.ready(), "Vault, but no economy plugin yet");

        server.getServicesManager().register(Economy.class, fake(), vault, ServicePriority.Normal);
        assertTrue(economy.ready());
        assertTrue(economy.give(steve, 10));
        assertTrue(economy.has(steve, 10));
        assertTrue(economy.take(steve, 4));
        assertFalse(economy.take(steve, 7), "more than they have");
        assertEquals(6, economy.balance(steve));
        assertTrue(economy.take(steve, 0));
        assertEquals("$6.00", economy.format(6));
    }

    /** An economy that keeps balances in a map: just the methods VaultEconomy calls. */
    private Economy fake() {
        return (Economy) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Economy.class}, (proxy, method, args) -> {
            UUID id = args != null && args.length > 0 && args[0] instanceof OfflinePlayer player ? player.getUniqueId() : null;
            double balance = id == null ? 0 : balances.getOrDefault(id, 0.0);
            return switch (method.getName()) {
                case "isEnabled" -> true;
                case "getName" -> "Fake";
                case "has" -> balance >= (double) args[1];
                case "getBalance" -> balance;
                case "format" -> String.format(java.util.Locale.ROOT, "$%.2f", (double) args[0]);
                case "withdrawPlayer" -> {
                    balances.put(id, balance - (double) args[1]);
                    yield new EconomyResponse((double) args[1], balance - (double) args[1], EconomyResponse.ResponseType.SUCCESS, "");
                }
                case "depositPlayer" -> {
                    balances.put(id, balance + (double) args[1]);
                    yield new EconomyResponse((double) args[1], balance + (double) args[1], EconomyResponse.ResponseType.SUCCESS, "");
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "FakeEconomy";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
    }
}
