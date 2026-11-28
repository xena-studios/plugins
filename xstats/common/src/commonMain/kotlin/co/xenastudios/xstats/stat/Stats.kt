package co.xenastudios.xstats.stat

import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.locked
import co.xenastudios.xstats.api.Period
import co.xenastudios.xstats.api.StatInfo
import co.xenastudios.xstats.api.StatUnit
import co.xenastudios.xstats.config.StatSettings
import co.xenastudios.xstats.config.XStatsConfig
import kotlin.concurrent.Volatile

/**
 * One stat as it runs: from the config, or registered by a plugin. Its [index] is its place in every
 * player's arrays, fixed for as long as the server runs, so a reload that adds or drops a stat never
 * moves another's numbers.
 */
public class StatDef(
    public val index: Int,
    public val id: String,
    public val name: String,
    public val unit: StatUnit,
    public val decimals: Int,
    public val periods: Boolean,
    public val inGame: Boolean,
    public val website: Boolean,
    public val owner: String,
    public val source: Source,
) {
    /** Where a stat's numbers come from. */
    public sealed class Source

    /** Minecraft's own stats, added up: read from players live ([prepared], the adapter's), or from stats files when importing. */
    public class Vanillas(public val terms: List<Vanilla.Term>, public val prepared: VanillaReader.Prepared?) : Source() {
        /** The counters it adds up, in a fixed order: what the `sources` table records. */
        public val fingerprint: String = terms.map { it.category.name + ":" + it.name }.sorted().joinToString("+")
    }

    /** What xStats counts itself: [playtime] (seconds online, not counting AFK) or sessions. */
    public class Tracked(public val playtime: Boolean) : Source()

    /** A PlaceholderAPI value, read with the rest and on quit. */
    public class Placeholder(public val placeholder: String) : Source()

    /** Set by another plugin through the API; [latest] for a value set outright, such as a balance. */
    public class Custom(public val plugin: String, public val latest: Boolean) : Source()

    public val info: StatInfo get() = StatInfo(id, name, unit, decimals, periods, inGame, owner, website)

    /**
     * Whether a value only ever grows, so a fall means its counter was reset, and a network adds
     * every server's up. Placeholder values and other plugins' set values (a balance) go up and down,
     * and a network takes the latest.
     */
    public val counter: Boolean get() = !(source is Placeholder || source is Custom && source.latest)

    override fun toString(): String = "StatDef($id)"
}

/** Turns the config's `stats` section into the stats xStats runs, warning about any it can't use. */
public object Definitions {
    /** A stat id: what placeholders, commands and the web API name it by. */
    public val ID: Regex = Regex("[a-z0-9_]{1,32}")

    /** Words a stat id can't be, because placeholders use them: `%xstats_rank_kills%`, `%xstats_top_kills_1_name%`. */
    private val RESERVED = listOf("rank", "top", "kd", "first_join", "last_seen", "private")

    /** Endings placeholders add to a stat's id: `%xstats_kills_daily%`, `%xstats_kills_raw%`. */
    private val SUFFIXES = listOf("_raw", "_all", "_daily", "_weekly", "_monthly")

    // A placeholder, and on Fabric (Text Placeholder API) its argument after a space: %player:statistic jump%.
    private val PLACEHOLDER = Regex("%[^%\\s]+( [^%]+)?%")

    /**
     * Why an id can't be used, or `null` if it can: one placeholders would read as another stat's
     * (`kills_daily` is kills today), or as `rank_` and `top_` placeholders.
     */
    public fun clash(id: String): String? {
        if (id in RESERVED) return "\"$id\" is kept for placeholders; pick another id"
        if (id.startsWith("top_") || id.startsWith("rank_")) return "an id can't start with top_ or rank_, which placeholders use; pick another id"
        for (suffix in SUFFIXES) if (id.endsWith(suffix)) return "an id can't end with $suffix, which placeholders add; pick another id"
        return null
    }

    /** A stat registered through the API, kept across reloads. */
    public class Registered(
        public val id: String,
        public val name: String,
        public val unit: StatUnit,
        public val plugin: String,
        public val latest: Boolean,
        public val decimals: Int,
    )

    /** The config's stats, then those other plugins registered. A stat with a problem is left out, and [warnings] says why. */
    public fun build(
        settings: Map<String, StatSettings>,
        registered: List<Registered>,
        catalog: Catalog,
        reader: VanillaReader?,
        /** Where each problem goes, as `config.yml: ...`. */
        warnings: MutableList<String>,
        /** What isn't wrong but worth knowing: a stat this server's Minecraft is too old for. */
        notes: MutableList<String> = warnings,
    ): List<StatDef> {
        val stats = ArrayList<StatDef>()
        for ((id, stat) in settings) {
            try {
                stats += define(id, stat, catalog, reader, notes)
            } catch (e: IllegalArgumentException) {
                warnings += "config.yml: stats.$id: ${e.message}. It's left out until that's fixed."
            }
        }
        for (stat in registered) {
            if (stats.any { it.id == stat.id }) {
                warnings += "config.yml: ${stat.plugin} registered the stat ${stat.id}, but the config already has one by that name; the config's is used."
                continue
            }
            stats += StatDef(
                catalog.index(stat.id), stat.id, stat.name, stat.unit, stat.decimals, true, true, true,
                stat.plugin, StatDef.Custom(stat.plugin, stat.latest),
            )
        }
        return stats
    }

    internal fun define(id: String, settings: StatSettings, catalog: Catalog, reader: VanillaReader?, notes: MutableList<String>): StatDef {
        require(ID.matches(id)) { "an id is 1 to 32 lowercase letters, digits and _" }
        clash(id)?.let { throw IllegalArgumentException(it) }
        val source = settings.source.trim()
        require(source.isNotEmpty()) { "it needs a source, such as player_kills" }
        val name = settings.name.ifBlank { id }
        val lower = source.lowercase()
        val parsed: StatDef.Source
        var unit: StatUnit
        val owner: String
        when {
            lower.startsWith("xstats:") -> {
                val playtime = when (lower.removePrefix("xstats:")) {
                    "playtime" -> true
                    "sessions" -> false
                    else -> throw IllegalArgumentException("xStats counts xstats:playtime and xstats:sessions, not $source")
                }
                parsed = StatDef.Tracked(playtime)
                unit = if (playtime) StatUnit.TIME else StatUnit.COUNT
                owner = "xstats"
            }
            lower.startsWith("papi:") -> {
                val placeholder = source.substring("papi:".length).trim()
                require(PLACEHOLDER.matches(placeholder)) { "write a placeholder as papi:%name%, such as papi:%vault_eco_balance%" }
                parsed = StatDef.Placeholder(placeholder)
                unit = StatUnit.COUNT
                owner = "placeholder"
            }
            else -> {
                val terms = ArrayList<Vanilla.Term>()
                var first: StatUnit? = null
                for (part in source.split('+')) {
                    val term = Vanilla.term(part)
                    require(!(term.category == Vanilla.Category.CUSTOM && term.name in Vanilla.NOT_COUNTERS)) {
                        "${term.name} goes back to 0 by itself (at each death or sleep), so it can't be counted up; use a placeholder for it instead"
                    }
                    val termUnit = Vanilla.unit(term)
                    require(first == null || termUnit == first) { "its parts count different things (${terms.first()} and $term), so they can't be added up" }
                    first = termUnit
                    terms += term
                }
                val prepared = reader?.prepare(terms)
                // A part this server's Minecraft doesn't have yet reads 0 (the newest mounts, on an older server).
                if (prepared != null && prepared.missing.size == terms.size) {
                    notes += "config.yml: stats.$id: this server's Minecraft has none of ${prepared.missing.joinToString(", ")}, so it reads 0 here."
                }
                parsed = StatDef.Vanillas(terms, prepared)
                unit = first ?: StatUnit.COUNT
                owner = "minecraft"
            }
        }
        if (settings.unit != XStatsConfig.UnitSetting.AUTO) unit = StatUnit.valueOf(settings.unit.name)
        return StatDef(catalog.index(id), id, name, unit, settings.decimals, settings.periods, settings.inGame, settings.website, owner, parsed)
    }
}

/**
 * Every stat xStats keeps right now: the config's, then other plugins'. Replaced whole on reload or
 * registration, so readers on any thread see one consistent set.
 *
 * Indexes are handed out by id and never reused while the server runs: a stat dropped on reload
 * keeps its slot, so it can come back with its numbers, and other stats never move.
 */
public class Catalog {
    private val indexes = concurrentMap<String, Int>()

    @Volatile
    public var snapshot: Snapshot = Snapshot(emptyList())
        private set

    /** A stat and a period, as placeholders and commands name them together. */
    public class StatPeriod(public val stat: StatDef, public val period: Period)

    /** One set of stats, with lookups for placeholders: `kills`, `kills_daily`. */
    public class Snapshot(public val stats: List<StatDef>) {
        public val byId: Map<String, StatDef> = LinkedHashMap<String, StatDef>().also { map -> for (stat in stats) map[stat.id] = stat }

        /** Stat and period by the text placeholders write them as. */
        public val byParam: Map<String, StatPeriod> = HashMap<String, StatPeriod>().also { map ->
            for (stat in stats) {
                map[stat.id] = StatPeriod(stat, Period.ALL)
                map[stat.id + "_all"] = StatPeriod(stat, Period.ALL)
                if (stat.periods) {
                    for (period in PERIODS) map[stat.id + "_" + period.id] = StatPeriod(stat, period)
                }
            }
        }

        /** The highest index in use, plus one: how long players' arrays must be. */
        public val width: Int = stats.maxOfOrNull { it.index + 1 } ?: 0

        public operator fun get(id: String): StatDef? = byId[id]
    }

    /** The index for an id: the same one every time while the server runs. */
    public fun index(id: String): Int = indexes[id] ?: locked(this) { indexes.getOrPut(id) { indexes.size } }

    /** Swaps in a new set. Ids must be unique. */
    public fun set(stats: List<StatDef>) {
        snapshot = Snapshot(stats.toList())
    }

    public companion object {
        /** The periods besides all time, in order. */
        public val PERIODS: List<Period> = listOf(Period.DAILY, Period.WEEKLY, Period.MONTHLY)
    }
}
