package co.xenastudios.xrewind.fabric

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.event.Subscription
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.world.BlockChange
import co.xenastudios.core.common.world.ItemData
import co.xenastudios.core.common.world.WorldHooks
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.fabric.MinecraftRenderer
import co.xenastudios.core.fabric.level
import co.xenastudios.core.fabric.player
import co.xenastudios.core.fabric.ref
import co.xenastudios.xrewind.Heard
import co.xenastudios.xrewind.Holders
import co.xenastudios.xrewind.Inspecting
import co.xenastudios.xrewind.Previous
import co.xenastudios.xrewind.RewindServer
import co.xenastudios.xrewind.ServerEvents
import co.xenastudios.xrewind.Shown
import co.xenastudios.xrewind.Spot
import co.xenastudios.xrewind.query.Center
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.resources.RegistryOps
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.entity.HopperBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.concurrent.Volatile
//? if identifier {
import net.minecraft.resources.Identifier
//?} else
//import net.minecraft.resources.ResourceLocation as Identifier
//? if value_io {
import net.minecraft.util.ProblemReporter
import net.minecraft.world.level.storage.TagValueInput
//?}
//? if item_components {
import net.minecraft.nbt.NbtAccounter
//?}

/**
 * xRewind on Fabric, server-side, 1.20.1, 1.21.11 and 26.3: the world hooks' mixins for what
 * changes, Fabric API's events for joins, clicks and the inspector, chat through the core's chat
 * bridge, and Minecraft's own block states, block entities and items. Minecraft has no event for a
 * sign being written or a command before it runs, so neither is logged here (the config says so).
 *
 * **Contents' bytes**: `B`, then a block entity's own data as Minecraft saves it, gzipped (a
 * chest's items, a sign's text and wax, a banner's patterns, whatever the block keeps). **An
 * item**: `F`, then its stack as Minecraft saves it, gzipped. A Bukkit server's bytes (`I`, `J`,
 * `S`) aren't read here, nor these there: a history moved between the two keeps its blocks, and
 * a rollback leaves what it can't read as it is.
 */
internal class FabricRewindServer(private val platform: FabricPlatform) : RewindServer {
    private val server get() = platform.server

    private fun player(ref: PlayerRef): ServerPlayer? = server?.player(ref)

    override fun online(): List<PlayerRef> = server?.playerList?.players?.map { it.ref }.orEmpty()
    override fun isOnline(player: PlayerRef): Boolean = player(player) != null
    override fun name(player: PlayerRef): String? = player(player)?.scoreboardName

    override fun send(player: PlayerRef, message: Component) {
        val p = player(player) ?: return
        val drawn = MinecraftRenderer.draw(message)
        onServer { p.sendSystemMessage(drawn) }
    }

    override fun actionBar(player: PlayerRef, message: Component) {
        val p = player(player) ?: return
        val text = MinecraftRenderer.draw(message)
        onServer {
            //? if overlay_message {
            p.sendOverlayMessage(text)
            //?} else
            //p.displayClientMessage(text, true)
        }
    }

    private fun onServer(work: () -> Unit) {
        val s = server ?: return
        if (s.isSameThread) work() else s.execute(work)
    }

    override fun position(player: PlayerRef): Center? {
        val p = player(player) ?: return null
        val at = p.blockPosition()
        return Center(p.level().ref.name, at.x, at.y, at.z)
    }

    override fun hooks(): WorldHooks = platform.world(EventPriority.MONITOR)

    // ---- what the hooks don't cover: Fabric API's events can't be taken away, so each is
    // registered once, the first time it's wanted, and does nothing while nothing listens.

    @Volatile
    private var events: ServerEvents? = null

    @Volatile
    private var heard: Set<Heard> = emptySet()

    @Volatile
    private var inspecting: Inspecting? = null
    private var registered = false

    private fun register(): Unit = synchronized(this) {
        if (registered) return
        registered = true
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val events = events ?: return@register
            if (Heard.SESSIONS in heard) handler.player.let { events.joined(it.ref, it.scoreboardName, spot(it)) }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val events = events ?: return@register
            if (Heard.SESSIONS in heard) handler.player.let { events.quit(it.ref, it.scoreboardName, spot(it)) }
        }
        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            val p = player as? ServerPlayer ?: return@register InteractionResult.PASS
            if (hand != InteractionHand.MAIN_HAND) return@register InteractionResult.PASS
            val pos = hit.blockPos
            val inspect = inspecting
            if (inspect != null && inspect.isInspecting(p.ref)) {
                // Placing a block shows what was where it would go; using one shows its history.
                val held = p.getItemInHand(hand).item
                val at = if (held is BlockItem) pos.relative(hit.direction) else pos
                inspect.inspect(p.ref, Spot(level.ref.name, at.x, at.y, at.z))
                return@register InteractionResult.FAIL
            }
            val events = events
            if (events != null && Heard.CLICKS in heard) {
                val state = level.getBlockState(pos)
                if (state.hasProperty(BlockStateProperties.OPEN) || state.block is LeverBlock || state.block is ButtonBlock) {
                    events.click(p.ref, p.scoreboardName, Spot(level.ref.name, pos.x, pos.y, pos.z), text(state))
                }
            }
            InteractionResult.PASS
        }
        AttackBlockCallback.EVENT.register { player, level, _, pos, _ ->
            val p = player as? ServerPlayer ?: return@register InteractionResult.PASS
            val inspect = inspecting
            if (inspect == null || !inspect.isInspecting(p.ref)) return@register InteractionResult.PASS
            inspect.inspect(p.ref, Spot(level.ref.name, pos.x, pos.y, pos.z))
            InteractionResult.FAIL
        }
        PlayerBlockBreakEvents.BEFORE.register { _, player, _, _, _ ->
            val inspect = inspecting
            !(player is ServerPlayer && inspect != null && inspect.isInspecting(player.ref))
        }
    }

    private fun spot(player: ServerPlayer): Spot {
        val at = player.blockPosition()
        return Spot(player.level().ref.name, at.x, at.y, at.z)
    }

    override fun listen(kinds: Set<Heard>, events: ServerEvents): AutoCloseable {
        register()
        this.heard = kinds
        this.events = events
        var chat: Subscription? = null
        var bridge: co.xenastudios.core.common.chat.ChatBridge? = null
        if (Heard.CHAT in kinds) {
            val made = platform.chat(EventPriority.MONITOR)
            bridge = made
            chat = made.listen(EventPriority.MONITOR, true) { event ->
                val world = player(event.sender)?.level()?.ref?.name ?: return@listen
                events.chat(event.sender, event.senderName, world, event.originalMessage)
            }
        }
        return AutoCloseable {
            if (this.events === events) this.events = null
            chat?.cancel()
            bridge?.close()
        }
    }

    override fun inspector(inspecting: Inspecting): AutoCloseable {
        register()
        this.inspecting = inspecting
        return AutoCloseable { if (this.inspecting === inspecting) this.inspecting = null }
    }

    // ---- the world ----

    override fun worlds(): List<String> = server?.allLevels?.map { it.ref.name }.orEmpty()

    private fun level(world: String): ServerLevel? = server?.level(WorldRef.of(world))

    override fun hasWorld(world: String): Boolean = level(world) != null

    override fun known(name: String): String? {
        val id = Identifier.tryParse(if (':' in name) name.lowercase() else "minecraft:" + name.lowercase()) ?: return null
        return if (BuiltInRegistries.BLOCK.containsKey(id) || BuiltInRegistries.ITEM.containsKey(id)) id.toString() else null
    }

    override fun holds(type: String): Boolean = Holders.holds(type)

    override fun contents(change: BlockChange): ByteArray? {
        val level = change.nativeWorld as? ServerLevel ?: return null
        return contentsOf(level, BlockPos(change.x, change.y, change.z))
    }

    private fun contentsOf(level: ServerLevel, pos: BlockPos): ByteArray? {
        val entity = level.getBlockEntity(pos) ?: return null
        if (entity is Container && entity.isEmpty && !Holders.isSign(text(level.getBlockState(pos)).substringBefore('['))) return null
        //? if item_components {
        val tag = entity.saveWithoutMetadata(level.registryAccess())
        //?} else
        //val tag = entity.saveWithoutMetadata()
        return gzip(CONTENTS, tag)
    }

    private fun putContents(level: ServerLevel, pos: BlockPos, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes[0] != CONTENTS) return
        val entity = level.getBlockEntity(pos) ?: return
        val tag = gunzip(bytes) ?: return
        //? if value_io {
        entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag))
        //?} else
        //entity.load(tag)
        entity.setChanged()
        val state = level.getBlockState(pos)
        level.sendBlockUpdated(pos, state, state, 3)
    }

    override fun itemBytes(item: ItemData): ByteArray? {
        val stack = (item.native as? ItemStack)?.copy() ?: return null
        return bytes(stack.copyWithCount(1))
    }

    override fun itemBytes(id: String): ByteArray? {
        val key = Identifier.tryParse(id) ?: return null
        if (!BuiltInRegistries.ITEM.containsKey(key)) return null
        //? if registry_lookup {
        val item = BuiltInRegistries.ITEM.getValue(key)
        //?} else
        //val item = BuiltInRegistries.ITEM.get(key)
        return bytes(ItemStack(item))
    }

    private fun ops(): RegistryOps<net.minecraft.nbt.Tag>? = server?.registryAccess()?.let { RegistryOps.create(NbtOps.INSTANCE, it) }

    private fun bytes(stack: ItemStack): ByteArray? {
        if (stack.isEmpty) return null
        val ops = ops() ?: return null
        val encoded = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null) ?: return null
        val tag = CompoundTag()
        tag.put("item", encoded)
        return gzip(ITEM, tag)
    }

    private fun stack(bytes: ByteArray): ItemStack? {
        if (bytes.isEmpty() || bytes[0] != ITEM) return null
        val tag = gunzip(bytes) ?: return null
        val ops = ops() ?: return null
        val item = tag.get("item") ?: return null
        return ItemStack.CODEC.parse(ops, item).result().orElse(null)
    }

    override fun itemType(bytes: ByteArray): String? = stack(bytes)?.let { BuiltInRegistries.ITEM.getKey(it.item).toString() }

    override fun readState(state: String): String? = parse(state)?.let(::text)

    private fun parse(state: String): BlockState? = try {
        //? if registry_lookup {
        BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false).blockState()
        //?} else
        //BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), state, false).blockState()
    } catch (e: Exception) {
        // A state from an older Minecraft that's since changed (a renamed property): its block
        // with default properties. A block that no longer exists can't be read.
        try {
            //? if registry_lookup {
            BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state.substringBefore('['), false).blockState()
            //?} else
            //BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), state.substringBefore('['), false).blockState()
        } catch (e: Exception) {
            null
        }
    }

    private fun text(state: BlockState): String = BlockStateParser.serialize(state)

    // ---- applying ----

    // On the server thread, where the applier asks: loaded now, and kept loaded while it's worked on
    // in this tick, which is how long a chunk's budget lasts at most beyond the next few.
    override fun load(world: String, chunkX: Int, chunkZ: Int): Pending<Boolean> {
        val level = level(world) ?: return pendingOf(false)
        level.getChunk(chunkX, chunkZ)
        return pendingOf(true)
    }

    override fun release(world: String, chunkX: Int, chunkZ: Int) {}

    override fun set(at: Spot, state: String, contents: ByteArray?): Previous? {
        val level = level(at.world) ?: return null
        val pos = BlockPos(at.x, at.y, at.z)
        val before = level.getBlockState(pos)
        val beforeText = text(before)
        val held = if (Holders.holds(BuiltInRegistries.BLOCK.getKey(before.block).toString())) contentsOf(level, pos) else null
        if (beforeText != state) {
            val target = parse(state) ?: return null
            // Set without physics, a container would spill what it held: empty it first.
            (level.getBlockEntity(pos) as? Container)?.clearContent()
            level.setBlock(pos, target, SET_FLAGS)
        }
        if (contents != null) putContents(level, pos, contents)
        return Previous(beforeText, held)
    }

    override fun moveItems(at: Spot, item: ByteArray, delta: Int): Int {
        val level = level(at.world) ?: return 0
        val container = level.getBlockEntity(BlockPos(at.x, at.y, at.z)) as? Container ?: return 0
        val one = stack(item) ?: return 0
        if (delta > 0) {
            val left = HopperBlockEntity.addItem(null, container, one.copyWithCount(delta), null)
            return delta - left.count
        }
        var wanted = -delta
        for (slot in 0 until container.containerSize) {
            if (wanted == 0) break
            val held = container.getItem(slot)
            //? if item_components {
            val same = ItemStack.isSameItemSameComponents(held, one)
            //?} else
            //val same = ItemStack.isSameItemSameTags(held, one)
            if (held.isEmpty || !same) continue
            val taken = minOf(wanted, held.count)
            held.shrink(taken)
            wanted -= taken
        }
        container.setChanged()
        return -(-delta - wanted)
    }

    override fun stateAt(at: Spot): String? = level(at.world)?.getBlockState(BlockPos(at.x, at.y, at.z))?.let(::text)

    override fun preview(player: PlayerRef, blocks: List<Shown>) {
        val p = player(player) ?: return
        onServer {
            val world = p.level().ref.name
            for (shown in blocks) {
                if (shown.spot.world != world) continue
                val state = parse(shown.state) ?: continue
                p.connection.send(ClientboundBlockUpdatePacket(BlockPos(shown.spot.x, shown.spot.y, shown.spot.z), state))
            }
        }
    }

    private fun gzip(kind: Byte, tag: CompoundTag): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(kind.toInt())
        NbtIo.writeCompressed(tag, out)
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): CompoundTag? = try {
        val input = ByteArrayInputStream(bytes, 1, bytes.size - 1)
        //? if item_components {
        NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap())
        //?} else
        //NbtIo.readCompressed(input)
    } catch (e: Exception) {
        null
    }

    companion object {
        const val CONTENTS: Byte = 'B'.code.toByte()
        const val ITEM: Byte = 'F'.code.toByte()

        /** Tell clients (2), skip neighbours' shape updates (16) and drop nothing (32): no physics. */
        const val SET_FLAGS = 2 or 16 or 32
    }
}
