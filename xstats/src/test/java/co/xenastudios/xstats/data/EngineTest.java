package co.xenastudios.xstats.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xstats.api.Period;
import co.xenastudios.xstats.api.StatUnit;
import co.xenastudios.xstats.stat.Catalog;
import co.xenastudios.xstats.stat.StatDef;
import co.xenastudios.xstats.storage.Store;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The save cycle against a real SQLite file: readings, periods, playtime, restarts and networks. */
class EngineTest {

    static final Logger LOGGER = Logger.getLogger("xStats test");
    static final Calendar CALENDAR = new Calendar(ZoneOffset.UTC, DayOfWeek.MONDAY);
    static final long MINUTE = 60_000;

    @TempDir
    Path folder;

    Catalog catalog;
    StatDef kills;
    StatDef jumps;
    StatDef playtime;
    StatDef sessions;
    StatDef balance;
    final UUID steve = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    final UUID alex = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @BeforeEach
    void stats() {
        catalog = new Catalog();
        kills = vanilla("kills", true);
        jumps = vanilla("jumps", false);
        playtime = new StatDef(catalog.index("playtime"), "playtime", "Playtime", StatUnit.TIME, 0, true, true, true, "xstats",
            new StatDef.Tracked(StatDef.Tracked.Kind.PLAYTIME));
        sessions = new StatDef(catalog.index("sessions"), "sessions", "Sessions", StatUnit.COUNT, 0, true, true, true, "xstats",
            new StatDef.Tracked(StatDef.Tracked.Kind.SESSIONS));
        balance = new StatDef(catalog.index("balance"), "balance", "Balance", StatUnit.COUNT, 2, true, true, true, "placeholder",
            new StatDef.Placeholder("%vault_eco_balance%"));
        catalog.set(List.of(kills, jumps, playtime, sessions, balance));
    }

    private StatDef vanilla(String id, boolean periods) {
        return new StatDef(catalog.index(id), id, id, StatUnit.COUNT, 0, periods, true, true, "minecraft",
            new StatDef.Vanillas(List.of(), List.of()));
    }

    /** Where the tests keep their stats: SQLite in a fresh folder; {@link MySqlTest} uses MySQL or MariaDB. */
    StorageSettings storage() {
        return StorageSettings.defaults("xstats_");
    }

    Engine engine(String server, boolean network) throws Exception {
        Engine engine = new Engine(catalog, new Engine.Settings(CALENDAR, true, 5 * MINUTE, 90, network), LOGGER);
        engine.open(Store.open(storage(), folder, server), ChangeFeed.off());
        return engine;
    }

    Event.Sample sample(UUID player, String name, long at, float yaw, long kills, long jumps) {
        long[] raw = new long[catalog.snapshot().width()];
        Arrays.fill(raw, Event.Sample.NOT_READ);
        raw[this.kills.index()] = kills;
        raw[this.jumps.index()] = jumps;
        return new Event.Sample(player, name, at, raw, yaw, 0, false, false);
    }

    long value(Engine engine, UUID player, StatDef stat, Period period) {
        return engine.value(engine.player(player), stat, period);
    }

    @Test
    void theFirstReadingIsHistoryNotToday() throws Exception {
        Engine engine = engine("main", false);
        long now = System.currentTimeMillis();
        engine.queue(sample(steve, "Steve", now, 0, 10, 100));
        assertTrue(engine.save(now));
        assertEquals(10, value(engine, steve, kills, Period.ALL));
        assertEquals(0, value(engine, steve, kills, Period.DAILY));
        engine.queue(sample(steve, "Steve", now + MINUTE, 0, 13, 100));
        engine.save(now);
        assertEquals(13, value(engine, steve, kills, Period.ALL));
        assertEquals(3, value(engine, steve, kills, Period.DAILY));
        assertEquals(3, value(engine, steve, kills, Period.WEEKLY));
        assertEquals(3, value(engine, steve, kills, Period.MONTHLY));
    }

    @Test
    void aCounterThatWentBackStartedAgainFromZero() throws Exception {
        Engine engine = engine("main", false);
        long now = System.currentTimeMillis();
        engine.queue(sample(steve, "Steve", now, 0, 10, 0));
        engine.queue(sample(steve, "Steve", now + MINUTE, 0, 2, 0));
        engine.save(now);
        assertEquals(12, value(engine, steve, kills, Period.ALL));
        assertEquals(2, value(engine, steve, kills, Period.DAILY));
    }

    @Test
    void aRestartPicksUpFromTheLastReading() throws Exception {
        long now = System.currentTimeMillis();
        Engine first = engine("main", false);
        first.queue(sample(steve, "Steve", now, 0, 10, 5));
        first.queue(sample(steve, "Steve", now + MINUTE, 0, 12, 5));
        first.save(now);
        // A crash: what changed after this save is lost, but the counter kept counting.
        Engine second = engine("main", false);
        assertEquals(12, value(second, steve, kills, Period.ALL));
        assertEquals(2, value(second, steve, kills, Period.DAILY));
        second.queue(sample(steve, "Steve", now + 3 * MINUTE, 0, 20, 5));
        second.save(now);
        assertEquals(20, value(second, steve, kills, Period.ALL));
        assertEquals(10, value(second, steve, kills, Period.DAILY));
        assertEquals("Steve", second.byName("steve").name());
    }

    @Test
    void playtimeLeavesOutTimeWithTheCameraStill() throws Exception {
        Engine engine = engine("main", false);
        long at = System.currentTimeMillis();
        engine.queue(new Event.Joined(steve, "Steve", at, at - 1000, 0, 0));
        // Five minutes looking the same way: away from the keyboard.
        engine.queue(sample(steve, "Steve", at + 5 * MINUTE, 0, 0, 0));
        // Five more, having looked around.
        engine.queue(sample(steve, "Steve", at + 10 * MINUTE, 45, 0, 0));
        engine.save(at);
        assertEquals(300, value(engine, steve, playtime, Period.ALL));
        assertEquals(1, value(engine, steve, sessions, Period.ALL));
        // A reading long after the last (a stall): at most one and a half saves count.
        engine.queue(sample(steve, "Steve", at + 70 * MINUTE, 90, 0, 0));
        engine.save(at);
        assertEquals(300 + 450, value(engine, steve, playtime, Period.ALL));
    }

    @Test
    void placeholderValuesGoUpAndDown() throws Exception {
        Engine engine = engine("main", false);
        long now = System.currentTimeMillis();
        engine.queue(sample(steve, "Steve", now, 0, 0, 0));
        engine.save(now);
        engine.queue(new Event.Set(steve, "balance", 10_000));
        engine.save(now);
        assertEquals(10_000, value(engine, steve, balance, Period.ALL));
        assertEquals(0, value(engine, steve, balance, Period.DAILY), "the first value isn't earned today");
        engine.queue(new Event.Set(steve, "balance", 12_500));
        engine.queue(new Event.Set(steve, "balance", 9_000));
        engine.save(now);
        assertEquals(9_000, value(engine, steve, balance, Period.ALL));
        assertEquals(2_500, value(engine, steve, balance, Period.DAILY), "only rises count towards periods");
        engine.queue(new Event.Increment(steve, "kills", 4));
        engine.save(now);
        assertEquals(4, value(engine, steve, kills, Period.DAILY));
    }

    @Test
    void statsWithoutPeriodsWriteNoDailyRows() throws Exception {
        Engine engine = engine("main", false);
        long now = System.currentTimeMillis();
        engine.queue(sample(steve, "Steve", now, 0, 1, 1));
        engine.queue(sample(steve, "Steve", now + MINUTE, 0, 2, 50));
        engine.save(now);
        assertEquals(50, value(engine, steve, jumps, Period.ALL));
        assertEquals(0, value(engine, steve, jumps, Period.DAILY));
        assertEquals(1, count(engine, "SELECT COUNT(*) FROM {p}daily"), "one row: kills, today");
        // Nothing changed: nothing written.
        engine.queue(sample(steve, "Steve", now + 2 * MINUTE, 0, 2, 50));
        engine.save(now);
        assertEquals(1, count(engine, "SELECT COUNT(*) FROM {p}daily"));
    }

    @Test
    void leaderboardsShareTiedPlacesAndLeaveOutTheExcluded() throws Exception {
        Engine engine = engine("main", false);
        long now = System.currentTimeMillis();
        UUID staff = UUID.randomUUID();
        engine.queue(sample(steve, "Steve", now, 0, 5, 0));
        engine.queue(sample(alex, "Alex", now, 0, 5, 0));
        UUID third = UUID.randomUUID();
        engine.queue(sample(third, "Notch", now, 0, 3, 0));
        long[] raw = sample(staff, "Mod", now, 0, 99, 0).raw();
        engine.queue(new Event.Sample(staff, "Mod", now, raw, 0, 0, true, false));
        engine.save(now);
        Board board = engine.leaderboards().get("kills", Period.ALL);
        assertEquals(3, board.size(), "staff with the exclude permission are left out");
        assertEquals(1, engine.rank(engine.player(steve), kills, Period.ALL));
        assertEquals(1, engine.rank(engine.player(alex), kills, Period.ALL));
        assertEquals(3, engine.rank(engine.player(third), kills, Period.ALL));
        assertEquals(0, engine.rank(engine.player(staff), kills, Period.ALL));
        assertEquals("Alex", board.top(1, false).getFirst().name(), "ties go by name");
        assertEquals(5 + 5 + 3 + 99, engine.leaderboards().total(kills.index()), "server totals still count everyone");
        engine.bannedPlayers(java.util.Set.of(steve));
        engine.queue(sample(alex, "Alex", now, 0, 6, 0));
        engine.save(now);
        assertEquals(2, engine.leaderboards().get("kills", Period.ALL).size(), "banned players are left out");
        assertEquals(0, engine.rank(engine.player(steve), kills, Period.ALL));
    }

    @Test
    void aNewLeaderIsReported() throws Exception {
        Engine engine = engine("main", false);
        long now = System.currentTimeMillis();
        List<String> leaders = new java.util.ArrayList<>();
        engine.onNewLeader((board, previous) -> leaders.add(board.stat().id() + ":" + board.period().id() + ":" + board.leader().name()
            + ":" + (previous == null ? "-" : previous.name())));
        engine.queue(sample(steve, "Steve", now, 0, 5, 0));
        engine.queue(sample(alex, "Alex", now, 0, 1, 0));
        engine.save(now);
        engine.queue(sample(alex, "Alex", now + MINUTE, 0, 10, 0));
        engine.save(now);
        assertTrue(leaders.contains("kills:all:Alex:Steve"), leaders.toString());
        assertTrue(leaders.contains("kills:daily:Alex:-"), leaders.toString());
    }

    @Test
    void searchFindsNamesByTheirStartAndSkipsHiddenPlayers() throws Exception {
        Engine engine = engine("main", false);
        long now = System.currentTimeMillis();
        engine.queue(sample(steve, "Steve", now, 0, 1, 0));
        engine.queue(sample(alex, "Stella", now, 0, 1, 0));
        engine.save(now);
        assertEquals(List.of("Stella", "Steve"), engine.search("ste", 10, p -> false).stream().map(PlayerRecord::name).toList());
        engine.hide(engine.player(alex), true);
        assertEquals(List.of("Steve"), engine.search("ST", 10, PlayerRecord::hidden).stream().map(PlayerRecord::name).toList());
        assertTrue(engine(("main"), false).player(alex).hidden(), "kept in the database");
    }

    @Test
    void aNetworkAddsUpEveryServer() throws Exception {
        long now = System.currentTimeMillis();
        Engine survival = engine("survival", true);
        Engine creative = engine("creative", true);
        survival.queue(sample(steve, "Steve", now, 0, 10, 0));
        survival.queue(sample(steve, "Steve", now + MINUTE, 0, 14, 0));
        survival.save(now);
        creative.queue(sample(steve, "Steve", now, 0, 1, 0));
        creative.queue(sample(steve, "Steve", now + MINUTE, 0, 3, 0));
        creative.save(now);
        survival.networkChanged();
        survival.save(now);
        assertEquals(14 + 3, value(survival, steve, kills, Period.ALL));
        assertEquals(4 + 2, value(survival, steve, kills, Period.DAILY));
        // Each server's own counter is still worked out from its own last reading.
        survival.queue(sample(steve, "Steve", now + 2 * MINUTE, 0, 15, 0));
        survival.save(now);
        assertEquals(18, value(survival, steve, kills, Period.ALL));
        List<Store.ServerValue> servers = survival.store().perServer(survival.player(steve).dbId());
        assertEquals(2, servers.stream().filter(v -> v.stat().equals("kills")).count());
    }

    @Test
    void aStatRegisteredLateKeepsItsSavedValues() throws Exception {
        StatDef votes = new StatDef(catalog.index("votes"), "votes", "Votes", StatUnit.COUNT, 0, true, true, true, "xVote",
            new StatDef.Custom("xVote"));
        List<StatDef> without = catalog.snapshot().stats();
        List<StatDef> with = new java.util.ArrayList<>(without);
        with.add(votes);
        long now = System.currentTimeMillis();
        catalog.set(with);
        Engine before = engine("main", false);
        before.queue(sample(steve, "Steve", now, 0, 1, 0));
        before.queue(new Event.Increment(steve, "votes", 5));
        before.save(now);
        // A restart where xVote registers its stat after xStats has loaded and read Steve.
        catalog.set(without);
        Engine after = engine("main", false);
        after.queue(sample(steve, "Steve", now + MINUTE, 0, 2, 0));
        after.save(now);
        catalog.set(with);
        after.queue(new Event.Increment(steve, "votes", 1));
        after.save(now);
        assertEquals(6, value(after, steve, votes, Period.ALL));
        assertEquals(6, engine("main", false).player(steve) == null ? -1
            : value(engine("main", false), steve, votes, Period.ALL), "and that's what's saved");
    }

    @Test
    void oldDaysAreAddedIntoTheirMonth() throws Exception {
        Engine engine = engine("main", false);
        long now = System.currentTimeMillis();
        engine.queue(sample(steve, "Steve", now, 0, 1, 0));
        engine.save(now);
        int player = engine.player(steve).dbId();
        int today = CALENDAR.today(now);
        try (Connection connection = engine.store().connect(); Statement statement = connection.createStatement()) {
            for (int back : new int[] {200, 199, 120, 3}) {
                statement.executeUpdate(engine.store().database().sql("INSERT INTO {p}daily (player, stat, server, day, delta) VALUES (") + player + ", 1, "
                    + engine.store().server() + ", " + (today - back) + ", 5)");
            }
        }
        assertEquals(3, engine.store().fold(today - 90, 31));
        assertEquals(1, count(engine, "SELECT COUNT(*) FROM {p}daily"), "the recent day stays");
        assertEquals(15, count(engine, "SELECT SUM(delta) FROM {p}monthly"));
        assertTrue(count(engine, "SELECT COUNT(*) FROM {p}monthly") >= 2, "200 and 120 days ago are different months");
    }

    @Test
    void importsNeverOverwriteWhatsCounted() throws Exception {
        Engine engine = engine("main", false);
        long now = System.currentTimeMillis();
        engine.queue(sample(steve, "Steve", now, 0, 10, 0));
        engine.save(now);
        int added = engine.store().importRows(List.of(
            new Store.Imported(steve, "Steve", java.util.Map.of("kills", 999L), java.util.Map.of("kills", true)),
            new Store.Imported(alex, "Alex", java.util.Map.of("kills", 7L, "playtime", 3600L),
                java.util.Map.of("kills", true, "playtime", false))));
        assertEquals(2, added);
        engine.afterImport();
        assertEquals(10, value(engine, steve, kills, Period.ALL));
        assertEquals(7, value(engine, alex, kills, Period.ALL));
        assertEquals(3600, value(engine, alex, playtime, Period.ALL));
        assertEquals(0, value(engine, alex, kills, Period.DAILY), "history isn't today");
        assertNotNull(engine.leaderboards().get("kills", Period.ALL));
        assertEquals(2, engine.leaderboards().get("kills", Period.ALL).size());
        // Alex's first live reading carries on from the file's counter.
        engine.queue(sample(alex, "Alex", now, 0, 9, 0));
        engine.save(now);
        assertEquals(9, value(engine, alex, kills, Period.ALL));
        assertEquals(2, value(engine, alex, kills, Period.DAILY));
        assertFalse(engine.store().flag("imported"));
        engine.store().setFlag("imported");
        assertTrue(engine.store().flag("imported"));
    }

    private static long count(Engine engine, String sql) throws Exception {
        try (Connection connection = engine.store().connect(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(engine.store().database().sql(sql))) {
            return result.next() ? result.getLong(1) : -1;
        }
    }
}
