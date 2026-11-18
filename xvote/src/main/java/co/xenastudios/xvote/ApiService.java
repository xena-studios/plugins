package co.xenastudios.xvote;

import co.xenastudios.xvote.api.PartyProgress;
import co.xenastudios.xvote.api.Period;
import co.xenastudios.xvote.api.RewardType;
import co.xenastudios.xvote.api.TopVoter;
import co.xenastudios.xvote.api.VoteResult;
import co.xenastudios.xvote.api.VoteSource;
import co.xenastudios.xvote.api.VoterStats;
import co.xenastudios.xvote.api.XVoteApi;
import co.xenastudios.xvote.vote.IncomingVote;
import co.xenastudios.xvote.vote.Voter;
import co.xenastudios.xvote.vote.Voters;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.Plugin;

/** xVote's developer API, registered with Bukkit's services manager while xVote runs. */
final class ApiService implements XVoteApi {

    private final XVote plugin;

    ApiService(XVote plugin) {
        this.plugin = plugin;
    }

    @Override
    public VoterStats stats(UUID player) {
        Objects.requireNonNull(player, "player");
        Voter voter = plugin.voters().get(player);
        if (voter == null) {
            return new VoterStats(player, "", 0, 0, 0, 0, 0, 0, Map.of());
        }
        long now = System.currentTimeMillis();
        var periods = plugin.periods();
        Map<String, Instant> last = new LinkedHashMap<>();
        voter.lastBySite().forEach((site, at) -> last.put(site, Instant.ofEpochMilli(at)));
        return new VoterStats(player, voter.name(), voter.total(), voter.today(periods, now), voter.week(periods, now),
            voter.month(periods, now), voter.streakNow(periods, now), voter.bestStreak(), Map.copyOf(last));
    }

    @Override
    public List<TopVoter> top(Period period, int limit) {
        Objects.requireNonNull(period, "period");
        List<Voters.Ranked> ranked = plugin.voters().top(period, System.currentTimeMillis());
        List<TopVoter> top = new ArrayList<>(Math.min(Math.max(1, limit), ranked.size()));
        for (int i = 0; i < ranked.size() && i < Math.max(1, limit); i++) {
            Voters.Ranked entry = ranked.get(i);
            top.add(new TopVoter(entry.rank(), entry.voter().id(), entry.voter().name(), entry.votes()));
        }
        return List.copyOf(top);
    }

    @Override
    public PartyProgress party() {
        var settings = plugin.config().party();
        return new PartyProgress((int) Math.min(Integer.MAX_VALUE, plugin.votes().party().votes()),
            settings.enabled() ? settings.votes() : 0);
    }

    @Override
    public void registerRewardType(Plugin owner, String id, RewardType type) {
        plugin.rewardTypes().register(owner, id, type);
    }

    @Override
    public CompletableFuture<VoteResult> submitVote(String site, String username, String address) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(username, "username");
        long now = System.currentTimeMillis();
        return plugin.votes().accept(new IncomingVote(site, username, address == null ? "" : address,
            Long.toString(now), VoteSource.API, now));
    }
}
