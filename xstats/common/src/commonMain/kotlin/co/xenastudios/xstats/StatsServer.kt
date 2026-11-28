package co.xenastudios.xstats

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.text.Component
import co.xenastudios.xstats.data.Event
import co.xenastudios.xstats.stat.Catalog
import co.xenastudios.xstats.stat.VanillaReader
import co.xenastudios.xstats.store.Store

/**
 * What xStats needs from the server it runs on, written by each adapter (Bukkit, Fabric, Velocity).
 * Every method is safe from any thread unless it says otherwise; the adapter moves work to the thread
 * it belongs on.
 */
public interface StatsServer {
    /** Whether this is the proxy (Velocity): it reads the network's stats and counts none itself. */
    public val proxy: Boolean get() = false

    /** Every online player: on the proxy, everyone on the network. */
    public fun online(): List<PlayerRef>

    /** Whether [player] is online here. */
    public fun isOnline(player: PlayerRef): Boolean

    /** Sends [message] to an online player. */
    public fun send(player: PlayerRef, message: Component)

    /** Whether online [player] has [node], as the server's permissions say. */
    public fun hasPermission(player: PlayerRef, node: String): Boolean

    /** How this server reads Minecraft's stats, or `null` where it can't (the proxy, tests). */
    public val vanilla: VanillaReader? get() = null

    /**
     * Reads online [player]'s stats into an [Event.Sample]: Minecraft's own counters, placeholders
     * (unless [placeholders] is off, as while the server stops), and where they're looking. On the
     * player's own thread; `null` if they're gone.
     */
    public fun read(player: PlayerRef, stats: Catalog.Snapshot, quit: Boolean, placeholders: Boolean): Event.Sample? = null

    /** The server's own ban list. On the global thread. */
    public fun banned(): Set<PlayerRef> = emptySet()

    /**
     * Checks xStats' table of Minecraft's stats against this server's own names for them: what
     * doesn't match, or `null` where the server can't say (Spigot, Fabric). On the global thread.
     */
    public fun verifyTable(): List<String>? = null

    /** How many of Minecraft's custom stats this server has, for `/xstats status`. */
    public fun customStats(): Int = 0

    /** The names of the worlds, for `/xstats board add`'s completions. */
    public fun worlds(): List<String> = emptyList()

    /** Boards in the world, where the server has text displays; `null` where it can't place them. */
    public val boards: BoardHost? get() = null
}

/**
 * Where boards stand in the world: the adapter's text displays. Each call runs on the thread that
 * owns the board's place, which the adapter finds.
 */
public interface BoardHost {
    /** Where [player] stands, a little above, centred on the block: `world`, x, y, z; or `null`. On their thread. */
    public fun spot(player: PlayerRef): Store.BoardRow?

    /** Whether [world] exists here. */
    public fun hasWorld(world: String): Boolean

    /** Shows [text] on board [row], making its display if it isn't there and its chunk is loaded. */
    public fun show(row: Store.BoardRow, text: Component)

    /** Takes board [row]'s display away. */
    public fun remove(row: Store.BoardRow)

    /** Starts or stops making boards' displays as their chunks load, as there are boards or none. */
    public fun listen(rows: List<Store.BoardRow>)
}
