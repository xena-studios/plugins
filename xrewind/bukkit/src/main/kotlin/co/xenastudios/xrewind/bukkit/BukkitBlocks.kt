package co.xenastudios.xrewind.bukkit

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.bukkit.world.world
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.world.BlockChange
import co.xenastudios.core.common.world.ItemData
import co.xenastudios.core.common.world.WorldHooks
import co.xenastudios.xrewind.Heard
import co.xenastudios.xrewind.Holders
import co.xenastudios.xrewind.Inspecting
import co.xenastudios.xrewind.Previous
import co.xenastudios.xrewind.RewindServer
import co.xenastudios.xrewind.ServerEvents
import co.xenastudios.xrewind.Shown
import co.xenastudios.xrewind.Spot
import co.xenastudios.xrewind.query.Center
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Chest
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Blocks, their contents and items on a Bukkit-family server, for the logger and for rollbacks:
 * made the first time one is needed (a break, a rollback, an item), so none of it loads as the
 * server starts. Paper's ways ([Paths]) are found then too.
 *
 * **Contents' bytes** (with a block's break, 1.x's format): `I` and Paper's item format, or `J`
 * and Bukkit's object stream, for a container's slots; `S` for a sign: waxed, the line count, and
 * each line as chat JSON, front then back. An item is Paper's bytes as they are (1.x's), or Bukkit's
 * object stream (which starts `0xACED`, never Paper's gzip header).
 */
internal class BukkitBlocks(private val plugin: Plugin, private val platform: BukkitPlatform) {
    private val bukkit = plugin.server
    val paths: Paths = Paths.detect()

    private fun player(ref: PlayerRef): Player? = ref.online(bukkit)

    fun actionBar(player: Player, legacy: String) {
        if (paths.adventure) co.xenastudios.xrewind.bukkit.modern.AdventureBar.send(player, legacy) else SpigotBar.send(player, legacy)
    }

    fun contents(change: BlockChange): ByteArray? {
        val world = change.nativeWorld as? World ?: return null
        return contentsOf(world.getBlockAt(change.x, change.y, change.z))
    }

    /** What [block] holds now, as bytes; `null` if nothing. On its thread. */
    private fun contentsOf(block: Block): ByteArray? {
        val state: BlockState = if (paths.stateNoSnapshot) co.xenastudios.xrewind.bukkit.modern.Blocks.state(block) else block.state
        if (state is Container) {
            val items = own(state).contents
            if (items.none { it != null && it.type != Material.AIR }) return null
            return if (paths.paperItems) co.xenastudios.xrewind.bukkit.modern.Items.contents(items) else javaContents(items)
        }
        if (state is Sign) {
            return if (paths.signSides) co.xenastudios.xrewind.bukkit.modern.Signs.read(state) else SpigotSigns.read(state)
        }
        return null
    }

    private fun putContents(block: Block, contents: ByteArray) {
        val state = block.state
        when {
            state is Container && (contents[0] == ITEMS || contents[0] == JAVA_ITEMS) -> {
                val items = (if (contents[0] == ITEMS) {
                    if (paths.paperItems) co.xenastudios.xrewind.bukkit.modern.Items.readContents(contents) else null
                } else {
                    readJavaContents(contents)
                }) ?: return
                val inventory = own(state)
                inventory.contents = items.copyOf(inventory.size)
            }
            state is Sign && contents[0] == SIGN -> {
                if (paths.signSides) co.xenastudios.xrewind.bukkit.modern.Signs.write(state, contents) else SpigotSigns.write(state, contents)
            }
        }
    }

    /**
     * The block's own slots. A chest beside another is one 54-slot inventory to players, but each
     * half is its own block: only this half's 27 slots are kept, put back or emptied.
     */
    private fun own(container: Container): Inventory = if (container is Chest) container.blockInventory else container.inventory

    fun itemBytes(item: ItemData): ByteArray? {
        val stack = (item.native as? ItemStack)?.clone() ?: return null
        stack.amount = 1
        return bytes(stack)
    }

    fun itemBytes(id: String): ByteArray? {
        val material = Material.matchMaterial(id) ?: return null
        @Suppress("DEPRECATION")
        if (material.isLegacy || !material.isItem || material == Material.AIR) return null
        return bytes(ItemStack(material))
    }

    fun bytes(stack: ItemStack): ByteArray =
        if (paths.paperItems) co.xenastudios.xrewind.bukkit.modern.Items.bytes(stack) else javaBytes(stack)

    private fun stack(bytes: ByteArray): ItemStack? = try {
        if (bytes.size > 1 && bytes[0] == 0xAC.toByte() && bytes[1] == 0xED.toByte()) {
            BukkitObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as? ItemStack }
        } else if (paths.paperItems) {
            co.xenastudios.xrewind.bukkit.modern.Items.stack(bytes)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    fun itemType(bytes: ByteArray): String? = stack(bytes)?.type?.key?.toString()

    fun readState(state: String): String? = try {
        Bukkit.createBlockData(state).asString
    } catch (e: IllegalArgumentException) {
        // From an older Minecraft that's since changed (a renamed property): its block with
        // default properties. A block that no longer exists can't be read.
        val material = Material.matchMaterial(state.substringBefore('['))
        if (material != null && material.isBlock) material.createBlockData().asString else null
    }

    // ---- applying ----

    fun load(world: String, chunkX: Int, chunkZ: Int): Pending<Boolean> {
        val w = bukkit.getWorld(world) ?: return pendingOf(false)
        if (paths.asyncChunks) return co.xenastudios.xrewind.bukkit.modern.Chunks.load(plugin, w, chunkX, chunkZ, paths.tickets)
        // Spigot: on the server thread, which is where the applier asks.
        w.loadChunk(chunkX, chunkZ, true)
        return pendingOf(true)
    }

    fun release(world: String, chunkX: Int, chunkZ: Int) {
        val w = bukkit.getWorld(world) ?: return
        if (paths.tickets) co.xenastudios.xrewind.bukkit.modern.Chunks.release(plugin, w, chunkX, chunkZ)
    }

    fun set(at: Spot, state: String, contents: ByteArray?): Previous? {
        val world = bukkit.getWorld(at.world) ?: return null
        val block = world.getBlockAt(at.x, at.y, at.z)
        val before = block.blockData
        val beforeText = before.asString
        val held = if (Holders.holds(before.material.key.toString())) contentsOf(block) else null
        if (beforeText != state) {
            val target = try {
                Bukkit.createBlockData(state)
            } catch (e: IllegalArgumentException) {
                return null
            }
            // Set without physics, a container would spill what it held: empty it first.
            if (held != null) (block.state as? Container)?.let { own(it).clear() }
            block.setBlockData(target, false)
        }
        if (contents != null && contents.isNotEmpty()) putContents(block, contents)
        return Previous(beforeText, held)
    }

    fun moveItems(at: Spot, item: ByteArray, delta: Int): Int {
        val world = bukkit.getWorld(at.world) ?: return 0
        val container = world.getBlockAt(at.x, at.y, at.z).state as? Container ?: return 0
        val stack = stack(item) ?: return 0
        val amount = kotlin.math.abs(delta)
        stack.amount = amount
        val left = if (delta > 0) container.inventory.addItem(stack) else container.inventory.removeItem(stack)
        val moved = amount - left.values.sumOf { it.amount }
        return if (delta > 0) moved else -moved
    }

    fun stateAt(at: Spot): String? = bukkit.getWorld(at.world)?.getBlockAt(at.x, at.y, at.z)?.blockData?.asString

    fun preview(player: PlayerRef, blocks: List<Shown>) {
        val p = player(player) ?: return
        platform.scheduler.run(Where.on(player)) {
            val world = p.world
            for (shown in blocks) {
                if (shown.spot.world != world.name) continue
                val data = try {
                    Bukkit.createBlockData(shown.state)
                } catch (e: IllegalArgumentException) {
                    continue
                }
                p.sendBlockChange(org.bukkit.Location(world, shown.spot.x.toDouble(), shown.spot.y.toDouble(), shown.spot.z.toDouble()), data)
            }
        }
    }

    // ---- Spigot's ways ----

    private fun javaBytes(stack: ItemStack): ByteArray {
        val out = ByteArrayOutputStream()
        BukkitObjectOutputStream(out).use { it.writeObject(stack) }
        return out.toByteArray()
    }

    private fun javaContents(items: Array<ItemStack?>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(JAVA_ITEMS.toInt())
        BukkitObjectOutputStream(out).use { stream ->
            stream.writeInt(items.size)
            for (item in items) stream.writeObject(item?.takeIf { it.type != Material.AIR })
        }
        return out.toByteArray()
    }

    private fun readJavaContents(bytes: ByteArray): Array<ItemStack?>? = try {
        BukkitObjectInputStream(ByteArrayInputStream(bytes, 1, bytes.size - 1)).use { stream ->
            Array(stream.readInt()) { stream.readObject() as? ItemStack }
        }
    } catch (e: Exception) {
        null
    }

    /** A sign's lines on a server without sides (before 1.20) or Adventure: its front only, as chat JSON. */
    private object SpigotSigns {
        fun read(sign: Sign): ByteArray? {
            @Suppress("DEPRECATION")
            val lines = sign.lines
            if (lines.all { it.isNullOrEmpty() }) return null
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { data ->
                data.writeByte(SIGN.toInt())
                data.writeBoolean(false)
                data.writeByte(lines.size)
                for (line in lines) data.writeUTF(net.md_5.bungee.chat.ComponentSerializer.toString(*net.md_5.bungee.api.chat.TextComponent.fromLegacyText(line ?: "")))
            }
            return out.toByteArray()
        }

        fun write(sign: Sign, bytes: ByteArray) {
            try {
                DataInputStream(ByteArrayInputStream(bytes, 1, bytes.size - 1)).use { data ->
                    data.readBoolean()
                    val count = data.readUnsignedByte()
                    for (i in 0 until count) {
                        val text = net.md_5.bungee.api.chat.BaseComponent.toLegacyText(*net.md_5.bungee.chat.ComponentSerializer.parse(data.readUTF()))
                        @Suppress("DEPRECATION")
                        if (i < 4) sign.setLine(i, text)
                    }
                }
                sign.update(true, false)
            } catch (e: Exception) {
                // Not a sign this server can read: left blank.
            }
        }
    }

    private object SpigotBar {
        fun send(player: Player, legacy: String) {
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, *net.md_5.bungee.api.chat.TextComponent.fromLegacyText(legacy))
        }
    }

    /** What this server has beyond Spigot 1.13.2's API, found once at startup. */
    internal class Paths(
        val asyncChunks: Boolean,
        val tickets: Boolean,
        val paperItems: Boolean,
        val signSides: Boolean,
        val adventure: Boolean,
        val stateNoSnapshot: Boolean,
    ) {
        companion object {
            fun detect(): Paths {
                val loader = Player::class.java.classLoader
                fun exists(type: String): Boolean = try {
                    Class.forName(type, false, loader)
                    true
                } catch (e: ClassNotFoundException) {
                    false
                }
                fun has(type: String, method: String, vararg parameters: Class<*>): Boolean = try {
                    Class.forName(type, false, loader).getMethod(method, *parameters)
                    true
                } catch (e: ReflectiveOperationException) {
                    false
                } catch (e: LinkageError) {
                    false
                }
                val int = Int::class.javaPrimitiveType!!
                val boolean = Boolean::class.javaPrimitiveType!!
                val adventure = try {
                    Player::class.java.getMethod("sendActionBar", Class.forName("net.kyori.adventure.text.Component", false, loader))
                    true
                } catch (e: ReflectiveOperationException) {
                    false
                } catch (e: LinkageError) {
                    false
                }
                return Paths(
                    asyncChunks = has("org.bukkit.World", "getChunkAtAsync", int, int, boolean),
                    tickets = has("org.bukkit.Chunk", "addPluginChunkTicket", Plugin::class.java),
                    paperItems = has("org.bukkit.inventory.ItemStack", "serializeItemsAsBytes", Array<ItemStack>::class.java) &&
                        has("org.bukkit.inventory.ItemStack", "deserializeBytes", ByteArray::class.java),
                    // Sides came in 1.20, waxing in 1.20.1: both, or a sign's front alone.
                    signSides = exists("org.bukkit.block.sign.Side") && has("org.bukkit.block.Sign", "isWaxed") && adventure &&
                        exists("net.kyori.adventure.text.serializer.gson.GsonComponentSerializer"),
                    adventure = adventure,
                    stateNoSnapshot = has("org.bukkit.block.Block", "getState", boolean),
                )
            }
        }
    }

    companion object {
        const val ITEMS: Byte = 'I'.code.toByte()
        const val JAVA_ITEMS: Byte = 'J'.code.toByte()
        const val SIGN: Byte = 'S'.code.toByte()
    }
}
