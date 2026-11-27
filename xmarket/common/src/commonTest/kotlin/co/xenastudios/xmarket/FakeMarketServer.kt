package co.xenastudios.xmarket

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.menu.Icon
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakeMenus
import co.xenastudios.core.testing.FakeMenus.FakeStack
import co.xenastudios.core.testing.VirtualScheduler
import co.xenastudios.xmarket.api.ItemKey
import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.price.ItemSpec

/**
 * A server for xMarket's tests: who's online, what each was sent, their permissions, an
 * inventory of 36 slots each (stacks of up to 64), levels, the menus ([FakeMenus]) and a money
 * economy kept here, which a test can make refuse.
 */
class FakeMarketServer(private val scheduler: VirtualScheduler) : MarketServer, OnlinePlayers, LanguageSource {
    val online = LinkedHashMap<PlayerRef, FakeCommandSource>()
    val sent = HashMap<PlayerRef, MutableList<String>>()
    val slots = HashMap<PlayerRef, Array<FakeStack?>>()
    val levels = HashMap<PlayerRef, Int>()
    val balances = HashMap<PlayerRef, Money>()
    val console = ArrayList<String>()
    val dropped = ArrayList<FakeStack>()
    val explicit = HashMap<PlayerRef, MutableSet<String>>()
    var placeholderValues = HashMap<String, String>()
    var refusePayments = false
    var hasEconomy = true
    var players: PlayerEvents? = null
    var prompt: ((String?) -> Unit)? = null
    override val menus: FakeMenus = FakeMenus()
    override val features: Features = Features("fake stacks", placeholders = true)
    override var renamedIsPlain: Boolean = true

    fun join(source: FakeCommandSource) {
        val player = source.player!!
        online[player] = source
        slots[player] = arrayOfNulls(36)
        scheduler.spawn(player.uuid, Location(WorldRef.of("world"), 0.5, 64.0, 0.5))
        players?.joined(player, source.name)
    }

    fun quit(source: FakeCommandSource) {
        val player = source.player!!
        online.remove(player)
        scheduler.despawn(player.uuid)
        menus.offline += player
        players?.quit(player)
    }

    fun inv(player: PlayerRef): Array<FakeStack?> = slots.getValue(player)

    /** How many of [id] (`minecraft:` added) [player] has. */
    fun has(player: PlayerRef, id: String): Int = inv(player).filterNotNull().filter { it.item == ItemKey.namespaced(id) }.sumOf { it.amount }

    fun put(player: PlayerRef, id: String, amount: Int, data: String? = null) {
        var left = amount
        val inv = inv(player)
        while (left > 0) {
            val slot = inv.indexOfFirst { it == null }
            check(slot >= 0) { "full" }
            val n = minOf(64, left)
            inv[slot] = FakeStack(ItemKey.namespaced(id), n, data)
            left -= n
        }
    }

    override fun isOnline(player: PlayerRef): Boolean = player in online
    override fun name(player: PlayerRef): String? = online[player]?.name
    override fun online(): List<PlayerRef> = online.keys.toList()
    override fun send(player: PlayerRef, message: Component) {
        sent.getOrPut(player) { ArrayList() } += message.plain()
    }
    override fun hasPermission(player: PlayerRef, node: String): Boolean = online[player]?.hasPermission(node) == true
    override fun hasExplicit(player: PlayerRef, node: String): Boolean = node in explicit[player].orEmpty()

    override fun icon(spec: ItemSpec): Icon? = if (spec.id == "minecraft:unknown_item") null else Icon(spec.id, data = spec.data ?: spec.potion)
    override fun knownItems(): Set<String> = setOf("minecraft:stone", "minecraft:diamond", "minecraft:mystery_block", "minecraft:barrier")

    // A potion's type, or data, is the stack's data; anything else is plain.
    override fun key(native: Any): ItemKey {
        val s = native as FakeStack
        val potion = s.data?.takeIf { it.startsWith("potion:") }?.removePrefix("potion:")
        val custom = s.data != null && potion == null
        return ItemKey(s.item, s.amount, potion, emptyMap(), !custom, if (custom) s.data else null)
    }

    override fun copy(native: Any, amount: Int): Any = (native as FakeStack).copy(amount = amount)

    override fun hand(player: PlayerRef): Held? = inv(player)[0]?.let { Held(0, key(it), it) }

    override fun inventory(player: PlayerRef): List<Held> = inv(player).withIndex().mapNotNull { (i, s) -> s?.let { Held(i, key(it), it) } }

    override fun take(player: PlayerRef, takes: List<Take>): List<Any>? {
        val inv = inv(player)
        for (t in takes) {
            val s = inv[t.slot] ?: return null
            if (s.item != t.item.id || s.amount < t.amount) return null
        }
        return takes.map { t ->
            val s = inv[t.slot]!!
            inv[t.slot] = if (s.amount == t.amount) null else s.copy(amount = s.amount - t.amount)
            s.copy(amount = t.amount)
        }
    }

    override fun give(player: PlayerRef, natives: List<Any>) {
        for (n in natives) {
            val s = n as FakeStack
            val slot = inv(player).indexOfFirst { it == null }
            if (slot < 0) dropped += s else inv(player)[slot] = s
        }
    }

    override fun room(player: PlayerRef, spec: ItemSpec, max: Int): Int {
        val inv = inv(player)
        var room = 0
        for (s in inv) room += if (s == null) 64 else if (s.item == spec.id && s.data == spec.data) 64 - s.amount else 0
        return minOf(room, max)
    }

    override fun giveNew(player: PlayerRef, spec: ItemSpec, amount: Int): Int {
        val fits = room(player, spec, amount)
        var left = fits
        val inv = inv(player)
        for (i in inv.indices) {
            if (left == 0) break
            val s = inv[i]
            if (s != null && s.item == spec.id && s.data == spec.data && s.amount < 64) {
                val n = minOf(64 - s.amount, left)
                inv[i] = s.copy(amount = s.amount + n)
                left -= n
            }
        }
        for (i in inv.indices) {
            if (left == 0) break
            if (inv[i] == null) {
                val n = minOf(64, left)
                inv[i] = FakeStack(spec.id, n, spec.data ?: spec.potion?.let { "potion:$it" })
                left -= n
            }
        }
        return fits
    }

    override fun dropNew(player: PlayerRef, spec: ItemSpec, amount: Int) {
        dropped += FakeStack(spec.id, amount)
    }

    override fun count(player: PlayerRef, spec: ItemSpec): Int = inv(player).filterNotNull().filter { it.item == spec.id }.sumOf { it.amount }

    override fun takeItems(player: PlayerRef, spec: ItemSpec, amount: Int): Boolean {
        if (count(player, spec) < amount) return false
        var left = amount
        val inv = inv(player)
        for (i in inv.indices) {
            val s = inv[i] ?: continue
            if (s.item != spec.id || left == 0) continue
            val n = minOf(s.amount, left)
            inv[i] = if (n == s.amount) null else s.copy(amount = s.amount - n)
            left -= n
        }
        return true
    }

    override fun level(player: PlayerRef): Int = levels[player] ?: 0

    override fun addLevels(player: PlayerRef, change: Int): Boolean {
        val now = level(player) + change
        if (now < 0) return false
        levels[player] = now
        return true
    }

    override fun console(command: String) {
        console += command
    }

    override fun placeholders(player: PlayerRef, text: String): String? = placeholderValues[text]

    override fun ask(player: PlayerRef, seconds: Int, answer: (String?) -> Unit) {
        prompt = answer
    }

    val economy = object : Economy {
        override val name: String get() = "Fake"
        override fun has(player: PlayerRef, amount: Money): Boolean = (balances[player] ?: Money.ZERO) >= amount
        override fun withdraw(player: PlayerRef, amount: Money, reason: String): Pending<Boolean> {
            if (refusePayments || !has(player, amount)) return pendingOf(false)
            balances[player] = (balances[player] ?: Money.ZERO) - amount
            return pendingOf(true)
        }
        override fun deposit(player: PlayerRef, amount: Money, reason: String): Pending<Boolean> {
            if (refusePayments) return pendingOf(false)
            balances[player] = (balances[player] ?: Money.ZERO) + amount
            return pendingOf(true)
        }
        override fun format(amount: Money): String = "$" + amount.toPlainString(2)
    }

    override fun economy(which: String): Economy? = if (hasEconomy) economy else null

    override fun watchPlayers(listener: PlayerEvents): AutoCloseable {
        players = listener
        return AutoCloseable { players = null }
    }

    override fun find(name: String, viewer: CommandSource): PlayerRef? = online.entries.firstOrNull { it.value.name.equals(name, true) }?.key
    override fun names(viewer: CommandSource): List<String> = online.values.map { it.name }
    override fun clientLocale(player: PlayerRef): String? = null
}
