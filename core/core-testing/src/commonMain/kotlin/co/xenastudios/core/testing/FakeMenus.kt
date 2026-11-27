package co.xenastudios.core.testing

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.menu.Click
import co.xenastudios.core.common.menu.DepositHandler
import co.xenastudios.core.common.menu.Icon
import co.xenastudios.core.common.menu.Menu
import co.xenastudios.core.common.menu.MenuStack
import co.xenastudios.core.common.menu.MenuView
import co.xenastudios.core.common.menu.Menus
import co.xenastudios.core.common.text.Component

/**
 * [Menus] for tests: what each player sees, as icons a test reads and clicks ([click]), and
 * deposits a test fills and closes ([closeDeposit]). Stacks are [FakeStack]s, and what's given
 * back is in [given]. Players are online unless [offline] says otherwise.
 *
 * ```kotlin
 * val menus = FakeMenus()
 * plugin.openShop(steve)
 * menus.click(steve, 10)
 * assertEquals("minecraft:diamond", menus.icon(steve, 13)?.item)
 * ```
 */
public class FakeMenus : Menus {
    /** A stack in a fake deposit or a fake inventory: an id, an amount, and the item's data. */
    public data class FakeStack(val item: String, val amount: Int, val data: String? = null)

    private inner class Open(val viewer: PlayerRef, val shown: Menu?, val deposit: DepositHandler?, val title: Component) : MenuView {
        val icons: MutableList<Icon?> = shown?.icons?.toMutableList()?.also { while (it.size < shown.size) it += null } ?: ArrayList()
        var closed = false

        override val menu: Menu get() = checkNotNull(shown) { "a deposit has no menu" }

        override val open: Boolean get() = !closed && views[viewer] === this

        override fun set(slot: Int, icon: Icon?) {
            if (open && slot in icons.indices) icons[slot] = icon
        }

        override fun close() {
            if (open) end(this, online = true)
        }
    }

    private val views = HashMap<PlayerRef, Open>()

    /** Players a test has taken offline: nothing opens for them. */
    public val offline: MutableSet<PlayerRef> = HashSet()

    /** What [Menus.give] gave each player, in order. */
    public val given: MutableMap<PlayerRef, MutableList<FakeStack>> = HashMap()

    /** Whether [closeAll] has run. */
    public var closedAll: Boolean = false
        private set

    override fun open(viewer: PlayerRef, menu: Menu): MenuView? {
        if (viewer in offline) return null
        val open = Open(viewer, menu, null, menu.title)
        replace(viewer, open)
        return open
    }

    override fun deposit(viewer: PlayerRef, title: Component, rows: Int, handler: DepositHandler): Boolean {
        if (viewer in offline) return false
        replace(viewer, Open(viewer, null, handler, title))
        return true
    }

    private fun replace(viewer: PlayerRef, open: Open) {
        views[viewer]?.let { end(it, online = true) }
        views[viewer] = open
    }

    override fun give(viewer: PlayerRef, stacks: List<Any>): Boolean {
        if (viewer in offline) return false
        given.getOrPut(viewer) { ArrayList() } += stacks.filterIsInstance<FakeStack>()
        return true
    }

    override fun data(native: Any): String? = (native as? FakeStack)?.let { it.data ?: it.item }

    override fun viewing(viewer: PlayerRef): MenuView? = views[viewer]?.takeIf { it.shown != null && it.open }

    override fun closeAll() {
        closedAll = true
        for (open in views.values.toList()) end(open, online = open.viewer !in offline)
    }

    /** The title of what [viewer] has open, as plain text; `null` for nothing. */
    public fun title(viewer: PlayerRef): String? = views[viewer]?.title?.plain()

    /** The icon in [slot] of [viewer]'s open menu. */
    public fun icon(viewer: PlayerRef, slot: Int): Icon? = views[viewer]?.takeIf { it.shown != null }?.icons?.getOrNull(slot)

    /** Every icon of [viewer]'s open menu, by slot. */
    public fun icons(viewer: PlayerRef): List<Icon?> = views[viewer]?.takeIf { it.shown != null }?.icons.orEmpty()

    /** Whether [viewer] has a deposit open. */
    public fun hasDeposit(viewer: PlayerRef): Boolean = views[viewer]?.deposit != null

    /** Clicks [slot] of [viewer]'s open menu, as the platform would. */
    public fun click(viewer: PlayerRef, slot: Int, click: Click = Click.LEFT) {
        val open = views[viewer] ?: error("$viewer has no menu open")
        val menu = open.shown ?: error("$viewer has a deposit open, not a menu")
        menu.handler.click(viewer, open, slot, click)
    }

    /** The player closes what they have open; a deposit's [items] go to its handler. [online] false is a quit. */
    public fun close(viewer: PlayerRef, online: Boolean = true) {
        views[viewer]?.let { end(it, online) }
    }

    /** Closes [viewer]'s deposit with [items] in it, as a close (or with [online] false, a quit). */
    public fun closeDeposit(viewer: PlayerRef, items: List<FakeStack>, online: Boolean = true) {
        val open = views[viewer]?.takeIf { it.deposit != null } ?: error("$viewer has no deposit open")
        pending[open] = items
        end(open, online)
    }

    private val pending = HashMap<Open, List<FakeStack>>()

    private fun end(open: Open, online: Boolean) {
        if (open.closed) return
        open.closed = true
        if (views[open.viewer] === open) views.remove(open.viewer)
        val deposit = open.deposit
        if (deposit != null) {
            val items = pending.remove(open).orEmpty()
            deposit.closed(open.viewer, items.map { MenuStack(it.item, it.amount, it) }, online)
        } else {
            open.shown?.handler?.closed(open.viewer)
        }
    }
}
