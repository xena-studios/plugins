package co.xenastudios.core.common.menu

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.testing.FakeMenus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The menus' model, and the fake platform's menus that every plugin's tests click through. */
class MenusTest {
    private val steve = PlayerRef.of(Uuid.parse("00000000-0000-0000-0000-000000000001"))

    private class Recorder : MenuHandler {
        val clicks = ArrayList<Pair<Int, Click>>()
        var closed = 0

        override fun click(viewer: PlayerRef, view: MenuView, slot: Int, click: Click) {
            clicks += slot to click
            if (slot == 0) view.set(1, Icon("minecraft:emerald"))
        }

        override fun closed(viewer: PlayerRef) {
            closed++
        }
    }

    @Test
    fun aMenuHasOneToSixRows() {
        assertFailsWith<IllegalArgumentException> { Menu(Component.EMPTY, 0, emptyList(), Recorder()) }
        assertFailsWith<IllegalArgumentException> { Menu(Component.EMPTY, 7, emptyList(), Recorder()) }
        assertEquals(54, Menu(Component.EMPTY, 6, emptyList(), Recorder()).size)
    }

    @Test
    fun anIconsAmountIsWhatASlotShows() {
        assertEquals(64, Icon("minecraft:stone").times(200).amount)
        assertEquals(1, Icon("minecraft:stone").times(0).amount)
        assertTrue(Icon("minecraft:stone", glow = true).named(Component.text("x"), emptyList()).glow)
    }

    @Test
    fun clicksReachTheHandlerAndAViewChangesInPlace() {
        val menus = FakeMenus()
        val handler = Recorder()
        val view = menus.open(steve, Menu(Component.text("Shop"), 1, listOf(Icon("minecraft:diamond")), handler))!!
        menus.click(steve, 0, Click.SHIFT_LEFT)
        assertEquals(listOf(0 to Click.SHIFT_LEFT), handler.clicks)
        assertEquals("minecraft:emerald", menus.icon(steve, 1)?.item)
        assertEquals("Shop", menus.title(steve))
        // Another menu replaces it, and the first hears it closed.
        menus.open(steve, Menu(Component.text("Other"), 1, emptyList(), Recorder()))
        assertFalse(view.open)
        assertEquals(1, handler.closed)
    }

    @Test
    fun aDepositHandsItsItemsOverOnceAsItCloses() {
        val menus = FakeMenus()
        val got = ArrayList<Pair<String, Boolean>>()
        assertTrue(menus.deposit(steve, Component.text("Sell"), 3) { _, items, online -> items.forEach { got += it.item to online } })
        assertTrue(menus.hasDeposit(steve))
        menus.closeDeposit(steve, listOf(FakeMenus.FakeStack("minecraft:diamond", 3)), online = false)
        assertEquals(listOf("minecraft:diamond" to false), got)
        assertNull(menus.viewing(steve))
        menus.offline += steve
        assertNull(menus.open(steve, Menu(Component.EMPTY, 1, emptyList(), Recorder())))
        assertFalse(menus.give(steve, listOf(FakeMenus.FakeStack("minecraft:stone", 1))))
    }
}
