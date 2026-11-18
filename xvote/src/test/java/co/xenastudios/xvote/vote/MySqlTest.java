package co.xenastudios.xvote.vote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.core.db.NetworkSettings;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.core.task.Tasks;
import co.xenastudios.xvote.api.VoteResult;
import co.xenastudios.xvote.api.VoteSource;
import co.xenastudios.xvote.config.XVConfig;
import co.xenastudios.xvote.reward.Rewards;
import co.xenastudios.xvote.storage.Ledger;
import co.xenastudios.xvote.storage.Schema;
import co.xenastudios.xvote.storage.Store;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Two servers sharing a real MySQL or MariaDB database in network mode, with the real
 * change feed between them: a vote received on one pays the player on the other, once.
 * Runs only with XR_MYSQL=host:port:database:user:password, as xRewind's MySqlTest.
 */
@EnabledIfEnvironmentVariable(named = "XR_MYSQL", matches = ".+")
class MySqlTest {

    static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID NOTCH = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    final Logger logger = Logger.getLogger("test");
    final Map<String, Votes.Resolved> serverKnows = new ConcurrentHashMap<>();
    final XVConfig config = VotesTest.config(5);
    StorageSettings storage;
    ServerMock server;
    Tasks tasks;
    Server a;
    Server b;

    final class Server implements Votes.Effects {
        final Store store;
        final Votes votes;
        final ChangeFeed feed;
        final List<Facts> paid = Collections.synchronizedList(new ArrayList<>());
        final List<Long> parties = Collections.synchronizedList(new ArrayList<>());

        Server() throws Exception {
            store = Store.open(storage, Path.of("unused"), logger);
            Rewards rewards = Rewards.of(config, item -> {
                throw new IllegalArgumentException("no items in tests");
            }, warning -> {});
            votes = new Votes(store, new Ledger(store.database()), new Voters(new Periods(ZoneOffset.UTC, DayOfWeek.MONDAY)),
                new Online(), () -> rewards, () -> config, this, name -> serverKnows.get(name.toLowerCase(Locale.ROOT)), logger);
            votes.load(System.currentTimeMillis());
            feed = ChangeFeed.open(store.database(), new NetworkSettings(true, 1), logger);
            votes.network(feed);
            feed.start(tasks);
        }

        @Override
        public void pay(UUID player, Supplier<List<Facts>> take, Runnable left) {
            paid.addAll(take.get());
        }

        @Override
        public void party(long since) {
            parties.add(since);
        }

        @Override
        public void progress(long left) {}

        @Override
        public boolean onServerThread() {
            return false;
        }

        @Override
        public void async(Runnable work) {
            work.run();
        }

        VoteResult vote(String name, String site) throws Exception {
            long now = System.currentTimeMillis();
            return votes.accept(new IncomingVote(site, name, "", Long.toString(System.nanoTime()), VoteSource.VOTIFIER_V2, now))
                .get(10, TimeUnit.SECONDS);
        }

        void close() {
            try {
                feed.close();
            } catch (UnsupportedOperationException | org.mockbukkit.mockbukkit.exception.UnimplementedOperationException e) {
                // MockBukkit can't cancel a task; the mock server goes away with it.
            }
            store.stop(5, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void start() throws Exception {
        server = MockBukkit.mock();
        tasks = new Tasks(MockBukkit.createMockPlugin());
        String[] parts = System.getenv("XR_MYSQL").split(":");
        storage = new StorageSettings(StorageSettings.Type.MYSQL, "t" + Long.toHexString(System.nanoTime()) + "_",
            new StorageSettings.MySql(parts[0], Integer.parseInt(parts[1]), parts[2], parts[3], parts[4], ""));
        a = new Server();
        b = new Server();
        assertTrue(a.feed.on() && b.feed.on());
    }

    @AfterEach
    void stop() throws Exception {
        try {
            if (a != null && b != null) {
                a.close();
                b.close();
                try (Connection connection = a.store.database().connect(); Statement statement = connection.createStatement()) {
                    for (String table : List.of("votes", "voters", "held", "state", "changes", "meta")) {
                        statement.execute("DROP TABLE IF EXISTS " + a.store.database().table(table));
                    }
                }
            }
        } finally {
            MockBukkit.unmock();
        }
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < until, "timed out");
            server.getScheduler().performOneTick();
            Thread.sleep(20);
        }
    }

    @Test
    void schemaRunsTwice() throws Exception {
        assertEquals(Schema.STEPS.size(), a.store.database().migrate(Schema.STEPS));
    }

    @Test
    void aVoteReceivedOnOneServerPaysThePlayerOnTheOther() throws Exception {
        serverKnows.put("steve", new Votes.Resolved(STEVE, "Steve"));
        b.votes.joined(STEVE, "Steve").get(10, TimeUnit.SECONDS);
        assertEquals(VoteResult.COUNTED, a.vote("Steve", "List"));
        await(() -> b.paid.size() == 1);
        await(() -> b.votes.voters().get(STEVE) != null && b.votes.voters().get(STEVE).total() == 1);
        Thread.sleep(2500);
        assertEquals(1, b.paid.size(), "paid once");
        assertEquals(0, a.paid.size());
    }

    @Test
    void aNameTheReceivingServerDoesntKnowIsMatchedWhereItsOnline() throws Exception {
        b.votes.joined(NOTCH, "Notch").get(10, TimeUnit.SECONDS);
        assertEquals(VoteResult.WAITING, a.vote("Notch", "List"));
        await(() -> b.paid.size() == 1);
        // And the receiving server learns the totals too.
        await(() -> a.votes.voters().get(NOTCH) != null);
    }

    @Test
    void bothServersRacingForOfflineVotesPayEachOnce() throws Exception {
        serverKnows.put("steve", new Votes.Resolved(STEVE, "Steve"));
        for (int i = 0; i < 25; i++) {
            assertEquals(VoteResult.COUNTED, a.vote("Steve", "List " + i));
        }
        CompletableFuture.allOf(a.votes.joined(STEVE, "Steve"), b.votes.joined(STEVE, "Steve")).get(30, TimeUnit.SECONDS);
        Thread.sleep(2500);
        List<Facts> all = new ArrayList<>(a.paid);
        all.addAll(b.paid);
        Set<Long> ids = new HashSet<>();
        all.forEach(facts -> ids.add(facts.id()));
        assertEquals(25, all.size());
        assertEquals(25, ids.size());
    }

    @Test
    void twoServersCountingOnePlayersFirstVoteAtOnceGiveOneFirstOfTheDay() throws Exception {
        // Each round, a player who voted yesterday votes on both servers at the same moment,
        // from two sites. Under MySQL's REPEATABLE READ a plain count of today's votes reads a
        // snapshot from before the other server's vote; the locked voters row doesn't.
        int rounds = 5;
        long day = 86_400_000L;
        for (int i = 0; i < rounds; i++) {
            UUID id = new UUID(7, i);
            serverKnows.put("racer" + i, new Votes.Resolved(id, "Racer" + i));
            long now = System.currentTimeMillis();
            IncomingVote yesterday = new IncomingVote("Zero", "Racer" + i, "", "z" + i, VoteSource.VOTIFIER_V2, now - day);
            assertEquals(VoteResult.COUNTED, a.votes.accept(yesterday).get(20, TimeUnit.SECONDS));
            IncomingVote one = new IncomingVote("One", "Racer" + i, "", "a" + i, VoteSource.VOTIFIER_V2, now);
            IncomingVote two = new IncomingVote("Two", "Racer" + i, "", "b" + i, VoteSource.VOTIFIER_V2, now);
            CompletableFuture<VoteResult> onA = a.votes.accept(one);
            CompletableFuture<VoteResult> onB = b.votes.accept(two);
            assertEquals(VoteResult.COUNTED, onA.get(20, TimeUnit.SECONDS));
            assertEquals(VoteResult.COUNTED, onB.get(20, TimeUnit.SECONDS));
        }
        try (Connection connection = a.store.database().connect(); Statement statement = connection.createStatement();
             java.sql.ResultSet row = statement.executeQuery("SELECT uuid, SUM(first_today), COUNT(*), MAX(number), MAX(streak) FROM "
                 + a.store.database().table("votes") + " WHERE uuid LIKE '00000000-0000-0007-%' GROUP BY uuid")) {
            int players = 0;
            while (row.next()) {
                players++;
                assertEquals(2, row.getInt(2), "one first vote yesterday and one today for " + row.getString(1));
                assertEquals(3, row.getInt(3));
                assertEquals(3, row.getInt(4), "numbered 1 to 3");
                assertEquals(2, row.getInt(5), "the second vote today doesn't make the streak 3");
            }
            assertEquals(rounds, players);
        }
    }

    /**
     * MariaDB 11.6 and later refuse a locking read of a row changed since the transaction's
     * snapshot ("Record has changed since last read"), so the transaction starts again and
     * the race can't happen there. MySQL has no such check: this makes MariaDB behave as it.
     */
    private static void likeMySql(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION innodb_snapshot_isolation = OFF");
        } catch (java.sql.SQLException e) {
            // MySQL, or an older MariaDB: already like this.
        }
    }

    /**
     * The race above, step by step, so it happens every time: server A's transaction reads
     * (taking its snapshot), server B counts a vote for the same player and commits, then A
     * counts its own. A must see B's vote as the first of the day. (A plain count of today's
     * votes would read A's snapshot, miss B's vote, and pay the first of the day twice.)
     */
    @Test
    void aVoteCountedAfterAnotherServersCommitIsntFirstOfTheDayAgain() throws Exception {
        Ledger ledger = new Ledger(a.store.database());
        Periods periods = new Periods(ZoneOffset.UTC, DayOfWeek.MONDAY);
        long now = System.currentTimeMillis();
        serverKnows.put("steve", new Votes.Resolved(STEVE, "Steve"));
        IncomingVote yesterday = new IncomingVote("Zero", "Steve", "", "z", VoteSource.VOTIFIER_V2, now - 86_400_000L);
        assertEquals(VoteResult.COUNTED, a.votes.accept(yesterday).get(10, TimeUnit.SECONDS));
        IncomingVote one = new IncomingVote("One", "Steve", "", "1", VoteSource.VOTIFIER_V2, now);
        IncomingVote two = new IncomingVote("Two", "Steve", "", "2", VoteSource.VOTIFIER_V2, now + 1);
        try (Connection onA = a.store.database().connect(); Connection onB = b.store.database().connect()) {
            for (Connection connection : List.of(onA, onB)) {
                likeMySql(connection);
                connection.setAutoCommit(false);
            }
            assertTrue(!ledger.isRepeat(onA, STEVE, "steve", one, 0), "A's snapshot starts here");
            Facts first = ledger.count(onB, ledger.insert(onB, two, STEVE, "Steve", null), STEVE, "Steve", "Two", two.receivedAt(), periods);
            onB.commit();
            Facts second = ledger.count(onA, ledger.insert(onA, one, STEVE, "Steve", null), STEVE, "Steve", "One", one.receivedAt(), periods);
            onA.commit();
            assertTrue(first.firstToday());
            assertTrue(!second.firstToday(), "B's vote was today's first");
            assertEquals(3, second.number());
            assertEquals(2, second.streak());
        }
    }

    @Test
    void thePartyReachesEveryServer() throws Exception {
        for (int i = 0; i < 5; i++) {
            a.vote("Voter" + i, "List");
        }
        assertEquals(1, a.parties.size());
        await(() -> b.parties.size() == 1);
    }
}
