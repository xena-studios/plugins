package co.xenastudios.xrewind.bukkit.modern

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.resolve
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

// What Paper adds, each used only where BukkitRewindServer.Paths found it. Each in an object of
// its own: a server without Adventure (Spigot) can't verify a class that hands Adventure's types
// around, even in a method it never calls.

/** Paper 1.16.5 on: an action bar, from the text as `§` codes. */
internal object AdventureBar {
    fun send(player: Player, legacy: String) {
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(legacy))
    }
}

/** Paper: a block's state without copying it, read where it stands. */
internal object Blocks {
    fun state(block: Block): BlockState = block.getState(false)
}

/** Paper's item format, which carries its data version so items upgrade themselves: 1.x's. */
internal object Items {
    fun bytes(stack: ItemStack): ByteArray = stack.serializeAsBytes()

    fun stack(bytes: ByteArray): ItemStack = ItemStack.deserializeBytes(bytes)

    /** A container's slots, as 1.x wrote them: `I`, then Paper's items. */
    fun contents(items: Array<ItemStack?>): ByteArray {
        val copy = arrayOfNulls<ItemStack>(items.size)
        for (i in items.indices) copy[i] = items[i]?.takeIf { !it.isEmpty }
        val body = ItemStack.serializeItemsAsBytes(copy)
        return byteArrayOf('I'.code.toByte()) + body
    }

    fun readContents(bytes: ByteArray): Array<ItemStack?>? = try {
        @Suppress("UNCHECKED_CAST")
        (ItemStack.deserializeItemsFromBytes(bytes.copyOfRange(1, bytes.size)) as Array<ItemStack?>).map { it?.takeIf { s -> !s.isEmpty } }.toTypedArray()
    } catch (e: RuntimeException) {
        null
    }
}

/** 1.20 on: both sides of a sign, four lines each, front first, and its wax: 1.x's `S` format. */
internal object Signs {
    fun read(sign: Sign): ByteArray? {
        val lines = ArrayList<Component>(8)
        var any = false
        for (side in arrayOf(Side.FRONT, Side.BACK)) {
            for (line in sign.getSide(side).lines()) {
                lines += line
                any = any || line != Component.empty()
            }
        }
        if (!any) return null
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeByte('S'.code)
            data.writeBoolean(sign.isWaxed)
            data.writeByte(lines.size)
            for (line in lines) data.writeUTF(GsonComponentSerializer.gson().serialize(line))
        }
        return out.toByteArray()
    }

    fun write(sign: Sign, bytes: ByteArray) {
        try {
            DataInputStream(ByteArrayInputStream(bytes, 1, bytes.size - 1)).use { data ->
                val waxed = data.readBoolean()
                val count = data.readUnsignedByte()
                val sides = arrayOf(Side.FRONT, Side.BACK)
                for (i in 0 until count) {
                    val line = GsonComponentSerializer.gson().deserialize(data.readUTF())
                    if (i < 8) sign.getSide(sides[i / 4]).line(i % 4, line)
                }
                sign.isWaxed = waxed
            }
            sign.update(true, false)
        } catch (e: Exception) {
            // Not a sign this server can read: left blank.
        }
    }
}

/** Paper: chunks loaded off the server thread, and kept with a ticket while they're worked on. */
internal object Chunks {
    fun load(plugin: Plugin, world: World, x: Int, z: Int, tickets: Boolean): Pending<Boolean> {
        val out = newPending<Boolean>()
        world.getChunkAtAsync(x, z, true).whenComplete { chunk, error ->
            if (error != null || chunk == null) {
                out.resolve(false)
                return@whenComplete
            }
            if (tickets) chunk.addPluginChunkTicket(plugin)
            out.resolve(true)
        }
        return out
    }

    fun release(plugin: Plugin, world: World, x: Int, z: Int) {
        if (world.isChunkLoaded(x, z)) world.getChunkAt(x, z).removePluginChunkTicket(plugin)
    }
}
