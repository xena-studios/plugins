package co.xenastudios.xvote.vote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xvote.api.Period;
import co.xenastudios.xvote.api.VoteResult;
import co.xenastudios.xvote.api.VoteSource;
import co.xenastudios.xvote.config.XVConfig;
import co.xenastudios.xvote.reward.Rewards;
import co.xenastudios.xvote.storage.Ledger;
import co.xenastudios.xvote.storage.Store;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Votes on two servers sharing one database: every vote is paid exactly once, wherever
 * the player is, however the servers race. The change feed's delivery is played by
 * calling {@link Votes#heard} by hand, since the feed itself only runs on MySQL.
 */
class VotesTest {

    static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID NOTCH = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final UUID BEDROCK = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    static final long DAY = 86_400_000L;
    // 2026-11-16, a Monday, at noon UTC.
    static final long NOON = 1_763_294_400_000L;

    @TempDir
    Path folder;
    final Logger logger = Logger.getLogger("test");
    final Map<String, Votes.Resolved> serverKnows = new HashMap<>();
    XVConfig config = config(3);
    Server a;
    Server b;

    static XVConfig config(int partyVotes) {
        XVConfig d = XVConfig.DEFAULTS;
        return new XVConfig(d.theme(), d.messages(), d.receiver(), d.sites(), d.rewards(), d.triggers(),
            new XVConfig.Party(true, partyVotes, XVConfig.Party.Who.ONLINE, List.of("party"), d.party().started(), 0, d.party().progress()),
            d.reminders(), d.counting(), d.storage(), d.network(), d.updateChecker());
    }

    /** One server: its own database thread, memory and players, sharing the database file. */
    final class Server implements Votes.Effects {
        final Store store;
        final Votes votes;
        final List<Facts> paid = Collections.synchronizedList(new ArrayList<>());
        final List<Long> parties = Collections.synchronizedList(new ArrayList<>());
        volatile boolean playerLeaves;
        // Payments waiting for the player's thread, as on a busy server.
        volatile boolean holdPayments;
        final List<Supplier<List<Facts>>> held = Collections.synchronizedList(new ArrayList<>());

        Server() throws Exception {
            store = Store.open(StorageSettings.defaults("xvote_"), folder, logger);
            Rewards rewards = Rewards.of(config, item -> {
                throw new IllegalArgumentException("no items in tests");
            }, warning -> {});
            Voters voters = new Voters(new Periods(ZoneOffset.UTC, DayOfWeek.MONDAY));
            votes = new Votes(store, new Ledger(store.database()), voters, new Online(), () -> rewards, () -> config, this,
                name -> serverKnows.get(name.toLowerCase(java.util.Locale.ROOT)), logger);
            votes.load(NOON);
        }

        @Override
        public void pay(UUID player, Supplier<List<Facts>> take, Runnable left) {
            if (playerLeaves) {
                left.run();
            } else if (holdPayments) {
                held.add(take);
            } else {
                paid.addAll(take.get());
            }
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

        VoteResult vote(String name, String site, long at) throws Exception {
            return votes.accept(new IncomingVote(site, name, "203.0.113.1", Long.toString(at), VoteSource.VOTIFIER_V2, at))
                .get(10, TimeUnit.SECONDS);
        }

        void join(UUID id, String name) throws Exception {
            votes.joined(id, name).get(10, TimeUnit.SECONDS);
        }

        void heard(long id) throws Exception {
            votes.heard(id).get(10, TimeUnit.SECONDS);
        }

        /** Waits for the database thread to finish what's queued (unclaims after a payout fails). */
        void settle() throws Exception {
            store.submit(c -> null).get(10, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void start() throws Exception {
        a = new Server();
        b = new Server();
    }

    @AfterEach
    void stop() {
        a.store.stop(5, TimeUnit.SECONDS);
        b.store.stop(5, TimeUnit.SECONDS);
    }

    long lastId() throws Exception {
        return a.store.now(c -> {
            try (Statement statement = c.createStatement(); ResultSet row = statement.executeQuery("SELECT MAX(id) FROM xvote_votes")) {
                row.next();
                return row.getLong(1);
            }
        });
    }

    @Test
    void paidOnceWhereThePlayerIs() throws Exception {
        a.join(STEVE, "Steve");
        assertEquals(VoteResult.COUNTED, a.vote("Steve", "List", NOON));
        assertEquals(1, a.paid.size());
        Facts facts = a.paid.get(0);
        assertEquals(1, facts.number());
        assertEquals(1, facts.streak());
        assertTrue(facts.firstToday());
        // The other server hears of it, and later Steve joins there: neither pays again.
        b.heard(lastId());
        b.join(STEVE, "Steve");
        a.join(STEVE, "Steve");
        assertEquals(0, b.paid.size());
        assertEquals(1, a.paid.size());
        assertEquals(1, b.votes.voters().get(STEVE).total(), "the other server's totals caught up");
    }

    @Test
    void offlineVotesArePaidByOneServerEvenWhenBothRace() throws Exception {
        serverKnows.put("steve", new Votes.Resolved(STEVE, "Steve"));
        for (int i = 0; i < 40; i++) {
            assertEquals(VoteResult.COUNTED, a.vote("Steve", "List " + i, NOON + i));
        }
        assertEquals(0, a.paid.size());
        // Steve "joins" both servers at once: each tries to take every vote.
        CompletableFuture<Void> onA = a.votes.joined(STEVE, "Steve");
        CompletableFuture<Void> onB = b.votes.joined(STEVE, "Steve");
        CompletableFuture.allOf(onA, onB).get(20, TimeUnit.SECONDS);
        List<Facts> all = new ArrayList<>(a.paid);
        all.addAll(b.paid);
        assertEquals(40, all.size());
        Set<Long> ids = new HashSet<>();
        all.forEach(facts -> ids.add(facts.id()));
        assertEquals(40, ids.size(), "no vote paid twice");
        // Joining again, anywhere, pays nothing more.
        a.join(STEVE, "Steve");
        b.join(STEVE, "Steve");
        assertEquals(40, a.paid.size() + b.paid.size());
    }

    @Test
    void anUnknownNameWaitsForItsPlayer() throws Exception {
        assertEquals(VoteResult.WAITING, a.vote("Notch", "List", NOON));
        assertEquals(VoteResult.WAITING, a.vote("notch", "Other", NOON + 1));
        b.join(NOTCH, "Notch");
        assertEquals(2, b.paid.size());
        assertEquals(2, b.paid.get(1).number());
        assertFalse(b.paid.get(1).firstToday());
        a.join(NOTCH, "Notch");
        assertEquals(0, a.paid.size());
    }

    @Test
    void aBedrockPlayerWhoLeftOffThePrefixIsPaid() throws Exception {
        assertEquals(VoteResult.WAITING, a.vote("Steve B", "List", NOON));
        a.join(BEDROCK, ".Steve_B");
        assertEquals(1, a.paid.size());
        // And once known, a later vote without the prefix finds them straight away.
        assertEquals(VoteResult.COUNTED, a.vote("Steve_B", "Other", NOON + 1));
        assertEquals(2, a.paid.size());
    }

    @Test
    void repeatsAreDropped() throws Exception {
        a.join(STEVE, "Steve");
        // A listed site with a 24-hour cooldown.
        assertEquals(VoteResult.COUNTED, a.vote("Steve", "PlanetMinecraft.com", NOON));
        assertEquals(VoteResult.DUPLICATE, a.vote("Steve", "planetminecraft.com", NOON + 3_600_000L));
        assertEquals(VoteResult.COUNTED, a.vote("Steve", "PlanetMinecraft.com", NOON + DAY - 60_000L), "within the grace");
        assertEquals(VoteResult.INVALID, a.vote("not a name!", "List", NOON));
        assertEquals(2, a.paid.size());
    }

    @Test
    void repeatsFromAnUnlistedSiteAreDroppedToo() throws Exception {
        a.join(STEVE, "Steve");
        // Not in sites: counting.unlisted-cooldown-hours (24) applies, so a replayed or resent vote isn't paid twice.
        assertEquals(VoteResult.COUNTED, a.vote("Steve", "Some List", NOON));
        assertEquals(VoteResult.DUPLICATE, a.vote("Steve", "Some List", NOON + 60_000L));
        assertEquals(VoteResult.DUPLICATE, a.vote("Steve", "SOME LIST", NOON + 12 * 3_600_000L), "the name ignoring case");
        IncomingVote replay = new IncomingVote("Some List", "Steve", "", Long.toString(NOON), VoteSource.VOTIFIER_V1, NOON + 3_600_000L);
        assertEquals(VoteResult.DUPLICATE, a.votes.accept(replay).get());
        assertEquals(VoteResult.COUNTED, a.vote("Steve", "Some List", NOON + DAY), "a day later");
        // Another unlisted site has its own cooldown.
        assertEquals(VoteResult.COUNTED, a.vote("Steve", "Another List", NOON + 60_000L));
        // /vote test without a site can be sent again and again.
        IncomingVote test = new IncomingVote("test", "Steve", "", "1", VoteSource.TEST, NOON + 1);
        IncomingVote again = new IncomingVote("test", "Steve", "", "2", VoteSource.TEST, NOON + 2);
        assertEquals(VoteResult.COUNTED, a.votes.accept(test).get());
        assertEquals(VoteResult.COUNTED, a.votes.accept(again).get());
        assertEquals(5, a.paid.size());
    }

    @Test
    void withNoUnlistedCooldownOnlyAReplayIsARepeat() throws Exception {
        XVConfig d = config;
        config = new XVConfig(d.theme(), d.messages(), d.receiver(), d.sites(), d.rewards(), d.triggers(), d.party(), d.reminders(),
            new XVConfig.Counting("UTC", DayOfWeek.MONDAY, ".", 30, 0), d.storage(), d.network(), d.updateChecker());
        Server c = new Server();
        try {
            c.join(STEVE, "Steve");
            IncomingVote once = new IncomingVote("List", "Steve", "", "same", VoteSource.VOTIFIER_V1, NOON);
            assertEquals(VoteResult.COUNTED, c.votes.accept(once).get());
            IncomingVote replay = new IncomingVote("List", "Steve", "", "same", VoteSource.VOTIFIER_V1, NOON + 60_000L);
            assertEquals(VoteResult.DUPLICATE, c.votes.accept(replay).get());
            assertEquals(VoteResult.COUNTED, c.vote("Steve", "List", NOON + 120_000L));
            assertEquals(2, c.paid.size());
        } finally {
            c.store.stop(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aPlayerWhoLeavesBeforePaymentIsPaidNextTime() throws Exception {
        a.join(STEVE, "Steve");
        a.playerLeaves = true;
        assertEquals(VoteResult.COUNTED, a.vote("Steve", "List", NOON));
        a.settle();
        assertEquals(0, a.paid.size());
        a.playerLeaves = false;
        b.join(STEVE, "Steve");
        assertEquals(1, b.paid.size());
        a.join(STEVE, "Steve");
        assertEquals(0, a.paid.size());
    }

    @Test
    void votesTakenButNotYetPaidAreGivenBackOnStop() throws Exception {
        a.join(STEVE, "Steve");
        a.holdPayments = true;
        assertEquals(VoteResult.COUNTED, a.vote("Steve", "List", NOON));
        assertEquals(VoteResult.COUNTED, a.vote("Steve", "Other", NOON + 1));
        assertEquals(2, a.votes.unpaid());
        // A normal stop, before the player's thread got to them.
        a.votes.shutdown();
        a.settle();
        assertEquals(0, a.votes.unpaid());
        // The payment that was waiting finds nothing left to pay...
        for (Supplier<List<Facts>> payment : a.held) {
            assertEquals(List.of(), payment.get());
        }
        assertEquals(0, a.paid.size());
        // ...and the next server Steve is on pays both, once.
        b.join(STEVE, "Steve");
        assertEquals(2, b.paid.size());
        b.join(STEVE, "Steve");
        assertEquals(2, b.paid.size());
    }

    @Test
    void aPaymentTakenBeforeTheStopIsntGivenBack() throws Exception {
        a.join(STEVE, "Steve");
        a.holdPayments = true;
        assertEquals(VoteResult.COUNTED, a.vote("Steve", "List", NOON));
        // The player's thread gets to it first: paid here, so the stop has nothing to give back.
        a.paid.addAll(a.held.get(0).get());
        a.votes.shutdown();
        a.settle();
        assertEquals(1, a.paid.size());
        b.join(STEVE, "Steve");
        assertEquals(0, b.paid.size(), "never paid twice");
    }

    @Test
    void aJoinWithNothingNewLeavesTheLeaderboardsSorted() throws Exception {
        a.join(STEVE, "Steve");
        a.vote("Steve", "List", NOON);
        // A join reads Steve as of the clock, not NOON as the test's vote did: that one is a change.
        a.join(STEVE, "Steve");
        a.votes.voters().top(Period.ALL, NOON);
        long version = a.votes.voters().version();
        a.join(STEVE, "Steve");
        a.join(STEVE, "Steve");
        assertEquals(version, a.votes.voters().version(), "unchanged, so not sorted again");
        a.vote("Steve", "Other", NOON + 1);
        assertTrue(a.votes.voters().version() > version);
    }

    @Test
    void streaksAndFirstVotesOfTheDay() throws Exception {
        a.join(STEVE, "Steve");
        long[] times = {NOON, NOON + 3_600_000L, NOON + DAY, NOON + 2 * DAY, NOON + 4 * DAY};
        for (int i = 0; i < times.length; i++) {
            // The second vote that day is from another site: a site's cooldown would drop it.
            assertEquals(VoteResult.COUNTED, a.vote("Steve", i == 1 ? "Other" : "List", times[i]), "vote " + i);
        }
        int[] streaks = {1, 1, 2, 3, 1};
        boolean[] first = {true, false, true, true, true};
        for (int i = 0; i < times.length; i++) {
            Facts facts = a.paid.get(i);
            assertEquals(i + 1, facts.number());
            assertEquals(streaks[i], facts.streak(), "vote " + i);
            assertEquals(first[i], facts.firstToday(), "vote " + i);
        }
        Voter steve = a.votes.voters().get(STEVE);
        assertEquals(5, steve.total());
        assertEquals(3, steve.bestStreak());
    }

    @Test
    void anotherServersNewsPaysThePlayerHereOnce() throws Exception {
        serverKnows.put("steve", new Votes.Resolved(STEVE, "Steve"));
        b.join(STEVE, "Steve");
        assertEquals(VoteResult.COUNTED, a.vote("Steve", "List", NOON));
        assertEquals(0, a.paid.size(), "Steve isn't on the server that got the vote");
        long id = lastId();
        b.heard(id);
        b.heard(id);
        assertEquals(1, b.paid.size());
    }

    @Test
    void aServerThatMissedTheNewsPaysOnceWhenItReadsAgain() throws Exception {
        serverKnows.put("steve", new Votes.Resolved(STEVE, "Steve"));
        b.join(STEVE, "Steve");
        assertEquals(VoteResult.COUNTED, a.vote("Steve", "List", NOON));
        // B never hears about it (its feed was down), then the feed tells it to read again.
        b.votes.resync().get(10, TimeUnit.SECONDS);
        assertEquals(1, b.paid.size());
        b.votes.resync().get(10, TimeUnit.SECONDS);
        b.heard(lastId());
        assertEquals(1, b.paid.size(), "paid once, however it heard");
    }

    @Test
    void anotherServersNewsMatchesANameOnlineHere() throws Exception {
        assertEquals(VoteResult.WAITING, a.vote("Notch", "List", NOON));
        b.join(NOTCH, "Notch");
        b.paid.clear();
        assertEquals(VoteResult.WAITING, a.vote("Notch", "Other", NOON + 1), "the receiving server still doesn't know Notch");
        b.heard(lastId());
        assertEquals(1, b.paid.size());
        assertEquals(2, b.paid.get(0).number());
    }

    @Test
    void thePartyStartsEveryNVotes() throws Exception {
        for (int i = 0; i < 7; i++) {
            a.vote("Voter" + i, "List", NOON + i);
        }
        assertEquals(2, a.parties.size());
        assertEquals(1, a.votes.party().votes());
    }

    @Test
    void thePlayerWhoseVoteStartedAPartyIsntInTheNextOne() throws Exception {
        for (int i = 0; i < 6; i++) {
            a.vote("Voter" + i, "List", NOON + i);
        }
        assertEquals(List.of(NOON, NOON + 3), a.parties, "the second round starts after the vote that ended the first");
    }

    @Test
    void aNameTakenByANewPlayerIsTheirs() throws Exception {
        // Steve votes as Bob, then renames to Rob; a new player takes the name Bob.
        a.join(STEVE, "Bob");
        a.vote("Bob", "One", NOON);
        a.votes.quit(STEVE, "Bob");
        a.join(STEVE, "Rob");
        a.votes.quit(STEVE, "Rob");
        a.join(NOTCH, "Bob");
        a.votes.quit(NOTCH, "Bob");
        serverKnows.put("bob", new Votes.Resolved(NOTCH, "Bob"));
        a.paid.clear();
        a.vote("Bob", "Two", NOON + DAY);
        a.join(NOTCH, "Bob");
        assertEquals(1, a.paid.size(), "the new Bob is paid");
        assertEquals(1, a.votes.voters().get(STEVE).total(), "not added to the old Bob's");
        assertEquals("Rob", a.votes.voters().get(STEVE).name());
    }

    @Test
    void leaderboardsAndAFreshLoadAgree() throws Exception {
        a.join(STEVE, "Steve");
        a.join(NOTCH, "Notch");
        a.vote("Steve", "One", NOON);
        a.vote("Notch", "One", NOON + 1);
        a.vote("Notch", "Two", NOON + 2);
        List<Voters.Ranked> top = a.votes.voters().top(Period.ALL, NOON + 10);
        assertEquals("Notch", top.get(0).voter().name());
        assertEquals(2, top.get(0).votes());
        assertEquals(2, top.get(1).rank());
        assertEquals(top.size(), a.votes.voters().top(Period.DAY, NOON + 10).size());
        assertEquals(0, a.votes.voters().top(Period.DAY, NOON + DAY).size(), "a new day starts empty");
        Server fresh = new Server();
        try {
            Voter loaded = fresh.votes.voters().get(NOTCH);
            assertEquals(2, loaded.total());
            assertEquals(2, loaded.today(fresh.votes.voters().periods(), NOON));
            assertTrue(loaded.lastOn("two") > 0);
        } finally {
            fresh.store.stop(5, TimeUnit.SECONDS);
        }
    }
}
