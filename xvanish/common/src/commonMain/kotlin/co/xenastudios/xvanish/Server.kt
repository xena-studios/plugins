package co.xenastudios.xvanish

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.text.Component

/**
 * What xVanish asks of the server it runs on: written by each adapter (Bukkit, Fabric). The
 * shared code decides who's hidden from whom and when; the adapter hides, shows and listens.
 * Each call says the thread it's on: a player's own is their entity scheduler's on Folia.
 */
public interface VanishServer {
    /** What this server can do, for `/xvanish debug` and the docs. */
    public val features: Features

    /** The players online now. Any thread. */
    public fun online(): Collection<PlayerRef>

    /** [player]'s name, if they're online. Any thread. */
    public fun name(player: PlayerRef): String?

    /** Whether online [player] has [node]. Any thread. */
    public fun hasPermission(player: PlayerRef, node: String): Boolean

    /** Sends [message] to online [player]. Any thread. */
    public fun send(player: PlayerRef, message: Component)

    /** Sends [message] to the console. Any thread. */
    public fun console(message: Component)

    /** Hides [target] from [viewer]: their entity and tab list entry. On [viewer]'s thread. */
    public fun hide(viewer: PlayerRef, target: PlayerRef)

    /** Shows [target] to [viewer] again. On [viewer]'s thread. */
    public fun show(viewer: PlayerRef, target: PlayerRef)

    /**
     * Where the server can (Folia, Paper and Spigot 1.19.3 on): makes [target] hidden from
     * everyone by default ([hidden]) or seen by everyone again, so a player hidden this way is
     * never shown to anyone for a moment, whichever thread each viewer is on. On [target]'s thread.
     * `false` where it can't, and then every viewer is told one by one ([hide]).
     */
    public fun hiddenByDefault(target: PlayerRef, hidden: Boolean): Boolean

    /**
     * What being vanished changes for [player] themselves ([Effects]), or `null` to undo all of
     * it as they show again or leave: the `vanished` metadata, flight, night vision, collisions,
     * sleeping, spawning, the locator bar and the boss bar. On [player]'s thread.
     */
    public fun effects(player: PlayerRef, effects: Effects?)

    /** Starts the listener for [switch] ([Switch]), until it's closed. Asks [view] from memory. */
    public fun listen(switch: Int, view: VanishView): AutoCloseable

    /** What a server has, found once at startup. */
    public class Features(
        /** How players are hidden here, for `/xvanish debug`: `Bukkit's hidePlayer`. */
        public val hiding: String,
        /** The switches this server has listeners for ([Switch]); the rest do nothing here. */
        public val switches: Set<Int>,
    )
}

/** The switches the adapters listen for, each only while its setting is on. */
public object Switch {
    /** Joins and quits: always on. Calls [VanishView.joining] and [VanishView.quitting]. */
    public const val PLAYERS: Int = 0
    public const val SERVER_LIST: Int = 1
    public const val DEATH_MESSAGES: Int = 2
    public const val ADVANCEMENT_MESSAGES: Int = 3
    public const val MOB_TARGETING: Int = 4
    public const val PICKUP: Int = 5
    public const val PHYSICAL: Int = 6
    public const val SCULK: Int = 7
    public const val PROJECTILES: Int = 8
    public const val RAIDS: Int = 9
    public const val SILENT_CONTAINERS: Int = 10
    public const val INVULNERABLE: Int = 11
    public const val CHAT: Int = 12

    /** Each switch's name, as `/xvanish debug` shows it. */
    public val NAMES: List<String> = listOf(
        "players", "server-list", "death-messages", "advancement-messages", "mob-targeting", "pickup", "physical",
        "sculk", "projectiles", "raids", "silent-containers", "invulnerable", "chat",
    )
}

/** What vanishing changes for the player themselves, from the config: see [VanishServer.effects]. */
public class Effects(
    public val fly: Boolean,
    public val nightVision: Boolean,
    public val noCollisions: Boolean,
    public val ignoreSleep: Boolean,
    public val noSpawning: Boolean,
    public val offLocatorBar: Boolean,
    /** The boss bar's title in the player's language, or `null` for none. */
    public val bossBar: Component?,
)

/** What the adapters' listeners ask, from memory, on whichever thread their event is on. */
public interface VanishView {
    /** Whether [player] is vanished here. */
    public fun isVanished(player: PlayerRef): Boolean

    /** Whether nobody's vanished: every listener returns at once. */
    public val nobody: Boolean

    /** Whether [viewer] can see vanished [target]. */
    public fun canSee(viewer: PlayerRef, target: PlayerRef): Boolean

    /** Whether vanished [player] picks things up (`/vanish pickup`). */
    public fun picksUp(player: PlayerRef): Boolean

    /**
     * [player] is joining, on their thread, before anyone's told: hides them (and the vanished
     * from them) as they should be. Returns whether they're vanished, so the adapter leaves out
     * their join message.
     */
    public fun joining(player: PlayerRef, name: String): Boolean

    /** [player] is leaving, on their thread. Returns whether they were vanished, so the adapter leaves out their quit message. */
    public fun quitting(player: PlayerRef): Boolean

    /** Vanished [player] says [message] in public chat: whether it may go (chat.confirm-public). Any thread. */
    public fun mayChat(player: PlayerRef, message: String): Boolean
}
