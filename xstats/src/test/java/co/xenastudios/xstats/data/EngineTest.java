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
import co.xenastudios.xstats.stat.Vanilla;
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
        // Leaderboards and totals follow the other servers' numbers too, not only this one's.
        assertEquals(17, survival.leaderboards().get("kills", Period.ALL).values()[0]);
        assertEquals(6, survival.leaderboards().get("kills", Period.DAILY).values()[0]);
        assertEquals(17, survival.leaderboards().total(kills.index()));
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
            new StatDef.Custom("xVote", false));
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
        engine.hide(engine.player(steve), true);
        int added = engine.store().importRows(List.of(
            new Store.Imported(steve, "Steve", 5, java.util.Map.of("kills", 999L), java.util.Map.of("kills", true)),
            new Store.Imported(alex, "Alex", 7, java.util.Map.of("kills", 7L, "playtime", 3600L),
                java.util.Map.of("kills", true, "playtime", false))));
        assertEquals(2, added);
        engine.afterImport();
        assertEquals(10, value(engine, steve, kills, Period.ALL));
        assertTrue(engine.player(steve).hidden(), "a player already counted is left as they are");
        assertTrue(engine(("main"), false).player(steve).hidden(), "in the database too");
        assertEquals(7, engine.player(alex).lastSeen());
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

    @Test
    void aGaugeIsTheLatestOnANetworkNotTheHighestNorEveryServerAddedUp() throws Exception {
        long now = System.currentTimeMillis();
        Engine survival = engine("survival", true);
        Engine creative = engine("creative", true);
        survival.queue(sample(steve, "Steve", now, 0, 1, 0));
        survival.queue(new Event.Set(steve, "balance", 1_000));
        survival.save(now);
        assertEquals(1_000, value(survival, steve, balance, Period.ALL));
        Thread.sleep(5);
        // He spends 900 on creative; survival's row still says 1000 while he's away.
        creative.queue(sample(steve, "Steve", now, 0, 1, 0));
        creative.queue(new Event.Set(steve, "balance", 100));
        creative.save(now);
        assertEquals(100, value(creative, steve, balance, Period.ALL));
        survival.networkChanged();
        survival.save(now);
        assertEquals(100, value(survival, steve, balance, Period.ALL), "the latest, not the highest");
        // It stays so across reloads, on every server, and for a server that just started.
        survival.networkChanged();
        survival.save(now);
        assertEquals(100, value(survival, steve, balance, Period.ALL), "no flipping back");
        creative.networkChanged();
        creative.save(now);
        assertEquals(100, value(creative, steve, balance, Period.ALL));
        assertEquals(100, value(engine("lobby", true), steve, balance, Period.ALL));
        assertEquals(100, survival.leaderboards().total(balance.index()), "one balance, not one per server");
        Thread.sleep(5);
        // Back on survival, it's set again there: that's now the latest.
        survival.queue(new Event.Set(steve, "balance", 250));
        survival.save(now);
        creative.networkChanged();
        creative.save(now);
        assertEquals(250, value(creative, steve, balance, Period.ALL));
    }

    @Test
    void readingsKeepTheirOrderAfterADatabaseError() throws Exception {
        long now = System.currentTimeMillis();
        Engine before = engine("main", false);
        before.queue(sample(steve, "Steve", now, 0, 100, 0));
        before.save(now);
        // A restart: Steve's own rows are read again at his first event.
        Engine engine = engine("main", false);
        rename(engine, "totals", "totals_off");
        engine.queue(sample(steve, "Steve", now + MINUTE, 0, 500, 0));
        engine.queue(sample(alex, "Alex", now + MINUTE, 0, 7, 0));
        engine.queue(sample(steve, "Steve", now + 2 * MINUTE, 0, 510, 0));
        assertFalse(engine.save(now), "the database can't be read or written");
        assertEquals(7, value(engine, alex, kills, Period.ALL), "someone else's events aren't held up");
        assertEquals(100, value(engine, steve, kills, Period.ALL), "Steve's wait for his rows");
        engine.queue(sample(steve, "Steve", now + 3 * MINUTE, 0, 520, 0));
        assertFalse(engine.save(now));
        rename(engine, "totals_off", "totals");
        assertTrue(engine.save(now));
        assertEquals(520, value(engine, steve, kills, Period.ALL), "applied in order: no counter restart, no jump");
        assertEquals(420, value(engine, steve, kills, Period.DAILY));
        assertEquals(0, engine.waiting());
        assertEquals(520, value(engine("main", false), steve, kills, Period.ALL));
        assertEquals(7, value(engine("main", false), alex, kills, Period.ALL));
    }

    @Test
    void manyIncrementsWaitAsOne() throws Exception {
        Engine engine = engine("main", false);
        long now = System.currentTimeMillis();
        engine.queue(sample(steve, "Steve", now, 0, 0, 0));
        engine.save(now);
        for (int i = 0; i < 10_000; i++) {
            engine.queue(new Event.Increment(steve, "kills", 1));
        }
        assertEquals(1, engine.waiting(), "added up while waiting");
        // A set and an increment of one stat keep their order.
        engine.queue(new Event.Set(steve, "balance", 100));
        engine.queue(new Event.Set(steve, "balance", 200));
        engine.queue(new Event.Increment(steve, "balance", 5));
        assertEquals(3, engine.waiting());
        engine.save(now);
        assertEquals(10_000, value(engine, steve, kills, Period.ALL));
        assertEquals(10_000, value(engine, steve, kills, Period.DAILY));
        assertEquals(205, value(engine, steve, balance, Period.ALL));
        assertEquals(0, engine.waiting());
    }

    @Test
    void incrementsAfterAJoinArentAddedIntoOneBeforeIt() throws Exception {
        StatDef votes = new StatDef(catalog.index("votes"), "votes", "Votes", StatUnit.COUNT, 0, true, true, true, "xVote",
            new StatDef.Custom("xVote", false));
        List<StatDef> with = new java.util.ArrayList<>(catalog.snapshot().stats());
        with.add(votes);
        catalog.set(with);
        Engine engine = engine("main", false);
        long now = System.currentTimeMillis();
        // Another plugin's join listener runs before xStats' own hears of the join.
        engine.queue(new Event.Increment(steve, "votes", 1));
        engine.queue(new Event.Joined(steve, "Steve", now, now, 0, 0));
        for (int i = 0; i < 9; i++) {
            engine.queue(new Event.Increment(steve, "votes", 1));
        }
        assertEquals(3, engine.waiting(), "the increments after the join wait behind it");
        assertTrue(engine.save(now));
        assertEquals(9, value(engine, steve, votes, Period.ALL), "only the one before they existed is lost");
    }

    @Test
    void tooManyWaitingAreDroppedNotKept() {
        Inbox inbox = new Inbox(LOGGER, 10, 5);
        for (int i = 0; i < 25; i++) {
            inbox.add(new Event.Joined(UUID.randomUUID(), "P" + i, 0, 0, 0, 0));
        }
        assertEquals(10, inbox.size());
        assertEquals(15, inbox.dropped());
        List<Event> applied = new java.util.ArrayList<>();
        assertEquals(null, inbox.drain(applied::add));
        assertEquals(10, applied.size());
        assertEquals(0, inbox.size());
    }

    @Test
    void aSmallDropIsADecreaseAndABigOneARestart() throws Exception {
        Engine engine = engine("main", false);
        long now = System.currentTimeMillis();
        engine.queue(sample(steve, "Steve", now, 0, 100, 0));
        engine.queue(sample(steve, "Steve", now + MINUTE, 0, 200, 0));
        // Another plugin took 5 away (decrementStatistic).
        engine.queue(sample(steve, "Steve", now + 2 * MINUTE, 0, 195, 0));
        engine.save(now);
        assertEquals(195, value(engine, steve, kills, Period.ALL));
        assertEquals(100, value(engine, steve, kills, Period.DAILY), "periods only count rises");
        // The stats file was deleted: it counts again from 0.
        engine.queue(sample(steve, "Steve", now + 3 * MINUTE, 0, 3, 0));
        engine.save(now);
        assertEquals(198, value(engine, steve, kills, Period.ALL));
        // A small counter going down by 1 is a decrease too, not a restart.
        engine.queue(sample(alex, "Alex", now, 0, 5, 0));
        engine.queue(sample(alex, "Alex", now + MINUTE, 0, 4, 0));
        engine.save(now);
        assertEquals(4, value(engine, alex, kills, Period.ALL));
    }

    @Test
    void aPlaceholdersFirstValueAfterARestartCounts() throws Exception {
        long now = System.currentTimeMillis();
        Engine first = engine("main", false);
        first.queue(sample(steve, "Steve", now, 0, 0, 0));
        first.queue(new Event.Set(steve, "balance", 1_000));
        first.save(now);
        Engine second = engine("main", false);
        second.queue(new Event.Set(steve, "balance", 1_500));
        second.save(now);
        assertEquals(1_500, value(second, steve, balance, Period.ALL));
        assertEquals(500, value(second, steve, balance, Period.DAILY), "a rise since the last value saved");
    }

    @Test
    void hidingSomeoneTellsTheWebsiteAndLeavesThemOutOfItsPlaces() throws Exception {
        Engine engine = engine("main", false);
        long now = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicInteger told = new java.util.concurrent.atomic.AtomicInteger();
        engine.onHiddenChanged(told::incrementAndGet);
        engine.queue(sample(steve, "Steve", now, 0, 9, 0));
        engine.queue(sample(alex, "Alex", now, 0, 5, 0));
        engine.save(now);
        int version = engine.hiddenVersion();
        engine.hide(engine.player(steve), true);
        assertEquals(1, told.get());
        assertTrue(engine.hiddenVersion() != version);
        assertEquals(2, engine.rank(engine.player(alex), kills, Period.ALL), "in game, Steve is still above");
        assertEquals(1, engine.visibleRank(engine.player(alex), kills, Period.ALL), "on the site, he isn't counted");
        Board board = engine.leaderboards().get("kills", Period.ALL);
        assertEquals(List.of("Alex"), board.top(10, true).stream().map(e -> e.name()).toList());
        assertEquals(1, board.top(10, true).getFirst().rank());
        // Another server of the network hid him: this one hears of it through the change feed.
        Engine other = engine("main", false);
        other.onHiddenChanged(told::incrementAndGet);
        engine.hide(engine.player(steve), false);
        other.refreshHidden(steve);
        assertFalse(other.player(steve).hidden());
        assertEquals(3, told.get());
    }

    @Test
    void aHideThatCouldntBeReadIsPickedUpAtTheNextReload() throws Exception {
        long now = System.currentTimeMillis();
        Engine survival = engine("survival", true);
        survival.queue(sample(steve, "Steve", now, 0, 1, 0));
        survival.save(now);
        Engine creative = engine("creative", true);
        survival.hide(survival.player(steve), true);
        rename(creative, "players", "players_off");
        creative.refreshHidden(steve);
        assertFalse(creative.player(steve).hidden(), "the database couldn't be read");
        rename(creative, "players_off", "players");
        creative.save(now);
        assertTrue(creative.player(steve).hidden(), "read again with everyone at the next save");
    }

    @Test
    void aBalanceReadEarlierButSavedLaterIsntTheLatest() throws Exception {
        long now = System.currentTimeMillis();
        Engine survival = engine("survival", true);
        Engine creative = engine("creative", true);
        // Steve leaves survival with 100, read as he quits; creative reads 150 after that.
        survival.queue(new Event.Sample(steve, "Steve", now, raw(balance, 100), 0, 0, false, true));
        creative.queue(new Event.Sample(steve, "Steve", now + MINUTE, raw(balance, 150), 0, 0, false, false));
        creative.save(now);
        // Survival's save comes later, with the older reading.
        survival.save(now);
        creative.networkChanged();
        creative.save(now);
        assertEquals(150, value(creative, steve, balance, Period.ALL), "read last, not saved last");
        assertEquals(150, value(engine("lobby", true), steve, balance, Period.ALL));
    }

    @Test
    void aSetValueStatIsTheLatestOnANetwork() throws Exception {
        StatDef coins = new StatDef(catalog.index("coins"), "coins", "Coins", StatUnit.COUNT, 0, false, true, true, "Shop",
            new StatDef.Custom("Shop", true));
        List<StatDef> with = new java.util.ArrayList<>(catalog.snapshot().stats());
        with.add(coins);
        catalog.set(with);
        long now = System.currentTimeMillis();
        Engine survival = engine("survival", true);
        Engine creative = engine("creative", true);
        survival.queue(sample(steve, "Steve", now, 0, 1, 0));
        survival.queue(new Event.Set(steve, "coins", 100));
        survival.save(now);
        Thread.sleep(5);
        creative.queue(sample(steve, "Steve", now, 0, 1, 0));
        creative.queue(new Event.Set(steve, "coins", 150));
        creative.save(now);
        survival.networkChanged();
        survival.save(now);
        assertEquals(150, value(survival, steve, coins, Period.ALL), "the latest, not 250");
    }

    @Test
    void aSaveDoesntUndoAHideAnotherServerMade() throws Exception {
        long now = System.currentTimeMillis();
        Engine survival = engine("survival", true);
        Engine creative = engine("creative", true);
        survival.queue(sample(steve, "Steve", now, 0, 1, 0));
        survival.save(now);
        creative.queue(sample(steve, "Steve", now, 0, 1, 0));
        creative.save(now);
        // Hidden on survival; creative hasn't heard yet, and saves him.
        survival.hide(survival.player(steve), true);
        creative.queue(sample(steve, "Steve", now + MINUTE, 0, 2, 0));
        creative.save(now);
        assertTrue(engine("lobby", true).player(steve).hidden(), "still private");
    }

    @Test
    void aStatWhoseSourceChangedStartsFromTheNewCounter() throws Exception {
        long now = System.currentTimeMillis();
        Engine engine = engine("main", false);
        engine.queue(sample(steve, "Steve", now, 0, 10, 0));
        engine.queue(sample(steve, "Steve", now + MINUTE, 0, 12, 0));
        engine.save(now);
        assertEquals(2, value(engine, steve, kills, Period.DAILY));
        // The owner adds deepslate to the stat: Minecraft's count from both is 500.
        StatDef wider = new StatDef(kills.index(), "kills", "kills", StatUnit.COUNT, 0, true, true, true, "minecraft",
            new StatDef.Vanillas(List.of(new Vanilla.Term(Vanilla.Category.MINED, "diamond_ore"),
                new Vanilla.Term(Vanilla.Category.MINED, "deepslate_diamond_ore")), List.of()));
        List<StatDef> changed = new java.util.ArrayList<>(catalog.snapshot().stats());
        changed.set(changed.indexOf(kills), wider);
        catalog.set(changed);
        engine.queue(sample(steve, "Steve", now + 2 * MINUTE, 0, 500, 0));
        engine.save(now);
        assertEquals(500, value(engine, steve, kills, Period.ALL), "the new counter's value");
        assertEquals(2, value(engine, steve, kills, Period.DAILY), "none of it today");
        // Narrowed again later: all time follows it down, not taken as a restart.
        catalog.set(catalog.snapshot().stats().stream().map(s -> s.id().equals("kills") ? kills : s).toList());
        engine.queue(sample(steve, "Steve", now + 3 * MINUTE, 0, 40, 0));
        engine.save(now);
        assertEquals(40, value(engine, steve, kills, Period.ALL));
        assertEquals(2, value(engine, steve, kills, Period.DAILY));
        // And it's remembered across a restart: no second change is seen.
        Engine again = engine("main", false);
        again.queue(sample(steve, "Steve", now + 4 * MINUTE, 0, 41, 0));
        again.save(now);
        assertEquals(41, value(again, steve, kills, Period.ALL));
        assertEquals(3, value(again, steve, kills, Period.DAILY));
    }

    long[] raw(StatDef stat, long value) {
        long[] raw = new long[catalog.snapshot().width()];
        Arrays.fill(raw, Event.Sample.NOT_READ);
        raw[stat.index()] = value;
        return raw;
    }

    @Test
    void aStatAddedInASaveThatFailedIsntRememberedAsStored() throws Exception {
        StatDef votes = new StatDef(catalog.index("votes"), "votes", "Votes", StatUnit.COUNT, 0, true, true, true, "xVote",
            new StatDef.Custom("xVote", false));
        List<StatDef> with = new java.util.ArrayList<>(catalog.snapshot().stats());
        with.add(votes);
        catalog.set(with);
        long now = System.currentTimeMillis();
        Engine engine = engine("main", false);
        engine.queue(sample(steve, "Steve", now, 0, 1, 0));
        engine.save(now);
        engine.queue(new Event.Increment(steve, "votes", 5));
        rename(engine, "daily", "daily_off");
        assertFalse(engine.save(now), "the votes stat is added, then rolled back");
        rename(engine, "daily_off", "daily");
        assertTrue(engine.save(now));
        assertEquals(5, value(engine("main", false), steve, votes, Period.ALL), "saved under an id that exists");
    }

    private static void rename(Engine engine, String from, String to) throws Exception {
        try (Connection connection = engine.store().connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(engine.store().database().sql("ALTER TABLE {p}" + from + " RENAME TO {p}" + to));
        }
    }

    private static long count(Engine engine, String sql) throws Exception {
        try (Connection connection = engine.store().connect(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(engine.store().database().sql(sql))) {
            return result.next() ? result.getLong(1) : -1;
        }
    }
}
