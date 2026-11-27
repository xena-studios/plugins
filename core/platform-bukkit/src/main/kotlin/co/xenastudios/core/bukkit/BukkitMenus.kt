package co.xenastudios.core.bukkit

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.bukkit.modern.Glint
import co.xenastudios.core.common.menu.Click
import co.xenastudios.core.common.menu.DepositHandler
import co.xenastudios.core.common.menu.Icon
import co.xenastudios.core.common.menu.Menu
import co.xenastudios.core.common.menu.MenuStack
import co.xenastudios.core.common.menu.MenuView
import co.xenastudios.core.common.menu.Menus
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.text.Component
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [Menus] on Bukkit, 1.8.8 to the newest Paper and Folia: each menu a chest inventory whose
 * holder is the menu's own, so a click is told apart from every other inventory by its holder,
 * with no title compared. Made by [BukkitPlatform.menus] the first time a plugin asks, which
 * registers its listener then; [closeAll] (from [BukkitPlatform.close]) unregisters it.
 *
 * - A menu's every click and drag is cancelled, the player's own inventory's included (a
 *   shift-click there would move an item into the menu), and passed to its handler only for the
 *   menu's own slots. Cancelled at the highest priority, after any other plugin had its say.
 * - A deposit lets everything through. Its items leave it as it closes, for any reason: the
 *   close (and Bukkit closes it on a quit, a kick and a death), a quit or a death it missed, and
 *   [closeAll]; the inventory is cleared before its handler hears of them, so they're never in
 *   two places. Given back during a death ([give]), they join the death's drops (or stay, with
 *   `keepInventory`), since the inventory is cleared after the death event.
 *
 * Never calls `InventoryView`, which became an interface in 1.21 and so can't be called from
 * code built against 1.8.8's API: the top inventory is the event's own, and the slot its raw one.
 */
public class BukkitMenus internal constructor(
    private val plugin: Plugin,
    private val text: BukkitText,
    private val version: BukkitServer,
    private val log: Log,
) : Menus {
    private val server: Server = plugin.server
    private val views = ConcurrentHashMap<UUID, Open>()
    private val dying = ConcurrentHashMap<UUID, PlayerDeathEvent>()

    @Volatile
    private var listening: Listener? = null

    /**
     * How an icon's id becomes an item where Bukkit's names aren't Minecraft's: before 1.13, a
     * plugin that knows the old names and data values sets this (`minecraft:red_wool` is
     * `WOOL` with data 14). From 1.13 the id is the material's own key, and this isn't asked.
     */
    @Volatile
    public var legacyItems: ((String) -> ItemStack?)? = null

    private val modern = version.atLeast(1, 13)
    private val glint = version.atLeast(1, 20, 5)

    // One open menu or deposit: the inventory, its holder's.
    private inner class Open(val viewer: PlayerRef, val shown: Menu?, val deposit: DepositHandler?) : InventoryHolder, MenuView {
        lateinit var top: Inventory

        @Volatile
        var closed = false

        override fun getInventory(): Inventory = top

        override val menu: Menu get() = checkNotNull(shown) { "a deposit has no menu" }

        override val open: Boolean get() = !closed && views[viewer.javaUuid] === this

        override fun set(slot: Int, icon: Icon?) {
            if (!open || slot !in 0 until top.size) return
            top.setItem(slot, icon?.let(::item))
        }

        override fun close() {
            if (open) server.getPlayer(viewer.javaUuid)?.closeInventory()
        }
    }

    override fun open(viewer: PlayerRef, menu: Menu): MenuView? {
        val player = viewer.online(server) ?: return null
        listen()
        val open = Open(viewer, menu, null)
        val inventory = server.createInventory(open, menu.size, title(menu.title))
        open.top = inventory
        for ((slot, icon) in menu.icons.withIndex()) {
            if (slot >= menu.size) break
            if (icon != null) inventory.setItem(slot, item(icon))
        }
        show(player, open)
        return open
    }

    override fun deposit(viewer: PlayerRef, title: Component, rows: Int, handler: DepositHandler): Boolean {
        require(rows in 1..6) { "a deposit has 1 to 6 rows, not $rows" }
        val player = viewer.online(server) ?: return false
        listen()
        val open = Open(viewer, null, handler)
        open.top = server.createInventory(open, rows * 9, title(title))
        show(player, open)
        return true
    }

    // The old view is closed by Bukkit as the new one opens (its close event finds it replaced).
    private fun show(player: Player, open: Open) {
        val previous = views.put(player.uniqueId, open)
        if (previous != null && previous.deposit != null) finish(previous, online = true)
        player.openInventory(open.top)
    }

    override fun give(viewer: PlayerRef, stacks: List<Any>): Boolean {
        val player = viewer.online(server) ?: return false
        val items = stacks.filterIsInstance<ItemStack>().filter { it.type != Material.AIR && it.amount > 0 }
        if (items.isEmpty()) return true
        val death = dying[player.uniqueId]
        if (death != null && !death.keepInventory) {
            death.drops.addAll(items)
            return true
        }
        val left = player.inventory.addItem(*items.toTypedArray())
        for (rest in left.values) player.world.dropItemNaturally(player.location, rest)
        return true
    }

    override fun data(native: Any): String? {
        val stack = (native as? ItemStack)?.clone() ?: return null
        return try {
            stack.amount = 1
            val yaml = YamlConfiguration()
            yaml.set(ITEM, stack)
            yaml.saveToString()
        } catch (e: Exception) {
            null
        }
    }

    override fun viewing(viewer: PlayerRef): MenuView? = views[viewer.javaUuid]?.takeIf { it.shown != null && it.open }

    override fun closeAll() {
        for (open in views.values.toList()) {
            val player = server.getPlayer(open.viewer.javaUuid)
            if (open.deposit != null) finish(open, online = player != null)
            views.remove(open.viewer.javaUuid, open)
            open.closed = true
            try {
                player?.closeInventory()
            } catch (e: Exception) {
                log.warn("Couldn't close ${player?.name}'s menu: $e")
            }
        }
        listening?.let { HandlerList.unregisterAll(it) }
        listening = null
    }

    /** The item [icon] shows: its full data where it has some and this server reads it, else its id. */
    public fun item(icon: Icon): ItemStack? {
        val base = icon.data?.let(::read) ?: material(icon.item) ?: return null
        base.amount = icon.amount.coerceIn(1, 64)
        val meta = base.itemMeta ?: return base
        icon.name?.let { meta.setDisplayName(RESET + text.legacy.draw(it)) }
        if (icon.lore.isNotEmpty()) meta.lore = icon.lore.map { RESET + text.legacy.draw(it) }
        if (icon.glow) glow(meta)
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        base.itemMeta = meta
        return base
    }

    private fun material(id: String): ItemStack? {
        if (!modern) return legacyItems?.invoke(id)
        val material = Material.matchMaterial(id) ?: return null
        return ItemStack(material)
    }

    private fun read(data: String): ItemStack? = try {
        val yaml = YamlConfiguration()
        yaml.loadFromString(data)
        yaml.getItemStack(ITEM)?.takeIf { it.type != Material.AIR }
    } catch (e: Exception) {
        null
    }

    private fun glow(meta: org.bukkit.inventory.meta.ItemMeta) {
        if (glint) return Glint.on(meta)
        try {
            @Suppress("DEPRECATION")
            val enchantment = Enchantment.getByName("DURABILITY") ?: Enchantment.getByName("UNBREAKING") ?: return
            meta.addEnchant(enchantment, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        } catch (e: Throwable) {
            // A server without the old names: the icon shows without its shine.
        }
    }

    // A chest's title: 32 characters at most before 1.9, which refuses a longer one.
    private fun title(title: Component): String {
        val drawn = text.legacy.draw(title)
        return if (version.atLeast(1, 9) || drawn.length <= 32) drawn else drawn.substring(0, if (drawn[31] == '§') 31 else 32)
    }

    private fun listen() {
        if (listening != null) return
        synchronized(this) {
            if (listening != null) return
            val listener = object : Listener {}
            val manager = server.pluginManager
            // Clicks and drags cancelled last, after any other plugin had its say; a close heard
            // once it's settled; a death and a quit first, before anything reads the inventory.
            manager.registerEvent(InventoryClickEvent::class.java, listener, EventPriority.HIGHEST, Events(CLICK), plugin)
            manager.registerEvent(InventoryDragEvent::class.java, listener, EventPriority.HIGHEST, Events(DRAG), plugin)
            manager.registerEvent(InventoryCloseEvent::class.java, listener, EventPriority.MONITOR, Events(CLOSE), plugin)
            manager.registerEvent(PlayerDeathEvent::class.java, listener, EventPriority.LOWEST, Events(DEATH), plugin)
            manager.registerEvent(PlayerQuitEvent::class.java, listener, EventPriority.LOWEST, Events(QUIT), plugin)
            listening = listener
        }
    }

    // A view's end: forgotten, and a deposit's items out of it and to its handler.
    private fun ended(open: Open, online: Boolean) {
        if (open.closed) return
        open.closed = true
        views.remove(open.viewer.javaUuid, open)
        if (open.deposit != null) {
            finish(open, online)
        } else {
            try {
                open.shown?.handler?.closed(open.viewer)
            } catch (e: Exception) {
                log.warn("A menu's close failed: $e")
            }
        }
    }

    private fun finish(open: Open, online: Boolean) {
        val handler = open.deposit ?: return
        open.closed = true
        val items = ArrayList<MenuStack>()
        val top = open.top
        for (slot in 0 until top.size) {
            val stack = top.getItem(slot) ?: continue
            if (stack.type == Material.AIR || stack.amount <= 0) continue
            items += MenuStack(key(stack), stack.amount, stack)
        }
        // Out of the chest first: from here the handler alone decides where they go.
        top.clear()
        try {
            handler.closed(open.viewer, items, online)
        } catch (e: Exception) {
            log.error("A deposit's close failed, so its items go back to its player: $e")
            give(open.viewer, items.map { it.native })
        }
    }

    // The item's id as Minecraft names it, from 1.13; before, Bukkit's material and data value.
    @Suppress("DEPRECATION")
    private fun key(stack: ItemStack): String =
        if (modern) "minecraft:" + stack.type.name.lowercase() else "legacy:" + stack.type.name + ":" + stack.durability

    // The open menu or deposit [inventory] is, if it's one: the viewer's own first, which costs a
    // map lookup, then any chest's holder (an inventory opened before this one was forgotten).
    private fun ours(inventory: Inventory, viewer: UUID): Open? {
        views[viewer]?.let { if (it.top == inventory) return it }
        if (inventory.type != InventoryType.CHEST) return null
        return inventory.holder as? Open
    }

    // Every event the menus hear, as one class told apart by [kind], rather than a lambda each.
    private inner class Events(private val kind: Int) : EventExecutor {
        override fun execute(listener: Listener, event: org.bukkit.event.Event) {
            when (kind) {
                CLICK -> click(event as? InventoryClickEvent ?: return)
                DRAG -> drag(event as? InventoryDragEvent ?: return)
                CLOSE -> close(event as? InventoryCloseEvent ?: return)
                DEATH -> death(event as? PlayerDeathEvent ?: return)
                else -> quit(event as? PlayerQuitEvent ?: return)
            }
        }
    }

    private fun click(event: InventoryClickEvent) {
        val open = ours(event.inventory, event.whoClicked.uniqueId) ?: return
        if (open.deposit != null) return
        event.isCancelled = true
        val slot = event.rawSlot
        if (slot < 0 || slot >= open.top.size || !open.open) return
        val menu = open.shown ?: return
        try {
            menu.handler.click(open.viewer, open, slot, click(event.click))
        } catch (e: Exception) {
            log.warn("A menu's click failed: $e")
        }
    }

    private fun drag(event: InventoryDragEvent) {
        val open = ours(event.inventory, event.whoClicked.uniqueId) ?: return
        if (open.deposit == null) event.isCancelled = true
    }

    private fun close(event: InventoryCloseEvent) {
        val open = ours(event.inventory, event.player.uniqueId) ?: return
        ended(open, online = true)
    }

    private fun death(event: PlayerDeathEvent) {
        val id = event.entity.uniqueId
        val open = views[id] ?: return
        dying[id] = event
        try {
            ended(open, online = true)
        } finally {
            dying.remove(id)
        }
    }

    private fun quit(event: PlayerQuitEvent) {
        val open = views[event.player.uniqueId] ?: return
        ended(open, online = false)
    }

    private companion object {
        const val ITEM = "item"
        const val CLICK = 0
        const val DRAG = 1
        const val CLOSE = 2
        const val DEATH = 3
        const val QUIT = 4

        // Bukkit's reset code: drawn first, it keeps a name or lore line from the client's italics.
        const val RESET = "§r"

        fun click(type: ClickType): Click = when (type) {
            ClickType.LEFT -> Click.LEFT
            ClickType.RIGHT -> Click.RIGHT
            ClickType.SHIFT_LEFT -> Click.SHIFT_LEFT
            ClickType.SHIFT_RIGHT -> Click.SHIFT_RIGHT
            ClickType.MIDDLE -> Click.MIDDLE
            ClickType.DROP, ClickType.CONTROL_DROP -> Click.DROP
            ClickType.NUMBER_KEY -> Click.NUMBER_KEY
            ClickType.DOUBLE_CLICK -> Click.DOUBLE
            else -> Click.OTHER
        }
    }
}
