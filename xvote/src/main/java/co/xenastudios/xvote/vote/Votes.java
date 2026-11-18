package co.xenastudios.xvote.vote;

import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.xvote.api.VoteResult;
import co.xenastudios.xvote.api.VoteSource;
import co.xenastudios.xvote.api.event.VoteReceivedEvent;
import co.xenastudios.xvote.config.XVConfig;
import co.xenastudios.xvote.reward.Rewards;
import co.xenastudios.xvote.storage.Ledger;
import co.xenastudios.xvote.storage.Store;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

/**
 * Where every vote goes: from the receiver, {@code /vote test} and the API. It checks a
 * vote, stores it, counts it for its player, and pays it once.
 *
 * <p><b>Never twice.</b> A vote is paid by the server that takes it: one
 * {@code UPDATE ... SET claim = ? WHERE claim IS NULL}, in the database, so two servers
 * sharing it can't both take one. A vote for a player online here is taken as it's stored;
 * one for a player elsewhere on the network is taken by their server when it hears of it
 * through the change feed; the rest wait, and the first server the player joins takes
 * them. A vote is taken before it's paid, so a crash in between loses that reward rather
 * than paying it again; a player who leaves before their reward reaches their thread gives
 * the vote back, untaken, for next time.
 *
 * <p>Every database step runs on the store's one thread, in order. Memory (totals,
 * leaderboards, the party's count) is updated after the step commits.
 */
public final class Votes {

    /** Cooldowns allow this much early, for lists whose clocks run a little fast. */
    static final long GRACE_MS = 5 * 60_000L;
    /** Each player's last vote per site is kept at least this far back, for /vote. */
    static final long SITE_WINDOW_MS = 48 * 3_600_000L;

    /** What a vote needs done outside the database: paying, parties, threads. */
    public interface Effects {
        /**
         * Pays these votes on the player's thread, in order, or calls {@code left} if the
         * player is gone first.
         */
        void pay(UUID player, List<Facts> votes, Runnable left);

        /** A vote party starts on this server; {@code since} is when its round began. */
        void party(long since);

        /** {@code left} votes to go until the party. */
        void progress(long left);

        /** Whether this is a server thread, where async events can't be fired. */
        boolean onServerThread();

        /** Runs off the server's threads. */
        void async(Runnable work);
    }

    /** A name's player, found in memory. */
    public record Resolved(UUID id, String name) {}

    private final Store store;
    private final Ledger ledger;
    private final Voters voters;
    private final Online online;
    private final Supplier<Rewards> rewards;
    private final Supplier<XVConfig> config;
    private final Effects effects;
    private final Function<String, Resolved> known;
    private final Logger logger;
    private final String origin = newOrigin();
    private final AtomicLong claims = new AtomicLong();
    private final Map<String, CompletableFuture<VoteResult>> tests = new ConcurrentHashMap<>();
    private volatile ChangeFeed feed = ChangeFeed.off();
    private volatile Ledger.PartyState party = new Ledger.PartyState(0, 0);

    /**
     * @param known players the server knows by name beyond who's online and who has voted
     *     (its user cache), from memory, or null
     */
    public Votes(Store store, Ledger ledger, Voters voters, Online online, Supplier<Rewards> rewards,
                 Supplier<XVConfig> config, Effects effects, Function<String, Resolved> known, Logger logger) {
        this.store = store;
        this.ledger = ledger;
        this.voters = voters;
        this.online = online;
        this.rewards = rewards;
        this.config = config;
        this.effects = effects;
        this.known = known;
        this.logger = logger;
    }

    private static String newOrigin() {
        byte[] bytes = new byte[6];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** A claim no other server or moment can have: this run's random id and a counter. */
    String newClaim() {
        return origin + "-" + Long.toString(claims.incrementAndGet(), 36);
    }

    public Voters voters() {
        return voters;
    }

    /** Who is online on this server. */
    public Online online() {
        return online;
    }

    /** The party's count, as last stored. */
    public Ledger.PartyState party() {
        return party;
    }

    /** Reads every voter and the party's count. Blocks: call once, at startup. */
    public void load(long now) throws SQLException {
        Periods periods = voters.periods();
        long window = siteWindow();
        voters.putAll(store.now(c -> ledger.loadAll(c, periods, now, window)));
        party = store.now(c -> ledger.party(c, false));
    }

    /** Listens to the other servers' votes, payouts and parties. Call before the feed starts. */
    public void network(ChangeFeed feed) {
        this.feed = feed;
        feed.subscribe("vote", change -> heard(parseLong(change.payload())));
        feed.subscribe("player", change -> refresh(UUID.fromString(change.payload())));
        feed.subscribe("party", change -> partyNews(change.payload()));
    }

    private long siteWindow() {
        return Math.max(SITE_WINDOW_MS, rewards.get().longestCooldown());
    }

    // ---- votes arriving ----

    /**
     * {@code /vote test} is about to send this vote through the receiver: when it arrives,
     * it's marked as a test and its result completes the future.
     */
    public CompletableFuture<VoteResult> expectTest(String site, String username, String timestamp) {
        CompletableFuture<VoteResult> future = new CompletableFuture<>();
        tests.put(testKey(site, username, timestamp), future);
        future.orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .whenComplete((result, error) -> tests.remove(testKey(site, username, timestamp), future));
        return future;
    }

    private static String testKey(String site, String username, String timestamp) {
        return site + "\n" + username + "\n" + timestamp;
    }

    /** Checks, stores, counts and pays a vote. Any thread. */
    public CompletableFuture<VoteResult> accept(IncomingVote arrived) {
        if (effects.onServerThread()) {
            CompletableFuture<VoteResult> later = new CompletableFuture<>();
            effects.async(() -> accept(arrived).whenComplete((result, error) -> {
                if (error != null) {
                    later.completeExceptionally(error);
                } else {
                    later.complete(result);
                }
            }));
            return later;
        }
        CompletableFuture<VoteResult> test = tests.remove(testKey(arrived.site(), arrived.username(), arrived.timestamp()));
        IncomingVote vote = test == null ? arrived : new IncomingVote(arrived.site(), arrived.username(), arrived.address(),
            arrived.timestamp(), VoteSource.TEST, arrived.receivedAt());
        CompletableFuture<VoteResult> result = handle(vote);
        if (test != null) {
            result.whenComplete((value, error) -> test.complete(error == null ? value : VoteResult.FAILED));
        }
        return result;
    }

    private CompletableFuture<VoteResult> handle(IncomingVote vote) {
        if (VoteReceivedEvent.getHandlerList().getRegisteredListeners().length > 0) {
            VoteReceivedEvent event = new VoteReceivedEvent(vote.site(), vote.username(), vote.address(), vote.timestamp(), vote.source());
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                log(vote, vote.username(), "dropped by another plugin");
                return CompletableFuture.completedFuture(VoteResult.CANCELLED);
            }
        }
        String prefix = config.get().counting().bedrockPrefix();
        String name = Names.clean(vote.username(), prefix);
        if (name == null) {
            log(vote, vote.username(), "dropped: \"" + vote.username() + "\" can't be a Minecraft name");
            return CompletableFuture.completedFuture(VoteResult.INVALID);
        }
        Resolved player = resolve(name, prefix);
        return store.submit(c -> record(c, vote, player, name)).handle((stored, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Couldn't store a vote from " + vote.site() + " for " + name, error);
                return VoteResult.FAILED;
            }
            return after(vote, stored);
        });
    }

    /** The player a name belongs to, from memory: online here, voted before, or known to the server. */
    Resolved resolve(String name, String prefix) {
        for (String candidate : Names.lookups(name, prefix)) {
            UUID here = online.byName(candidate);
            if (here != null) {
                return new Resolved(here, online.name(here));
            }
            UUID voted = voters.byName(candidate);
            if (voted != null) {
                return new Resolved(voted, voters.get(voted).name());
            }
            Resolved server = known == null ? null : known.apply(candidate);
            if (server != null) {
                return server;
            }
        }
        return null;
    }

    /** What storing a vote did. */
    private record Stored(VoteResult result, Facts facts, String claim, Voter voter, Ledger.PartyState party,
                          long partyFrom, long left) {}

    private Stored record(Connection c, IncomingVote vote, Resolved player, String name) throws SQLException {
        long cooldown = rewards.get().cooldown(vote.site());
        cooldown = cooldown > 0 ? Math.max(0, cooldown - GRACE_MS) : 0;
        UUID id = player == null ? null : player.id();
        String shown = player == null ? name : player.name();
        if (ledger.isRepeat(c, id, Ledger.key(shown), vote, cooldown)) {
            return new Stored(VoteResult.DUPLICATE, null, null, null, null, -1, -1);
        }
        String claim = id != null && online.has(id) ? newClaim() : null;
        long voteId = ledger.insert(c, vote, id, shown, claim);
        Facts facts = id == null ? null : ledger.count(c, voteId, id, shown, vote.site(), vote.receivedAt(), voters.periods());
        feed.publish(c, "vote", Long.toString(voteId));

        XVConfig.Party settings = config.get().party();
        Ledger.PartyState before = ledger.party(c, true);
        Ledger.PartyState after = before;
        long partyFrom = -1;
        long left = -1;
        if (settings.enabled()) {
            long since = before.since() == 0 ? vote.receivedAt() : before.since();
            long count = before.votes() + 1;
            if (count >= settings.votes()) {
                partyFrom = since;
                after = new Ledger.PartyState(0, vote.receivedAt());
                feed.publish(c, "party", "start:" + since);
            } else {
                after = new Ledger.PartyState(count, since);
                long remaining = settings.votes() - count;
                if (settings.progressEvery() > 0 && remaining % settings.progressEvery() == 0) {
                    left = remaining;
                    feed.publish(c, "party", "left:" + remaining);
                }
            }
            ledger.party(c, after);
        }
        Voter voter = id == null ? null : ledger.voter(c, id, voters.periods(), vote.receivedAt(), siteWindow());
        return new Stored(id == null ? VoteResult.WAITING : VoteResult.COUNTED, facts, claim, voter, after, partyFrom, left);
    }

    private VoteResult after(IncomingVote vote, Stored stored) {
        if (stored.party() != null) {
            party = stored.party();
        }
        if (stored.voter() != null) {
            voters.put(stored.voter());
        }
        String who = stored.facts() != null ? stored.facts().name() : vote.username();
        switch (stored.result()) {
            case DUPLICATE -> log(vote, who, "dropped: a repeat within the site's cooldown");
            case WAITING -> log(vote, who, "held until " + who + " first joins");
            default -> log(vote, who, stored.claim() != null ? "paid" : "counted; paid when " + who + " is next online");
        }
        if (stored.claim() != null) {
            pay(stored.facts().player(), List.of(stored.facts()), stored.claim());
        }
        if (stored.partyFrom() >= 0) {
            effects.party(stored.partyFrom());
        } else if (stored.left() >= 0) {
            effects.progress(stored.left());
        }
        return stored.result();
    }

    private void log(IncomingVote vote, String name, String outcome) {
        logger.info("Vote for " + name + " from " + vote.site() + " (" + source(vote.source()) + "): " + outcome + ".");
    }

    private static String source(VoteSource source) {
        return switch (source) {
            case VOTIFIER_V1 -> "v1";
            case VOTIFIER_V2 -> "v2";
            case TEST -> "test";
            case API -> "plugin";
        };
    }

    private void pay(UUID player, List<Facts> votes, String claim) {
        effects.pay(player, votes, () -> store.submit(c -> {
            for (Facts facts : votes) {
                ledger.unclaim(c, facts.id(), claim);
            }
            return null;
        }));
    }

    // ---- players joining ----

    private record Joined(Voter voter, List<Facts> unpaid, String claim, int matched) {}

    /** A player joined: their unmatched votes become theirs, and everything unpaid is paid. */
    public CompletableFuture<Void> joined(UUID player, String name) {
        online.add(player, name);
        Set<String> keys = Names.matchKeys(name, config.get().counting().bedrockPrefix());
        long now = System.currentTimeMillis();
        return store.submit(c -> {
            int matched = ledger.match(c, player, name, keys, voters.periods());
            if (matched > 0) {
                feed.publish(c, "player", player.toString());
            }
            String claim = newClaim();
            List<Facts> unpaid = ledger.claimUnpaid(c, player, claim, now);
            Voter voter = ledger.voter(c, player, voters.periods(), now, siteWindow());
            return new Joined(voter, unpaid, claim, matched);
        }).handle((joined, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Couldn't check " + name + "'s waiting votes", error);
                return null;
            }
            if (joined.voter() != null) {
                voters.put(joined.voter());
            }
            if (joined.matched() > 0) {
                logger.info(name + " joined: " + joined.matched() + " votes held for their name are now theirs.");
            }
            if (!joined.unpaid().isEmpty()) {
                logger.info("Paying " + name + " for " + joined.unpaid().size() + " votes from while they were away.");
                pay(player, joined.unpaid(), joined.claim());
            }
            return null;
        });
    }

    public void quit(UUID player, String name) {
        online.remove(player, name);
    }

    // ---- news from the other servers ----

    private record Heard(UUID player, Voter voter, List<Facts> unpaid, String claim, Ledger.PartyState party) {}

    /** Another server stored a vote: count it here, and pay it if its player is online here. */
    CompletableFuture<Void> heard(long voteId) {
        String prefix = config.get().counting().bedrockPrefix();
        long now = System.currentTimeMillis();
        return store.submit(c -> {
            Ledger.Row row = ledger.read(c, voteId);
            Ledger.PartyState party = ledger.party(c, false);
            if (row == null) {
                return new Heard(null, null, List.of(), null, party);
            }
            UUID player = row.player();
            if (player == null) {
                // Its name may be online here though the other server didn't know it.
                String name = Names.clean(row.name(), prefix);
                UUID here = name == null ? null : online.byName(name);
                if (here == null && name != null && !prefix.isEmpty() && !name.startsWith(prefix)) {
                    here = online.byName(prefix + name);
                }
                if (here == null) {
                    return new Heard(null, null, List.of(), null, party);
                }
                String hereName = online.name(here);
                if (ledger.match(c, here, hereName, Names.matchKeys(hereName, prefix), voters.periods()) > 0) {
                    feed.publish(c, "player", here.toString());
                }
                player = here;
            }
            String claim = null;
            List<Facts> unpaid = List.of();
            if (online.has(player)) {
                claim = newClaim();
                unpaid = ledger.claimUnpaid(c, player, claim, now);
            }
            return new Heard(player, ledger.voter(c, player, voters.periods(), now, siteWindow()), unpaid, claim, party);
        }).handle((heard, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Couldn't read a vote another server stored", error);
                return null;
            }
            party = heard.party();
            if (heard.voter() != null) {
                voters.put(heard.voter());
            }
            if (!heard.unpaid().isEmpty()) {
                pay(heard.player(), heard.unpaid(), heard.claim());
            }
            return null;
        });
    }

    /** Another server changed a player's votes: read them again. */
    void refresh(UUID player) {
        long now = System.currentTimeMillis();
        store.submit(c -> ledger.voter(c, player, voters.periods(), now, siteWindow())).whenComplete((voter, error) -> {
            if (voter != null) {
                voters.put(voter);
            }
        });
    }

    private void partyNews(String payload) {
        if (payload.startsWith("start:")) {
            party = new Ledger.PartyState(0, System.currentTimeMillis());
            effects.party(parseLong(payload.substring(6)));
        } else if (payload.startsWith("left:")) {
            long left = parseLong(payload.substring(5));
            party = new Ledger.PartyState(Math.max(0, config.get().party().votes() - left), party.since());
            effects.progress(left);
        }
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---- staff ----

    /** Starts a party now, as if the last vote it needed had arrived. */
    public CompletableFuture<Void> startParty() {
        long now = System.currentTimeMillis();
        return store.submit(c -> {
            Ledger.PartyState before = ledger.party(c, true);
            long since = before.since() == 0 ? now : before.since();
            Ledger.PartyState after = new Ledger.PartyState(0, now);
            ledger.party(c, after);
            feed.publish(c, "party", "start:" + since);
            return new long[] {since};
        }).thenAccept(since -> {
            party = new Ledger.PartyState(0, now);
            effects.party(since[0]);
        });
    }

    /** Deletes votes for names that never joined, past the configured days. */
    public void forgetUnknown(long now) {
        int days = config.get().counting().unknownNameDays();
        if (days <= 0) {
            return;
        }
        store.submit(c -> ledger.forgetUnknown(c, now - days * 86_400_000L)).whenComplete((deleted, error) -> {
            if (deleted != null && deleted > 0) {
                logger.info("Forgot " + deleted + " votes for names that didn't join within " + days + " days.");
            }
        });
    }
}
