package co.xenastudios.core.common.menu

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.text.Component

/*
 * Chest menus, the same on every platform (DECISIONS.md, "Menus in the core"): a plugin's shared
 * code says what a menu shows and what a click does, and each platform's [Menus] draws it as the
 * server's own inventory. Two kinds:
 *
 * - A [Menu] shows [Icon]s, which aren't items anyone can take: every click and drag in it, the
 *   player's own inventory below included, is cancelled and handed to the menu's [MenuHandler].
 *   Nothing in it is real, so nothing in it can be duplicated.
 * - A deposit ([Menus.deposit]) is a chest players put their own items into. What's in it when it
 *   closes, for whatever reason (closed, another menu opened, quit, kicked, died, the plugin
 *   stopping), is taken out of it and handed to its [DepositHandler] at once, so the items are
 *   always in exactly one place.
 *
 * Nothing here is made until a plugin opens its first menu: the platform's listener is
 * registered then, not as the server starts.
 */

/**
 * What a slot of a [Menu] shows: an item by its namespaced id (`minecraft:diamond`), with the
 * menu's own name and lore. [data] is the platform's full text of an item (as [Menus.data]
 * wrote it: Bukkit's serialised item, Fabric's SNBT), for an item that's more
 * than its id (an enchanted book, a head, another plugin's item); then [item] is a fallback for a
 * server that can't read it. A `null` [name] keeps the item's own name, in each player's language.
 */
public class Icon(
    public val item: String,
    public val amount: Int = 1,
    public val name: Component? = null,
    public val lore: List<Component> = emptyList(),
    /** Whether it shines as an enchanted item does. */
    public val glow: Boolean = false,
    public val data: String? = null,
) {
    /** The same icon with another [name] and [lore]. */
    public fun named(name: Component?, lore: List<Component>): Icon = Icon(item, amount, name, lore, glow, data)

    /** The same icon, [amount] of it (1 to 64, as a slot shows). */
    public fun times(amount: Int): Icon = Icon(item, amount.coerceIn(1, 64), name, lore, glow, data)
}

/** How a slot was clicked, as every platform tells it. */
public enum class Click {
    LEFT,
    RIGHT,
    SHIFT_LEFT,
    SHIFT_RIGHT,
    MIDDLE,
    /** The drop key (Q), with or without control. */
    DROP,
    /** A number key (1 to 9) or the off-hand key over the slot. */
    NUMBER_KEY,
    DOUBLE,
    OTHER,
    ;

    /** Whether shift was held. */
    public val shift: Boolean get() = this == SHIFT_LEFT || this == SHIFT_RIGHT

    /** Whether it was a right click, with or without shift. */
    public val right: Boolean get() = this == RIGHT || this == SHIFT_RIGHT
}

/** What a [Menu] does. Called on the viewer's thread; nothing here may block. */
public interface MenuHandler {
    /**
     * [viewer] clicked [slot] of the menu (0 is the top left, rows of 9). Clicks in the
     * player's own inventory below are cancelled and not passed on.
     */
    public fun click(viewer: PlayerRef, view: MenuView, slot: Int, click: Click)

    /** The menu closed for [viewer]: closed, replaced by another, or they left. */
    public fun closed(viewer: PlayerRef) {}
}

/** A chest menu of [rows] rows (1 to 6) of nine, with [icons] by slot (`null` for an empty slot), titled [title]. */
public class Menu(
    public val title: Component,
    public val rows: Int,
    public val icons: List<Icon?>,
    public val handler: MenuHandler,
) {
    init {
        require(rows in 1..6) { "a menu has 1 to 6 rows, not $rows" }
    }

    /** How many slots it has. */
    public val size: Int get() = rows * 9
}

/** One player's open [Menu]. */
public interface MenuView {
    /** The menu as it was opened. */
    public val menu: Menu

    /** Whether it's still the menu the player sees. */
    public val open: Boolean

    /** Shows [icon] in [slot] from now on, for this viewer alone. On the viewer's thread. */
    public fun set(slot: Int, icon: Icon?)

    /** Closes it, if it's still open. On the viewer's thread. */
    public fun close()
}

/**
 * An item a player put into a deposit ([Menus.deposit]): its id and how many, and the
 * platform's own item as [native] (Bukkit's `ItemStack`, Fabric's), which a plugin's adapter
 * reads for the rest and gives back if it isn't wanted.
 */
public class MenuStack(public val item: String, public val amount: Int, public val native: Any)

/** What happens to a deposit's items as it closes. */
public fun interface DepositHandler {
    /**
     * The deposit [viewer] had open has closed, and [items] were in it: they're out of it now,
     * so this decides where each goes (it gives back what it doesn't keep: [Menus.give]).
     * [online] is `false` when they left (quit or kicked), so anything given back goes where
     * the platform keeps a leaving player's items: their inventory, saved with them. On the
     * viewer's thread, or the platform's own as the plugin stops.
     */
    public fun closed(viewer: PlayerRef, items: List<MenuStack>, online: Boolean)
}

/** A platform's menus. Each call on the viewer's thread, unless it says otherwise. */
public interface Menus {
    /** Opens [menu] for [viewer], replacing whatever they had open; `null` if they aren't online. */
    public fun open(viewer: PlayerRef, menu: Menu): MenuView?

    /** Opens an empty chest of [rows] rows titled [title], for [viewer] to put items in; `false` if they aren't online. */
    public fun deposit(viewer: PlayerRef, title: Component, rows: Int, handler: DepositHandler): Boolean

    /**
     * Gives [stacks] (each a [MenuStack.native] of this platform) to [viewer]: into their
     * inventory, the rest dropped at their feet. `false` if they aren't online, and nothing given.
     */
    public fun give(viewer: PlayerRef, stacks: List<Any>): Boolean

    /**
     * The platform's full text of [native] (one of its own items, a [MenuStack.native]), with
     * everything but the amount, as an [Icon]'s `data` reads it back: Bukkit's serialised item,
     * Fabric's SNBT. Plain text, so a plugin can keep it in its files; `null` if it can't be written.
     */
    public fun data(native: Any): String?

    /** The menu [viewer] has open, if it's one of this plugin's. Any thread. */
    public fun viewing(viewer: PlayerRef): MenuView?

    /**
     * Closes every menu and deposit of this plugin, each deposit's items handed to its handler,
     * and stops listening: as the plugin stops. On the platform's main or global thread.
     */
    public fun closeAll()
}
