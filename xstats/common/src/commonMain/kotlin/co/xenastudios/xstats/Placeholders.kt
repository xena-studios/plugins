package co.xenastudios.xstats

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.xstats.api.Period
import co.xenastudios.xstats.stat.Catalog
import co.xenastudios.xstats.stat.Format

/**
 * xStats' placeholders, 1.x's, answered from memory: no database, no lookups beyond a map. The same
 * for PlaceholderAPI on Bukkit (`%xstats_<params>%`) and Text Placeholder API on Fabric
 * (`%xstats:<params>%`).
 *
 * - `<stat>`, `<stat>_<period>`: the player's value, written as players read it; add `_raw` for the bare number.
 * - `rank_<stat>[_<period>]`: their place, or blank.
 * - `top_<stat>[_<period>]_<n>_name`, `_value` and `_raw`: the nth place, or blank.
 * - `kd`: kills per death, from the `kills` and `deaths` stats.
 */
public object Placeholders {
    /** The placeholder's text for [player] (`null` for none), or `null` if [params] isn't one of xStats'. */
    public fun resolve(plugin: XStatsPlugin, player: PlayerRef?, params: String): String? {
        val engine = plugin.engine
        if (!engine.ready) return ""
        val stats = plugin.catalog.snapshot
        if (params.startsWith("top_")) return top(plugin, stats, params.substring("top_".length))
        val record = player?.let(engine::player)
        if (params.startsWith("rank_")) {
            val which = stats.byParam[params.substring("rank_".length)] ?: return null
            val rank = if (record == null) 0 else engine.rank(record, which.stat, which.period)
            return if (rank == 0) "" else rank.toString()
        }
        if (params == "kd") {
            val kills = stats["kills"] ?: return null
            val deaths = stats["deaths"] ?: return null
            if (record == null) return Format.ratio(0, 0)
            return Format.ratio(engine.value(record, kills, Period.ALL), engine.value(record, deaths, Period.ALL))
        }
        val raw = params.endsWith("_raw")
        val which = stats.byParam[if (raw) params.removeSuffix("_raw") else params] ?: return null
        val value = if (record == null) 0 else engine.value(record, which.stat, which.period)
        return if (raw) value.toString() else Format.value(which.stat, value)
    }

    // <stat>[_<period>]_<n>_name, _value or _raw.
    private fun top(plugin: XStatsPlugin, stats: Catalog.Snapshot, rest: String): String? {
        val field = rest.lastIndexOf('_')
        val place = if (field < 0) -1 else rest.lastIndexOf('_', field - 1)
        if (place < 0) return null
        val what = rest.substring(field + 1)
        val which = stats.byParam[rest.substring(0, place)]
        val n = rest.substring(place + 1, field).toIntOrNull() ?: return null
        if (which == null || n < 1 || (what != "name" && what != "value" && what != "raw")) return null
        val board = plugin.engine.leaderboards[which.stat.id, which.period]
        if (board == null || n > board.size || n > plugin.config[co.xenastudios.xstats.config.XStatsConfig.leaderboards.size]) return ""
        return when (what) {
            "name" -> board.order[n - 1].name ?: ""
            "value" -> Format.value(which.stat, board.values[n - 1])
            else -> board.values[n - 1].toString()
        }
    }
}
