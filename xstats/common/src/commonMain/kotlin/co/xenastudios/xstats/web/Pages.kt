package co.xenastudios.xstats.web

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.locked
import co.xenastudios.xstats.Actions
import co.xenastudios.xstats.XStatsPlugin
import co.xenastudios.xstats.api.LeaderboardEntry
import co.xenastudios.xstats.api.Period
import co.xenastudios.xstats.config.XStatsConfig
import co.xenastudios.xstats.data.Board
import co.xenastudios.xstats.data.PlayerRecord
import co.xenastudios.xstats.stat.Catalog
import co.xenastudios.xstats.stat.Format
import co.xenastudios.xstats.stat.StatDef
import co.xenastudios.xstats.store.Store
import kotlin.concurrent.Volatile
import co.xenastudios.xstats.data.search
import co.xenastudios.xstats.data.visibleRank

/** JSON written as it goes, for the web API: objects, arrays, and every value escaped. */
public class Json {
    private val out = StringBuilder()
    private val first = ArrayList<Boolean>()
    private var afterKey = false

    private fun comma() {
        if (afterKey) {
            afterKey = false
            return
        }
        if (first.isNotEmpty()) {
            if (first.last()) first[first.size - 1] = false else out.append(',')
        }
    }

    public fun obj(): Json = also {
        comma()
        out.append('{')
        first += true
    }

    public fun end(): Json = also {
        out.append('}')
        first.removeAt(first.size - 1)
    }

    public fun array(): Json = also {
        comma()
        out.append('[')
        first += true
    }

    public fun endArray(): Json = also {
        out.append(']')
        first.removeAt(first.size - 1)
    }

    public fun key(name: String): Json = also {
        comma()
        string(name)
        out.append(':')
        afterKey = true
    }

    public fun value(value: Any?): Json = also {
        comma()
        when (value) {
            null -> out.append("null")
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Double -> out.append(if (value.isFinite()) value.toString() else "null")
            else -> string(value.toString())
        }
    }

    public fun field(name: String, value: Any?): Json = key(name).value(value)

    private fun string(text: String) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                // < > & as escapes too, so nothing in a value can close a tag where JSON lands in HTML.
                c < ' ' || c == '<' || c == '>' || c == '&' || c == ' ' || c == ' ' ->
                    out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    override fun toString(): String = out.toString()
}

/** A page of the web API: its JSON, its status, and how long a browser may keep it. */
public class Page(public val status: Int, public val json: String, public val maxAge: Int) {
    public companion object {
        /** Kept by browsers and proxies, but checked with its ETag every time it's used. */
        public const val REVALIDATE: Int = -1

        public fun error(status: Int, message: String): Page = Page(status, Json().obj().field("error", message).end().toString(), 0)
    }
}

/**
 * The web API's answers, 1.x's, field for field. Everything every visitor asks for (the server's
 * page, each leaderboard) is made once after each save and kept, so serving it is a map lookup; a
 * profile is made from memory when asked, with its 30-day history read from the database (on the
 * JVM, through a small cache).
 */
public class Pages(private val plugin: XStatsPlugin) {
    @Volatile
    private var rendered: Map<String, Page> = emptyMap()
    private val renderedFrom = HashMap<String, Board>()
    private val boardPages = HashMap<String, Page>()
    private var renderedHidden = -1

    /** A page made at the last save, by its path, or `null`. Any thread. */
    public fun page(path: String): Page? = rendered[path]

    /** A known player's name, or `null` if xStats doesn't know them or they're private (their head isn't fetched). */
    public fun name(player: PlayerRef): String? = plugin.engine.player(player)?.takeIf { !it.hidden }?.name

    private fun shown(): List<StatDef> = plugin.catalog.snapshot.stats.filter { it.website }

    /** Makes every page visitors share again. On the database thread, after a save and after someone is hidden or shown. */
    public fun render() {
        val engine = plugin.engine
        if (!engine.ready) return
        locked(this) {
            val hiddenVersion = engine.hiddenVersion
            if (hiddenVersion != renderedHidden) {
                // Someone was hidden or shown since: no kept page can be trusted to leave out the right players.
                renderedFrom.clear()
                boardPages.clear()
                renderedHidden = hiddenVersion
            }
            val stats = shown()
            val pages = HashMap<String, Page>()
            pages[BASE + "server"] = Page(200, server(stats), 60)
            val size = plugin.config[XStatsConfig.leaderboards.size]
            val saved = engine.lastSave?.at?.takeIf { it > 0 } ?: engine.lastRead
            for (period in Period.entries) {
                val summary = Json().obj().field("period", period.id).field("saved", saved).key("boards").array()
                for (stat in stats) {
                    if (period != Period.ALL && !stat.periods) continue
                    val board = engine.leaderboards[stat.id, period] ?: continue
                    val path = BASE + "leaderboard/" + stat.id + "/" + period.id
                    // A board that didn't change since the last save keeps its page.
                    var page = if (renderedFrom[path] === board) boardPages[path] else null
                    if (page == null) {
                        val json = Json().obj().field("stat", stat.id).field("period", period.id).field("saved", saved).key("entries")
                        entries(json, stat, board.top(size, true))
                        // Checked on every visit, so a player who just went private isn't kept in a cache.
                        page = Page(200, json.end().toString(), Page.REVALIDATE)
                        renderedFrom[path] = board
                        boardPages[path] = page
                    }
                    pages[path] = page
                    summary.obj().field("stat", stat.id).key("entries")
                    entries(summary, stat, board.top(SUMMARY, true))
                    summary.end()
                }
                pages[BASE + "leaderboards/" + period.id] = Page(200, summary.endArray().end().toString(), Page.REVALIDATE)
            }
            rendered = pages
        }
    }

    private fun entries(json: Json, stat: StatDef, entries: List<LeaderboardEntry>) {
        json.array()
        for (entry in entries) {
            json.obj().field("rank", entry.rank).field("uuid", entry.player.toString()).field("name", entry.name)
                .field("value", entry.value).field("display", Format.value(stat, entry.value)).end()
        }
        json.endArray()
    }

    private fun server(stats: List<StatDef>): String {
        val c = plugin.config
        val b = XStatsConfig.web.branding
        val engine = plugin.engine
        val json = Json().obj()
            .field("api", VERSION)
            .field("version", plugin.version)
            .field("name", c[b.siteName].ifBlank { "Server stats" })
            .field("tagline", c[b.tagline])
            .field("logo", logo(c[b.logo]))
            .key("colours").obj()
            .field("accent", c[b.accent]).field("background", c[b.background]).field("surface", c[b.surface]).field("text", c[b.text])
            .end()
            .field("avatar", avatar())
            .field("players", engine.playerCount)
            // Players online now, vanished ones left out (2.0).
            .field("online", plugin.server.online().count { plugin.cache?.isVanished(it) != true })
            .field("saved", engine.lastSave?.at?.takeIf { it > 0 } ?: engine.lastRead)
            .field("network", c[XStatsConfig.network.enabled])
        json.key("periods").array()
        for (period in Period.entries) json.obj().field("id", period.id).field("name", Actions.periodName(plugin, null, period)).end()
        json.endArray().key("stats").array()
        for (stat in stats) {
            val total = engine.leaderboards.total(stat.index)
            json.obj().field("id", stat.id).field("name", stat.name).field("unit", stat.unit.name.lowercase())
                .field("decimals", stat.decimals).field("periods", stat.periods)
                .field("total", total).field("totalDisplay", Format.value(stat, total)).end()
        }
        return json.endArray().end().toString()
    }

    /** The heads' address: the owner's template, or xStats' own copy when heads are proxied. */
    private fun avatar(): String {
        val a = XStatsConfig.web.avatars
        if (plugin.config[a.proxy]) return BASE + "head/{uuid}"
        val url = plugin.config[a.url]
        return if (URL.matches(url)) url else "https://mc-heads.net/avatar/{uuid}/{size}"
    }

    /** The logo: a link as written, a file in plugins/xStats/web/ as `/brand/<file>`, or `null`. */
    private fun logo(logo: String): String? {
        val value = logo.trim()
        if (value.isEmpty()) return null
        if (URL.matches(value)) return value
        if (LOGO_FILE.matches(value) && brandFile(value)) return "/brand/$value"
        return null
    }

    /** Whether the owner's web/ folder has the file [name]: the JVM's [brandExists]. */
    @Volatile
    public var brandExists: (String) -> Boolean = { false }

    private fun brandFile(name: String): Boolean = brandExists(name)

    /** Players whose names start with what was typed, for the site's search. Any thread. */
    public fun search(query: String?): Page {
        if (query == null || !PlayerRecord.NAME.matches(query)) return Page(200, "{\"players\":[]}", Page.REVALIDATE)
        val json = Json().obj().key("players").array()
        for (player in plugin.engine.search(query, 10) { it.hidden }) {
            json.obj().field("uuid", player.id.toString()).field("name", player.name).end()
        }
        return Page(200, json.endArray().end().toString(), Page.REVALIDATE)
    }

    /** The record [who] names (a uuid, or a name), if the site may show them. */
    public fun find(who: String): PlayerRecord? {
        val engine = plugin.engine
        val record = when {
            UUID_TEXT.matches(who) -> PlayerRef.parseOrNull(who)?.let(engine::player)
            PlayerRecord.NAME.matches(who) -> engine.byName(who)
            else -> null
        }
        return record?.takeIf { !it.hidden && it.name != null }
    }

    /** A player's page from memory and their [history]. Off the server's threads: other plugins' sections may take a moment. */
    public fun profile(record: PlayerRecord?, history: Store.History?): Page {
        if (record == null) return Page.error(404, "No such player, or they keep their stats private.")
        val engine = plugin.engine
        val stats = shown()
        val vanished = plugin.cache?.isVanished(record.id) == true
        val json = Json().obj()
            .field("uuid", record.id.toString())
            .field("name", record.name)
            .field("firstJoin", record.firstJoin)
            .field("lastSeen", record.lastSeen)
            .field("online", record.online && plugin.server.isOnline(record.id) && !vanished)
        json.key("stats").array()
        for (stat in stats) {
            json.obj().field("id", stat.id)
            value(json, record, stat, Period.ALL)
            if (stat.periods) {
                json.key("periods").obj()
                for (period in Catalog.PERIODS) {
                    json.key(period.id).obj()
                    value(json, record, stat, period)
                    json.end()
                }
                json.end()
            }
            json.end()
        }
        json.endArray()
        json.key("sections").array()
        for (section in plugin.sections.fill(record.id, false)) {
            json.obj().field("id", section.key).field("title", section.title).key("lines").array()
            for (line in section.lines) json.obj().field("label", line.label).field("value", line.value).end()
            json.endArray().end()
        }
        json.endArray()
        val today = engine.today()
        val from = today - HISTORY_DAYS + 1
        json.key("history").obj().field("from", from).key("stats").obj()
        val shownIds = stats.map { it.id }.toSet()
        val byStat = HashMap<String, LongArray>()
        for (day in history?.days.orEmpty()) {
            val offset = day.day - from
            if (offset in 0 until HISTORY_DAYS && day.stat in shownIds) byStat.getOrPut(day.stat) { LongArray(HISTORY_DAYS) }[offset] += day.delta
        }
        for (stat in byStat.keys.sorted()) {
            json.key(stat).array()
            for (value in byStat.getValue(stat)) json.value(value)
            json.endArray()
        }
        json.end().end()
        if (plugin.config[XStatsConfig.network.enabled]) {
            val servers = HashMap<String, MutableMap<String, Long>>()
            for (value in history?.servers.orEmpty()) {
                if (value.stat in shownIds) servers.getOrPut(value.server) { HashMap() }[value.stat] = value.value
            }
            json.key("servers").array()
            for (server in servers.keys.sorted()) {
                val values = servers.getValue(server)
                json.obj().field("server", server).key("stats").obj()
                for (stat in values.keys.sorted()) json.field(stat, values.getValue(stat))
                json.end().end()
            }
            json.endArray()
        }
        return Page(200, json.end().toString(), 30)
    }

    private fun value(json: Json, record: PlayerRecord, stat: StatDef, period: Period) {
        val value = plugin.engine.value(record, stat, period)
        // The place among players the site shows, so it never gives away someone private above.
        json.field("value", value).field("display", Format.value(stat, value)).field("rank", plugin.engine.visibleRank(record, stat, period))
    }

    public companion object {
        /** The API's version: the `v1` in its paths. A change that breaks a field means v2, with v1 kept. */
        public const val VERSION: Int = 1
        public const val BASE: String = "/api/v1/"
        public const val HISTORY_DAYS: Int = 30
        private const val SUMMARY = 5
        private val LOGO_FILE = Regex("[A-Za-z0-9_.-]+\\.(png|jpe?g|svg|webp)")
        private val URL = Regex("https?://[^\\s\"'<>\\\\]+")
        private val UUID_TEXT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /** What the API's root answers: its endpoints. */
        public val INDEX: Page = Page(
            200,
            Json().obj().field("api", VERSION).key("endpoints").array()
                .value("/api/v1/server").value("/api/v1/leaderboards/{period}").value("/api/v1/leaderboard/{stat}/{period}")
                .value("/api/v1/search?q={name}").value("/api/v1/player/{name or uuid}").value("/api/v1/head/{uuid}")
                .endArray().field("docs", "https://plugins.xenastudios.co/xstats/web-api/").end().toString(),
            300,
        )
    }
}
