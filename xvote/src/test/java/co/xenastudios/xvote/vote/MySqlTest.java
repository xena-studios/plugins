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
        public void pay(UUID player, List<Facts> facts, Runnable left) {
            paid.addAll(facts);
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
    void thePartyReachesEveryServer() throws Exception {
        for (int i = 0; i < 5; i++) {
            a.vote("Voter" + i, "List");
        }
        assertEquals(1, a.parties.size());
        await(() -> b.parties.size() == 1);
    }
}
