package co.xenastudios.xrewind

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.world.BlockChange
import co.xenastudios.core.common.world.ItemData
import co.xenastudios.core.common.world.WorldHooks
import co.xenastudios.xrewind.query.Center

/** A block's place, by world name. */
public class Spot(public val world: String, public val x: Int, public val y: Int, public val z: Int) {
    /** The chunk's x: 16 blocks to a chunk. */
    public val chunkX: Int get() = x shr 4

    public val chunkZ: Int get() = z shr 4

    override fun equals(other: Any?): Boolean = other is Spot && other.x == x && other.y == y && other.z == z && other.world == world

    override fun hashCode(): Int = ((world.hashCode() * 31 + x) * 31 + y) * 31 + z

    override fun toString(): String = "$world $x $y $z"
}

/** What a block was before a rollback set it, for its undo: its state, and what it held, as the platform writes it. */
public class Previous(public val state: String, public val contents: ByteArray?)

/** One block for a player's preview: what they're shown at [spot] (or, cancelled, what's really there). */
public class Shown(public val spot: Spot, public val state: String)

/** What the adapter hears that the world hooks don't cover: joins and quits, signs, chat, commands and clicks. */
public interface ServerEvents {
    public fun joined(player: PlayerRef, name: String, at: Spot)
    public fun quit(player: PlayerRef, name: String, at: Spot)
    public fun sign(player: PlayerRef, name: String, at: Spot, text: String)
    public fun chat(player: PlayerRef, name: String, world: String, message: String)
    public fun command(player: PlayerRef, name: String, at: Spot, line: String)

    /** A door, trapdoor, gate, lever or button used; [state] what it is. */
    public fun click(player: PlayerRef, name: String, at: Spot, state: String)
}

/** The kinds of [ServerEvents] the adapter is asked to hear: only those, and only while asked. */
public enum class Heard { SESSIONS, SIGNS, CHAT, COMMANDS, CLICKS }

/** The inspector's clicks, while someone has it on: the adapter stops what the click would do. */
public interface Inspecting {
    /** Whether [player] has the inspector on; only their clicks are asked about and stopped. */
    public fun isInspecting(player: PlayerRef): Boolean

    /** [player] clicked or placed at [at] with the inspector on. */
    public fun inspect(player: PlayerRef, at: Spot)
}

/**
 * What xRewind needs from the server it runs on, which each adapter gives: players, the world
 * hooks, the block contents and items only it can read and write, chunks, and setting blocks for
 * a rollback. Everything that touches the world is called on the thread that owns the place
 * (its region's on Folia); the bytes functions on the writer, off the server's threads.
 */
public interface RewindServer {
    // ---- players ----
    public fun online(): List<PlayerRef>
    public fun isOnline(player: PlayerRef): Boolean
    public fun name(player: PlayerRef): String?
    public fun send(player: PlayerRef, message: Component)
    public fun actionBar(player: PlayerRef, message: Component)

    /** Where an online player is, on their own thread; `null` if they're not online. */
    public fun position(player: PlayerRef): Center?

    // ---- the world ----

    /** The world hooks, made at the logger's priority. */
    public fun hooks(): WorldHooks

    /** Listens for [kinds] of [events] until closed. */
    public fun listen(kinds: Set<Heard>, events: ServerEvents): AutoCloseable

    /** Starts hearing the inspector's clicks for [inspecting], until closed. */
    public fun inspector(inspecting: Inspecting): AutoCloseable

    /** The worlds' names. */
    public fun worlds(): List<String>

    public fun hasWorld(world: String): Boolean

    /** A block or item this server has, by name (`oak_log`, `minecraft:diamond`), as its id; `null` if there's none. */
    public fun known(name: String): String?

    /** Whether blocks of [type] hold contents worth keeping: containers and signs. Answered from a table made once. */
    public fun holds(type: String): Boolean

    /** What the block in [change] holds now (it's about to go), as bytes; `null` if nothing. On its thread. */
    public fun contents(change: BlockChange): ByteArray?

    /** A platform's stack of one as bytes, its own format. On the writer. */
    public fun itemBytes(item: ItemData): ByteArray?

    /** A plain stack of one of [id] as bytes; `null` if this server has no such item. Any thread. */
    public fun itemBytes(id: String): ByteArray?

    /** The item's type, from bytes [itemBytes] wrote: `minecraft:diamond`; `null` if this server can't read them. */
    public fun itemType(bytes: ByteArray): String?

    /** [state] as this server will set it (an older one's renamed properties dropped), or `null` if it can't be read. Any thread. */
    public fun readState(state: String): String?

    // ---- applying a rollback, on the place's thread ----

    /** Loads the chunk and keeps it loaded until [release]; settles `false` if it couldn't be. Any thread. */
    public fun load(world: String, chunkX: Int, chunkZ: Int): Pending<Boolean>

    public fun release(world: String, chunkX: Int, chunkZ: Int)

    /**
     * Sets the block at [at] to [state] without physics and puts [contents] in it, emptying what it
     * held first so nothing spills; returns what it was, for undo, or `null` if the world's gone.
     */
    public fun set(at: Spot, state: String, contents: ByteArray?): Previous?

    /**
     * Puts [delta] of the item in [item] into the container at [at] (taking out when negative);
     * returns how many really moved, signed as [delta].
     */
    public fun moveItems(at: Spot, item: ByteArray, delta: Int): Int

    /** The block at [at] now, for a preview that's cancelled. */
    public fun stateAt(at: Spot): String?

    /** Shows [player] the [blocks] (theirs only), on their thread. */
    public fun preview(player: PlayerRef, blocks: List<Shown>)
}
