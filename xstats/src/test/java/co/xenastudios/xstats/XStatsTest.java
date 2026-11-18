package co.xenastudios.xstats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xstats.api.Period;
import co.xenastudios.xstats.api.StatUnit;
import co.xenastudios.xstats.api.XStatsApi;
import co.xenastudios.xstats.api.CustomStat;
import co.xenastudios.xstats.api.ProfileLine;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Statistic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** xStats on a mock server: a save with players online, then /stats, /top and the API. */
class XStatsTest {

    ServerMock server;
    XStats plugin;
    PlayerMock steve;
    PlayerMock alex;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(XStats.class);
        await(() -> plugin.engine().ready());
        steve = server.addPlayer("Steve");
        alex = server.addPlayer("Alex");
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    private void save() {
        AtomicBoolean done = new AtomicBoolean();
        assertTrue(plugin.collector().begin(() -> done.set(true)));
        await(done::get);
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

    private String said(PlayerMock player) {
        StringBuilder text = new StringBuilder();
        net.kyori.adventure.text.Component message;
        while ((message = player.nextComponentMessage()) != null) {
            text.append(plain(message)).append('\n');
        }
        return text.toString();
    }

    @Test
    void aSaveReadsPlayersAndRanksThem() {
        steve.setStatistic(Statistic.PLAYER_KILLS, 4);
        alex.setStatistic(Statistic.PLAYER_KILLS, 9);
        save();
        steve.setStatistic(Statistic.PLAYER_KILLS, 6);
        save();
        XStatsApi api = XStatsApi.get();
        assertEquals(6, api.getStat(steve.getUniqueId(), "kills", Period.ALL));
        assertEquals(2, api.getStat(steve.getUniqueId(), "kills", Period.DAILY), "only what happened since the first reading");
        assertEquals(1, api.getRank(alex.getUniqueId(), "kills", Period.ALL));
        assertEquals(2, api.getRank(steve.getUniqueId(), "kills", Period.ALL));
        assertEquals(1, api.getRank(steve.getUniqueId(), "kills", Period.DAILY));
        assertEquals("Alex", api.getTop("kills", Period.ALL, 5).getFirst().name());
        assertEquals(1, api.getStat(steve.getUniqueId(), "sessions", Period.ALL));

        said(steve);
        steve.performCommand("top kills");
        String top = said(steve);
        assertTrue(top.contains("1. Alex - 9"), top);
        assertTrue(top.contains("2. Steve - 6"), top);
        steve.performCommand("top kills daily");
        assertTrue(said(steve).contains("1. Steve - 2"));
        steve.performCommand("stats Alex");
        await(() -> steve.nextComponentMessage() != null || false);
    }

    @Test
    void statsShowsAProfile() {
        steve.setStatistic(Statistic.DEATHS, 2);
        steve.setStatistic(Statistic.PLAYER_KILLS, 5);
        save();
        said(steve);
        steve.performCommand("stats");
        StringBuilder text = new StringBuilder();
        await(() -> {
            net.kyori.adventure.text.Component message = steve.nextComponentMessage();
            if (message != null) {
                text.append(plain(message)).append('\n');
            }
            return text.toString().contains("Raids won");
        });
        assertTrue(text.toString().contains("Steve's stats"), text.toString());
        assertTrue(text.toString().contains("Player kills: 5 #1"), text.toString());
        assertTrue(text.toString().contains("Deaths: 2"), text.toString());
    }

    @Test
    void statsPrivateHidesFromTheWebsite() {
        save();
        said(steve);
        steve.performCommand("stats private");
        await(() -> plugin.engine().player(steve.getUniqueId()).hidden());
        await(() -> said(steve).contains("hidden"));
        assertEquals(404, plugin.pages().profile("Steve").status());
        assertEquals(200, plugin.pages().profile("Alex").status());
        steve.performCommand("stats private");
        await(() -> !plugin.engine().player(steve.getUniqueId()).hidden());
    }

    @Test
    void otherPluginsAddStatsAndSections() {
        PluginMock other = MockBukkit.createMockPlugin("xVote");
        XStatsApi api = XStatsApi.get();
        CustomStat votes = api.registerStat(other, "votes", "Votes", StatUnit.COUNT);
        assertEquals("xvote_votes", votes.id());
        api.registerProfileSection(other, "votes", "Voting", (player, staff) -> List.of(new ProfileLine("Streak", "3 days")));
        save();
        votes.increment(steve.getUniqueId(), 2);
        votes.increment(steve.getUniqueId(), 1);
        save();
        assertEquals(3, api.getStat(steve.getUniqueId(), "xvote_votes", Period.ALL));
        assertEquals(3, api.getStat(steve.getUniqueId(), "xvote_votes", Period.MONTHLY));
        assertNotNull(api.getStatInfo("xvote_votes").orElse(null));
        String profile = new String(plugin.pages().profile("Steve").body(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(profile.contains("\"title\":\"Voting\""), profile);
        assertTrue(profile.contains("\"value\":\"3 days\""), profile);
        assertTrue(profile.contains("\"id\":\"xvote_votes\""), profile);
    }
}
