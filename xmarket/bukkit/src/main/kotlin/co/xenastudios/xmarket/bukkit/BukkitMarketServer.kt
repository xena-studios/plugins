package co.xenastudios.xmarket.bukkit

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.bukkit.BukkitMenus
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.menu.Icon
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.later
import co.xenastudios.core.common.text.Component
import co.xenastudios.xmarket.Economy
import co.xenastudios.xmarket.Features
import co.xenastudios.xmarket.Held
import co.xenastudios.xmarket.MarketServer
import co.xenastudios.xmarket.PlayerEvents
import co.xenastudios.xmarket.Take
import co.xenastudios.xmarket.MaterialNames
import co.xenastudios.xmarket.XMoneyBridge
import co.xenastudios.xmarket.api.ItemKey
import co.xenastudios.xmarket.price.ItemSpec
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.java.JavaPlugin
import kotlin.time.Duration.Companion.seconds

/**
 * xMarket on a Bukkit-family server, 1.8.8 to the newest Paper and Folia: the player's
 * inventory (its 36 storage and hotbar slots), permissions, the core's chest menus, chat
 * prompts for the amount screen, PlaceholderAPI for requirements, and money through xMoney or
 * whatever economy is registered with Vault.
 */
internal class BukkitMarketServer(private val plugin: JavaPlugin, private val platform: BukkitPlatform) :
    MarketServer, MaterialNames, (ItemStack) -> ItemKey {
    private val bukkit = plugin.server

    /** The plugin, once made: what the price list says about an item's data. */
    @Volatile
    var xmarket: co.xenastudios.xmarket.XMarketPlugin? = null

    // Whether the price list has an entry for [id] made from an item's full data.
    fun wants(id: String): Boolean = xmarket?.prices?.wantsData(id) == true

    override fun invoke(stack: ItemStack): ItemKey = items.key(stack)

    override fun id(name: String): String? = items.material(name)

    // Made the first time an item is looked at, not as the server starts.
    @Volatile
    private var madeItems: BukkitItems? = null
    private val items: BukkitItems get() = madeItems ?: synchronized(this) { madeItems ?: BukkitItems(platform.server, platform.menus, this).also { it.renamedIsPlain = renamed; madeItems = it } }

    @Volatile
    private var renamed = true

    override val features: Features = Features(
        items = if (platform.server.atLeast(1, 13)) "Minecraft's ids" else "1.8 to 1.12's names and data values, flattened",
        placeholders = bukkit.pluginManager.getPlugin("PlaceholderAPI") != null,
    )

    override var renamedIsPlain: Boolean
        get() = renamed
        set(value) {
            renamed = value
            madeItems?.renamedIsPlain = value
        }

    override val menus: BukkitMenus get() = platform.menus.also { m -> if (m.legacyItems == null) m.legacyItems = { id -> items.create(ItemSpec.ofKey(id.removePrefix("minecraft:"))) } }

    private fun player(ref: PlayerRef): Player? = ref.online(bukkit)

    override fun isOnline(player: PlayerRef): Boolean = player(player) != null
    override fun name(player: PlayerRef): String? = player(player)?.name
    override fun online(): List<PlayerRef> = bukkit.onlinePlayers.map { it.ref }

    override fun send(player: PlayerRef, message: Component) {
        val p = player(player) ?: return
        platform.text.send(p, message)
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = player(player)?.hasPermission(node) == true

    override fun hasExplicit(player: PlayerRef, node: String): Boolean = player(player)?.let { it.isPermissionSet(node) && it.hasPermission(node) } == true

    // An icon per spec, made once: the real item, written as its full data so potions and books show as they are.
    private val icons = concurrentMap<String, Any>()

    override fun icon(spec: ItemSpec): Icon? {
        val key = spec.key + (spec.data?.hashCode() ?: 0)
        val cached = icons[key]
        if (cached != null) return cached as? Icon
        val stack = items.create(spec)
        val icon = if (stack == null) NONE else Icon(spec.id, data = if (spec.data != null || spec.specific > 0) platform.menus.data(stack) else null)
        icons[key] = icon
        return icon as? Icon
    }

    override fun knownItems(): Set<String> = items.known()

    override fun key(native: Any): ItemKey = items.key(native as ItemStack)

    override fun copy(native: Any, amount: Int): Any = (native as ItemStack).clone().also { it.amount = amount }

    override fun hand(player: PlayerRef): Held? {
        val p = player(player) ?: return null
        val slot = p.inventory.heldItemSlot
        val stack = p.inventory.getItem(slot)?.takeIf { it.type != Material.AIR && it.amount > 0 } ?: return null
        return Held(slot, items.key(stack), stack)
    }

    override fun inventory(player: PlayerRef): List<Held> {
        val p = player(player) ?: return emptyList()
        val inv = p.inventory
        val out = ArrayList<Held>()
        for (slot in 0 until SLOTS) {
            val stack = inv.getItem(slot) ?: continue
            if (stack.type == Material.AIR || stack.amount <= 0) continue
            out += Held(slot, items.key(stack), stack)
        }
        return out
    }

    override fun take(player: PlayerRef, takes: List<Take>): List<Any>? {
        val inv = player(player)?.inventory ?: return null
        for (t in takes) {
            val stack = inv.getItem(t.slot) ?: return null
            if (stack.type == Material.AIR || stack.amount < t.amount || items.id(stack) != t.item.id) return null
        }
        val out = ArrayList<Any>(takes.size)
        for (t in takes) {
            val stack = inv.getItem(t.slot)!!
            val taken = stack.clone().also { it.amount = t.amount }
            if (stack.amount == t.amount) inv.setItem(t.slot, null) else stack.amount = stack.amount - t.amount
            out += taken
        }
        return out
    }

    override fun give(player: PlayerRef, natives: List<Any>) {
        platform.menus.give(player, natives)
    }

    override fun room(player: PlayerRef, spec: ItemSpec, max: Int): Int {
        val inv = player(player)?.inventory ?: return 0
        val template = items.create(spec) ?: return 0
        val stackSize = template.maxStackSize.coerceAtLeast(1)
        var room = 0
        for (slot in 0 until SLOTS) {
            val s = inv.getItem(slot)
            room += when {
                s == null || s.type == Material.AIR -> stackSize
                s.isSimilar(template) -> (stackSize - s.amount).coerceAtLeast(0)
                else -> 0
            }
            if (room >= max) return max
        }
        return room
    }

    override fun giveNew(player: PlayerRef, spec: ItemSpec, amount: Int): Int {
        val inv = player(player)?.inventory ?: return 0
        val template = items.create(spec) ?: return 0
        val fits = room(player, spec, amount)
        var left = fits
        val stackSize = template.maxStackSize.coerceAtLeast(1)
        // Onto stacks of the same item first, then empty slots, within the 36 slots counted.
        for (slot in 0 until SLOTS) {
            if (left == 0) break
            val s = inv.getItem(slot) ?: continue
            if (s.type == Material.AIR || !s.isSimilar(template)) continue
            val n = minOf(stackSize - s.amount, left)
            if (n <= 0) continue
            s.amount = s.amount + n
            left -= n
        }
        for (slot in 0 until SLOTS) {
            if (left == 0) break
            val s = inv.getItem(slot)
            if (s != null && s.type != Material.AIR) continue
            val n = minOf(stackSize, left)
            inv.setItem(slot, template.clone().also { it.amount = n })
            left -= n
        }
        return fits - left
    }

    override fun dropNew(player: PlayerRef, spec: ItemSpec, amount: Int) {
        val p = player(player) ?: return
        val template = items.create(spec) ?: return
        var left = amount
        while (left > 0) {
            val n = minOf(template.maxStackSize.coerceAtLeast(1), left)
            p.world.dropItemNaturally(p.location, template.clone().also { it.amount = n })
            left -= n
        }
    }

    override fun count(player: PlayerRef, spec: ItemSpec): Int {
        var n = 0
        for (h in inventory(player)) if (spec.matches(h.item)) n += h.item.amount
        return n
    }

    override fun takeItems(player: PlayerRef, spec: ItemSpec, amount: Int): Boolean {
        if (count(player, spec) < amount) return false
        val inv = player(player)?.inventory ?: return false
        var left = amount
        for (h in inventory(player)) {
            if (left == 0) break
            if (!spec.matches(h.item)) continue
            val stack = inv.getItem(h.slot) ?: continue
            val n = minOf(stack.amount, left)
            if (n == stack.amount) inv.setItem(h.slot, null) else stack.amount = stack.amount - n
            left -= n
        }
        return true
    }

    override fun level(player: PlayerRef): Int = player(player)?.level ?: 0

    override fun addLevels(player: PlayerRef, change: Int): Boolean {
        val p = player(player) ?: return false
        if (p.level + change < 0) return false
        p.level = p.level + change
        return true
    }

    override fun console(command: String) {
        // The console's commands run on the global thread (Folia's global region; the main thread elsewhere).
        platform.scheduler.run(Where.Global) { bukkit.dispatchCommand(bukkit.consoleSender, command) }
    }

    @Volatile
    private var papi: Boolean? = null

    override fun placeholders(player: PlayerRef, text: String): String? {
        val has = papi ?: (bukkit.pluginManager.isPluginEnabled("PlaceholderAPI")).also { papi = it }
        if (!has) return null
        val p = player(player) ?: return null
        return try {
            PapiHook.fill(p, text)
        } catch (e: Throwable) {
            null
        }
    }

    // ---- chat prompts ----

    private val prompts = concurrentMap<java.util.UUID, (String?) -> Unit>()

    @Volatile
    private var chat: Listener? = null

    override fun ask(player: PlayerRef, seconds: Int, answer: (String?) -> Unit) {
        prompts[player.javaUuid] = answer
        listenChat()
        platform.scheduler.later(Where.on(player), seconds.seconds) { if (prompts.remove(player.javaUuid, answer)) answer(null) }
    }

    private fun listenChat() {
        if (chat != null) return
        synchronized(this) {
            if (chat != null) return
            val listener = object : Listener {}
            bukkit.pluginManager.registerEvent(AsyncPlayerChatEvent::class.java, listener, EventPriority.LOWEST, Heard(0), plugin)
            chat = listener
        }
    }

    // Chat a prompt waits for, and players joining and leaving, as one class told apart by [kind].
    private inner class Heard(private val kind: Int, private val players: PlayerEvents? = null) : EventExecutor {
        override fun execute(listener: Listener, event: org.bukkit.event.Event) {
            when (kind) {
                0 -> {
                    val e = event as? AsyncPlayerChatEvent ?: return
                    val answer = prompts.remove(e.player.uniqueId) ?: return
                    e.isCancelled = true
                    val ref = e.player.ref
                    val text = e.message
                    platform.scheduler.run(Where.on(ref)) { answer(text) }
                }
                1 -> (event as? PlayerJoinEvent)?.let { players!!.joined(it.player.ref, it.player.name) }
                else -> (event as? PlayerQuitEvent)?.let {
                    prompts.remove(it.player.uniqueId)
                    players!!.quit(it.player.ref)
                }
            }
        }
    }

    // ---- money ----

    @Volatile
    private var economy: Economy? = null

    @Volatile
    private var lookedAt = 0L

    override fun economy(which: String): Economy? {
        economy?.let { return it }
        val now = System.currentTimeMillis()
        if (now - lookedAt < 5_000) return null
        lookedAt = now
        val found = when (which) {
            "vault" -> VaultMoney.find(bukkit)
            "xmoney" -> xmoney()
            else -> xmoney() ?: VaultMoney.find(bukkit)
        }
        economy = found
        return found
    }

    private fun xmoney(): Economy? {
        val xmoney = bukkit.pluginManager.getPlugin("xMoney")?.takeIf { it.isEnabled } ?: return null
        return XMoneyBridge.find(xmoney.javaClass.classLoader)
    }

    /** Forgets the economy found, for a reload (or an economy plugin enabled later). */
    fun forgetEconomy() {
        economy = null
        lookedAt = 0
    }

    override fun watchPlayers(listener: PlayerEvents): AutoCloseable {
        val l = object : Listener {}
        bukkit.pluginManager.registerEvent(PlayerJoinEvent::class.java, l, EventPriority.MONITOR, Heard(1, listener), plugin)
        bukkit.pluginManager.registerEvent(PlayerQuitEvent::class.java, l, EventPriority.MONITOR, Heard(2, listener), plugin)
        return AutoCloseable { HandlerList.unregisterAll(l) }
    }

    fun close() {
        chat?.let { HandlerList.unregisterAll(it) }
        chat = null
        prompts.clear()
    }

    private companion object {
        /** The storage and hotbar: 0 to 8 the hotbar, 9 to 35 the rest, on every version. */
        const val SLOTS = 36
        val NONE = Any()
    }
}
