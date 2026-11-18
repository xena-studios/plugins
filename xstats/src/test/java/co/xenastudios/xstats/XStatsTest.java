package co.xenastudios.xstats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** xStats on a mock server: a save with players online, then /stats, /leaderboard and the API. */
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
        steve.performCommand("leaderboard kills");
        String top = said(steve);
        assertTrue(top.contains("1. Alex - 9"), top);
        assertTrue(top.contains("2. Steve - 6"), top);
        steve.performCommand("lb kills daily");
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

    private String page(String path) {
        co.xenastudios.xstats.web.Response page = plugin.pages().page(path);
        return page == null ? "" : new String(page.body(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private String body(co.xenastudios.xstats.web.Response response) {
        return new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void statsPrivateHidesFromTheWebsite() {
        steve.setStatistic(Statistic.PLAYER_KILLS, 9);
        alex.setStatistic(Statistic.PLAYER_KILLS, 5);
        save();
        assertTrue(page("/api/v1/leaderboard/kills/all").contains("\"Steve\""));
        said(steve);
        steve.performCommand("stats private");
        await(() -> plugin.engine().player(steve.getUniqueId()).hidden());
        await(() -> said(steve).contains("hidden"));
        assertEquals(404, plugin.pages().profile("Steve").status());
        assertEquals(200, plugin.pages().profile("Alex").status());
        // Straight away, not at the next save: off every leaderboard page and summary.
        await(() -> !page("/api/v1/leaderboard/kills/all").contains("Steve"));
        assertFalse(page("/api/v1/leaderboards/all").contains("Steve"), page("/api/v1/leaderboards/all"));
        assertTrue(page("/api/v1/leaderboard/kills/all").contains("{\"rank\":1,\"uuid\":\"" + alex.getUniqueId()),
            "Alex's place doesn't give away someone private above");
        assertTrue(body(plugin.pages().profile("Alex")).contains("\"id\":\"kills\",\"value\":5,\"display\":\"5\",\"rank\":1"),
            body(plugin.pages().profile("Alex")));
        assertEquals(2, XStatsApi.get().getRank(alex.getUniqueId(), "kills", Period.ALL), "in game, he still shows");
        steve.performCommand("stats private");
        await(() -> !plugin.engine().player(steve.getUniqueId()).hidden());
        await(() -> page("/api/v1/leaderboard/kills/all").contains("Steve"));
    }

    @Test
    void bedrockPlayersHavePagesToo() {
        PlayerMock bedrock = server.addPlayer(".Bedrock_Steve");
        bedrock.setStatistic(Statistic.PLAYER_KILLS, 3);
        save();
        assertEquals(200, plugin.pages().profile(".Bedrock_Steve").status());
        assertEquals(200, plugin.pages().profile(bedrock.getUniqueId().toString()).status());
        assertTrue(body(plugin.pages().search(".bed")).contains(bedrock.getUniqueId().toString()));
        var config = new XSConfigWithUrl().config;
        assertEquals("https://stats.example.com/#/player/" + bedrock.getUniqueId(),
            co.xenastudios.xstats.command.StatsCommand.pageUrl(config, plugin.engine().player(bedrock.getUniqueId())));
    }

    /** The defaults with a public url, for /stats' link. */
    static final class XSConfigWithUrl {
        final co.xenastudios.xstats.config.XSConfig config;

        XSConfigWithUrl() {
            var d = co.xenastudios.xstats.config.XSConfig.DEFAULTS;
            var w = d.web();
            var web = new co.xenastudios.xstats.config.XSConfig.Web(w.enabled(), w.bind(), w.port(), "https://stats.example.com",
                w.site(), w.corsOrigins(), w.requestsPerMinute(), w.maxConcurrent(), w.trustedProxies(), w.clientIpHeader(),
                w.avatars(), w.branding());
            config = new co.xenastudios.xstats.config.XSConfig(d.theme(), d.messages(), d.commands(), d.stats(), d.collecting(),
                d.leaderboards(), d.periods(), web, d.serverName(), d.storage(), d.network(), d.updateChecker());
        }
    }

    private void edit(java.util.function.Predicate<org.bukkit.configuration.file.YamlConfiguration> change) {
        AtomicBoolean done = new AtomicBoolean();
        plugin.editConfig(change, problem -> {
            assertEquals(null, problem);
            done.set(true);
        });
        await(done::get);
    }

    @Test
    void turningExcludeBannedOffAppliesOnReload() {
        steve.setStatistic(Statistic.PLAYER_KILLS, 4);
        save();
        steve.ban("test", (java.util.Date) null, null);
        save();
        assertEquals(0, XStatsApi.get().getRank(steve.getUniqueId(), "kills", Period.ALL));
        edit(yaml -> {
            yaml.set("leaderboards.exclude-banned", false);
            return true;
        });
        assertFalse(plugin.engine().banned(steve.getUniqueId()));
        await(() -> XStatsApi.get().getRank(steve.getUniqueId(), "kills", Period.ALL) == 1);
    }

    @Test
    void aReloadStartsTheWebsiteOnceItsPortIsFree() throws Exception {
        java.net.ServerSocket taken = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
        int port = taken.getLocalPort();
        try {
            edit(yaml -> {
                yaml.set("web.enabled", true);
                yaml.set("web.bind", "127.0.0.1");
                yaml.set("web.port", port);
                return true;
            });
            assertEquals(0, plugin.webPort(), "the port is taken");
        } finally {
            taken.close();
        }
        plugin.reload();
        assertEquals(port, plugin.webPort(), "tried again, and it's free now");
        plugin.reload();
        assertEquals(port, plugin.webPort(), "and a reload with nothing changed leaves it running");
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
