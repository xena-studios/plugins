package co.xenastudios.xmarket

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.menu.Icon
import co.xenastudios.core.common.menu.Menus
import co.xenastudios.core.common.text.Component
import co.xenastudios.xmarket.api.ItemKey
import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.price.ItemSpec

/**
 * What xMarket needs from the server, as each platform's adapter does it. Players' items are
 * the platform's own objects (`native`), which shared code only passes back.
 *
 * Threads: [isOnline], [name], [online], [send], [hasPermission], [knownItems], [icon] and
 * [economy] may be called from any thread. Everything that reads or changes a player's
 * inventory or experience is called on that player's thread, and does it at once.
 */
public interface MarketServer {
    public val features: Features

    public fun isOnline(player: PlayerRef): Boolean

    public fun name(player: PlayerRef): String?

    public fun online(): List<PlayerRef>

    public fun send(player: PlayerRef, message: Component)

    public fun hasPermission(player: PlayerRef, node: String): Boolean

    /**
     * Whether [node] is given to [player] outright, by a permissions plugin: never by being an
     * operator, so an operator doesn't get every `xmarket.discount.<n>` at once.
     */
    public fun hasExplicit(player: PlayerRef, node: String): Boolean

    /** The chest menus. */
    public val menus: Menus

    /** The icon [spec] shows as, with its potion or enchantments where it has them; `null` if this server has no such item. */
    public fun icon(spec: ItemSpec): Icon?

    /** Every item id this server has (`minecraft:stone`), for `/shop audit`. */
    public fun knownItems(): Set<String>

    /** The item in [player]'s main hand, or `null` for none. */
    public fun hand(player: PlayerRef): Held?

    /** Every stack in [player]'s inventory that could be sold: the storage and hotbar, not armour or the off hand. */
    public fun inventory(player: PlayerRef): List<Held>

    /**
     * Takes each [Take.amount] from its slot, if the slot still holds what [Take.item] says, and
     * returns what was taken (to give back with [give] if the sale fails); `null` and nothing
     * taken if any slot changed.
     */
    public fun take(player: PlayerRef, takes: List<Take>): List<Any>?

    /** Gives back [natives] (as [take] or a deposit gave them): into the inventory, the rest dropped at the player's feet. */
    public fun give(player: PlayerRef, natives: List<Any>)

    /** How many of [spec] (up to [max]) fit in [player]'s inventory. */
    public fun room(player: PlayerRef, spec: ItemSpec, max: Int): Int

    /** Makes [amount] of [spec] and puts them in [player]'s inventory; returns how many fit (the rest aren't made). */
    public fun giveNew(player: PlayerRef, spec: ItemSpec, amount: Int): Int

    /** What [native] (one of this platform's items) is, as prices see it. */
    public fun key(native: Any): ItemKey

    /** A stack like [native], [amount] of it: the part of a deposit's stack that's sold or given back. */
    public fun copy(native: Any, amount: Int): Any

    /** Makes [amount] of [spec] and drops them at [player]'s feet: an item currency's pay that didn't fit. */
    public fun dropNew(player: PlayerRef, spec: ItemSpec, amount: Int)

    /**
     * Whether an item a player only renamed counts as plain ([ItemKey.plain]), as `sell.renamed`
     * says; one with lore or a custom model never does. Set on every reload.
     */
    public var renamedIsPlain: Boolean

    /** How many items matching [spec] [player] has. */
    public fun count(player: PlayerRef, spec: ItemSpec): Int

    /** Takes [amount] items matching [spec] from [player], all or none. */
    public fun takeItems(player: PlayerRef, spec: ItemSpec, amount: Int): Boolean

    /** [player]'s experience level. */
    public fun level(player: PlayerRef): Int

    /** Adds [change] (negative takes) to [player]'s level; `false` and nothing changed if they'd go below 0. */
    public fun addLevels(player: PlayerRef, change: Int): Boolean

    /** Runs [command] (no leading slash) as the console. */
    public fun console(command: String)

    /** [text] with its placeholders filled for [player], or `null` where there's no placeholder plugin. */
    public fun placeholders(player: PlayerRef, text: String): String?

    /**
     * Hears the next chat line [player] sends within [seconds] (keeping it out of chat) and calls
     * [answer] with it on their thread, or with `null` if they leave or time runs out.
     */
    public fun ask(player: PlayerRef, seconds: Int, answer: (String?) -> Unit)

    /** The economy money goes through, found as it's first needed; `null` if there's none. [which] is `auto`, `xmoney` or `vault`. */
    public fun economy(which: String): Economy?

    /** Tells [listener] about players joining and leaving, until the handle is closed. */
    public fun watchPlayers(listener: PlayerEvents): AutoCloseable
}

/** What a server can do, found once at startup. */
public class Features(
    /** Where a sold item's data is read from and written to, for `/xmarket debug`. */
    public val items: String = "",
    /** Whether placeholders can be read (PlaceholderAPI). */
    public val placeholders: Boolean = false,
)

/** A stack a player holds: its [slot] in their inventory, what it is, and the platform's own object. */
public class Held(public val slot: Int, public val item: ItemKey, public val native: Any)

/** [amount] of what's in [slot], which should be [item]. */
public class Take(public val slot: Int, public val item: ItemKey, public val amount: Int)

/** Players joining and leaving, as the adapter hears them. On the player's thread. */
public interface PlayerEvents {
    public fun joined(player: PlayerRef, name: String)
    public fun quit(player: PlayerRef)
}

/**
 * Money, through xMoney's API, any economy registered with Vault on Bukkit, or the Common
 * Economy API on Fabric. Exact amounts; an economy that works in doubles is given a double
 * from them at the edge.
 */
public interface Economy {
    /** For the console: `xMoney`, `Vault (EssentialsX Economy)`. */
    public val name: String

    /** Whether [player] has [amount], from memory where the economy allows. On the player's thread. */
    public fun has(player: PlayerRef, amount: Money): Boolean

    /** Takes [amount], settling with whether it was taken. [reason] is a few words, `xMarket: 16 diamond`. */
    public fun withdraw(player: PlayerRef, amount: Money, reason: String): Pending<Boolean>

    public fun deposit(player: PlayerRef, amount: Money, reason: String): Pending<Boolean>

    /** [amount] as the economy writes money, or `null` to write the number. */
    public fun format(amount: Money): String?
}
