package co.xenastudios.xmoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xmoney.api.TransactionKind;
import co.xenastudios.xmoney.api.TransactionResult;
import co.xenastudios.xmoney.api.XMoneyApi;
import co.xenastudios.xmoney.api.event.BalanceChangedEvent;
import co.xenastudios.xmoney.api.event.PreTransactionEvent;
import co.xenastudios.xmoney.ledger.Account;
import co.xenastudios.xmoney.money.Money;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** xMoney on a mock server: commands, Vault, the API, placeholders and events. */
@SuppressWarnings("deprecation")
class XMoneyTest {

    ServerMock server;
    XMoney plugin;
    PlayerMock steve;
    PlayerMock alex;
    ConsoleCommandSenderMock console;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(XMoney.class);
        steve = server.addPlayer("Steve");
        alex = server.addPlayer("Alex");
        console = server.getConsoleSender();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    private void await(BooleanSupplier condition) {
        long until = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < until, "timed out");
            server.getScheduler().performOneTick();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Waits for a message to arrive that contains {@code text}, skipping others. */
    private String heard(org.mockbukkit.mockbukkit.command.MessageTarget target, String text) {
        String[] found = {null};
        await(() -> {
            net.kyori.adventure.text.Component next;
            while ((next = target.nextComponentMessage()) != null) {
                String line = plain(next);
                if (line.contains(text)) {
                    found[0] = line;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    private Account account(PlayerMock player) {
        return plugin.bank().accounts().get(player.getUniqueId());
    }

    private long units(String amount) {
        return new BigDecimal(amount).movePointRight(Money.SCALE).longValueExact();
    }

    private void give(String name, String amount) {
        server.dispatchCommand(console, "eco give " + name + " " + amount);
        heard(console, "Gave");
    }

    @Test
    void joiningPlayersHaveAccounts() {
        assertNotNull(account(steve));
        assertEquals(0, account(steve).balance());
        assertEquals(2, plugin.bank().accounts().ranked());
    }

    @Test
    void staffGiveTakeSetAndReset() {
        give("Steve", "100");
        assertEquals(units("100"), account(steve).balance());
        server.dispatchCommand(console, "eco take Steve 30.5");
        heard(console, "Took");
        assertEquals(units("69.5"), account(steve).balance());
        server.dispatchCommand(console, "eco take Steve 1000");
        assertTrue(heard(console, "only has").contains("$69.50"));
        server.dispatchCommand(console, "eco set Steve 1k");
        heard(console, "Set");
        assertEquals(units("1000"), account(steve).balance());
        server.dispatchCommand(console, "eco reset Steve");
        heard(console, "Reset");
        assertEquals(0, account(steve).balance());
        // A name nobody has played under goes to Mojang, which MockBukkit can't: the boot checks cover it.
    }

    @Test
    void playersPayEachOther() {
        give("Steve", "100");
        steve.performCommand("pay Alex 30");
        assertTrue(heard(steve, "Sent").contains("$70.00"));
        assertTrue(heard(alex, "Received").contains("$30.00"));
        assertEquals(units("70"), account(steve).balance());
        assertEquals(units("30"), account(alex).balance());

        steve.performCommand("pay Steve 1");
        heard(steve, "can't pay yourself");
        steve.performCommand("pay Alex lots");
        heard(steve, "isn't an amount");
        steve.performCommand("pay Alex 1.005");
        heard(steve, "at most 2 decimals");
        steve.performCommand("pay Alex 0");
        heard(steve, "The least you can send");
        steve.performCommand("pay Alex 500");
        heard(steve, "You don't have");
        assertEquals(units("70"), account(steve).balance());
    }

    @Test
    void largePaymentsAskFirst() throws Exception {
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        Files.writeString(config, Files.readString(config).replace("confirm-above: 0", "confirm-above: 50"));
        plugin.reload();
        give("Steve", "100");
        steve.performCommand("pay Alex 60");
        assertTrue(heard(steve, "Send").contains("[Confirm]"));
        assertEquals(units("100"), account(steve).balance(), "nothing moves until it's confirmed");
        steve.performCommand("pay Alex 60.00 confirm");
        heard(steve, "Sent");
        assertEquals(units("40"), account(steve).balance());
    }

    @Test
    void balanceAndBaltop() {
        give("Steve", "250");
        give("Alex", "1250.5");
        steve.performCommand("balance");
        assertTrue(heard(steve, "Balance").contains("$250.00"));
        server.dispatchCommand(console, "balance Alex");
        assertTrue(heard(console, "Alex").contains("$1,250.50"));
        steve.performCommand("balance Alex");
        heard(steve, "permission");
        server.dispatchCommand(console, "baltop");
        assertTrue(heard(console, "Richest").contains("$1,500.50"));
        assertTrue(heard(console, "#1").contains("Alex"));
        assertTrue(heard(console, "#2").contains("Steve"));
    }

    @Test
    void historyShowsWhatHappened() {
        give("Steve", "100");
        steve.performCommand("pay Alex 25");
        heard(steve, "Sent");
        server.dispatchCommand(console, "eco history Steve");
        assertTrue(heard(console, "History of").contains("2 changes"));
        assertTrue(heard(console, "paid Alex").contains("-$25.00"));
        assertTrue(heard(console, "given by Console").contains("+$100.00"));
        server.dispatchCommand(console, "eco history Alex");
        assertTrue(heard(console, "from Steve").contains("+$25.00"));
    }

    @Test
    void vaultFindsXMoney() {
        net.milkbowl.vault.economy.Economy v1 = Bukkit.getServicesManager().load(net.milkbowl.vault.economy.Economy.class);
        net.milkbowl.vault2.economy.Economy v2 = Bukkit.getServicesManager().load(net.milkbowl.vault2.economy.Economy.class);
        assertEquals("xMoney", v1.getName());
        assertEquals("xMoney", v2.getName());
        assertTrue(v1.depositPlayer(steve, 12.345).transactionSuccess());
        assertEquals(12.35, v1.getBalance(steve), 1e-9);
        assertEquals(new BigDecimal("12.35"), v2.getBalance("Test", steve.getUniqueId()));
        assertFalse(v1.withdrawPlayer(steve, Double.NaN).transactionSuccess());
        assertFalse(v1.depositPlayer(steve, -1).transactionSuccess());
        assertEquals("$12.35", v1.format(12.345));
        assertEquals(units("12.35"), account(steve).balance());
    }

    @Test
    void theApiRecordsThePlugin() {
        XMoneyApi api = XMoneyApi.get();
        TransactionResult paid = api.deposit(plugin, steve.getUniqueId(), new BigDecimal("5"), "vote reward").join();
        assertTrue(paid.success());
        assertEquals(new BigDecimal("5.00"), paid.balance());
        assertEquals(TransactionResult.Status.INSUFFICIENT_FUNDS,
            api.withdraw(plugin, steve.getUniqueId(), new BigDecimal("6"), "teleport").join().status());
        assertEquals(TransactionResult.Status.INVALID_AMOUNT,
            api.deposit(plugin, steve.getUniqueId(), new BigDecimal("-1"), "").join().status());
        assertTrue(api.transfer(plugin, steve.getUniqueId(), alex.getUniqueId(), new BigDecimal("2"), "gift").join().success());
        assertEquals(new BigDecimal("3.00"), api.balance(steve.getUniqueId()));
        assertEquals("Steve", api.top(1, 1).getFirst().name());
        assertEquals("$1,234.50", api.format(new BigDecimal("1234.5")));
        assertEquals(2, api.currency().decimals());
        var history = api.history(steve.getUniqueId(), 1);
        await(history::isDone);
        assertEquals(2, history.join().total());
        assertEquals(TransactionKind.PLUGIN, history.join().entries().getFirst().kind());
        assertEquals(plugin.getName(), history.join().entries().getFirst().source());
        assertEquals("gift", history.join().entries().getFirst().reason());
    }

    @Test
    void eventsCanRefuseAndHearChanges() {
        List<BalanceChangedEvent> changed = new CopyOnWriteArrayList<>();
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void on(PreTransactionEvent event) {
                if (event.change().compareTo(new BigDecimal("-50")) < 0) {
                    event.setCancelled(true);
                }
            }

            @EventHandler
            public void on(BalanceChangedEvent event) {
                changed.add(event);
            }
        }, plugin);
        XMoneyApi api = XMoneyApi.get();
        api.deposit(plugin, steve.getUniqueId(), new BigDecimal("100"), "").join();
        assertEquals(TransactionResult.Status.CANCELLED, api.withdraw(plugin, steve.getUniqueId(), new BigDecimal("60"), "").join().status());
        assertTrue(api.withdraw(plugin, steve.getUniqueId(), new BigDecimal("40"), "").join().success());
        await(() -> changed.size() == 2);
        assertEquals(new BigDecimal("-40.0000"), changed.get(1).change());
        assertEquals(new BigDecimal("60.0000"), changed.get(1).balance());
    }

    @Test
    void placeholders() {
        give("Alex", "1234.5");
        Bank bank = plugin.bank();
        assertEquals("$1,234.50", co.xenastudios.xmoney.hook.PlaceholderValues.resolve(bank, account(alex), "balance"));
        assertEquals("1234.50", co.xenastudios.xmoney.hook.PlaceholderValues.resolve(bank, account(alex), "balance_raw"));
        assertEquals("1.2k", co.xenastudios.xmoney.hook.PlaceholderValues.resolve(bank, account(alex), "balance_short"));
        assertEquals("Alex", co.xenastudios.xmoney.hook.PlaceholderValues.resolve(bank, null, "top_1_name"));
        assertEquals("$1,234.50", co.xenastudios.xmoney.hook.PlaceholderValues.resolve(bank, null, "top_1_balance"));
        assertEquals("", co.xenastudios.xmoney.hook.PlaceholderValues.resolve(bank, null, "top_9_name"));
        assertNull(co.xenastudios.xmoney.hook.PlaceholderValues.resolve(bank, null, "nonsense"));
    }

    @Test
    void reloadKeepsEveryBalance() {
        give("Steve", "42");
        server.dispatchCommand(console, "xmoney reload");
        heard(console, "Reloaded");
        assertEquals(units("42"), account(steve).balance());
        server.dispatchCommand(console, "xmoney status");
        assertTrue(heard(console, "Problem").contains("none"));
    }
}
