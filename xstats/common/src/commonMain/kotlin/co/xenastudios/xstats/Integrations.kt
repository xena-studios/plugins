package co.xenastudios.xstats

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.xstats.api.CustomStat
import co.xenastudios.xstats.api.StatUnit
import co.xenastudios.xstats.config.XStatsConfig

/**
 * The stats the other Xena plugins add (ROADMAP.md, the family: "every plugin adds stats through its
 * API"): which plugin, which of its events, and what each counts. xStats hears the events on each
 * plugin's own bus, by name (on the JVM, `FamilyHooks`): each plugin relocates its own core, so their
 * types can't be named here. Each is registered as a custom stat, owned by that plugin, once it's
 * installed and its `integrations.<plugin>` is on.
 */
public object Integrations {
    /** One stat a plugin adds. */
    public class StatSpec(
        public val id: String,
        public val name: String,
        public val unit: StatUnit,
        public val latest: Boolean = false,
        public val decimals: Int = 0,
    )

    /**
     * One plugin's stats: its [plugin] name (as servers and the API name it), its API's package, the
     * setting that turns it off, and its [stats].
     */
    public class Spec(public val plugin: String, public val key: ConfigKey<Boolean>, public val stats: List<StatSpec>) {
        /** Its id, the package its API is in: `xvote`. */
        public val id: String = plugin.lowercase()
    }

    private val I = XStatsConfig.integrations

    /** Every plugin xStats keeps stats for, in the order `/xstats status` lists them. */
    public val SPECS: List<Spec> = listOf(
        Spec("xVote", I.xvote, listOf(StatSpec("votes", "Votes", StatUnit.COUNT))),
        Spec(
            "xMoney", I.xmoney,
            listOf(StatSpec("balance", "Balance", StatUnit.COUNT, latest = true, decimals = 2), StatSpec("earned", "Money earned", StatUnit.COUNT, decimals = 2)),
        ),
        Spec("xBans", I.xbans, listOf(StatSpec("punishments", "Punishments", StatUnit.COUNT))),
        Spec("xTeleport", I.xteleport, listOf(StatSpec("teleports", "Teleports", StatUnit.COUNT))),
        Spec("xRewind", I.xrewind, listOf(StatSpec("rollbacks", "Blocks rolled back", StatUnit.COUNT))),
        Spec("xMarket", I.xmarket, listOf(StatSpec("bought", "Items bought", StatUnit.COUNT), StatSpec("sold", "Items sold", StatUnit.COUNT))),
        Spec("zChat", I.zchat, listOf(StatSpec("messages", "Chat messages", StatUnit.COUNT))),
    )

    /** The plugins hooked now, by name, with their stats by short id. */
    private val hooked = concurrentMap<XStatsPlugin, MutableMap<String, Map<String, CustomStat>>>()

    /**
     * Registers [spec]'s stats for [plugin], if its setting is on: what the hook counts into, by short
     * id (`votes`), or `null` when it's off. Any thread.
     */
    public fun hook(plugin: XStatsPlugin, spec: Spec): Map<String, CustomStat>? {
        if (!plugin.config[spec.key] || plugin.server.proxy) return null
        val stats = LinkedHashMap<String, CustomStat>()
        for (stat in spec.stats) stats[stat.id] = plugin.registerStat(spec.plugin, stat.id, stat.name, stat.unit, stat.latest, stat.decimals)
        hooked.getOrPut(plugin) { concurrentMap() }[spec.plugin] = stats
        return stats
    }

    /** [spec]'s plugin went away: its stats out of use (their numbers stay saved). */
    public fun unhook(plugin: XStatsPlugin, spec: Spec) {
        if (hooked[plugin]?.remove(spec.plugin) != null) plugin.unregisterStats(spec.plugin)
    }

    /** Forgets every hook of [plugin], as it stops. */
    public fun clear(plugin: XStatsPlugin) {
        hooked.remove(plugin)
    }

    /** Which plugins' stats are kept now, for `/xstats status` and the debug report. */
    public fun describe(plugin: XStatsPlugin): String =
        hooked[plugin]?.keys?.sorted()?.joinToString(", ")?.ifEmpty { null } ?: "none installed"

    /** [player] counted [amount] more of [stat] ([spec]'s), on whichever thread the plugin fired it. */
    public fun count(stats: Map<String, CustomStat>, stat: String, player: PlayerRef, amount: Long) {
        if (amount != 0L) stats[stat]?.increment(player, amount)
    }

    /** [player]'s [stat] ([spec]'s) is now [value]. */
    public fun set(stats: Map<String, CustomStat>, stat: String, player: PlayerRef, value: Long) {
        stats[stat]?.set(player, value)
    }
}
