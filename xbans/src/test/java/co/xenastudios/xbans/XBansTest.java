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
        plugin.store().insert(new Entry(p, null), null, null, () -> {}).join();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
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

        console("unwarn Steve");
        settle();
        int active = plugin.store().activeWarnings(steve.getUniqueId()).join().size();
        assertEquals(2, active);
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
    void operatorsArentHelpers() {
        steve.setOp(true);
        steve.performCommand("ban Alex 7d an operator isn't capped");
        await(() -> ban(alex.getUniqueId()) != null);
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
        steve.performCommand("history Steve");
        List<String> lines = awaitSaid(steve, "Steve's history (1)");
        assertTrue(anyContains(lines, "first"), String.join("\n", lines));
        steve.performCommand("staffhistory console kicks");
        awaitSaid(steve, "second");
        steve.performCommand("warnings");
        awaitSaid(steve, "1 active warnings");
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
