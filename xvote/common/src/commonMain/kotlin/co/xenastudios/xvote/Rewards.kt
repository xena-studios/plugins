package co.xenastudios.xvote

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.Sound
import co.xenastudios.xvote.config.XVoteConfig
import co.xenastudios.xvote.vote.Facts

/**
 * The sites, rewards and triggers from the config, read once per load: items parsed, names
 * checked, so a vote only looks things up. Never changed; a reload makes a new one. 1.x's `Rewards`.
 */
public class Rewards private constructor(
    public val sites: List<SiteEntry>,
    private val byService: Map<String, SiteEntry>,
    private val byKey: Map<String, SiteEntry>,
    private val rewards: Map<String, RewardEntry>,
    private val everyVote: List<String>,
    private val firstOfDay: List<String>,
    private val every: Map<Int, List<String>>,
    private val streak: Map<Int, List<String>>,
    private val total: Map<Int, List<String>>,
    public val party: List<String>,
    private val unlistedCooldown: Long,
    /** The longest cooldown of any site: how far back each player's last vote per site is kept. */
    public val longestCooldown: Long,
) {
    /** A site from the config. */
    public class SiteEntry(public val key: String, public val name: String, public val service: String, public val link: String, public val cooldownMs: Long, public val rewards: List<String>)

    /**
     * A reward from the config, ready to give. Its items are read by the server the first time
     * they're wanted ([items]): Paper's `/give` reader is slow to start, so it never runs as the
     * plugin enables, and [Rewards.check] reads them all a moment later.
     */
    public class RewardEntry(
        public val name: String,
        public val chance: Double,
        public val money: Double,
        private val written: List<String>,
        public val commands: List<String>,
        public val message: String,
        public val broadcast: String,
        public val sound: Sound,
        public val custom: List<String>,
        private val server: VoteServer?,
        private val warn: ((String) -> Unit)?,
    ) {
        @kotlin.concurrent.Volatile
        private var read: List<Any>? = null

        /** The items, as the server reads them; one it can't is skipped, with a warning. */
        public val items: List<Any>
            get() {
                read?.let { return it }
                val stacks = ArrayList<Any>()
                for ((i, text) in written.withIndex()) {
                    when (val item = server?.item(text) ?: Outcome.Err("items aren't given here")) {
                        is Outcome.Ok -> stacks += item.value
                        is Outcome.Err -> if (server != null && !server.proxy) warn?.invoke("config.yml: rewards.$name.items[$i] (\"$text\") ${item.error}, so it's skipped.")
                    }
                }
                read = stacks
                return stacks
            }
    }

    /** Reads every reward's items now, so a mistake in one is said in the console soon after a load. */
    public fun check() {
        for (reward in rewards.values) reward.items
    }

    /** The site a list's service name belongs to, ignoring case, or `null` for an unlisted one. */
    public fun site(service: String): SiteEntry? = byService[service.lowercase()]

    /** A site by its key in the config, ignoring case, or `null`. */
    public fun siteByKey(key: String): SiteEntry? = byKey[key.lowercase()]

    /** What players see for a site: its name if it's listed, otherwise what the list sent. */
    public fun siteName(service: String): String = site(service)?.name ?: service

    /**
     * The name a console command or custom reward gets as `<site>`: a listed site's key in the
     * config (`planetminecraft`), or for an unlisted one the service name the list sent; either with
     * everything but letters, digits, `.`, `_` and `-` made `_`, so nothing can add arguments or
     * another command.
     */
    public fun commandSite(service: String): String = safe(site(service)?.key ?: service)

    /** The cooldown for votes from a site: its own, or `counting.unlisted-cooldown-hours` for unlisted ones. */
    public fun cooldown(service: String): Long = site(service)?.cooldownMs ?: unlistedCooldown

    public fun reward(name: String): RewardEntry? = rewards[name]

    /** The names of the rewards a counted vote earns, in the order they're given. */
    public fun earned(facts: Facts): MutableList<String> {
        val earned = ArrayList(everyVote)
        site(facts.site)?.let { site -> for (name in site.rewards) if (name in rewards) earned += name }
        if (facts.firstToday) earned += firstOfDay
        for ((n, names) in every) if (facts.number % n == 0) earned += names
        if (facts.firstToday) streak[facts.streak]?.let { earned += it }
        total[facts.number]?.let { earned += it }
        return earned
    }

    public companion object {
        /** No sites, no rewards: before the config is read, and on a proxy. */
        public val NONE: Rewards = Rewards(emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList(), 0, 0)

        /** A service name from the network made safe to put in a command. */
        public fun safe(service: String): String {
            val out = StringBuilder(service.length)
            for (c in service) out.append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-') c else '_')
            return out.toString().ifEmpty { "_" }
        }

        /**
         * Reads the config's sites, rewards and triggers. [server] reads each item; [warn] is told
         * about anything that's skipped, for the console.
         */
        public fun of(config: Config, server: VoteServer?, warn: (String) -> Unit, later: ((String) -> Unit)? = null): Rewards {
            val sites = ArrayList<SiteEntry>()
            for ((key, site) in config[XVoteConfig.sites]) {
                val service = site.service.trim().ifBlank { key }
                sites += SiteEntry(key, site.name.ifBlank { key }, service, site.link.trim(), site.cooldownHours * 3_600_000L, site.rewards)
            }
            val byService = LinkedHashMap<String, SiteEntry>()
            val byKey = LinkedHashMap<String, SiteEntry>()
            var longest = 0L
            for (site in sites) {
                if (byService.containsKey(site.service.lowercase())) {
                    warn("sites.${site.key} has the same service as another site, so it's never matched.")
                } else {
                    byService[site.service.lowercase()] = site
                }
                byKey[site.key.lowercase()] = site
                longest = maxOf(longest, site.cooldownMs)
            }
            val rewards = LinkedHashMap<String, RewardEntry>()
            for ((name, reward) in config[XVoteConfig.rewards]) {
                val sound = if (reward.sound.isBlank()) Sound.NONE else Sound.parse(reward.sound)
                if (!sound.isNone && server?.knowsSound(sound) == false) {
                    warn("rewards.$name.sound (\"${reward.sound}\") isn't a sound this server knows; it plays only with a resource pack that adds it.")
                }
                rewards[name] = RewardEntry(name, reward.chance, reward.money, reward.items, reward.commands, reward.message, reward.broadcast, sound, reward.custom, server, later)
            }
            fun known(names: List<String>, path: String): List<String> {
                val kept = ArrayList<String>()
                for (n in names) if (n in rewards) kept += n else warn("$path names \"$n\", which isn't in rewards, so it's skipped.")
                return kept
            }
            fun numbered(milestones: Map<String, List<String>>, path: String): Map<Int, List<String>> {
                val out = LinkedHashMap<Int, List<String>>()
                for ((key, names) in milestones) {
                    val n = key.trim().toIntOrNull() ?: 0
                    if (n < 1) {
                        warn("$path.$key should be a whole number above 0, so it's skipped.")
                        continue
                    }
                    out[n] = known(names, "$path.$key")
                }
                return out
            }
            val t = XVoteConfig.triggers
            val everyVote = known(config[t.everyVote], "triggers.every-vote")
            val firstOfDay = known(config[t.firstVoteOfDay], "triggers.first-vote-of-day")
            val every = numbered(config[t.every], "triggers.every")
            val streak = numbered(config[t.streak], "triggers.streak")
            val total = numbered(config[t.total], "triggers.total")
            val party = known(config[XVoteConfig.party.rewards], "party.rewards")
            for (site in sites) known(site.rewards, "sites.${site.key}.rewards")
            val unlisted = config[XVoteConfig.counting.unlistedCooldownHours] * 3_600_000L
            return Rewards(sites, byService, byKey, rewards, everyVote, firstOfDay, every, streak, total, party, unlisted, maxOf(longest, unlisted))
        }
    }
}
