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
 * What xRewind does on a Bukkit-family server from 1.13 (block data's first version) to the
 * newest Paper and Folia. Compiled against Spigot 1.13.2's API; what Paper adds is in `modern`,
 * used only where [Paths] found it at startup: chunks loaded off the server thread and kept with
 * a ticket, items in Paper's own format (1.x's), signs with both sides and their wax, action bars.
 * Without them (Spigot) chunks load on the server thread and items are written with Bukkit's
 * object stream.
 *
 * Blocks, contents and items are [BukkitBlocks]', made with the first that's needed.
 */
internal class BukkitRewindServer(private val plugin: Plugin, private val platform: BukkitPlatform) : RewindServer {
    private val bukkit = plugin.server
    @Volatile
    private var madeBlocks: BukkitBlocks? = null

    /** Blocks, contents and items, made with the first break, rollback or item: not as the server starts. */
    val blocks: BukkitBlocks get() = madeBlocks ?: synchronized(this) { madeBlocks ?: BukkitBlocks(plugin, platform).also { madeBlocks = it } }

    private fun player(ref: PlayerRef): Player? = ref.online(bukkit)

    override fun online(): List<PlayerRef> = bukkit.onlinePlayers.map { it.ref }
    override fun isOnline(player: PlayerRef): Boolean = player(player) != null
    override fun name(player: PlayerRef): String? = player(player)?.name

    override fun send(player: PlayerRef, message: Component) {
        player(player)?.let { platform.text.send(it, message) }
    }

    override fun actionBar(player: PlayerRef, message: Component) {
        val p = player(player) ?: return
        val legacy = platform.text.legacy.draw(message)
        blocks.actionBar(p, legacy)
    }

    override fun position(player: PlayerRef): Center? {
        val at = player(player)?.location ?: return null
        val world = at.world ?: return null
        return Center(world.name, at.blockX, at.blockY, at.blockZ)
    }

    override fun hooks(): WorldHooks = platform.world(EventPriority.MONITOR)

    override fun listen(kinds: Set<Heard>, events: ServerEvents): AutoCloseable = Listeners.listen(plugin, platform, kinds, events)

    override fun inspector(inspecting: Inspecting): AutoCloseable = Inspection.start(plugin, inspecting)

    override fun worlds(): List<String> = bukkit.worlds.map { it.name }

    override fun hasWorld(world: String): Boolean = bukkit.getWorld(world) != null

    override fun known(name: String): String? {
        val material = Material.matchMaterial(name) ?: return null
        @Suppress("DEPRECATION")
        if (material.isLegacy || !(material.isBlock || material.isItem)) return null
        return material.key.toString()
    }

    override fun holds(type: String): Boolean = Holders.holds(type)

    override fun contents(change: BlockChange): ByteArray? = blocks.contents(change)
    override fun itemBytes(item: ItemData): ByteArray? = blocks.itemBytes(item)
    override fun itemBytes(id: String): ByteArray? = blocks.itemBytes(id)
    override fun itemType(bytes: ByteArray): String? = blocks.itemType(bytes)
    override fun readState(state: String): String? = blocks.readState(state)
    override fun load(world: String, chunkX: Int, chunkZ: Int): Pending<Boolean> = blocks.load(world, chunkX, chunkZ)
    override fun release(world: String, chunkX: Int, chunkZ: Int) = blocks.release(world, chunkX, chunkZ)
    override fun set(at: Spot, state: String, contents: ByteArray?): Previous? = blocks.set(at, state, contents)
    override fun moveItems(at: Spot, item: ByteArray, delta: Int): Int = blocks.moveItems(at, item, delta)
    override fun stateAt(at: Spot): String? = blocks.stateAt(at)
    override fun preview(player: PlayerRef, blocks: List<Shown>) = this.blocks.preview(player, blocks)
}
