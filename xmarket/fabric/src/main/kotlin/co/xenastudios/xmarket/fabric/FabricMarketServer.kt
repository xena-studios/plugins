package co.xenastudios.xmarket.fabric

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.menu.Icon
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.later
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.fabric.FabricMenus
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.fabric.MinecraftRenderer
import co.xenastudios.core.fabric.player
import co.xenastudios.core.fabric.ref
import co.xenastudios.xmarket.Economy
import co.xenastudios.xmarket.Features
import co.xenastudios.xmarket.Held
import co.xenastudios.xmarket.MarketServer
import co.xenastudios.xmarket.PlayerEvents
import co.xenastudios.xmarket.Take
import co.xenastudios.xmarket.XMoneyBridge
import co.xenastudios.xmarket.api.ItemKey
import co.xenastudios.xmarket.price.ItemAliases
import co.xenastudios.xmarket.price.ItemSpec
import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.util.TriState
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
//? if identifier {
import net.minecraft.resources.Identifier
//?} else
//import net.minecraft.resources.ResourceLocation as Identifier
//? if item_components {
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.item.enchantment.ItemEnchantments
//?} else {
/*import net.minecraft.world.item.EnchantedBookItem
import net.minecraft.world.item.alchemy.PotionUtils
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.EnchantmentInstance
*///?}

/**
 * xMarket on Fabric, server-side, 1.20.1, 1.21.11 and 26.3: the player's inventory (its 36
 * storage and hotbar slots), permissions through fabric-permissions-api, the core's chest menus,
 * chat prompts through Fabric API's message events, and money through xMoney or another economy
 * mod's Common Economy API. Items are Minecraft's own: an id from its registry, a potion's type and
 * a book's enchantments from its data components (NBT on 1.20.1). There's no placeholder plugin on
 * Fabric that xMarket reads, so an entry with requirements can't be used here.
 */
internal class FabricMarketServer(private val platform: FabricPlatform) : MarketServer {
    override val features: Features = Features(items = "Minecraft's registry ids", placeholders = false)

    @Volatile
    override var renamedIsPlain: Boolean = true

    /** The plugin, once made: what the price list says about an item's data. */
    @Volatile
    var xmarket: co.xenastudios.xmarket.XMarketPlugin? = null

    override val menus: FabricMenus get() = platform.menus

    private fun player(ref: PlayerRef): ServerPlayer? = platform.server?.player(ref)

    override fun isOnline(player: PlayerRef): Boolean = player(player) != null
    override fun name(player: PlayerRef): String? = player(player)?.scoreboardName
    override fun online(): List<PlayerRef> = platform.server?.playerList?.players?.map { it.ref }.orEmpty()

    override fun send(player: PlayerRef, message: Component) {
        player(player)?.sendSystemMessage(MinecraftRenderer.draw(message))
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = player(player)?.let { platform.permissions.has(it, node) } == true

    override fun hasExplicit(player: PlayerRef, node: String): Boolean {
        val p = player(player) ?: return false
        return PermissionCheckEvent.EVENT.invoker().onPermissionCheck(p.createCommandSourceStack(), node) == TriState.TRUE
    }

    private val icons = concurrentMap<String, Any>()

    override fun icon(spec: ItemSpec): Icon? {
        val key = spec.key + (spec.data?.hashCode() ?: 0)
        icons[key]?.let { return it as? Icon }
        val stack = create(spec)
        val icon = if (stack == null) NONE else Icon(spec.id, data = if (spec.data != null || spec.specific > 0) menus.data(stack) else null)
        icons[key] = icon
        return icon as? Icon
    }

    override fun knownItems(): Set<String> = BuiltInRegistries.ITEM.keySet().map { ItemAliases.current(it.toString()) }.toSet()

    private fun id(stack: ItemStack): String = ItemAliases.current(BuiltInRegistries.ITEM.getKey(stack.item).toString())

    override fun key(native: Any): ItemKey {
        val stack = native as ItemStack
        val id = id(stack)
        var potion: String? = null
        val enchantments = LinkedHashMap<String, Int>()
        var plain = true
        //? if item_components {
        stack.get(DataComponents.POTION_CONTENTS)?.potion()?.flatMap { it.unwrapKey() }?.ifPresent { potion = it.identifier().path }
        stack.get(DataComponents.STORED_ENCHANTMENTS)?.let { stored ->
            for (e in stored.entrySet()) e.key.unwrapKey().ifPresent { k -> enchantments[k.identifier().toString()] = e.intValue }
        }
        if (stack.has(DataComponents.LORE) || stack.has(DataComponents.CUSTOM_MODEL_DATA) || stack.has(DataComponents.CUSTOM_DATA)) plain = false
        if (stack.has(DataComponents.CUSTOM_NAME) && !renamedIsPlain) plain = false
        //?} else {
        /*if (id.endsWith("potion") || id == "minecraft:tipped_arrow") potion = BuiltInRegistries.POTION.getKey(PotionUtils.getPotion(stack)).path
        if (id == "minecraft:enchanted_book") for ((e, level) in EnchantmentHelper.getEnchantments(stack)) BuiltInRegistries.ENCHANTMENT.getKey(e)?.let { enchantments[it.toString()] = level }
        val tag = stack.tag
        if (tag != null && (tag.contains("CustomModelData") || tag.getCompound("display").contains("Lore") || tag.contains("PublicBukkitValues"))) plain = false
        if (stack.hasCustomHoverName() && !renamedIsPlain) plain = false
        *///?}
        val data = if (!plain && xmarket?.prices?.wantsData(id) == true) menus.data(stack) else null
        return ItemKey(id, stack.count, potion, enchantments, plain, data)
    }

    /** One of [spec], as this server makes it; `null` if it has no such item. */
    private fun create(spec: ItemSpec): ItemStack? {
        val stack = (menus.item(Icon(spec.id, data = spec.data)) ?: ItemAliases.older(spec.id)?.let { menus.item(Icon(it)) })?.also { it.count = 1 } ?: return null
        if (spec.data != null) return stack
        val potion = spec.potion
        //? if item_components {
        if (potion != null) {
            val holder = BuiltInRegistries.POTION.get(Identifier.withDefaultNamespace(potion)).orElse(null) ?: return null
            stack.set(DataComponents.POTION_CONTENTS, PotionContents(holder))
        }
        if (spec.enchantments.isNotEmpty()) {
            val registries = platform.server?.registryAccess() ?: return null
            val lookup = registries.lookupOrThrow(Registries.ENCHANTMENT)
            val mutable = ItemEnchantments.Mutable(ItemEnchantments.EMPTY)
            for ((key, level) in spec.enchantments) {
                val id = Identifier.tryParse(key) ?: return null
                val holder = lookup.get(ResourceKey.create(Registries.ENCHANTMENT, id)).orElse(null) ?: return null
                mutable.set(holder, level)
            }
            stack.set(DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable())
        }
        //?} else {
        /*if (potion != null) {
            val p = BuiltInRegistries.POTION.getOptional(Identifier(potion)).orElse(null) ?: return null
            PotionUtils.setPotion(stack, p)
        }
        for ((key, level) in spec.enchantments) {
            val e = BuiltInRegistries.ENCHANTMENT.getOptional(Identifier(key)).orElse(null) ?: return null
            EnchantedBookItem.addEnchantment(stack, EnchantmentInstance(e, level))
        }
        *///?}
        return stack
    }

    override fun copy(native: Any, amount: Int): Any = (native as ItemStack).copyWithCount(amount)

    private fun selected(player: ServerPlayer): Int =
        //? if selected_slot {
        player.inventory.selectedSlot
        //?} else
        //player.inventory.selected

    override fun hand(player: PlayerRef): Held? {
        val p = player(player) ?: return null
        val slot = selected(p)
        val stack = p.inventory.getItem(slot).takeUnless { it.isEmpty } ?: return null
        return Held(slot, key(stack), stack)
    }

    override fun inventory(player: PlayerRef): List<Held> {
        val p = player(player) ?: return emptyList()
        val out = ArrayList<Held>()
        for (slot in 0 until SLOTS) {
            val stack = p.inventory.getItem(slot)
            if (!stack.isEmpty) out += Held(slot, key(stack), stack)
        }
        return out
    }

    override fun take(player: PlayerRef, takes: List<Take>): List<Any>? {
        val inv = player(player)?.inventory ?: return null
        for (t in takes) {
            val stack = inv.getItem(t.slot)
            if (stack.isEmpty || stack.count < t.amount || id(stack) != t.item.id) return null
        }
        val out = ArrayList<Any>(takes.size)
        for (t in takes) {
            val stack = inv.getItem(t.slot)
            out += stack.copyWithCount(t.amount)
            if (stack.count == t.amount) inv.setItem(t.slot, ItemStack.EMPTY) else stack.count = stack.count - t.amount
        }
        inv.setChanged()
        return out
    }

    override fun give(player: PlayerRef, natives: List<Any>) {
        menus.give(player, natives)
    }

    private fun same(a: ItemStack, b: ItemStack): Boolean =
        //? if item_components {
        ItemStack.isSameItemSameComponents(a, b)
        //?} else
        //ItemStack.isSameItemSameTags(a, b)

    override fun room(player: PlayerRef, spec: ItemSpec, max: Int): Int {
        val inv = player(player)?.inventory ?: return 0
        val template = create(spec) ?: return 0
        val size = template.maxStackSize.coerceAtLeast(1)
        var room = 0
        for (slot in 0 until SLOTS) {
            val s = inv.getItem(slot)
            room += if (s.isEmpty) size else if (same(s, template)) (size - s.count).coerceAtLeast(0) else 0
            if (room >= max) return max
        }
        return room
    }

    override fun giveNew(player: PlayerRef, spec: ItemSpec, amount: Int): Int {
        val inv = player(player)?.inventory ?: return 0
        val template = create(spec) ?: return 0
        val fits = room(player, spec, amount)
        val size = template.maxStackSize.coerceAtLeast(1)
        var left = fits
        for (slot in 0 until SLOTS) {
            if (left == 0) break
            val s = inv.getItem(slot)
            if (s.isEmpty || !same(s, template)) continue
            val n = minOf(size - s.count, left)
            if (n <= 0) continue
            s.count = s.count + n
            left -= n
        }
        for (slot in 0 until SLOTS) {
            if (left == 0) break
            if (!inv.getItem(slot).isEmpty) continue
            val n = minOf(size, left)
            inv.setItem(slot, template.copyWithCount(n))
            left -= n
        }
        inv.setChanged()
        return fits - left
    }

    override fun dropNew(player: PlayerRef, spec: ItemSpec, amount: Int) {
        val template = create(spec) ?: return
        var left = amount
        val stacks = ArrayList<Any>()
        while (left > 0) {
            val n = minOf(template.maxStackSize.coerceAtLeast(1), left)
            stacks += template.copyWithCount(n)
            left -= n
        }
        menus.give(player, stacks)
    }

    override fun count(player: PlayerRef, spec: ItemSpec): Int = inventory(player).filter { spec.matches(it.item) }.sumOf { it.item.amount }

    override fun takeItems(player: PlayerRef, spec: ItemSpec, amount: Int): Boolean {
        if (count(player, spec) < amount) return false
        val inv = player(player)?.inventory ?: return false
        var left = amount
        for (h in inventory(player)) {
            if (left == 0) break
            if (!spec.matches(h.item)) continue
            val stack = inv.getItem(h.slot)
            val n = minOf(stack.count, left)
            if (n == stack.count) inv.setItem(h.slot, ItemStack.EMPTY) else stack.count = stack.count - n
            left -= n
        }
        inv.setChanged()
        return true
    }

    override fun level(player: PlayerRef): Int = player(player)?.experienceLevel ?: 0

    override fun addLevels(player: PlayerRef, change: Int): Boolean {
        val p = player(player) ?: return false
        if (p.experienceLevel + change < 0) return false
        p.giveExperienceLevels(change)
        return true
    }

    override fun console(command: String) {
        val server = platform.server ?: return
        platform.scheduler.run(Where.Global) { server.commands.performPrefixedCommand(server.createCommandSourceStack(), command) }
    }

    override fun placeholders(player: PlayerRef, text: String): String? = null

    // ---- chat prompts ----

    private val prompts = concurrentMap<UUID, (String?) -> Unit>()

    @Volatile
    private var listening = false

    override fun ask(player: PlayerRef, seconds: Int, answer: (String?) -> Unit) {
        val id = UUID.fromString(player.toString())
        prompts[id] = answer
        listen()
        platform.scheduler.later(Where.on(player), seconds.seconds) { if (prompts.remove(id, answer)) answer(null) }
    }

    private fun listen() {
        if (listening) return
        synchronized(this) {
            if (listening) return
            listening = true
            // Fabric's events can't be unregistered: once the server stops, this answers nothing.
            ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { message, sender, _ ->
                val answer = prompts.remove(sender.uuid) ?: return@register true
                val text = message.signedContent()
                platform.scheduler.run(Where.on(sender.ref)) { answer(text) }
                false
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
        val xmoney = if (which != "vault") XMoneyBridge.find(FabricMarketServer::class.java.classLoader) else null
        val found = xmoney ?: if (FabricLoader.getInstance().isModLoaded("common-economy-api")) CommonEconomyMoney.find(platform) else null
        economy = found
        return found
    }

    @Volatile
    private var watching: PlayerEvents? = null

    @Volatile
    private var registered = false

    override fun watchPlayers(listener: PlayerEvents): AutoCloseable {
        watching = listener
        if (!registered) {
            registered = true
            ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> watching?.joined(handler.player.ref, handler.player.scoreboardName) }
            ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
                prompts.remove(handler.player.uuid)
                watching?.quit(handler.player.ref)
            }
        }
        return AutoCloseable { if (watching === listener) watching = null }
    }

    private companion object {
        /** The storage and hotbar: 0 to 8 the hotbar, 9 to 35 the rest. */
        const val SLOTS = 36
        val NONE = Any()
    }
}
