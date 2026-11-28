package co.xenastudios.xstats

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.i18n.ChatPages
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.xstats.api.Period
import co.xenastudios.xstats.api.event.PrivacyChangeEvent
import co.xenastudios.xstats.config.XStatsConfig
import co.xenastudios.xstats.data.Calendar
import co.xenastudios.xstats.data.PlayerRecord
import co.xenastudios.xstats.stat.Format
import co.xenastudios.xstats.stat.StatDef
import co.xenastudios.xstats.store.DbFailed
import co.xenastudios.xstats.store.Store
import kotlin.math.floor

/** What the commands do, made the first time one runs, not as the server starts. */
internal class Actions private constructor(private val plugin: XStatsPlugin) {
    private val texts get() = plugin.texts
    private val engine get() = plugin.engine

    fun info(source: CommandSource) {
        val db = plugin.database
        val storage = db?.description ?: plugin.failure ?: "a database still opening"
        texts.reply(
            source, if (plugin.server.proxy) XStatsMessages.proxyInfo else XStatsMessages.info,
            "version" to plugin.version, "platform" to plugin.platform.name, "players" to engine.playerCount, "storage" to storage,
        )
    }

    private fun ready(source: CommandSource): Boolean {
        if (engine.ready) return true
        texts.reply(source, XStatsMessages.notReady)
        return false
    }

    // ---- /stats ----

    fun stats(source: CommandSource, typed: String?) {
        if (!ready(source)) return
        val self = source.player
        if (typed != null && typed.equals("private", ignoreCase = true) && !source.name.equals(typed, ignoreCase = true) && !plugin.server.proxy) {
            if (!source.hasPermission(plugin.permissions.private.node)) {
                texts.reply(source, CoreMessages.commandNoPermission)
                return
            }
            if (self == null) {
                texts.reply(source, CoreMessages.commandPlayersOnly)
                return
            }
            plugin.onDatabase { q ->
                val record = engine.ensure(self, source.name)
                val hide = !record.hidden
                if (plugin.hasListeners(PrivacyChangeEvent.TYPE) && plugin.bus.fire(PrivacyChangeEvent.TYPE, PrivacyChangeEvent(self, hide)).isCancelled) {
                    texts.reply(source, XStatsMessages.privateRefused)
                    return@onDatabase
                }
                engine.hide(q, record, hide)
                texts.reply(source, if (hide) XStatsMessages.privateOn else XStatsMessages.privateOff)
            }
            return
        }
        if (typed == null) {
            if (self == null) {
                texts.reply(source, CoreMessages.commandPlayersOnly)
                return
            }
            show(source, engine.player(self) ?: PlayerRecord(self, source.name))
            return
        }
        engine.byName(typed)?.let { return show(source, it) }
        if (!PlayerRecord.NAME.matches(typed)) {
            texts.reply(source, XStatsMessages.neverJoined, "player" to typed)
            return
        }
        // Not under that name: maybe they changed it, and the family's cache knows the new one.
        val found = plugin.cache?.find(typed)?.let { engine.player(it.player) }
        if (found != null) show(source, found) else texts.reply(source, XStatsMessages.neverJoined, "player" to typed)
    }

    /** Builds the profile off the server's threads (other plugins' sections may take a moment), then sends it. */
    private fun show(source: CommandSource, record: PlayerRecord) {
        val staff = source.hasPermission(plugin.permissions.staff.node)
        plugin.platform.scheduler.run(Where.Async, action = Profile(this, source, record, staff))
    }

    private class Profile(private val actions: Actions, private val source: CommandSource, private val record: PlayerRecord, private val staff: Boolean) : (Task) -> Unit {
        override fun invoke(task: Task) {
            for (line in actions.profile(source.player, record, staff)) source.reply(line)
        }
    }

    fun profile(reader: PlayerRef?, record: PlayerRecord, staff: Boolean): List<Component> {
        val language = texts.language(reader)
        val stats = plugin.catalog.snapshot
        val calendar = engine.settings.calendar
        val name = record.name ?: record.id.toString()
        val lines = ArrayList<Component>()
        val playtime = stats["playtime"]?.let { Format.value(it, engine.value(record, it, Period.ALL)) } ?: ""
        texts.render(
            language, XStatsMessages.profileHeader,
            listOf(
                "player" to name,
                "first-join" to if (record.firstJoin > 0) date(record.firstJoin, calendar) else plainWord(reader, XStatsMessages.never),
                "last-seen" to lastSeen(reader, record),
                "playtime" to playtime,
                "kd" to kd(record),
            ),
        )?.let(lines::add)
        for (stat in stats.stats) {
            if (!stat.inGame) continue
            val rank = engine.rank(record, stat, Period.ALL)
            val rankText: Any = if (rank > 0) texts.render(language, XStatsMessages.profileRank, listOf("rank" to rank.toString())) ?: Component.EMPTY else Component.EMPTY
            texts.render(
                language, XStatsMessages.profileLine,
                listOf("stat" to stat.name, "value" to Format.value(stat, engine.value(record, stat, Period.ALL)), "rank" to rankText),
            )?.let(lines::add)
        }
        for (section in plugin.sections.fill(record.id, staff)) {
            texts.render(language, XStatsMessages.profileSection, listOf("title" to section.title))?.let(lines::add)
            for (line in section.lines) {
                texts.render(language, XStatsMessages.profileSectionLine, listOf("label" to line.label, "value" to line.value))?.let(lines::add)
            }
        }
        pageUrl(record)?.let { url -> texts.render(language, XStatsMessages.profileLink, listOf("url" to url))?.let(lines::add) }
        return lines
    }

    private fun plainWord(reader: PlayerRef?, key: MessageKey): String = texts.word(reader, key)

    private fun kd(record: PlayerRecord): String {
        val stats = plugin.catalog.snapshot
        val kills = stats["kills"] ?: return ""
        val deaths = stats["deaths"] ?: return ""
        return Format.ratio(engine.value(record, kills, Period.ALL), engine.value(record, deaths, Period.ALL))
    }

    private fun lastSeen(reader: PlayerRef?, record: PlayerRecord): String {
        if (plugin.server.isOnline(record.id) && plugin.cache?.isVanished(record.id) != true) return plainWord(reader, XStatsMessages.now)
        if (record.lastSeen <= 0) return plainWord(reader, XStatsMessages.never)
        val ago = Format.time(maxOf(0, (plugin.now() - record.lastSeen) / 1000))
        return texts.render(texts.language(reader), XStatsMessages.ago, listOf("time" to ago))?.plain() ?: ago
    }

    /** The player's page on the website, or `null` when there's no public url or they're hidden. */
    fun pageUrl(record: PlayerRecord): String? {
        val base = plugin.config[XStatsConfig.web.publicUrl].trim()
        if (base.isEmpty() || record.hidden || record.name == null || !URL.matches(base)) return null
        // By uuid: it never changes, and Bedrock players' names (Floodgate's .Steve) aren't Java names.
        return (if (base.endsWith("/")) base else "$base/") + "#/player/" + record.id
    }

    // ---- /leaderboard ----

    fun top(source: CommandSource, typed: String, periodOrPage: String?, pageArg: Int?) {
        if (!ready(source)) return
        val stat = find(typed)
        if (stat == null) {
            texts.reply(source, XStatsMessages.unknownStat, "stat" to typed, "stats" to shown().joinToString(", ") { it.id })
            return
        }
        var period = Period.ALL
        var page = pageArg ?: 1
        if (periodOrPage != null) {
            val parsed = Period.parse(periodOrPage)
            when {
                parsed != null -> period = parsed
                periodOrPage.toIntOrNull() != null && pageArg == null -> page = periodOrPage.toInt()
                else -> {
                    texts.reply(source, CoreMessages.commandUsage, "usage" to "/${leaderboardCommand()} <stat> [daily|weekly|monthly|all] [page]")
                    return
                }
            }
        }
        if (period != Period.ALL && !stat.periods) {
            texts.reply(source, XStatsMessages.noPeriods, "stat" to stat.name)
            return
        }
        val board = engine.leaderboards[stat.id, period]
        val kept = minOf(board?.size ?: 0, plugin.config[XStatsConfig.leaderboards.size])
        if (board == null || kept == 0) {
            texts.reply(source, XStatsMessages.topEmpty)
            return
        }
        val language = texts.language(source.player)
        val perPage = plugin.config[XStatsConfig.leaderboards.perPage]
        val pages = ChatPages.pages(kept.toLong(), perPage)
        val number = page.coerceIn(1, pages)
        val from = (number - 1) * perPage
        val entries = board.slice(from, minOf(perPage, kept - from))
        val shown = ChatPages.of(entries, kept.toLong(), number, perPage)
        texts.reply(source, XStatsMessages.topHeader, "stat" to stat.name, "period" to periodName(plugin, source.player, period))
        val self = source.player
        for (entry in entries) {
            texts.reply(
                source, if (entry.player == self) XStatsMessages.topLineSelf else XStatsMessages.topLine,
                "rank" to entry.rank.toString(), "player" to entry.name, "value" to Format.value(stat, entry.value),
            )
        }
        if (shown.pages > 1) {
            val command = leaderboardCommand() + " " + stat.id + " " + period.id + " {page}"
            texts.translations.render(CoreMessages.pagesFooter, language, ChatPages.footer(shown, "/$command", language))?.let(source::reply)
        }
    }

    private fun leaderboardCommand(): String = if (plugin.server.proxy) plugin.config[XStatsConfig.proxy.leaderboard.command] else "leaderboard"

    private fun shown(): List<StatDef> = plugin.catalog.snapshot.stats.filter { it.inGame }

    /** A stat by id, or by name ignoring case and spaces: "mob_kills", "Mobs_killed". */
    private fun find(typed: String): StatDef? {
        val wanted = typed.lowercase()
        return shown().firstOrNull { it.id == wanted || it.name.lowercase().replace(' ', '_') == wanted }
    }

    // ---- /xstats status, save, import ----

    fun status(source: CommandSource) {
        val db = plugin.database
        if (db == null || !engine.ready) {
            val failure = plugin.failure
            if (failure == null) texts.reply(source, XStatsMessages.statusLoading) else texts.reply(source, XStatsMessages.statusFailed, "reason" to failure)
            return
        }
        if (plugin.server.proxy) return proxyStatus(source)
        // Checked on the global thread, against the server's own names for its stats.
        plugin.platform.scheduler.run(Where.Global, action = Status(this, source))
    }

    private class Status(private val actions: Actions, private val source: CommandSource) : (Task) -> Unit {
        override fun invoke(task: Task) {
            val plugin = actions.plugin
            val wrong = plugin.server.verifyTable()
            val count = plugin.server.customStats()
            val table: Any = when {
                wrong == null -> Texts.Word(XStatsMessages.statusTableUnchecked, "count" to count)
                wrong.isEmpty() -> Texts.Word(XStatsMessages.statusTable, "count" to count)
                else -> Texts.Word(XStatsMessages.statusTableWrong, "count" to wrong.size, "wrong" to wrong.joinToString(", "))
            }
            val online = plugin.server.online().count { plugin.cache?.isVanished(it) != true }
            plugin.onDatabase { q ->
                val store = plugin.engine.store
                val rows: Any = try {
                    val counts = store!!.rowCounts(q)
                    Texts.Word(XStatsMessages.statusRows, "totals" to counts[0], "daily" to counts[1], "monthly" to counts[2])
                } catch (e: DbFailed) {
                    Texts.Word(XStatsMessages.statusRowsFailed, "reason" to e.error.detail)
                }
                actions.report(source, table, online, rows)
            }
        }
    }

    private fun report(source: CommandSource, table: Any, online: Int, rows: Any) {
        val last = engine.lastSave
        val saved: Any = if (last.at == 0L) XStatsMessages.statusNotSaved
        else Texts.Word(XStatsMessages.statusSaved, "ago" to Format.time((plugin.now() - last.at) / 1000), "players" to last.players, "millis" to last.millis)
        texts.reply(
            source, XStatsMessages.status,
            "server" to (engine.store?.serverName ?: "?"),
            "storage" to (plugin.database?.description ?: "?"),
            "network" to if (engine.feed != null) XStatsMessages.statusNetwork else Component.EMPTY,
            "known" to engine.playerCount, "online" to online,
            "stats" to plugin.catalog.snapshot.stats.size, "table" to table, "saved" to saved,
            "waiting" to engine.waiting,
            "dropped" to if (engine.dropped > 0) Texts.Word(XStatsMessages.statusDropped, "count" to engine.dropped) else Component.EMPTY,
            "rows" to rows, "website" to website(),
            "integrations" to Integrations.describe(plugin),
            "problem" to (last.problem?.let { Texts.Word(XStatsMessages.statusProblem, "reason" to it) } ?: XStatsMessages.statusNone),
        )
    }

    private fun website(): Any {
        val port = plugin.extras?.webPort ?: 0
        return when {
            !plugin.config[XStatsConfig.web.enabled] -> XStatsMessages.statusWebOff
            port == 0 -> XStatsMessages.statusWebDown
            else -> Texts.Word(XStatsMessages.statusWebOn, "bind" to plugin.config[XStatsConfig.web.bind], "port" to port.toString())
        }
    }

    private fun proxyStatus(source: CommandSource) {
        val online = plugin.server.online().count { plugin.cache?.isVanished(it) != true }
        plugin.onDatabase { q ->
            val servers = try {
                plugin.engine.store!!.servers(q).joinToString(", ").ifEmpty { "none yet" }
            } catch (e: DbFailed) {
                e.error.detail
            }
            val read: Any = if (engine.lastRead == 0L) XStatsMessages.statusNotSaved
            else Texts.Word(XStatsMessages.ago, "time" to Format.time((plugin.now() - engine.lastRead) / 1000))
            if (engine.feed == null) texts.reply(source, XStatsMessages.proxyNoNetwork)
            texts.reply(
                source, XStatsMessages.proxyStatus,
                "storage" to (plugin.database?.description ?: "?"),
                "network" to if (engine.feed != null) XStatsMessages.statusNetwork else Component.EMPTY,
                "known" to engine.playerCount, "online" to online, "servers" to servers, "read" to read, "website" to website(),
            )
        }
    }

    fun save(source: CommandSource) {
        if (!ready(source)) return
        if (plugin.server.proxy) {
            plugin.engine.networkChanged()
            if (!plugin.save { texts.reply(source, XStatsMessages.proxyRefreshed, "players" to engine.playerCount) }) texts.reply(source, XStatsMessages.saveRunning)
            return
        }
        val started = plugin.save { written ->
            val last = engine.lastSave
            if (written && last.problem == null) texts.reply(source, XStatsMessages.saved, "players" to last.players, "millis" to last.millis)
            else texts.reply(source, XStatsMessages.saveFailed, "reason" to (last.problem ?: "it didn't reach the database"))
        }
        if (!started) texts.reply(source, XStatsMessages.saveRunning)
    }

    fun importHistory(source: CommandSource) {
        if (!ready(source)) return
        val extras = plugin.extras ?: return texts.reply(source, XStatsMessages.importNoWorld)
        texts.reply(source, XStatsMessages.importReading)
        plugin.platform.scheduler.run(Where.Async) { _ ->
            extras.importHistory(plugin, false) { message, placeholders -> texts.reply(source, message, *placeholders.toTypedArray()) }
        }
    }

    // ---- /xstats board ----

    fun boardAdd(source: CommandSource, typed: String, periodText: String?, world: String?, x: Double?, y: Double?, z: Double?) {
        if (!ready(source)) return
        val host = plugin.server.boards ?: return texts.reply(source, XStatsMessages.boardUnsupported)
        val stat = plugin.catalog.snapshot[typed.lowercase()] ?: return texts.reply(source, XStatsMessages.boardUnknownStat, "stat" to typed)
        val period = if (periodText == null) Period.ALL else Period.parse(periodText)
            ?: return texts.reply(source, CoreMessages.commandUsage, "usage" to BOARD_USAGE)
        if (period != Period.ALL && !stat.periods) return texts.reply(source, XStatsMessages.boardNoPeriods, "stat" to stat.name)
        if (world != null) {
            if (x == null || y == null || z == null) return texts.reply(source, CoreMessages.commandUsage, "usage" to BOARD_USAGE)
            if (!host.hasWorld(world)) return texts.reply(source, XStatsMessages.boardNoWorld, "world" to world)
            place(source, Store.BoardRow(0, world, x, y, z, 0f, stat.id, period.id), stat, period)
            return
        }
        val player = source.player ?: return texts.reply(source, CoreMessages.commandPlayersOnly)
        plugin.platform.scheduler.run(Where.On(player.uuid), action = Spot(this, source, player, host, stat, period))
    }

    private class Spot(
        private val actions: Actions,
        private val source: CommandSource,
        private val player: PlayerRef,
        private val host: BoardHost,
        private val stat: StatDef,
        private val period: Period,
    ) : (Task) -> Unit {
        override fun invoke(task: Task) {
            val spot = host.spot(player) ?: return
            actions.place(source, Store.BoardRow(0, spot.world, spot.x, spot.y, spot.z, 0f, stat.id, period.id), stat, period)
        }
    }

    private fun place(source: CommandSource, row: Store.BoardRow, stat: StatDef, period: Period) {
        plugin.onDatabase { q ->
            val store = engine.store ?: return@onDatabase
            val id = try {
                store.addBoard(q, row)
            } catch (e: DbFailed) {
                texts.reply(source, XStatsMessages.boardFailed, "reason" to e.error.detail)
                return@onDatabase
            }
            val placed = Store.BoardRow(id, row.world, row.x, row.y, row.z, row.yaw, row.stat, row.period)
            locked(plugin) { plugin.boards = plugin.boards + placed }
            plugin.updateBoards()
            texts.reply(source, XStatsMessages.boardPlaced, "id" to id.toString(), "stat" to stat.name, "period" to period.id)
        }
    }

    fun boardList(source: CommandSource) {
        if (!ready(source)) return
        val rows = plugin.boards
        if (rows.isEmpty()) return texts.reply(source, XStatsMessages.boardNone)
        texts.reply(source, XStatsMessages.boardListHeader, "count" to rows.size)
        for (row in rows) {
            texts.reply(
                source, XStatsMessages.boardListLine,
                "id" to row.id.toString(), "stat" to row.stat, "period" to row.period, "world" to row.world,
                "x" to floor(row.x).toInt().toString(), "y" to floor(row.y).toInt().toString(), "z" to floor(row.z).toInt().toString(),
            )
        }
    }

    fun boardRemove(source: CommandSource, id: Int) {
        if (!ready(source)) return
        val row = plugin.boards.firstOrNull { it.id == id } ?: return texts.reply(source, XStatsMessages.boardMissing, "id" to id.toString())
        plugin.onDatabase { q ->
            val removed = try {
                engine.store?.removeBoard(q, id) ?: false
            } catch (e: DbFailed) {
                texts.reply(source, XStatsMessages.boardFailed, "reason" to e.error.detail)
                return@onDatabase
            }
            if (!removed) return@onDatabase texts.reply(source, XStatsMessages.boardMissing, "id" to id.toString())
            locked(plugin) { plugin.boards = plugin.boards.filter { it.id != id } }
            plugin.server.boards?.let {
                it.remove(row)
                it.listen(plugin.boards)
            }
            texts.reply(source, XStatsMessages.boardRemoved, "id" to id.toString())
        }
    }

    // ---- /xstats web ----

    fun webTest(source: CommandSource) {
        if (!ready(source)) return
        val extras = plugin.extras ?: return texts.reply(source, XStatsMessages.webUnavailable)
        plugin.platform.scheduler.run(Where.Async) { _ ->
            val (count, failures) = extras.webTest(plugin)
            if (failures.isEmpty()) texts.reply(source, XStatsMessages.webPassed, "count" to count)
            else texts.reply(source, XStatsMessages.webFailed, "reason" to failures.joinToString("; "))
        }
    }

    fun webExport(source: CommandSource) {
        val extras = plugin.extras ?: return texts.reply(source, XStatsMessages.webUnavailable)
        plugin.platform.scheduler.run(Where.Async) { _ ->
            when (val done = extras.webExport(plugin)) {
                is Outcome.Ok -> {
                    val url = plugin.config[XStatsConfig.web.publicUrl].trim()
                    texts.reply(
                        source, XStatsMessages.webExported, "folder" to done.value,
                        "then" to if (url.isEmpty()) XStatsMessages.webExportedNoUrl else Texts.Word(XStatsMessages.webExportedUrl, "url" to url),
                    )
                }
                is Outcome.Err -> texts.reply(source, XStatsMessages.webExportFailed, "reason" to done.error)
            }
        }
    }

    companion object {
        private const val BOARD_USAGE = "/xstats board add <stat> [daily|weekly|monthly|all] [<world> <x> <y> <z>]"
        private val URL = Regex("https?://[^\\s'\"<>]+")

        fun of(plugin: XStatsPlugin): Actions = plugin.madeActions ?: locked(plugin) { plugin.madeActions ?: Actions(plugin).also { plugin.madeActions = it } }

        /** A period as players read it, in [reader]'s language (`periods.*`). */
        fun periodName(plugin: XStatsPlugin, reader: PlayerRef?, period: Period): String = plugin.texts.word(
            reader,
            when (period) {
                Period.DAILY -> XStatsMessages.periodDaily
                Period.WEEKLY -> XStatsMessages.periodWeekly
                Period.MONTHLY -> XStatsMessages.periodMonthly
                Period.ALL -> XStatsMessages.periodAll
            },
        )

        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        /** A date as 1.x wrote it: `5 Mar 2026`, in the config's time zone. */
        fun date(millis: Long, calendar: Calendar): String {
            val (year, month, day) = Calendar.civil(calendar.today(millis).toLong())
            return "$day ${MONTHS[month - 1]} $year"
        }
    }
}

/** What the commands do, made the first time one runs. */
internal fun XStatsPlugin.actions(): Actions = Actions.of(this)
