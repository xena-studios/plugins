package co.xenastudios.xutilities

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.config.Sound

/**
 * What xUtilities does to players, as each platform's adapter does it: the one thing its shared
 * code can't do itself. Each adapter picks how once, at startup, from what its server has
 * ([features]), and never per call.
 *
 * Everything that touches a player is called on that player's thread (the shared code
 * schedules it there); [send], [isOnline] and [name] may be called from any thread.
 */
public interface Server {
    /** What this server can do, found once at startup. */
    public val features: Features

    /** Whether [player] is online. Any thread. */
    public fun isOnline(player: PlayerRef): Boolean

    /** [player]'s name, if they're online. Any thread. */
    public fun name(player: PlayerRef): String?

    /** Sends [message] to [player], if they're online. Any thread. */
    public fun send(player: PlayerRef, message: Component)

    /** Where [player] is, if they're online. */
    public fun location(player: PlayerRef): Location?

    /** Whether [world] is loaded, so a `/back` spot in it can be gone to. */
    public fun isLoaded(world: WorldRef): Boolean

    public fun setGameMode(player: PlayerRef, mode: GameMode)

    /** Turns [player]'s flight on or off; off also stops them flying. Returns the new state. */
    public fun toggleFlight(player: PlayerRef): Boolean

    /** Whether [player] is flying now, which decides which speed `/speed` sets. */
    public fun isFlying(player: PlayerRef): Boolean

    /** Sets [player]'s fly speed or walk speed, on Minecraft's 0 to 1. */
    public fun setSpeed(player: PlayerRef, flying: Boolean, speed: Float)

    /** Turns [player] to face [yaw] and [pitch], where they stand. */
    public fun setRotation(player: PlayerRef, yaw: Float, pitch: Float)

    /** [player]'s latency in milliseconds; only called when [Features.ping] is `null`. */
    public fun ping(player: PlayerRef): Int

    /**
     * Restores [player]'s health and, as asked, hunger, fire and harmful effects. `false` if
     * they're dead, so there was nothing to heal.
     */
    public fun heal(player: PlayerRef, hunger: Boolean, fire: Boolean, harmfulEffects: Boolean): Boolean

    /** Restores hunger and saturation. */
    public fun feed(player: PlayerRef)

    /** Repairs the held item, or with [all] every item; returns how many it repaired. */
    public fun repair(player: PlayerRef, all: Boolean): Int

    /** Empties the inventory; with [armor], the armour and off-hand too. */
    public fun clear(player: PlayerRef, armor: Boolean)

    /**
     * Opens [owner]'s Ender Chest for [viewer]. `false` when it can't be shared safely (on Folia,
     * the owner is in another region), and nothing opened.
     */
    public fun openEnderChest(viewer: PlayerRef, owner: PlayerRef): Boolean

    /** Opens [station]'s menu with no block; only called when [Features.stations] has no reason for it. */
    public fun openStation(player: PlayerRef, station: Station)

    /** Opens an empty four-row menu called [title] whose contents are gone when it closes. */
    public fun openTrash(player: PlayerRef, title: Component)

    /** Plays [sound] to [player], where they stand. */
    public fun playSound(player: PlayerRef, sound: Sound)

    /** Whether the server knows [sound], or `null` if it can't tell: for a warning about a typo. */
    public fun knowsSound(sound: Sound): Boolean?

    /** The column [player] stands in, for `/top`. */
    public fun column(player: PlayerRef): Column?

    /**
     * Teleports [player] to [to] as a command does (so `/back` hears it where the server says),
     * then calls [done] on the player's thread with whether it happened.
     */
    public fun teleport(player: PlayerRef, to: Location, done: (Boolean) -> Unit)

    /**
     * Calls [listener] about the moves `/back` remembers, from now until the returned handle is
     * closed: `/back`'s listener exists only while `/back` is on.
     */
    public fun watch(listener: MoveListener): AutoCloseable
}

/** What a server can do, found once at startup: anything missing says what it would need, in English. */
public class Features(
    /** Each workstation this server can't open without its block, with what it would need. */
    public val stations: Map<Station, String> = emptyMap(),
    /** What `/ping` would need here, or `null` if it works. */
    public val ping: String? = null,
    /**
     * Whether the server tells `/back` about every teleport (Bukkit's teleport event). Where it
     * doesn't (Fabric has no teleport event without a mixin), `/back` hears deaths, xUtilities'
     * own teleports and other mods' through the API.
     */
    public val teleportEvents: Boolean = true,
)

/** Where `/back` hears about players moving. Called on the player's thread. */
public interface MoveListener {
    /** [player] was teleported by a command or a plugin, from [from]. */
    public fun teleported(player: PlayerRef, from: Location)

    /** [player] died, at [at]. */
    public fun died(player: PlayerRef, at: Location)

    /** [player] left the server. */
    public fun quit(player: PlayerRef)
}

/** Minecraft's gamemodes, by the number Minecraft gives each. */
public enum class GameMode(public val id: Int) {
    SURVIVAL(0),
    CREATIVE(1),
    ADVENTURE(2),
    SPECTATOR(3),
    ;

    /** Its name as commands write it: `creative`. */
    public val label: String get() = name.lowercase()
}

/** The workstation menus xUtilities opens without their block, by command. */
public enum class Station(public val command: String) {
    ANVIL("anvil"),
    CRAFT("craft"),
    GRINDER("grinder"),
    LOOM("loom"),
    SMITH("smith"),
    STONECUTTER("stonecutter"),
    CARTOGRAPHY("cartography"),
}

/**
 * The blocks above and below a player, for `/top`: the adapter reads its world; [Top] decides.
 * [top] is the highest y to look at; [hasCeiling] is a world with a roof, such as the Nether.
 */
public interface Column {
    public val top: Int
    public val hasCeiling: Boolean

    /** Whether the block at [y] can be stood on. */
    public fun solid(y: Int): Boolean

    /** Whether a player fits through the block at [y] (air, grass, an open door). */
    public fun passable(y: Int): Boolean

    /** The block's type at [y], as the server names it (`LAVA`, `minecraft:lava`), for [Top.UNSAFE]. */
    public fun type(y: Int): String
}

/** `/top`'s search, the same on every platform. */
public object Top {
    /** Blocks that hurt to stand on, by name on every version (and the names before 1.13). */
    public val UNSAFE: Set<String> = setOf(
        "lava", "stationary_lava", "fire", "soul_fire", "magma_block", "magma", "cactus", "campfire", "soul_campfire",
        "sweet_berry_bush", "powder_snow", "wither_rose", "pointed_dripstone",
    )

    /**
     * The y of the block to stand on above [from] (the player's block y), or `null` if there's
     * none that's safe. In a world with a roof the highest solid block is the roof, so the search
     * is from below it: the highest solid block with room to stand above it.
     */
    public fun find(column: Column, from: Int): Int? {
        var y = if (column.hasCeiling) column.top - 3 else column.top
        while (y > from) {
            if (column.solid(y)) {
                if (column.hasCeiling && !(column.passable(y + 1) && column.passable(y + 2))) {
                    y--
                    continue
                }
                if (unsafe(column.type(y))) return null
                return y
            }
            y--
        }
        return null
    }

    private fun unsafe(type: String): Boolean = type.substringAfter(':').lowercase() in UNSAFE
}
