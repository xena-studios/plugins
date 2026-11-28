package co.xenastudios.xvote.bukkit

import co.xenastudios.core.bukkit.ref
import co.xenastudios.xvote.XVotePlugin
import co.xenastudios.xvote.api.Period
import co.xenastudios.xvote.config.XVoteConfig
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

/**
 * Where [XVoteBukkit] reaches the placeholders: only this class names PlaceholderAPI's, so the entry
 * point's own class is verified without it, on a server that doesn't have it.
 */
internal object PlaceholderHook {
    fun register(plugin: XVotePlugin, version: String): Any = Placeholders(plugin, version).also { it.register() }

    fun unregister(expansion: Any) {
        (expansion as Placeholders).unregister()
    }
}

/**
 * xVote's PlaceholderAPI placeholders, 1.x's, all answered from memory. Loaded only while
 * PlaceholderAPI is on.
 *
 * - `%xvote_total%`, `_today`, `_week`, `_month`: the player's votes
 * - `%xvote_streak%`, `%xvote_best_streak%`
 * - `%xvote_available%`: sites they can vote on now
 * - `%xvote_party_votes%`, `_needed`, `_left`
 * - `%xvote_top_<period>_<n>_name%` and `_votes`: the leaderboards, period day, week, month or all
 */
internal class Placeholders(private val plugin: XVotePlugin, private val version: String) : PlaceholderExpansion() {
    override fun getIdentifier(): String = "xvote"

    override fun getAuthor(): String = "Xena Studios"

    override fun getVersion(): String = version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val key = params.lowercase()
        if (key.startsWith("top_")) return top(key)
        val party = plugin.party()
        when (key) {
            "party_votes" -> return party.votes.toString()
            "party_needed" -> return party.needed.toString()
            "party_left" -> return if (plugin.config[XVoteConfig.party.enabled]) party.left.toString() else "0"
        }
        if (player == null) return ""
        val stats = plugin.stats(player.ref)
        return when (key) {
            "total" -> stats.total.toString()
            "today" -> stats.today.toString()
            "week" -> stats.week.toString()
            "month" -> stats.month.toString()
            "streak" -> stats.streak.toString()
            "best_streak" -> stats.bestStreak.toString()
            "available" -> plugin.rewards.sites.count { (stats.lastVotes[it.service.lowercase()] ?: 0L) + it.cooldownMs <= System.currentTimeMillis() }.toString()
            else -> null
        }
    }

    // top_<period>_<n>_name or top_<period>_<n>_votes; blank past the end of the board.
    private fun top(key: String): String? {
        val parts = key.split('_')
        if (parts.size != 4) return null
        val period = when (parts[1]) {
            "day", "today" -> Period.DAY
            "week" -> Period.WEEK
            "month" -> Period.MONTH
            "all" -> Period.ALL
            else -> return null
        }
        val n = parts[2].toIntOrNull() ?: return null
        if (n < 1 || (parts[3] != "name" && parts[3] != "votes")) return null
        val ranked = plugin.top(period, n)
        if (n > ranked.size) return if (parts[3] == "name") "" else "0"
        val entry = ranked[n - 1]
        return if (parts[3] == "name") entry.name else entry.votes.toString()
    }
}
