package co.xenastudios.core.common.family

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.map
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.PlayerData
import co.xenastudios.core.common.admin.PlayerLookup
import co.xenastudios.core.common.db.Column
import co.xenastudios.core.common.db.ColumnType
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Migration
import co.xenastudios.core.common.db.Migrations
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.Statement
import co.xenastudios.core.common.db.Table
import co.xenastudios.core.common.db.descending
import co.xenastudios.core.common.db.eq
import co.xenastudios.core.common.db.values
import co.xenastudios.core.common.i18n.LanguageChangeListener
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Clock
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.platform.Storage
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.async
import co.xenastudios.core.common.storage.RecordFile
import kotlin.concurrent.Volatile
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What the family knows about one [player]: the [name] they last joined with, when they were
 * last seen (joined or left), the [language] their client last said (`pt_br`, as Minecraft sends
 * it; `null` if it never has), whether they're [vanished], and whether they're [online] now.
 */
public class CachedPlayer(
    public val player: PlayerRef,
    public val name: String,
    public val lastSeen: Instant,
    public val language: String?,
    public val vanished: Boolean,
    public val online: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is CachedPlayer && other.player == player && other.name == name &&
        other.lastSeen == lastSeen && other.language == language && other.vanished == vanished && other.online == online

    override fun hashCode(): Int = player.hashCode()

    override fun toString(): String = "CachedPlayer($name, $player, seen $lastSeen, ${language ?: "no language"}${if (vanished) ", vanished" else ""}${if (online) ", online" else ""})"
}

/** Where the family's player cache is kept between restarts: see [PlayerCache]. */
public sealed class PlayerStore {
    /** Nowhere: the cache lives in memory only, and starts empty. */
    public object None : PlayerStore()

    /**
     * The family's shared database (`database:` in `plugins/Xena/config.yml`), in its own
     * `xena_players` table, whatever prefix the plugin's own tables have. Every player is kept
     * there; memory holds the most recent, and [PlayerCache.lookup] reads the rest.
     */
    public class InDatabase(public val database: Database) : PlayerStore()

    /**
     * A file in the family's folder, `plugins/Xena/players.records`, when there's no shared
     * database. It holds what memory holds: the most recently seen players, up to the cache's
     * size.
     */
    public class InFile(public val folder: Storage) : PlayerStore()
}

/**
 * The family's player cache: every Xena plugin on a server reads and writes the same one, by
 * UUID (ROADMAP.md, The family). One plugin records a join, and the others know the name; xVanish
 * marks a player vanished, and zChat and the tab list see it.
 *
 * **One cache for the family, through the [FamilyBoard].** Each plugin makes its own
 * `PlayerCache` (its copy of the core is its own), and they all keep their players in the same
 * maps on the board, in JDK types only: a player is an array of their name, last seen (epoch
 * milliseconds), language, vanished and online, keyed by their UUID as text. A change replaces
 * the array, compare-and-set, so two plugins changing one player at once both land.
 *
 * **Bounded.** At most [capacity] players are in memory; past that, the least recently seen
 * players who are offline and saved are dropped, down to nine tenths, so dropping happens once
 * per many joins rather than on every one. With a shared database the dropped are still there,
 * for [lookup].
 *
 * **Saved by whoever changed it, one plugin at a time.** A change marks the player dirty on the
 * board and schedules a save on the async thread. The save claims the board's `saving` slot, so
 * only one plugin writes at a time, and keeps writing until nothing is dirty, other plugins'
 * changes included; a plugin that finds the slot taken leaves its change to the one holding it.
 * The database gets a row per changed player; the file is written whole. Loaded once per board,
 * by the first plugin to start, off the server's threads; until then the cache answers from
 * what's been recorded since.
 *
 * It's also where messages find a player's language ([LanguageSource]): the platform records the
 * client's locale when they join and when it changes.
 */
public class PlayerCache(
    board: FamilyBoard,
    private val store: PlayerStore,
    private val scheduler: Scheduler,
    private val clock: Clock,
    private val log: Log,
    /** The plugin using it, for the console when something goes wrong: `xbans`. */
    private val pluginId: String,
    /** How many players memory holds at most. */
    public val capacity: Int = DEFAULT_CAPACITY,
) : LanguageSource {
    init {
        require(capacity >= 10) { "a player cache holds at least 10 players" }
    }

    private val players = board.map(PLAYERS)
    private val names = board.map(NAMES)
    private val dirty = board.map(DIRTY)
    private val state = board.map(STATE)

    // Who this cache is on the board, for the slots it claims. Unique per cache, also when two
    // caches of one plugin share a board in a test.
    private val token = "$pluginId#${nextToken()}"

    private val lock = Any()
    private var saveScheduled = false

    @Volatile
    private var listeners: List<LanguageChangeListener> = emptyList()

    private val sql: Sql? = (store as? PlayerStore.InDatabase)?.let { Sql(it.database.dialect, PREFIX) }

    /**
     * Loads the saved players into the board, if no plugin has yet: on the async thread, after
     * bringing the family's table up to date. The [Pending] settles when this cache's part is
     * done, at once if another plugin loaded them; it never fails, and a load that couldn't be
     * done is logged and left for the next plugin to start.
     */
    public fun load(): Pending<Unit> {
        if (store == PlayerStore.None || state.putIfAbsent(LOADED, token) != null) return pendingOf(Unit)
        val done = newPending<Unit>()
        scheduler.async {
            when (store) {
                is PlayerStore.InDatabase -> loadFromDatabase(store.database) { done.resolve(Unit) }
                is PlayerStore.InFile -> {
                    loadFromFile(store.folder)
                    done.resolve(Unit)
                }
                PlayerStore.None -> done.resolve(Unit)
            }
        }
        return done
    }

    /** What the family knows about [player], from memory: `null` if they're not in it. */
    public operator fun get(player: PlayerRef): CachedPlayer? = decode(player, players[player.uuid.toString()])

    /** The player who last joined with [name], in any case, from memory: `null` if none is in it. */
    public fun find(name: String): CachedPlayer? {
        val key = names[name.lowercase()] as? String ?: return null
        val player = PlayerRef.parseOrNull(key) ?: return null
        return get(player)?.takeIf { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * [player] from memory or, with a shared database, from there, for one who hasn't been seen
     * in a while. Read from the database, they're kept in memory again.
     */
    public fun lookup(player: PlayerRef): Pending<Outcome<CachedPlayer?, DbError>> {
        get(player)?.let { return pendingOf(Outcome.Ok(it)) }
        return read(Players.uuid eq player.uuid)
    }

    /** The player who last joined with [name], from memory or the shared database. */
    public fun lookup(name: String): Pending<Outcome<CachedPlayer?, DbError>> {
        find(name)?.let { return pendingOf(Outcome.Ok(it)) }
        return read(Players.lookup eq name.lowercase())
    }

    /** Every player in memory, in no order. */
    public fun all(): List<CachedPlayer> = players.keys().mapNotNull { key -> PlayerRef.parseOrNull(key)?.let { get(it) } }

    /**
     * [player] joined as [name], their client saying [locale] (`null` if it hasn't yet). Every
     * plugin's adapter calls this on a join; the first records it and the rest find nothing to
     * change.
     */
    public fun joined(player: PlayerRef, name: String, locale: String?) {
        val sent = locale?.take(MAX_LOCALE)
        update(player) { old ->
            if (old != null && old.online && old.name == name && (sent == null || old.language == sent)) return@update old
            CachedPlayer(player, name, clock.now(), sent ?: old?.language, old?.vanished ?: false, online = true)
        }
    }

    /** [player] left: they're offline, last seen now. */
    public fun left(player: PlayerRef) {
        update(player) { old ->
            if (old == null || !old.online) return@update old
            CachedPlayer(player, old.name, clock.now(), old.language, old.vanished, online = false)
        }
    }

    /** [player]'s client now says [locale]. The listeners of [onLanguageChange] hear about it. */
    public fun languageChanged(player: PlayerRef, locale: String) {
        val sent = locale.take(MAX_LOCALE)
        val changed = update(player) { old ->
            if (old == null || old.language == sent) return@update old
            CachedPlayer(player, old.name, old.lastSeen, sent, old.vanished, old.online)
        }
        if (changed) for (listener in listeners) listener.languageChanged(player)
    }

    /** Whether [player] is vanished, as far as the family knows. */
    public fun isVanished(player: PlayerRef): Boolean = get(player)?.vanished ?: false

    /** Marks [player] vanished or not (xVanish). A player the cache doesn't know is left alone. */
    public fun setVanished(player: PlayerRef, vanished: Boolean) {
        update(player) { old ->
            if (old == null || old.vanished == vanished) return@update old
            CachedPlayer(player, old.name, old.lastSeen, old.language, vanished, old.online)
        }
    }

    /**
     * Forgets [player] (`/<plugin> forget`): from memory, and from where the cache is saved.
     * Returns whether it knew them.
     */
    public fun forget(player: PlayerRef): Boolean {
        val key = player.uuid.toString()
        val removed = players.remove(key) ?: return false
        decode(player, removed)?.let { names.remove(it.name.lowercase(), key) }
        markDirty(key)
        return true
    }

    /**
     * The cache as the admin commands' [PlayerLookup]: `/<plugin> forget <name>` finds the player
     * here, from memory or the shared database.
     */
    public fun asLookup(): PlayerLookup = PlayerLookup { name -> lookup(name).map { (it as? Outcome.Ok)?.value?.player } }

    /** The cache as [PlayerData], for a plugin's `forget` to register: it forgets the player here too. */
    public fun asPlayerData(): PlayerData = PlayerData { player -> pendingOf(Outcome.Ok(if (forget(player)) 1L else 0L)) }

    override fun clientLocale(player: PlayerRef): String? = (players[player.uuid.toString()] as? Array<*>)?.get(LANGUAGE) as? String

    override fun onLanguageChange(listener: LanguageChangeListener) {
        locked(lock) { listeners = listeners + listener }
    }

    // Changes a player compare-and-set: [change] returns the new entry, or the old one to change
    // nothing. Returns whether it changed something.
    private fun update(player: PlayerRef, change: (CachedPlayer?) -> CachedPlayer?): Boolean {
        val key = player.uuid.toString()
        while (true) {
            val raw = players[key]
            val old = decode(player, raw)
            val new = change(old) ?: return false
            if (new == old) return false
            val written = encode(new)
            val swapped = if (raw == null) players.putIfAbsent(key, written) == null else players.replace(key, raw, written)
            if (!swapped) continue
            if (old == null || old.name != new.name) {
                if (old != null) names.remove(old.name.lowercase(), key)
                names.put(new.name.lowercase(), key)
            }
            markDirty(key)
            if (raw == null && players.size > capacity) trim()
            return true
        }
    }

    // Drops the least recently seen offline, saved players, down to nine tenths of the capacity.
    private fun trim() {
        val target = capacity - capacity / 10
        val candidates = players.keys().mapNotNull { key ->
            if (dirty[key] != null) return@mapNotNull null
            val raw = players[key] as? Array<*> ?: return@mapNotNull null
            if (raw[ONLINE] == true) null else Triple(key, raw, raw[LAST_SEEN] as Long)
        }.sortedBy { it.third }
        var excess = players.size - target
        for ((key, raw, _) in candidates) {
            if (excess <= 0) break
            if (players.remove(key, raw)) {
                names.remove((raw[NAME] as String).lowercase(), key)
                excess--
            }
        }
    }

    private fun markDirty(key: String) {
        if (store == PlayerStore.None) return
        dirty.put(key, true)
        val schedule = locked(lock) { (!saveScheduled).also { saveScheduled = true } }
        if (schedule) scheduler.async { save() }
    }

    // On the async thread: claims the saving slot and writes until nothing is dirty.
    private fun save() {
        locked(lock) { saveScheduled = false }
        if (state.putIfAbsent(SAVING, token) != null) return
        saveRound()
    }

    private fun saveRound() {
        val keys = dirty.keys()
        for (key in keys) dirty.remove(key)
        if (keys.isEmpty()) {
            state.remove(SAVING, token)
            // A change marked after the keys were read, whose own save found the slot still
            // taken, would wait for the next change: take the slot back and write it now.
            if (dirty.size > 0 && state.putIfAbsent(SAVING, token) == null) saveRound()
            return
        }
        when (store) {
            is PlayerStore.InDatabase -> {
                store.database.write(statements(keys)).onSettled { result, error ->
                    if (result !is Outcome.Ok) {
                        val why = (result as? Outcome.Err)?.error?.detail ?: error?.toString()
                        log.warn("[$pluginId] Couldn't save ${keys.size} player${if (keys.size == 1) "" else "s"} to the shared database ($why); they're saved with the next change.")
                        for (key in keys) dirty.put(key, true)
                        state.remove(SAVING, token)
                    } else {
                        saveRound()
                    }
                }
            }
            is PlayerStore.InFile -> {
                val written = store.folder.writeText(FILE, RecordFile.write(COLUMNS, snapshot()))
                if (written is Outcome.Err) {
                    log.warn("[$pluginId] Couldn't save the player cache to $FILE (${written.error.detail}); it's saved with the next change.")
                    for (key in keys) dirty.put(key, true)
                    state.remove(SAVING, token)
                } else {
                    saveRound()
                }
            }
            PlayerStore.None -> state.remove(SAVING, token)
        }
    }

    private fun statements(keys: List<String>): List<Statement> {
        val sql = sql!!
        return keys.flatMap { key ->
            val player = PlayerRef.parseOrNull(key) ?: return@flatMap emptyList()
            val cached = get(player) ?: return@flatMap listOf(sql.delete(Players, Players.uuid eq player.uuid))
            sql.upsert(values(Players) { write(cached, it) })
        }
    }

    private fun snapshot(): List<Row> = players.keys().mapNotNull { key ->
        val cached = PlayerRef.parseOrNull(key)?.let { get(it) } ?: return@mapNotNull null
        val values = values(Players) { write(cached, it) }
        Row(COLUMNS, Players.columns.map { values.encoded(it) })
    }

    private fun read(where: co.xenastudios.core.common.db.Where): Pending<Outcome<CachedPlayer?, DbError>> {
        val database = (store as? PlayerStore.InDatabase)?.database ?: return pendingOf(Outcome.Ok(null))
        val sql = sql!!
        return database.read { session ->
            when (val rows = session.query(sql.select(Players, where, limit = 1))) {
                is Outcome.Ok -> Outcome.Ok(rows.value.firstOrNull()?.let(::readRow))
                is Outcome.Err -> rows
            }
        }.map { result ->
            if (result is Outcome.Ok) result.value?.let(::remember)?.let { return@map Outcome.Ok(it) }
            result
        }
    }

    // A player read from where they're saved: into memory unless memory has them already
    // (a fresher join wins), and not dirty, since they're saved as they are.
    private fun remember(cached: CachedPlayer): CachedPlayer {
        val key = cached.player.uuid.toString()
        val existing = players.putIfAbsent(key, encode(cached))
        if (existing != null) return decode(cached.player, existing) ?: cached
        names.putIfAbsent(cached.name.lowercase(), key)
        if (players.size > capacity) trim()
        return cached
    }

    private fun loadFromDatabase(database: Database, done: () -> Unit) {
        val sql = sql!!
        database.session { session -> Migrations.run(session, sql, MIGRATIONS) }.onSettled { migrated, error ->
            if (migrated !is Outcome.Ok) {
                failedLoad((migrated as? Outcome.Err)?.error?.detail ?: error.toString())
                done()
                return@onSettled
            }
            database.read { session -> session.query(sql.select(Players, order = listOf(Players.lastSeen.descending), limit = capacity)) }
                .onSettled { rows, failure ->
                    when (rows) {
                        is Outcome.Ok -> rows.value.forEach { row -> decodeRow(row)?.let(::remember) }
                        is Outcome.Err -> failedLoad(rows.error.detail)
                        null -> failedLoad(failure.toString())
                    }
                    done()
                }
        }
    }

    private fun loadFromFile(folder: Storage) {
        val text = when (val read = folder.readText(FILE)) {
            is Outcome.Ok -> read.value ?: return
            is Outcome.Err -> return failedLoad(read.error.detail)
        }
        when (val rows = RecordFile.read(text)) {
            is Outcome.Ok -> rows.value.forEach { row -> decodeRow(row)?.let(::remember) }
            is Outcome.Err -> failedLoad("$FILE: ${rows.error}")
        }
    }

    private fun failedLoad(why: String) {
        log.warn("[$pluginId] Couldn't load the family's player cache ($why); it starts empty, and the next plugin to start tries again.")
        state.remove(LOADED, token)
    }

    private fun decodeRow(row: Row): CachedPlayer? = try {
        readRow(row)
    } catch (e: co.xenastudios.core.common.db.CorruptValueException) {
        log.warn("[$pluginId] Skipped a player in the cache that can't be read: ${e.message}")
        null
    }

    /** The family's table of players, `xena_players` in the shared database. */
    public object Players : Table("players") {
        public val uuid: Column<Uuid> = column("uuid", ColumnType.UUID)
        public val name: Column<String> = column("name", ColumnType.text(32))

        /** The name in lower case, for finding a player by name in any case. */
        public val lookup: Column<String> = column("lookup", ColumnType.text(32))
        public val lastSeen: Column<Instant> = column("last_seen", ColumnType.INSTANT)
        public val language: Column<String?> = column("language", ColumnType.text(MAX_LOCALE).nullable())
        public val vanished: Column<Boolean> = column("vanished", ColumnType.BOOLEAN)

        init {
            primaryKey(uuid)
            index(lookup)
        }
    }

    public companion object {
        /** How many players memory holds unless the family's config says otherwise. */
        public const val DEFAULT_CAPACITY: Int = 10_000

        // Clients send what they like as their locale; Minecraft's are five or six characters.
        private const val MAX_LOCALE = 16

        /** The family's tables' prefix in the shared database, whatever the plugins' own are. */
        public const val PREFIX: String = "xena_"

        // The board's maps. A new layout of the entries is a new version in these names.
        internal const val PLAYERS = "xena.players.v1"
        internal const val NAMES = "xena.players.v1.names"
        internal const val DIRTY = "xena.players.v1.dirty"
        internal const val STATE = "xena.players.v1.state"
        internal const val LOADED = "loaded"
        internal const val SAVING = "saving"

        // Where each field is in a board entry.
        private const val NAME = 0
        private const val LAST_SEEN = 1
        private const val LANGUAGE = 2
        private const val VANISHED = 3
        private const val ONLINE = 4

        /** The file the cache is saved in without a shared database, in `plugins/Xena/`. */
        public val FILE: StoragePath = StoragePath.of("players.records")

        private val COLUMNS = Players.columns.map { it.name }

        private val MIGRATIONS = listOf(Migration(1, "the family's player cache") { it.createTable(Players) })

        private val tokenLock = Any()
        private var tokens = 0

        private fun nextToken(): Int = locked(tokenLock) { ++tokens }

        private fun encode(cached: CachedPlayer): Array<Any?> =
            arrayOf(cached.name, cached.lastSeen.toEpochMilliseconds(), cached.language, cached.vanished, cached.online)

        private fun decode(player: PlayerRef, raw: Any?): CachedPlayer? {
            val fields = raw as? Array<*> ?: return null
            return CachedPlayer(
                player,
                fields[NAME] as String,
                Instant.fromEpochMilliseconds(fields[LAST_SEEN] as Long),
                fields[LANGUAGE] as String?,
                fields[VANISHED] as Boolean,
                fields[ONLINE] as Boolean,
            )
        }

        private fun readRow(row: Row): CachedPlayer =
            CachedPlayer(PlayerRef.of(row[Players.uuid]), row[Players.name], row[Players.lastSeen], row[Players.language], row[Players.vanished], online = false)

        private fun write(cached: CachedPlayer, values: co.xenastudios.core.common.db.Values) {
            values[Players.uuid] = cached.player.uuid
            values[Players.name] = cached.name
            values[Players.lookup] = cached.name.lowercase()
            values[Players.lastSeen] = cached.lastSeen
            values[Players.language] = cached.language
            values[Players.vanished] = cached.vanished
        }
    }
}
