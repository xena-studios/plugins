package co.xenastudios.xbans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.text.ChatPages;
import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.api.PunishRequest;
import co.xenastudios.xbans.api.XBansApi;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.storage.Store;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.chat.ChatType;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.papermc.paper.chat.ChatRenderer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * xBans on a mock server, through its commands: each way a ban or mute could be dodged is
 * tried, along with history pages, staff limits, templates and warning actions.
 */
class XBansTest {

    ServerMock server;
    WorldMock world;
    XBans plugin;
    PlayerMock steve;
    PlayerMock alex;

    @BeforeEach
    void start() throws Exception {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.load(XBans.class);
        plugin.store().ready().get(10, TimeUnit.SECONDS);
        steve = join("Steve", "203.0.113.9");
        alex = join("Alex", "203.0.113.20");
    }

    // A player who joins from an address of their own; the mock's default is loopback, which xBans never records.
    private PlayerMock join(String name, String address) throws Exception {
        PlayerMock player = new PlayerMock(server, name);
        player.setAddress(new InetSocketAddress(InetAddress.getByName(address), 25565));
        server.addPlayer(player);
        return player;
    }

    // Waits for a player to be told something.
    private List<String> awaitSaid(PlayerMock player, String text) {
        List<String> lines = new ArrayList<>();
        await(() -> {
            lines.addAll(said(player));
            return anyContains(lines, text);
        });
        return lines;
    }

    // A punishment stored straight away, as if made long ago or by a command.
    private void record(PunishmentType type, UUID target, String reason) {
        Punishment p = new Punishment(0, type, target, "Steve", false, reason, null, "Console", Instant.now(), null, "*",
            false, null, null);
        plugin.store().insert(new Entry(p, null), List.of(), null).join();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    /** What {@code player} is told, once {@code command} (run again every half second) replies with {@code text}. */
    private List<String> awaitReply(PlayerMock player, String command, String text) {
        List<String> lines = new ArrayList<>();
        long[] next = {0};
        await(() -> {
            if (System.nanoTime() >= next[0]) {
                player.performCommand(command);
                next[0] = System.nanoTime() + 500_000_000L;
            }
            lines.addAll(said(player));
            return anyContains(lines, text);
        });
        return lines;
    }

    private void console(String command) {
        server.dispatchCommand(server.getConsoleSender(), command);
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

    // Lets queued database work and replies land.
    private void settle() {
        try {
            plugin.store().write((c, b) -> null).get(10, TimeUnit.SECONDS);
            plugin.store().read((c, b) -> null).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        for (int i = 0; i < 5; i++) {
            server.getScheduler().performOneTick();
        }
    }

    private Store.Backend backend() {
        return plugin.store().backend();
    }

    private Entry ban(UUID id) {
        return backend().active().ban(id, null, "server", System.currentTimeMillis());
    }

    private Entry mute(UUID id) {
        return backend().active().mute(id, null, "server", System.currentTimeMillis());
    }

    private AsyncPlayerPreLoginEvent preLogin(UUID id, String name, String address) throws Exception {
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(name, InetAddress.getByName(address), id);
        Thread thread = new Thread(() -> server.getPluginManager().callEvent(event));
        thread.start();
        thread.join(10_000);
        return event;
    }

    private static List<String> said(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return lines;
    }

    private static boolean anyContains(List<String> lines, String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    // ---- Bans ----

    @Test
    void aBanKicksThePlayerAndKeepsThemOut() throws Exception {
        UUID id = steve.getUniqueId();
        console("ban Steve griefing the spawn");
        await(() -> !steve.isOnline());
        Entry ban = ban(id);
        assertNotNull(ban);
        assertEquals("griefing the spawn", ban.punishment().reason());
        assertTrue(ban.punishment().isPermanent());

        AsyncPlayerPreLoginEvent login = preLogin(id, "Steve", "203.0.113.50");
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, login.getLoginResult());
        assertTrue(PlainTextComponentSerializer.plainText().serialize(login.kickMessage()).contains("griefing the spawn"));
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, preLogin(UUID.randomUUID(), "Other", "203.0.113.51").getLoginResult());

        console("unban Steve appealed");
        await(() -> ban(id) == null);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, preLogin(id, "Steve", "203.0.113.50").getLoginResult());
    }

    @Test
    void aBanMadeWhileThePlayerIsConnectingCatchesThemAtJoin() {
        UUID id = steve.getUniqueId();
        // Past the pre-login check, not yet in the world: the ban lands in between.
        steve.disconnect();
        console("ban Steve evading");
        await(() -> ban(id) != null);
        steve.reconnect();
        await(() -> !steve.isOnline());
        assertNotNull(ban(id));
    }

    @Test
    void aTempBanEnds() {
        console("tempban Steve 2h -s caught");
        await(() -> ban(steve.getUniqueId()) != null);
        Entry ban = ban(steve.getUniqueId());
        assertTrue(ban.punishment().silent());
        long left = ban.left(System.currentTimeMillis());
        assertTrue(left > Duration.ofMinutes(119).toMillis() && left <= Duration.ofHours(2).toMillis());
        assertNull(backend().active().ban(steve.getUniqueId(), null, "server", System.currentTimeMillis() + Duration.ofHours(3).toMillis()));
    }

    @Test
    void tempbanNeedsALength() {
        console("tempban Steve griefing");
        settle();
        assertNull(ban(steve.getUniqueId()));
    }

    @Test
    void anIpBanKeepsOutEveryAccountOnIt() throws Exception {
        console("ipban Steve alts everywhere");
        await(() -> !steve.isOnline());
        UUID stranger = UUID.randomUUID();
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, preLogin(stranger, "Stranger", "203.0.113.9").getLoginResult());
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, preLogin(stranger, "Stranger", "203.0.113.10").getLoginResult());
        assertTrue(alex.isOnline(), "another IP isn't touched");

        console("ipban 198.51.100.7 a raw address");
        await(() -> backend().active().ban(UUID.randomUUID(), "198.51.100.7", "server", System.currentTimeMillis()) != null);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, preLogin(stranger, "Stranger", "198.51.100.7").getLoginResult());
        console("unban 198.51.100.7");
        await(() -> backend().active().ban(UUID.randomUUID(), "198.51.100.7", "server", System.currentTimeMillis()) == null);
    }

    @Test
    void loopbackCantBeIpBanned() {
        console("ipban 127.0.0.1 everyone behind the proxy");
        settle();
        assertEquals(0, backend().active().size());
    }

    @Test
    void aShorterBanDoesntReplaceALongerOne() {
        console("ban Steve forever");
        await(() -> ban(steve.getUniqueId()) != null);
        long first = ban(steve.getUniqueId()).id();
        console("tempban Steve 1d shorter");
        settle();
        assertEquals(first, ban(steve.getUniqueId()).id());
    }

    @Test
    void aLongerBanReplacesAShorterOne() {
        steve.disconnect();
        console("tempban Steve 1d first");
        await(() -> ban(steve.getUniqueId()) != null);
        long first = ban(steve.getUniqueId()).id();
        console("ban Steve second");
        await(() -> ban(steve.getUniqueId()).id() != first);
        assertTrue(ban(steve.getUniqueId()).punishment().isPermanent());
        assertEquals(1, backend().active().size());
    }

    @Test
    void scopedBansApplyOnlyOnTheirServer() throws Exception {
        console("ban Steve server:survival elsewhere");
        await(() -> !backend().active().all(PunishmentType.BAN, null, System.currentTimeMillis()).isEmpty());
        assertTrue(steve.isOnline(), "banned on survival, not here");
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED,
            preLogin(steve.getUniqueId(), "Steve", "203.0.113.9").getLoginResult());
    }

    @Test
    void kickRecordsAKick() {
        console("kick Steve -s calm down");
        await(() -> !steve.isOnline());
        settle();
        assertNull(ban(steve.getUniqueId()));
    }

    // ---- Mutes ----

    @Test
    void aMuteBlocksChatCommandsSignsAndBooks() throws Exception {
        console("mute Steve spam");
        await(() -> mute(steve.getUniqueId()) != null);
        said(steve);

        AsyncChatEvent chat = new AsyncChatEvent(true, steve, Set.of(), ChatRenderer.defaultRenderer(),
            Component.text("hi"), Component.text("hi"), SignedMessage.system("hi", null));
        Thread thread = new Thread(() -> server.getPluginManager().callEvent(chat));
        thread.start();
        thread.join(10_000);
        assertTrue(chat.isCancelled(), "chat");

        for (String line : List.of("/msg Alex hi", "/MSG Alex hi", "/minecraft:tell Alex hi", "/w Alex hi", "/r hi",
            "/me waves", "/essentials:msg Alex hi")) {
            PlayerCommandPreprocessEvent command = new PlayerCommandPreprocessEvent(steve, line);
            server.getPluginManager().callEvent(command);
            assertTrue(command.isCancelled(), line);
        }
        // An alias of a blocked command, from another plugin.
        server.getCommandMap().register("mailer", new Command("mail", "", "/mail", List.of("post")) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return true;
            }
        });
        PlayerCommandPreprocessEvent alias = new PlayerCommandPreprocessEvent(steve, "/post Alex hi");
        server.getPluginManager().callEvent(alias);
        assertTrue(alias.isCancelled(), "an alias of /mail");
        PlayerCommandPreprocessEvent spawn = new PlayerCommandPreprocessEvent(steve, "/spawn");
        server.getPluginManager().callEvent(spawn);
        assertFalse(spawn.isCancelled(), "other commands still work");

        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.OAK_SIGN);
        SignChangeEvent sign = new SignChangeEvent(block, steve, List.of(Component.text("buy cheap"),
            Component.empty(), Component.empty(), Component.empty()));
        server.getPluginManager().callEvent(sign);
        assertTrue(sign.isCancelled(), "sign");

        BookMeta book = (BookMeta) server.getItemFactory().getItemMeta(Material.WRITABLE_BOOK);
        BookMeta written = book.clone();
        written.setPages("hello");
        PlayerEditBookEvent edit = new PlayerEditBookEvent(steve, 0, book, written, true);
        server.getPluginManager().callEvent(edit);
        assertTrue(edit.isCancelled(), "book");
        assertTrue(anyContains(said(steve), "Spamming"), "they're told why, with the spam template's reason");

        console("unmute Steve");
        await(() -> mute(steve.getUniqueId()) == null);
        PlayerCommandPreprocessEvent after = new PlayerCommandPreprocessEvent(steve, "/msg Alex hi");
        server.getPluginManager().callEvent(after);
        assertFalse(after.isCancelled());
    }

    @Test
    void aMuteStopsTheOlderChatEventsToo() throws Exception {
        org.bukkit.plugin.Plugin bridge = MockBukkit.createMockPlugin("DiscordBridge");
        List<String> relayed = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            // A plugin that un-cancels chat, then one that relays what isn't cancelled.
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.HIGH)
            public void uncancel(org.bukkit.event.player.AsyncPlayerChatEvent event) {
                event.setCancelled(false);
            }

            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
            public void relay(org.bukkit.event.player.AsyncPlayerChatEvent event) {
                relayed.add(event.getMessage());
            }
        }, bridge);
        server.getPluginManager().callEvent(new org.bukkit.event.server.ServerLoadEvent(
            org.bukkit.event.server.ServerLoadEvent.LoadType.STARTUP));
        console("mute Steve spam");
        await(() -> mute(steve.getUniqueId()) != null);

        org.bukkit.event.player.AsyncPlayerChatEvent chat = new org.bukkit.event.player.AsyncPlayerChatEvent(true, steve,
            "hello discord", new java.util.HashSet<>(server.getOnlinePlayers()));
        Thread thread = new Thread(() -> server.getPluginManager().callEvent(chat));
        thread.start();
        thread.join(10_000);
        assertTrue(chat.isCancelled());
        assertTrue(relayed.isEmpty(), "nothing relayed");

        org.bukkit.event.player.AsyncPlayerChatEvent fine = new org.bukkit.event.player.AsyncPlayerChatEvent(true, alex,
            "hi", new java.util.HashSet<>(server.getOnlinePlayers()));
        Thread other = new Thread(() -> server.getPluginManager().callEvent(fine));
        other.start();
        other.join(10_000);
        assertEquals(List.of("hi"), relayed, "others still chat");
    }

    @Test
    void theSynchronousChatEventIsntListenedToUnlessAnotherPluginDoes() {
        // A listener there would move every message onto the main thread.
        assertEquals(0, org.bukkit.event.player.PlayerChatEvent.getHandlerList().getRegisteredListeners().length);
    }

    @Test
    void aChatListenerAddedAfterStartupIsGuardedFromItsFirstMessage() throws Exception {
        console("mute Steve spam");
        await(() -> mute(steve.getUniqueId()) != null);
        // A script loaded late, or a bridge that just connected: no plugin was enabled, nothing announced it.
        org.bukkit.plugin.Plugin script = MockBukkit.createMockPlugin("LateScript");
        List<String> relayed = new java.util.concurrent.CopyOnWriteArrayList<>();
        org.bukkit.event.Listener late = new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
            public void relay(org.bukkit.event.player.PlayerChatEvent event) {
                relayed.add(event.getMessage());
            }
        };
        org.bukkit.event.HandlerList.unregisterAll(script);
        server.getPluginManager().registerEvents(late, script);

        // As Paper does it: the asynchronous event, then the synchronous one, cancelled if that was.
        org.bukkit.event.player.AsyncPlayerChatEvent first = new org.bukkit.event.player.AsyncPlayerChatEvent(true, steve,
            "hello", new java.util.HashSet<>(server.getOnlinePlayers()));
        Thread thread = new Thread(() -> server.getPluginManager().callEvent(first));
        thread.start();
        thread.join(10_000);
        assertTrue(first.isCancelled());
        org.bukkit.event.player.PlayerChatEvent then = new org.bukkit.event.player.PlayerChatEvent(steve, "hello");
        server.getPluginManager().callEvent(then);
        assertTrue(then.isCancelled());
        assertTrue(relayed.isEmpty(), "nothing relayed");
        // Even if the asynchronous event's result is ignored: the synchronous guard is on.
        org.bukkit.event.player.PlayerChatEvent alone = new org.bukkit.event.player.PlayerChatEvent(steve, "hi again");
        server.getPluginManager().callEvent(alone);
        assertTrue(alone.isCancelled());
        assertTrue(relayed.isEmpty());

        // Once the script stops listening, the next message takes the guard off again.
        org.bukkit.event.HandlerList.unregisterAll(late);
        org.bukkit.event.player.AsyncPlayerChatEvent next = new org.bukkit.event.player.AsyncPlayerChatEvent(true, alex,
            "bye", new java.util.HashSet<>(server.getOnlinePlayers()));
        Thread other = new Thread(() -> server.getPluginManager().callEvent(next));
        other.start();
        other.join(10_000);
        assertFalse(next.isCancelled(), "others still chat");
        assertEquals(0, org.bukkit.event.player.PlayerChatEvent.getHandlerList().getRegisteredListeners().length);
    }

    @Test
    void aBanMadeWhileThePlayerIsConfiguringCatchesThemThere() throws Exception {
        UUID id = UUID.randomUUID();
        console("ban " + id + " connecting");
        await(() -> ban(id) != null);
        PlayerConfigurationConnection connection = configuring(id, "203.0.113.70");
        io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent event =
            new io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent(connection, null);
        server.getPluginManager().callEvent(event);
        assertFalse(event.isAllowed());
        assertTrue(PlainTextComponentSerializer.plainText().serialize(event.getKickMessage()).contains("connecting"));

        // An IP ban, for an account it doesn't name.
        console("ipban 203.0.113.71 the address");
        await(() -> backend().active().ban(UUID.randomUUID(), "203.0.113.71", "server", System.currentTimeMillis()) != null);
        io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent byIp =
            new io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent(configuring(UUID.randomUUID(), "203.0.113.71"), null);
        server.getPluginManager().callEvent(byIp);
        assertFalse(byIp.isAllowed());

        io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent clean =
            new io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent(configuring(UUID.randomUUID(), "203.0.113.72"), null);
        server.getPluginManager().callEvent(clean);
        assertTrue(clean.isAllowed());
    }

    // A connection still configuring, as Paper hands it to PlayerConnectionValidateLoginEvent.
    private PlayerConfigurationConnection configuring(UUID id, String address) throws Exception {
        com.destroystokyo.paper.profile.PlayerProfile profile = server.createProfile(id, "Joining");
        InetSocketAddress socket = new InetSocketAddress(InetAddress.getByName(address), 25565);
        return (PlayerConfigurationConnection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {PlayerConfigurationConnection.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getProfile" -> profile;
                case "getAddress", "getClientAddress" -> socket;
                case "isConnected" -> true;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "connection";
                default -> null;
            });
    }

    @Test
    void anIpMuteSilencesEveryoneOnIt() throws Exception {
        PlayerMock sharer = join("Sharer", "203.0.113.9");
        console("ipmute Steve shared spam");
        await(() -> mute(steve.getUniqueId()) != null);
        PlayerCommandPreprocessEvent command = new PlayerCommandPreprocessEvent(sharer, "/msg Steve hi");
        server.getPluginManager().callEvent(command);
        assertTrue(command.isCancelled(), "same IP");
        PlayerCommandPreprocessEvent other = new PlayerCommandPreprocessEvent(alex, "/msg Steve hi");
        server.getPluginManager().callEvent(other);
        assertFalse(other.isCancelled(), "another IP");
    }

    // ---- Warnings and templates ----

    @Test
    void warningsAddUpToTheirAction() {
        for (int i = 0; i < 3; i++) {
            console("warn Steve language " + i);
            settle();
        }
        await(() -> mute(steve.getUniqueId()) != null);
        Entry mute = mute(steve.getUniqueId());
        assertEquals("Reached 3 warnings", mute.punishment().reason());
        assertTrue(mute.punishment().silent());

        // unwarn looks Steve up before it writes, so its write can come after settle()'s.
        console("unwarn Steve");
        await(() -> plugin.store().activeWarnings(steve.getUniqueId()).join().size() == 2);
        settle();
        assertEquals(2, plugin.store().activeWarnings(steve.getUniqueId()).join().size(), "only the newest is taken off");
    }

    @Test
    void templatesClimbTheirLadder() throws Exception {
        steve.disconnect();
        console("ban Steve hacking");
        await(() -> ban(steve.getUniqueId()) != null);
        Entry first = ban(steve.getUniqueId());
        assertEquals("Hacking", first.punishment().reason());
        assertEquals("hacking", first.punishment().template());
        assertEquals(Duration.ofDays(7), Duration.between(first.punishment().created(), first.punishment().expires()));

        // The second time, the next step: 30 days, replacing the 7.
        console("ban Steve hacking");
        await(() -> ban(steve.getUniqueId()).id() != first.id());
        Entry second = ban(steve.getUniqueId());
        assertEquals(Duration.ofDays(30), Duration.between(second.punishment().created(), second.punishment().expires()));

        console("ban Steve hacking");
        await(() -> ban(steve.getUniqueId()).id() != second.id());
        assertTrue(ban(steve.getUniqueId()).punishment().isPermanent());
    }

    // ---- Staff limits ----

    @Test
    void staffCantPunishThoseWhoOutrankThem() {
        steve.addAttachment(plugin, "xbans.ban", true);
        steve.addAttachment(plugin, "xbans.weight.10", true);
        alex.addAttachment(plugin, "xbans.weight.20", true);
        steve.performCommand("ban Alex no");
        awaitSaid(steve, "outranks you");
        assertNull(ban(alex.getUniqueId()));
        // Offline, the weight they had when they last joined counts.
        UUID alexId = alex.getUniqueId();
        plugin.store().seen(alexId, "Alex", null, 20, false);
        alex.disconnect();
        settle();
        steve.performCommand("ban Alex no");
        awaitSaid(steve, "outranks you");
        assertNull(ban(alexId));
    }

    @Test
    void exemptPlayersCantBePunished() {
        steve.addAttachment(plugin, "xbans.ban", true);
        alex.addAttachment(plugin, "xbans.exempt", true);
        steve.performCommand("ban Alex no");
        awaitSaid(steve, "can't be punished");
        assertNull(ban(alex.getUniqueId()));
    }

    @Test
    void helpersAreCapped() {
        steve.addAttachment(plugin, "xbans.ban", true);
        steve.addAttachment(plugin, "xbans.limit.helper", true);
        steve.performCommand("ban Alex 7d too long");
        awaitSaid(steve, "at most 1d");
        assertNull(ban(alex.getUniqueId()));
        steve.performCommand("ban Alex 12h fine");
        await(() -> ban(alex.getUniqueId()) != null);
    }

    @Test
    void templatesAreHeldToWeightsAndCaps() {
        steve.addAttachment(plugin, "xbans.ban", true);
        steve.addAttachment(plugin, "xbans.limit.helper", true);
        // A template needs its own permission, which operators have and a helper isn't given.
        steve.performCommand("ban Alex hacking");
        awaitSaid(steve, "can't use the hacking template");
        steve.addAttachment(plugin, "xbans.template.hacking", true);
        alex.addAttachment(plugin, "xbans.exempt", true);
        steve.performCommand("ban Alex hacking");
        awaitSaid(steve, "can't be punished");
        alex.addAttachment(plugin, "xbans.exempt", false);
        // The template's 7 days is past a helper's 1d: refused, so repeating it can't climb to permanent.
        steve.performCommand("ban Alex hacking");
        awaitSaid(steve, "at most 1d");
        steve.performCommand("ban Alex 3d hacking");
        awaitSaid(steve, "at most 1d");
        assertNull(ban(alex.getUniqueId()));
        // With the bypass, a template's own lengths go past the cap; a typed one still doesn't.
        steve.addAttachment(plugin, Power.BYPASS_TEMPLATES, true);
        steve.performCommand("ban Alex 3d hacking");
        awaitSaid(steve, "at most 1d");
        steve.performCommand("ban Alex hacking");
        await(() -> ban(alex.getUniqueId()) != null);
    }

    @Test
    void operatorsArentHelpers() {
        steve.setOp(true);
        steve.performCommand("ban Alex 7d an operator isn't capped");
        await(() -> ban(alex.getUniqueId()) != null);
    }

    // ---- IP punishments and staff rules ----

    @Test
    void anAccountBanDoesntLiftAnIpBan() throws Exception {
        UUID stranger = UUID.randomUUID();
        console("ipban Steve alts");
        await(() -> !steve.isOnline());
        console("ban Steve the account as well");
        settle();
        assertNotNull(backend().active().ban(UUID.randomUUID(), backend().keys().key("203.0.113.9"), "server",
            System.currentTimeMillis()), "the IP ban is still in force");
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, preLogin(stranger, "Stranger", "203.0.113.9").getLoginResult());

        // A temporary IP ban and a permanent account ban: neither covers the other, so both stay.
        console("tempipban Alex 1d shared");
        await(() -> !alex.isOnline());
        console("ban Alex for good");
        await(() -> backend().active().onScope(PunishmentType.BAN, alex.getUniqueId(), null, "*", System.currentTimeMillis())
            .stream().anyMatch(entry -> entry.punishment().isPermanent()));
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, preLogin(stranger, "Stranger", "203.0.113.20").getLoginResult());
        assertEquals(2, backend().active().onScope(PunishmentType.BAN, alex.getUniqueId(), null, "*",
            System.currentTimeMillis()).size(), "both in force");

        // An IP ban reaches the account and the IP, so it replaces an account ban it outlasts.
        PlayerMock carl = join("Carl", "203.0.113.30");
        console("tempban Carl 1d first");
        await(() -> !carl.isOnline());
        long first = ban(carl.getUniqueId()).id();
        console("ipban Carl wider");
        await(() -> ban(carl.getUniqueId()).id() != first);
        assertTrue(ban(carl.getUniqueId()).punishment().ipBased());
        assertNotNull(plugin.store().find(first).join().punishment().removal());
    }

    @Test
    void anAccountMuteDoesntLiftAnIpMute() throws Exception {
        console("ipmute Steve shared spam");
        await(() -> mute(steve.getUniqueId()) != null);
        long ipMute = mute(steve.getUniqueId()).id();
        console("mute Steve again");
        settle();
        assertNull(plugin.store().find(ipMute).join().punishment().removal(), "the IP mute is still in force");
        assertTrue(backend().active().mute(UUID.randomUUID(), backend().keys().key("203.0.113.9"), "server",
            System.currentTimeMillis()) != null);
    }

    @Test
    void ipBansAreHeldToEveryAccountsExemptionAndWeight() throws Exception {
        PlayerMock owner = join("Owner", "203.0.113.40");
        owner.addAttachment(plugin, Power.EXEMPT, true);
        steve.addAttachment(plugin, "xbans.ipban", true);
        steve.addAttachment(plugin, "xbans.weight.10", true);
        settle();
        // By the owner's address, typed: they're on it, and exempt.
        steve.performCommand("ipban 203.0.113.40 locked out");
        awaitSaid(steve, "Owner can't be punished");
        assertNull(backend().active().ban(UUID.randomUUID(), backend().keys().key("203.0.113.40"), "server",
            System.currentTimeMillis()));
        // An account seen on the IP who's offline now, recorded as exempt.
        plugin.store().seen(UUID.randomUUID(), "Admin", "203.0.113.20", 0, true);
        settle();
        steve.performCommand("ipban Alex shared");
        awaitSaid(steve, "Admin can't be punished");
        assertNull(ban(alex.getUniqueId()));
        // A weight as high as the sender's on the IP.
        PlayerMock mod = join("Mod", "203.0.113.50");
        mod.addAttachment(plugin, "xbans.weight.10", true);
        steve.performCommand("ipban 203.0.113.50");
        awaitSaid(steve, "Mod outranks you");
        // The console has no limits.
        console("ipban 203.0.113.40 the console may");
        await(() -> !owner.isOnline());
    }

    @Test
    void anIpBanShowsNoAddress() throws Exception {
        said(alex);
        console("ipban 198.51.100.7 a raw address");
        List<String> lines = awaitSaid(alex, "IP-banned");
        assertFalse(anyContains(lines, "198.51.100.7"), String.join("\n", lines));
        assertTrue(anyContains(lines, "198.51.x.x"), String.join("\n", lines));
        Entry ban = backend().active().ban(UUID.randomUUID(), "198.51.100.7", "server", System.currentTimeMillis());
        assertEquals("198.51.x.x", ban.punishment().targetName());
    }

    @Test
    void unbansNeedTheWeightOfWhoeverBanned() throws Exception {
        steve.addAttachment(plugin, "xbans.ban", true);
        steve.addAttachment(plugin, "xbans.unban", true);
        steve.addAttachment(plugin, "xbans.weight.10", true);
        PlayerMock carl = join("Carl", "203.0.113.30");
        alex.disconnect();
        carl.disconnect();
        console("ban Alex by the console");
        await(() -> ban(alex.getUniqueId()) != null);
        steve.performCommand("unban Alex");
        awaitSaid(steve, "outranks you");
        assertNotNull(ban(alex.getUniqueId()));
        // Their own, they can always lift.
        steve.performCommand("ban Carl mine");
        await(() -> ban(carl.getUniqueId()) != null);
        steve.performCommand("unban Carl");
        await(() -> ban(carl.getUniqueId()) == null);
        // With the override, anyone's.
        steve.addAttachment(plugin, Punisher.OVERRIDE, true);
        steve.performCommand("unban Alex");
        await(() -> ban(alex.getUniqueId()) == null);
    }

    // ---- Replacing: the rule's cases, each on a real server ----

    private void staff(PlayerMock player, int weight) {
        for (String command : List.of("ban", "tempban", "ipban", "tempipban", "mute", "tempmute", "unban", "unmute")) {
            player.addAttachment(plugin, "xbans." + command, true);
        }
        player.addAttachment(plugin, "xbans.weight." + weight, true);
    }

    private List<Entry> bansOf(UUID id) {
        return backend().active().onScope(PunishmentType.BAN, id, null, "*", System.currentTimeMillis());
    }

    @Test
    void reBanningAPermanentBanChangesItsReason() {
        alex.disconnect();
        console("ban Alex first reason");
        await(() -> ban(alex.getUniqueId()) != null);
        long first = ban(alex.getUniqueId()).id();
        console("ban Alex second reason");
        await(() -> ban(alex.getUniqueId()).id() != first);
        assertEquals("second reason", ban(alex.getUniqueId()).punishment().reason());
        assertEquals(1, bansOf(alex.getUniqueId()).size());
        assertEquals(Store.REPLACED_REASON, plugin.store().find(first).join().punishment().removal().reason());
    }

    @Test
    void aShorterBanIsRefusedWithHowToShortenIt() {
        alex.disconnect();
        steve.addAttachment(plugin, Punisher.OVERRIDE, true);
        staff(steve, 10);
        steve.performCommand("ban Alex forever");
        await(() -> ban(alex.getUniqueId()) != null);
        said(steve);
        steve.performCommand("tempban Alex 1d shorter");
        List<String> lines = awaitSaid(steve, "already banned");
        assertTrue(anyContains(lines, "Unban them first"), String.join("\n", lines));
        assertEquals(1, bansOf(alex.getUniqueId()).size());
    }

    @Test
    void aJuniorCantEscapeASeniorsBanByReplacingIt() {
        alex.disconnect();
        staff(steve, 10);
        console("tempban Alex 1d the console's");
        await(() -> ban(alex.getUniqueId()) != null);
        long console = ban(alex.getUniqueId()).id();

        // Longer: both stay in force, and the junior's unban lifts only their own.
        steve.performCommand("tempban Alex 2d mine");
        await(() -> bansOf(alex.getUniqueId()).size() == 2);
        assertNull(plugin.store().find(console).join().punishment().removal(), "the console's isn't replaced");
        steve.performCommand("unban Alex");
        await(() -> bansOf(alex.getUniqueId()).size() == 1);
        assertEquals(console, ban(alex.getUniqueId()).id(), "still banned by the console");

        // By IP, which would reach the account too: the same.
        steve.performCommand("ipban Alex wider");
        await(() -> bansOf(alex.getUniqueId()).size() == 2);
        assertNull(plugin.store().find(console).join().punishment().removal());

        // A mute: the same, by the console's mute.
        console("tempmute Alex 1d quiet");
        await(() -> mute(alex.getUniqueId()) != null);
        long consoleMute = mute(alex.getUniqueId()).id();
        steve.performCommand("mute Alex louder");
        await(() -> backend().active().onScope(PunishmentType.MUTE, alex.getUniqueId(), null, "*",
            System.currentTimeMillis()).size() == 2);
        assertNull(plugin.store().find(consoleMute).join().punishment().removal());
    }

    @Test
    void aJuniorCantReissueASeniorsPermanentBan() {
        alex.disconnect();
        staff(steve, 10);
        console("ban Alex the console's");
        await(() -> ban(alex.getUniqueId()) != null);
        long console = ban(alex.getUniqueId()).id();
        said(steve);
        steve.performCommand("ban Alex my reason");
        List<String> lines = awaitSaid(steve, "outranks you");
        assertFalse(anyContains(lines, "Unban them first"), "advice they can't follow");
        assertEquals(console, ban(alex.getUniqueId()).id());
        assertEquals(1, bansOf(alex.getUniqueId()).size());
        // Shorter: refused the same way.
        steve.performCommand("tempban Alex 1d shorter");
        awaitSaid(steve, "outranks you");
        assertEquals(1, bansOf(alex.getUniqueId()).size());

        // With the weight to lift it, or the override, they may replace it.
        steve.addAttachment(plugin, Punisher.OVERRIDE, true);
        steve.performCommand("ban Alex my reason");
        await(() -> ban(alex.getUniqueId()).id() != console);
        assertEquals("my reason", ban(alex.getUniqueId()).punishment().reason());
    }

    @Test
    void aMuteNeverReplacesABan() {
        alex.disconnect();
        console("ban Alex banned");
        await(() -> ban(alex.getUniqueId()) != null);
        long banned = ban(alex.getUniqueId()).id();
        console("mute Alex muted");
        await(() -> mute(alex.getUniqueId()) != null);
        assertEquals(banned, ban(alex.getUniqueId()).id(), "both in force");
        assertNull(plugin.store().find(banned).join().punishment().removal());
        console("ban Alex banned again");
        await(() -> ban(alex.getUniqueId()).id() != banned);
        assertNotNull(mute(alex.getUniqueId()), "and a ban never replaces a mute");
    }

    @Test
    void anUnbanOnAServerThatMissedTheBanStillLiftsIt() {
        alex.disconnect();
        console("ban Alex missed");
        await(() -> ban(alex.getUniqueId()) != null);
        long id = ban(alex.getUniqueId()).id();
        // As if this server never heard of it: memory has drifted from the database.
        backend().active().remove(id);
        console("unban Alex");
        await(() -> plugin.store().find(id).join().punishment().removal() != null);
    }

    // ---- History ----

    @Test
    void historyPagesStayPutAsNewPunishmentsArrive() {
        UUID id = steve.getUniqueId();
        for (int i = 1; i <= 25; i++) {
            record(PunishmentType.KICK, id, "number " + i);
        }
        int perPage = plugin.config().lists().perPage();
        Store.Filter filter = Store.Filter.target(id, null);
        ChatPages.Page<Entry> first = plugin.cursors().page("test", filter, 1, perPage).join();
        assertEquals(25, first.total());
        assertEquals(3, first.pages());
        assertEquals("number 25", first.items().get(0).punishment().reason());
        // A page jumped to straight away starts in the right place.
        ChatPages.Page<Entry> third = plugin.cursors().page("other", filter, 3, perPage).join();
        assertEquals(List.of("number 5", "number 4", "number 3", "number 2", "number 1"),
            third.items().stream().map(entry -> entry.punishment().reason()).toList());

        record(PunishmentType.KICK, id, "number 26");
        ChatPages.Page<Entry> second = plugin.cursors().page("test", filter, 2, perPage).join();
        assertEquals("number 15", second.items().get(0).punishment().reason(), "page 2 carries on below page 1");
    }

    @Test
    void historyCommandsReply() {
        console("warn Steve 1h first");
        console("kick Alex second");
        settle();
        steve.setOp(true);
        said(steve);
        // warn and kick look the player up before they write, so their writes can come
        // after settle()'s: each lookup is asked again until it shows them.
        List<String> lines = awaitReply(steve, "history Steve", "Steve's history (1)");
        assertTrue(anyContains(lines, "first"), String.join("\n", lines));
        awaitReply(steve, "staffhistory console kicks", "second");
        awaitReply(steve, "warnings", "1 active warnings");
    }

    @Test
    void pruneDeletesOnlyWhatsOver() {
        console("warn Steve 1h one");
        console("kick Steve two");
        settle();
        console("ban " + steve.getUniqueId() + " three");
        settle();
        UUID id = steve.getUniqueId();
        long before = plugin.cursors().page("p", Store.Filter.target(id, null), 1, 10).join().total();
        assertEquals(3, before);
        console("prunehistory Steve");
        settle();
        assertEquals(3, plugin.cursors().page("p", Store.Filter.target(id, null), 1, 10).join().total(), "a dry run first");
        console("prunehistory Steve confirm");
        settle();
        // The kick is gone; the warning and the ban are still in force.
        assertEquals(2, plugin.cursors().page("p", Store.Filter.target(id, null), 1, 10).join().total());
    }

    @Test
    void unbanningKeepsTheHistory() {
        steve.disconnect();
        console("ban Steve one");
        await(() -> ban(steve.getUniqueId()) != null);
        long id = ban(steve.getUniqueId()).id();
        console("unban #" + id + " sorry");
        await(() -> ban(steve.getUniqueId()) == null);
        Entry kept = plugin.store().find(id).join();
        assertNotNull(kept.punishment().removal());
        assertEquals("Console", kept.punishment().removal().staffName());
        assertEquals("sorry", kept.punishment().removal().reason());
    }

    @Test
    void altsShareAnIp() throws Exception {
        PlayerMock sharer = join("Sharer", "203.0.113.9");
        settle();
        assertEquals(List.of(sharer.getUniqueId()),
            plugin.store().alts(steve.getUniqueId(), null).join().stream().map(Store.IpUse::id).toList());
    }

    // ---- The API ----

    @Test
    void theApiChecksPunishesAndRevokes() throws Exception {
        XBansApi api = XBansApi.get();
        assertFalse(api.isBanned(steve.getUniqueId()));
        Punishment made = api.punish(PunishRequest.of(PunishmentType.MUTE, steve.getUniqueId(), "from an anti-cheat")
            .withDuration(Duration.ofHours(1)).withStaff(null, "AntiCheat")).get(10, TimeUnit.SECONDS);
        assertTrue(made.id() > 0);
        assertEquals("AntiCheat", made.staffName());
        assertTrue(api.isMuted(steve.getUniqueId()));
        assertEquals(made.id(), api.getActiveMute(steve.getUniqueId()).orElseThrow().id());
        assertEquals(1, api.history(steve.getUniqueId(), 1, 10).get(10, TimeUnit.SECONDS).total());
        assertTrue(api.revoke(steve.getUniqueId(), PunishmentType.MUTE, null, "AntiCheat", "")
            .get(10, TimeUnit.SECONDS).isPresent());
        assertFalse(api.isMuted(steve.getUniqueId()));
        assertTrue(api.revoke(steve.getUniqueId(), PunishmentType.MUTE, null, "AntiCheat", "")
            .get(10, TimeUnit.SECONDS).isEmpty(), "nothing left to revoke");
        assertEquals(Instant.class, made.created().getClass());
    }
}
