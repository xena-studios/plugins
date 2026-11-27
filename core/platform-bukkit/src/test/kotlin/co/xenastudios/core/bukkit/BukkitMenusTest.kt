package co.xenastudios.core.bukkit

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.menu.Click
import co.xenastudios.core.common.menu.Icon
import co.xenastudios.core.common.menu.Menu
import co.xenastudios.core.common.menu.MenuHandler
import co.xenastudios.core.common.menu.MenuStack
import co.xenastudios.core.common.menu.MenuView
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.testing.RecordingLog
import org.bukkit.Material
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemFactory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Bukkit menus against Spigot 1.8.8's API: a menu's clicks are cancelled and passed on by
 * slot, a deposit's items leave it on a close, a quit and a death, and nothing is left behind.
 */
class BukkitMenusTest {
    private val log = RecordingLog()
    private val text = BukkitText.legacy(BukkitServer.detect({ false }, "1.8.8", "CraftBukkit", adventure = false), FakeServer.server)
    private lateinit var menus: BukkitMenus
    private val opened = ArrayList<Inventory>()
    private val given = ArrayList<ItemStack>()
    private lateinit var steve: Player
    private val ref: PlayerRef get() = steve.ref

    // A chest inventory as Bukkit keeps one: its holder, its slots.
    private fun inventory(holder: InventoryHolder?, size: Int): Inventory {
        val slots = arrayOfNulls<ItemStack>(size)
        return fake(
            "Inventory",
            mapOf(
                "getSize" to { _ -> size },
                "getHolder" to { _ -> holder },
                "getType" to { _ -> InventoryType.CHEST },
                "getItem" to { args -> slots[args[0] as Int] },
                "setItem" to { args -> slots[args[0] as Int] = args[1] as ItemStack?; null },
                "clear" to { _ -> slots.fill(null); null },
                "getContents" to { _ -> slots.copyOf() },
            ),
        )
    }

    private inner class View(private val top: Inventory) : InventoryView() {
        override fun getTopInventory(): Inventory = top
        override fun getBottomInventory(): Inventory = inventory(null, 36)
        override fun getPlayer(): HumanEntity = steve
        override fun getType(): InventoryType = InventoryType.CHEST
    }

    @BeforeTest
    fun setUp() {
        FakeServer.reset()
        FakeServer.inventories = { args -> inventory(args[0] as InventoryHolder?, args[1] as Int) }
        FakeServer.itemFactory = fake<ItemFactory>("ItemFactory", mapOf("getItemMeta" to { _ -> null }))
        val id = UUID.nameUUIDFromBytes("Steve".toByteArray())
        val playerInventory = fake<PlayerInventory>("PlayerInventory", mapOf("addItem" to { args -> given += (args[0] as Array<*>).map { it as ItemStack }; HashMap<Int, ItemStack>() }))
        steve = fake(
            "Steve",
            mapOf(
                "getName" to { _ -> "Steve" },
                "getUniqueId" to { _ -> id },
                "openInventory" to { args -> opened += args[0] as Inventory; null },
                "closeInventory" to { _ -> opened.lastOrNull()?.let { FakeServer.plugins.callEvent(InventoryCloseEvent(View(it))) }; null },
                "getInventory" to { _ -> playerInventory },
                "getGameMode" to { _ -> org.bukkit.GameMode.SURVIVAL },
                "isValid" to { _ -> true },
            ),
        )
        FakeServer.players = listOf(steve)
        menus = BukkitMenus(FakeServer.plugin(), text, BukkitServer.detect({ false }, "1.8.8", "CraftBukkit", adventure = false), log)
        menus.legacyItems = { id -> if (id == "minecraft:diamond") ItemStack(Material.DIAMOND) else null }
    }

    @AfterTest
    fun tearDown() {
        menus.closeAll()
    }

    private class Clicks : MenuHandler {
        val heard = ArrayList<Pair<Int, Click>>()
        var closed = 0

        override fun click(viewer: PlayerRef, view: MenuView, slot: Int, click: Click) {
            heard += slot to click
        }

        override fun closed(viewer: PlayerRef) {
            closed++
        }
    }

    private fun click(top: Inventory, raw: Int, type: ClickType = ClickType.LEFT): InventoryClickEvent {
        val event = InventoryClickEvent(View(top), InventoryType.SlotType.CONTAINER, raw, type, InventoryAction.PICKUP_ALL)
        FakeServer.plugins.callEvent(event)
        return event
    }

    @Test
    fun aMenuShowsItsIconsAndCancelsEveryClick() {
        val handler = Clicks()
        val view = menus.open(ref, Menu(Component.text("Shop"), 3, listOf(null, Icon("minecraft:diamond", 5), Icon("minecraft:unknown")), handler))
        val top = opened.single()
        assertEquals(27, top.size)
        assertEquals(Material.DIAMOND, top.getItem(1)?.type)
        assertEquals(5, top.getItem(1)?.amount)
        assertNull(top.getItem(2), "an id this server doesn't have shows nothing")
        assertTrue(view!!.open)

        assertTrue(click(top, 1, ClickType.SHIFT_RIGHT).isCancelled)
        // A click in the player's own inventory below is cancelled too, and not passed on.
        assertTrue(click(top, 40).isCancelled)
        assertEquals(listOf(1 to Click.SHIFT_RIGHT), handler.heard)
        assertEquals(1, top.getItem(1)?.let { 1 }, "the icon is still there")

        view.set(1, null)
        assertNull(top.getItem(1))
        view.close()
        assertFalse(view.open)
        assertEquals(1, handler.closed)
        assertNull(menus.viewing(ref))
    }

    @Test
    fun anotherInventoryIsLeftAlone() {
        val other = inventory(null, 27)
        assertFalse(click(other, 1).isCancelled)
    }

    @Test
    fun aDepositsItemsLeaveItAsItCloses() {
        val got = ArrayList<List<MenuStack>>()
        var wasOnline: Boolean? = null
        assertTrue(menus.deposit(ref, Component.text("Sell"), 2) { _, items, online -> got += items; wasOnline = online })
        val top = opened.single()
        assertFalse(click(top, 3).isCancelled, "a deposit takes items")
        top.setItem(3, ItemStack(Material.DIAMOND, 12))
        top.setItem(17, ItemStack(Material.STONE, 64))

        steve.closeInventory()
        assertEquals(listOf("legacy:DIAMOND:0" to 12, "legacy:STONE:0" to 64), got.single().map { it.item to it.amount })
        assertEquals(true, wasOnline)
        assertNull(top.getItem(3), "out of the chest before the handler hears of them")
        // Closing again (a quit after the close) hands nothing over twice.
        FakeServer.plugins.callEvent(PlayerQuitEvent(steve, "left"))
        assertEquals(1, got.size)
    }

    @Test
    fun aQuitOrADeathEmptiesADeposit() {
        var online: Boolean? = null
        menus.deposit(ref, Component.text("Sell"), 1) { _, items, isOnline -> online = isOnline; menus.give(ref, items.map { it.native }) }
        opened.single().setItem(0, ItemStack(Material.DIAMOND, 2))
        FakeServer.plugins.callEvent(PlayerQuitEvent(steve, "left"))
        assertEquals(false, online)
        assertEquals(listOf(Material.DIAMOND to 2), given.map { it.type to it.amount })

        given.clear()
        menus.deposit(ref, Component.text("Sell"), 1) { _, items, _ -> menus.give(ref, items.map { it.native }) }
        opened.last().setItem(0, ItemStack(Material.STONE, 3))
        val drops = ArrayList<ItemStack>()
        FakeServer.plugins.callEvent(PlayerDeathEvent(steve, drops, 0, "died"))
        // Given back while dying, they're dropped with the rest, not put in an inventory that's about to be cleared.
        assertEquals(listOf(Material.STONE to 3), drops.map { it.type to it.amount })
        assertTrue(given.isEmpty())
    }

    @Test
    fun closingEverythingHandsDepositsOver() {
        val got = ArrayList<MenuStack>()
        menus.deposit(ref, Component.text("Sell"), 1) { _, items, _ -> got += items }
        opened.single().setItem(4, ItemStack(Material.DIAMOND, 1))
        menus.closeAll()
        assertEquals(1, got.size)
        assertNull(menus.viewing(ref))
    }

    @Test
    fun aLongTitleIsCutForOldServers() {
        menus.open(ref, Menu(Component.text("A title that is far too long for a 1.8 chest"), 1, emptyList(), Clicks()))
        assertEquals(1, opened.size)
    }
}
