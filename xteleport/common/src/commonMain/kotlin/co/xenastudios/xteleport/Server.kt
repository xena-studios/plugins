package co.xenastudios.xteleport

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.text.Component

/**
 * What xTeleport needs from the server, as each platform's adapter does it: the one thing its
 * shared code can't do itself. Each adapter picks how once, at startup, from what its server has
 * ([features]), never per call.
 *
 * Threads: [isOnline], [name], [send], [online], [hasPermission], [homeNumbers], [worlds] and
 * [world] may be called from any thread. [location], [bed], [actionBar] and [teleport] are
 * called on the player's thread, [blocks] on the thread that owns the column it reads, and
 * [loadChunk] anywhere (it never blocks the caller where the server can load in the background).
 */
public interface TeleportServer {
    /** What this server can do, found once at startup. */
    public val features: Features

    public fun isOnline(player: PlayerRef): Boolean

    /** [player]'s name, if they're online. */
    public fun name(player: PlayerRef): String?

    /** Every online player. */
    public fun online(): List<PlayerRef>

    /** Sends [message] to [player], if they're online. */
    public fun send(player: PlayerRef, message: Component)

    /** Shows [message] on [player]'s action bar, where the server has one ([Features.actionBar]). */
    public fun actionBar(player: PlayerRef, message: Component)

    /** Whether online [player] has [node] (their permissions plugin's answer, or operator for a node nobody set). */
    public fun hasPermission(player: PlayerRef, node: String): Boolean

    /** The highest `<n>` of the `xteleport.homes.<n>` permissions online [player] has, or 0. */
    public fun homeNumbers(player: PlayerRef): Int

    /** Where online [player] is, or `null`. */
    public fun location(player: PlayerRef): Location?

    /** The block of [player]'s bed or respawn anchor as they set it, without loading its chunk; `null` if they have none. */
    public fun bed(player: PlayerRef): Location?

    /**
     * Where to stand on the bed or respawn anchor at [block], or `null` if it isn't one any more
     * (broken since it was set). On the thread that owns the block, once its chunk is loaded.
     */
    public fun standOn(block: Location): Location?

    /** The worlds that are loaded, by the names commands and the config use. */
    public fun worlds(): List<WorldRef>

    /** What's known about the loaded world called [name] (its own name, or an alias the platform accepts), or `null` if none is. */
    public fun world(name: String): WorldInfo?

    /**
     * Loads the chunk at [chunkX], [chunkZ] in [world] and settles with whether it's there now:
     * in the background where the server can (Paper and Folia), otherwise on the server thread a
     * few at a time, spread over ticks. With [generate] false, a chunk never generated settles
     * `false` rather than being made. Settles on any thread; never fails.
     */
    public fun loadChunk(world: WorldRef, chunkX: Int, chunkZ: Int, generate: Boolean): Pending<Boolean>

    /** [world]'s blocks, for reading on the thread that owns them, or `null` if it isn't loaded. */
    public fun blocks(world: WorldRef): Blocks?

    /**
     * Teleports [player] to [to] as a command does (so `/back` hears it), without blocking where
     * the server can, then calls [done] on the player's thread with whether they arrived; with
     * `false` at once if they're offline or the world isn't loaded.
     */
    public fun teleport(player: PlayerRef, to: Location, done: (Boolean) -> Unit)

    /** Tells [listener] about players joining and leaving, until the handle is closed. */
    public fun watchPlayers(listener: PlayerEvents): AutoCloseable

    /**
     * Lets [listener] place players as they join and respawn, until the handle is closed: only
     * while one of the `spawn` settings is on.
     */
    public fun watchSpawn(listener: SpawnEvents): AutoCloseable

    /** Calls [hurt] (on the player's thread) each time a player takes damage, until the handle is closed: only while a warmup that damage cancels is running. */
    public fun watchDamage(hurt: (PlayerRef) -> Unit): AutoCloseable

    /** The economy teleport costs are taken through, found as a cost is first needed: xMoney's, or Vault's on Bukkit; `null` if there's none. */
    public fun economy(): Economy?
}

/** What a server can do, found once at startup: anything missing says what it would need, in English. */
public class Features(
    /** Whether [TeleportServer.actionBar] shows anything here (Spigot 1.8.8 has no action bar without NMS). */
    public val actionBar: Boolean = true,
    /** How chunks are loaded and players moved, for `/xteleport debug`: `in the background` or `on the server thread, one a tick`. */
    public val loading: String = "in the background",
    /** Whether a player can be placed at the spawn as they join, before they're in the world (Paper's spawn-location event); otherwise they're teleported just after. */
    public val spawnOnJoin: Boolean = true,
)

/** What's known about a loaded world, read once per use from the server's own values. */
public class WorldInfo(
    public val ref: WorldRef,
    public val minY: Int,
    /** One past the highest block. */
    public val maxY: Int,
    /** Where the Nether's roof starts; [maxY] elsewhere. */
    public val logicalHeight: Int,
    /** A roof over everything, as the Nether has. */
    public val hasCeiling: Boolean,
    /** The End, whose main island `/rtp` stays off. */
    public val end: Boolean,
    public val spawn: Location,
    public val borderCenterX: Double,
    public val borderCenterZ: Double,
    /** The border's side, in blocks. */
    public val borderSize: Double,
) {
    /** Whether [x], [z] is inside the world border, [margin] blocks in. */
    public fun insideBorder(x: Double, z: Double, margin: Double = 0.0): Boolean {
        val half = borderSize / 2 - margin
        return x >= borderCenterX - half && x <= borderCenterX + half && z >= borderCenterZ - half && z <= borderCenterZ + half
    }
}

/**
 * A world's blocks, read on the thread that owns them. Each block is a kind: the flags in
 * [BlockKind], which the adapter works out once per block type, so the shared code never names a
 * server's materials.
 */
public interface Blocks {
    /** The block at [x], [y], [z]'s [BlockKind] flags; [BlockKind.AIR] outside the world. */
    public fun kind(x: Int, y: Int, z: Int): Int

    /** The y of the highest block that isn't air in the column, or [Int.MIN_VALUE] for an empty one (the End's void). */
    public fun highest(x: Int, z: Int): Int

    /** The biome at the block, as Minecraft names it without its namespace: `deep_ocean`. */
    public fun biome(x: Int, y: Int, z: Int): String
}

/** A block's kind, as flags: what safe arrival and `/rtp` need to know about it. */
public object BlockKind {
    /** Nothing there that stops a player or hurts them. */
    public const val AIR: Int = 0

    /** Something stood on or walked into: Bukkit's `isSolid`. */
    public const val SOLID: Int = 1

    /** A full block: a player inside it suffocates. */
    public const val FULL: Int = 2

    /** Water, or anything waterlogged, kelp or seagrass: soft to fall into. */
    public const val WATER: Int = 4

    /** Hurts to be in or stand on: lava, fire, magma, cactus, berry bushes, wither roses, powder snow, dripstone. */
    public const val HURTS: Int = 8

    /** A ladder, vine or scaffolding: something to hold on to on the way down. */
    public const val CLIMBABLE: Int = 16

    /** Leaves, which `/rtp` never lands on. */
    public const val LEAVES: Int = 32

    /** Bedrock, which `/rtp` never lands on (the Nether's roof, the End's pillars). */
    public const val BEDROCK: Int = 64

    /** Soul sand or mud: a full block 0.875 high, so a player stands in its block. */
    public const val SHORT: Int = 128

    /** Farmland or a dirt path: 0.9375 high. */
    public const val SHORTER: Int = 256

    /** A liquid of any kind (water or lava), which a player can't stand on. */
    public const val LIQUID: Int = 512

    /** Whether [kind] has [flag]. */
    public fun has(kind: Int, flag: Int): Boolean = kind and flag != 0
}

/** Players joining and leaving, as the adapter hears them. Called on the player's thread. */
public interface PlayerEvents {
    /** [player] is in the world. */
    public fun joined(player: PlayerRef, name: String)

    /** [player] left. */
    public fun quit(player: PlayerRef)
}

/** Where players appear as they join and respawn. */
public interface SpawnEvents {
    /**
     * Where [player] should appear as they join, or `null` to leave them where the server puts them.
     * Called before they're in the world where the server allows ([Features.spawnOnJoin]), so it
     * reads only memory, and may be called off the server's threads.
     */
    public fun spawnOnJoin(player: PlayerRef, firstJoin: Boolean): Location?

    /** Where [player] respawns after dying without a bed or anchor, or `null` for where the game sends them. On their thread. */
    public fun respawn(player: PlayerRef): Location?
}

/**
 * The economy teleport costs go through: xMoney's API, or on Bukkit any economy registered with
 * Vault. Amounts are the config's numbers.
 */
public interface Economy {
    /** Its name, for the console: `xMoney`, `Vault (EssentialsX Economy)`. */
    public val name: String

    /** Whether [player] has [amount]. From memory where the economy allows; on the player's thread. */
    public fun has(player: PlayerRef, amount: Double): Boolean

    /** Takes [amount] from [player], settling with whether it was taken. */
    public fun withdraw(player: PlayerRef, amount: Double, reason: String): Pending<Boolean>

    /** Gives [amount] back to [player], settling with whether it was given. */
    public fun deposit(player: PlayerRef, amount: Double, reason: String): Pending<Boolean>

    /** [amount] as the economy writes money, or `null` to write the number. */
    public fun format(amount: Double): String?
}
