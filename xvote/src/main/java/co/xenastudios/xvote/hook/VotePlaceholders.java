package co.xenastudios.xvote.hook;

import co.xenastudios.xvote.XVote;
import co.xenastudios.xvote.api.Period;
import co.xenastudios.xvote.vote.Voter;
import co.xenastudios.xvote.vote.Voters;
import java.util.List;
import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * xVote's placeholders, all answered from memory. Only loaded when PlaceholderAPI is installed.
 *
 * <ul>
 *   <li>{@code %xvote_total%}, {@code _today}, {@code _week}, {@code _month}: the player's votes</li>
 *   <li>{@code %xvote_streak%}, {@code %xvote_best_streak%}</li>
 *   <li>{@code %xvote_available%}: sites they can vote on now</li>
 *   <li>{@code %xvote_party_votes%}, {@code _needed}, {@code _left}</li>
 *   <li>{@code %xvote_top_<period>_<n>_name%} and {@code _votes}: the leaderboards, period day, week, month or all</li>
 * </ul>
 */
public final class VotePlaceholders extends PlaceholderExpansion {

    private final XVote plugin;

    public VotePlaceholders(XVote plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "xvote";
    }

    @Override
    public String getAuthor() {
        return "Xena Studios";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        if (key.startsWith("top_")) {
            return top(key, now);
        }
        var party = plugin.config().party();
        switch (key) {
            case "party_votes" -> {
                return Long.toString(plugin.votes().party().votes());
            }
            case "party_needed" -> {
                return party.enabled() ? Integer.toString(party.votes()) : "0";
            }
            case "party_left" -> {
                return party.enabled() ? Long.toString(Math.max(0, party.votes() - plugin.votes().party().votes())) : "0";
            }
            default -> {
                // Below: the player's own.
            }
        }
        if (player == null) {
            return "";
        }
        Voter voter = plugin.voters().get(player.getUniqueId());
        var periods = plugin.periods();
        return switch (key) {
            case "total" -> Integer.toString(voter == null ? 0 : voter.total());
            case "today" -> Integer.toString(voter == null ? 0 : voter.today(periods, now));
            case "week" -> Integer.toString(voter == null ? 0 : voter.week(periods, now));
            case "month" -> Integer.toString(voter == null ? 0 : voter.month(periods, now));
            case "streak" -> Integer.toString(voter == null ? 0 : voter.streakNow(periods, now));
            case "best_streak" -> Integer.toString(voter == null ? 0 : voter.bestStreak());
            case "available" -> Integer.toString(plugin.available(player.getUniqueId(), now).size());
            default -> null;
        };
    }

    // top_<period>_<n>_name or top_<period>_<n>_votes; blank past the end of the board.
    private String top(String key, long now) {
        String[] parts = key.split("_");
        if (parts.length != 4) {
            return null;
        }
        Period period = switch (parts[1]) {
            case "day", "today" -> Period.DAY;
            case "week" -> Period.WEEK;
            case "month" -> Period.MONTH;
            case "all" -> Period.ALL;
            default -> null;
        };
        int n;
        try {
            n = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (period == null || n < 1 || !(parts[3].equals("name") || parts[3].equals("votes"))) {
            return null;
        }
        List<Voters.Ranked> ranked = plugin.voters().top(period, now);
        if (n > ranked.size()) {
            return parts[3].equals("name") ? "" : "0";
        }
        Voters.Ranked entry = ranked.get(n - 1);
        return parts[3].equals("name") ? entry.voter().name() : Integer.toString(entry.votes());
    }
}
