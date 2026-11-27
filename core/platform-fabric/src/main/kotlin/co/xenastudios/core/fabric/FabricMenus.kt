package co.xenastudios.core.fabric

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.menu.Click
import co.xenastudios.core.common.menu.DepositHandler
import co.xenastudios.core.common.menu.Icon
import co.xenastudios.core.common.menu.Menu
import co.xenastudios.core.common.menu.MenuStack
import co.xenastudios.core.common.menu.MenuView
import co.xenastudios.core.common.menu.Menus
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.text.Component
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.TagParser
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.toJavaUuid
import net.minecraft.network.chat.Component as McComponent
//? if identifier {
import net.minecraft.resources.Identifier
//?} else
//import net.minecraft.resources.ResourceLocation
//? if container_input {
import net.minecraft.world.inventory.ContainerInput
//?} else
//import net.minecraft.world.inventory.ClickType
//? if item_components {
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.NbtOps
import net.minecraft.world.item.component.ItemLore
//?} else {
/*import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.world.item.enchantment.Enchantments
*///?}

/**
 * [Menus] on Fabric, server-side with vanilla's own chest menus: players install nothing.
 * Made by [FabricPlatform.menus] the first time a plugin asks; its disconnect and death hooks are
 * registered then (Fabric's events can't be unregistered, so they do nothing once closed).
 *
 * - A menu is a [ChestMenu] over a container of its icons whose `clicked` never moves anything:
 *   every click, in the chest or the player's inventory, is cancelled, the client is sent the
 *   truth again, and a click on the chest's own slots goes to the handler.
 * - A deposit is a plain chest. As it closes (`removed`, which Minecraft calls on a close, another
 *   menu opening and a disconnect), or as its player disconnects or is about to die, its items are
 *   taken out and handed to its handler; given back before a death, they drop with the rest.
 */
public class FabricMenus internal constructor(
    private val server: () -> MinecraftServer?,
    private val log: Log,
) : Menus {
    private val views = ConcurrentHashMap<UUID, Open>()

    @Volatile
    private var running = true

    init {
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> if (running) left(handler.player, online = false) }
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, _, _ ->
            if (running && entity is ServerPlayer) left(entity, online = true)
            true
        }
    }

    // One open menu or deposit.
    private inner class Open(val viewer: PlayerRef, val shown: Menu?, val deposit: DepositHandler?, val container: SimpleContainer) : MenuView {
        @Volatile
        var closed = false

        override val menu: Menu get() = checkNotNull(shown) { "a deposit has no menu" }

        override val open: Boolean get() = !closed && views[viewer.uuid.toJavaUuid()] === this

        override fun set(slot: Int, icon: Icon?) {
            if (!open || slot !in 0 until container.containerSize) return
            container.setItem(slot, icon?.let(::item) ?: ItemStack.EMPTY)
        }

        override fun close() {
            if (open) player(viewer)?.closeContainer()
        }
    }

    override fun open(viewer: PlayerRef, menu: Menu): MenuView? {
        val player = player(viewer) ?: return null
        val container = SimpleContainer(menu.size)
        for ((slot, icon) in menu.icons.withIndex()) {
            if (slot >= menu.size) break
            if (icon != null) item(icon)?.let { container.setItem(slot, it) }
        }
        val open = Open(viewer, menu, null, container)
        show(player, open, menu.rows, menu.title)
        return open
    }

    override fun deposit(viewer: PlayerRef, title: Component, rows: Int, handler: DepositHandler): Boolean {
        require(rows in 1..6) { "a deposit has 1 to 6 rows, not $rows" }
        val player = player(viewer) ?: return false
        show(player, Open(viewer, null, handler, SimpleContainer(rows * 9)), rows, title)
        return true
    }

    private fun show(player: ServerPlayer, open: Open, rows: Int, title: Component) {
        val previous = views.put(player.uuid, open)
        if (previous != null && previous.deposit != null) finish(previous, online = true)
        val type = TYPES[rows - 1]
        player.openMenu(SimpleMenuProvider({ id, inventory, _ -> View(type, id, inventory, open, rows) }, MinecraftRenderer.draw(title)))
    }

    override fun give(viewer: PlayerRef, stacks: List<Any>): Boolean {
        val player = player(viewer) ?: return false
        for (stack in stacks) {
            if (stack !is ItemStack || stack.isEmpty) continue
            if (!player.inventory.add(stack) && !stack.isEmpty) drop(player, stack)
        }
        return true
    }

    override fun data(native: Any): String? {
        val stack = (native as? ItemStack)?.takeUnless { it.isEmpty }?.copyWithCount(1) ?: return null
        return try {
            //? if item_components {
            val ops = (server() ?: return null).registryAccess().createSerializationContext(NbtOps.INSTANCE)
            ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null)?.toString()
            //?} else
            //stack.save(net.minecraft.nbt.CompoundTag()).toString()
        } catch (e: Exception) {
            null
        }
    }

    override fun viewing(viewer: PlayerRef): MenuView? = views[viewer.uuid.toJavaUuid()]?.takeIf { it.shown != null && it.open }

    override fun closeAll() {
        running = false
        for (open in views.values.toList()) {
            val player = player(open.viewer)
            if (open.deposit != null) finish(open, online = player != null)
            open.closed = true
            views.remove(open.viewer.uuid.toJavaUuid(), open)
            try {
                player?.closeContainer()
            } catch (e: Exception) {
                log.warn("Couldn't close ${player?.scoreboardName}'s menu: $e")
            }
        }
    }

    /** The item [icon] shows: its full data where it has some and it reads here, else its id. */
    public fun item(icon: Icon): ItemStack? {
        val stack = icon.data?.let(::read) ?: base(icon.item) ?: return null
        stack.count = icon.amount.coerceIn(1, 64)
        val name = icon.name?.let(::plain)
        //? if item_components {
        if (name != null) stack.set(DataComponents.CUSTOM_NAME, name)
        if (icon.lore.isNotEmpty()) stack.set(DataComponents.LORE, ItemLore(icon.lore.map(::plain)))
        if (icon.glow) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        //?} else {
        /*if (name != null) stack.setHoverName(name)
        if (icon.lore.isNotEmpty()) {
            val lore = ListTag()
            for (line in icon.lore) lore.add(StringTag.valueOf(McComponent.Serializer.toJson(plain(line))))
            stack.getOrCreateTagElement("display").put("Lore", lore)
        }
        if (icon.glow) {
            stack.enchant(Enchantments.UNBREAKING, 1)
            stack.hideTooltipPart(ItemStack.TooltipPart.ENCHANTMENTS)
        }
        *///?}
        return stack
    }

    // A name or lore line as the menu wrote it, not in the italics Minecraft gives custom text.
    private fun plain(text: Component): McComponent = McComponent.empty().append(MinecraftRenderer.draw(text)).withStyle { it.withItalic(false) }

    private fun base(id: String): ItemStack? {
        //? if identifier {
        val key = Identifier.tryParse(id) ?: return null
        val item = BuiltInRegistries.ITEM.getValue(key)
        //?} else {
        /*val key = ResourceLocation.tryParse(id) ?: return null
        val item = BuiltInRegistries.ITEM.get(key)
        *///?}
        val stack = ItemStack(item)
        return if (stack.isEmpty || BuiltInRegistries.ITEM.getKey(item) != key) null else stack
    }

    private fun read(data: String): ItemStack? = try {
        //? if item_components {
        val ops = server()?.registryAccess()?.createSerializationContext(NbtOps.INSTANCE)
        ops?.let { ItemStack.CODEC.parse(it, TagParser.parseCompoundFully(data)).result().orElse(null) }?.takeUnless { it.isEmpty }
        //?} else
        //ItemStack.of(TagParser.parseTag(data)).takeUnless { it.isEmpty }
    } catch (e: Exception) {
        null
    }

    private fun player(ref: PlayerRef): ServerPlayer? = server()?.player(ref)

    private fun drop(player: ServerPlayer, stack: ItemStack) {
        //? if item_prediction {
        player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY)
        //?} else
        //player.drop(stack, false)
    }

    // A player leaving or about to die with a deposit or menu open.
    private fun left(player: ServerPlayer, online: Boolean) {
        val open = views[player.uuid] ?: return
        ended(open, online)
    }

    private fun ended(open: Open, online: Boolean) {
        if (open.closed) return
        open.closed = true
        views.remove(open.viewer.uuid.toJavaUuid(), open)
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
        // Out of the chest first: from here the handler alone decides where they go.
        val items = open.container.removeAllItems().filter { !it.isEmpty }.map { MenuStack(BuiltInRegistries.ITEM.getKey(it.item).toString(), it.count, it) }
        try {
            handler.closed(open.viewer, items, online)
        } catch (e: Exception) {
            log.error("A deposit's close failed, so its items go back to its player: $e")
            give(open.viewer, items.map { it.native })
        }
    }

    // The chest on screen. A menu's moves nothing; a deposit's is a chest's own.
    private inner class View(type: MenuType<ChestMenu>, id: Int, inventory: Inventory, private val open: Open, rows: Int) :
        ChestMenu(type, id, inventory, open.container, rows) {

        //? if container_input {
        override fun clicked(slotId: Int, button: Int, clickType: ContainerInput, player: Player) {
        //?} else
        //override fun clicked(slotId: Int, button: Int, clickType: ClickType, player: Player) {
            if (open.deposit != null) return super.clicked(slotId, button, clickType, player)
            sendAllDataToRemote()
            val menu = open.shown ?: return
            if (slotId < 0 || slotId >= open.container.containerSize || !open.open) return
            try {
                menu.handler.click(open.viewer, open, slotId, click(clickType.name, button))
            } catch (e: Exception) {
                log.warn("A menu's click failed: $e")
            }
        }

        override fun quickMoveStack(player: Player, slot: Int): ItemStack =
            if (open.deposit != null) super.quickMoveStack(player, slot) else ItemStack.EMPTY

        override fun stillValid(player: Player): Boolean = !open.closed

        override fun removed(player: Player) {
            super.removed(player)
            ended(open, online = true)
        }
    }

    private companion object {
        val TYPES: List<MenuType<ChestMenu>> = listOf(
            MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3, MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6,
        )

        // By the click's name, the same on every line (ClickType, then ContainerInput from 26.1).
        fun click(type: String, button: Int): Click = when (type) {
            "PICKUP" -> if (button == 1) Click.RIGHT else Click.LEFT
            "QUICK_MOVE" -> if (button == 1) Click.SHIFT_RIGHT else Click.SHIFT_LEFT
            "SWAP" -> Click.NUMBER_KEY
            "CLONE" -> Click.MIDDLE
            "THROW" -> Click.DROP
            "PICKUP_ALL" -> Click.DOUBLE
            else -> Click.OTHER
        }
    }
}
