package co.xenastudios.core.neoforge.world

import co.xenastudios.core.common.world.ChangeCause
import co.xenastudios.core.common.world.SpawnReason
import co.xenastudios.core.common.world.WorldHook
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * Where the mixins (`co.xenastudios.core.neoforge.mixin`) hand over to the world hooks: what's
 * happening on the server thread right now (a player using an item, an explosion, an entity's
 * tick), as a stack of frames the mixins push and pop around Minecraft's own methods, and the
 * one place every block change goes through, `Level.setBlock`, which asks the stack who did it.
 * A change made with nothing on the stack (crops growing, redstone, worldgen, a plugin's own
 * rollback) isn't anyone's, and isn't heard.
 *
 * Each mod carries its own relocated copy of this, so its fields are that mod's alone. Every
 * entry point reads [heard] first: with no hook listening, a mixin costs one field read.
 * Frames are only kept on the server thread; anything else is ignored.
 */
public object WorldPoints {

    /** One bit per [WorldHook] some [NeoForgeWorldHooks] of this mod hears. */
    @JvmField
    @Volatile
    public var heard: Int = 0

    @Volatile
    private var hooks: Array<NeoForgeWorldHooks> = emptyArray()

    @Volatile
    private var serverThread: Thread? = null

    // The frames, on the server thread only: a few deep at most.
    private const val MAX = 64
    private val kinds = IntArray(MAX)
    private val players = arrayOfNulls<ServerPlayer>(MAX)
    private val sources = arrayOfNulls<Any>(MAX)
    private val clicked = arrayOfNulls<BlockPos>(MAX)
    private var depth = 0

    // Frame kinds: a cause's ordinal, or one of these.
    private const val SUPPRESS = 100
    private const val REASON = 101

    internal fun add(hook: NeoForgeWorldHooks, thread: Thread?) {
        synchronized(this) {
            if (thread != null) serverThread = thread
            hooks += hook
            refresh()
        }
    }

    internal fun remove(hook: NeoForgeWorldHooks) {
        synchronized(this) {
            hooks = hooks.filter { it !== hook }.toTypedArray()
            refresh()
        }
    }

    internal fun refresh() {
        synchronized(this) {
            var bits = 0
            for (hook in hooks) bits = bits or hook.bits
            heard = bits
        }
    }

    internal fun serverStarted(thread: Thread) {
        serverThread = thread
    }

    /** Whether [hook] is heard by anything here. */
    @JvmStatic
    public fun hears(hook: WorldHook): Boolean = heard and (1 shl hook.ordinal) != 0

    private fun onServerThread(): Boolean = Thread.currentThread() === serverThread

    /** A new tick: frames a method left behind (it threw) are dropped. */
    internal fun tick() {
        if (depth != 0) {
            for (i in 0 until depth) {
                players[i] = null
                sources[i] = null
                clicked[i] = null
            }
            depth = 0
        }
    }

    // Whether it pushed: a mixin's end pops only then (MixinExtras' @Share carries it across).
    private fun push(kind: Int, player: ServerPlayer?, source: Any?, at: BlockPos? = null): Boolean {
        if (!onServerThread()) return false
        if (depth >= MAX) {
            depth++
            return true
        }
        kinds[depth] = kind
        players[depth] = player
        sources[depth] = source
        clicked[depth] = at
        depth++
        return true
    }

    /** Ends the frame its method's start pushed (when that said it had). */
    @JvmStatic
    public fun pop() {
        if (depth == 0) return
        depth--
        if (depth < MAX) {
            players[depth] = null
            sources[depth] = null
            clicked[depth] = null
        }
    }

    private fun blocksHeard(): Boolean = heard and BLOCK_BITS != 0

    /** A player starts breaking, using an item on a block, or using an item: what they change is theirs. */
    @JvmStatic
    public fun player(player: ServerPlayer, at: BlockPos?): Boolean {
        if (heard and PLAYER_BITS == 0) return false
        return push(ChangeCause.PLAYER.ordinal, player, null, at)
    }

    /** An explosion starts changing blocks: [direct] is what exploded, [indirect] who set it off. */
    @JvmStatic
    public fun explosion(direct: Entity?, indirect: Entity?): Boolean {
        if (!hears(WorldHook.BLOCK_EXPLOSION)) return false
        val source = direct?.let { Names.entity(it) } ?: "minecraft:explosion"
        return push(ChangeCause.EXPLOSION.ordinal, indirect as? ServerPlayer, source)
    }

    /** Fire's tick starts: the blocks it burns are fire's. */
    @JvmStatic
    public fun fire(): Boolean {
        if (!hears(WorldHook.BLOCK_FIRE)) return false
        return push(ChangeCause.FIRE.ordinal, null, "minecraft:fire")
    }

    /** A fluid spreads into a block. */
    @JvmStatic
    public fun fluid(fluid: net.minecraft.world.level.material.FlowingFluid): Boolean {
        if (!hears(WorldHook.BLOCK_FLUID)) return false
        return push(ChangeCause.FLUID.ordinal, null, fluid.source)
    }

    /** An entity's tick starts: the blocks it changes are its own (a player's are theirs). */
    @JvmStatic
    public fun entity(entity: Entity): Boolean {
        if (entity is ServerPlayer) {
            if (!hears(WorldHook.BLOCK_PLAYER)) return false
            return push(ChangeCause.PLAYER.ordinal, entity, null)
        }
        if (!hears(WorldHook.BLOCK_ENTITY)) return false
        return push(ChangeCause.ENTITY.ordinal, null, entity)
    }

    /** Something starts that spawns entities for [reason]. */
    @JvmStatic
    public fun reason(reason: SpawnReason): Boolean {
        if (!hears(WorldHook.ENTITY_SPAWN)) return false
        return push(REASON, null, reason)
    }

    /**
     * A block is about to change in [level] at [pos] to [state]: the hooks hear it if someone's
     * on the stack, and the result says whether to stop it.
     */
    @JvmStatic
    public fun setBlock(level: Level, pos: BlockPos, state: BlockState): Boolean {
        if (!blocksHeard() || depth == 0 || level !is ServerLevel || !onServerThread()) return false
        var top = depth - 1
        if (top >= MAX) return false
        while (top >= 0 && kinds[top] == REASON) top--
        if (top < 0) return false
        val kind = kinds[top]
        if (kind == SUPPRESS) return false
        val cause = CAUSES[kind]
        if (heard and (1 shl cause.ordinal) == 0) return false
        val before = level.getBlockState(pos)
        if (before === state) return false
        when (cause) {
            // What fire and fluids spread into isn't a change: only what they destroy.
            ChangeCause.FIRE, ChangeCause.FLUID -> if (Names.spreadable(before)) return false
            // An entity pressing a plate or a tripwire isn't a change to the block; one turning
            // farmland to dirt or taking a block is.
            ChangeCause.ENTITY -> if (before.block === state.block) return false
            else -> {}
        }
        val player = players[top]
        val source = when (val from = sources[top]) {
            is Entity -> Names.entity(from)
            is net.minecraft.world.level.material.Fluid -> Names.fluid(from)
            is String -> from
            else -> null
        }
        var cancelled = false
        for (hook in hooks) {
            if (hook.blockChange(level, pos, before, state, cause, player, source, cause == ChangeCause.PLAYER)) cancelled = true
        }
        return cancelled
    }

    /**
     * A piston is about to move blocks: the hooks hear each spot they arrive at or leave, from
     * Minecraft's own resolver, and the result says whether to stop the piston. Minecraft's own
     * changes while it moves them aren't heard again.
     */
    @JvmStatic
    public fun piston(level: Level, pos: BlockPos, direction: Direction, extending: Boolean): Boolean {
        if (!hears(WorldHook.BLOCK_PISTON) || level !is ServerLevel || !onServerThread()) return false
        val resolver = net.minecraft.world.level.block.piston.PistonStructureResolver(level, pos, direction, extending)
        if (!resolver.resolve()) return false
        val moving = if (extending) direction else direction.opposite
        val source = Names.block(level.getBlockState(pos))
        val toPush = resolver.toPush
        val arriving = LinkedHashMap<BlockPos, BlockState>(toPush.size * 2)
        for (from in toPush) arriving[from.relative(moving)] = level.getBlockState(from)
        val air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
        var cancelled = false
        for (hook in hooks) {
            if (!hook.hearsHook(WorldHook.BLOCK_PISTON)) continue
            for (broken in resolver.toDestroy) {
                if (hook.blockChange(level, broken, level.getBlockState(broken), air, ChangeCause.PISTON, null, source, true)) cancelled = true
            }
            for ((to, state) in arriving) {
                if (hook.blockChange(level, to, level.getBlockState(to), state, ChangeCause.PISTON, null, source, true)) cancelled = true
            }
            for (from in toPush) {
                if (from !in arriving && hook.blockChange(level, from, level.getBlockState(from), air, ChangeCause.PISTON, null, source, true)) cancelled = true
            }
        }
        return cancelled
    }

    /** Minecraft starts moving a piston's blocks itself: nothing it sets is heard (see [piston]). */
    @JvmStatic
    public fun suppress(): Boolean {
        if (!hears(WorldHook.BLOCK_PISTON)) return false
        return push(SUPPRESS, null, null)
    }

    /** An entity is about to be added to [level]: the hooks hear it, and the result says whether to stop it. */
    @JvmStatic
    public fun spawn(level: ServerLevel, entity: Entity): Boolean {
        if (!hears(WorldHook.ENTITY_SPAWN)) return false
        var reason = SpawnReason.OTHER
        var player: ServerPlayer? = null
        if (onServerThread()) {
            var i = minOf(depth, MAX) - 1
            while (i >= 0) {
                if (kinds[i] == REASON) {
                    reason = sources[i] as SpawnReason
                    break
                }
                if (kinds[i] == ChangeCause.PLAYER.ordinal) {
                    player = players[i]
                    if (!Names.thrown(entity)) reason = SpawnReason.PLACED
                    break
                }
                i--
            }
            // A spawn egg's frame sits inside the player's: the player is still who did it.
            if (player == null) {
                for (j in i - 1 downTo 0) if (kinds[j] == ChangeCause.PLAYER.ordinal) {
                    player = players[j]
                    break
                }
            }
        }
        var cancelled = false
        for (hook in hooks) if (hook.spawn(level, entity, reason, player)) cancelled = true
        return cancelled
    }

    /** [player] is about to tame [entity]: the result says whether to stop it. */
    @JvmStatic
    public fun tame(entity: Entity, player: Player): Boolean {
        if (!hears(WorldHook.ENTITY_CHANGE) || player !is ServerPlayer) return false
        var cancelled = false
        for (hook in hooks) if (hook.tame(entity, player)) cancelled = true
        return cancelled
    }

    /** [player] opened a menu: if it's a block's container they clicked, note what it holds. */
    @JvmStatic
    public fun opened(player: ServerPlayer) {
        if (!hears(WorldHook.CONTAINER) || !onServerThread()) return
        var at: BlockPos? = null
        for (i in minOf(depth, MAX) - 1 downTo 0) {
            if (kinds[i] == ChangeCause.PLAYER.ordinal && players[i] === player) {
                at = clicked[i]
                if (at != null) break
            }
        }
        if (at == null) return
        for (hook in hooks) hook.opened(player, at)
    }

    /** [player] is closing their menu. */
    @JvmStatic
    public fun closing(player: ServerPlayer) {
        if (!hears(WorldHook.CONTAINER)) return
        for (hook in hooks) hook.closing(player)
    }

    // A hopper's move: what it had before, for [moved].
    private var moving: ItemStack = ItemStack.EMPTY
    private var movingCount = 0

    /** A hopper is about to move [stack] from [from] to [to]. */
    @JvmStatic
    public fun moving(stack: ItemStack) {
        if (!hears(WorldHook.CONTAINER_HOPPER) || !onServerThread()) return
        moving = stack.copyWithCount(1)
        movingCount = stack.count
    }

    /** A hopper moved what [moving] saw, from [from] to [to], [remaining] left over. */
    @JvmStatic
    public fun moved(from: Container?, to: Container, remaining: ItemStack) {
        if (!hears(WorldHook.CONTAINER_HOPPER) || !onServerThread() || movingCount == 0) return
        val count = movingCount - remaining.count
        val item = moving
        movingCount = 0
        moving = ItemStack.EMPTY
        if (count <= 0) return
        for (hook in hooks) hook.hopper(from, to, item, count)
    }

    /** [entity] died, [killer] credited. */
    internal fun died(entity: net.minecraft.world.entity.LivingEntity, killer: ServerPlayer?) {
        for (hook in hooks) hook.died(entity, killer)
    }

    private val CAUSES = ChangeCause.entries.toTypedArray()

    private val BLOCK_BITS: Int = ChangeCause.entries.fold(0) { bits, cause -> bits or (1 shl WorldHook.of(cause).ordinal) }

    // What a player's frame is pushed for: their own blocks, their containers, their spawns.
    private val PLAYER_BITS: Int = (1 shl WorldHook.BLOCK_PLAYER.ordinal) or (1 shl WorldHook.CONTAINER.ordinal) or (1 shl WorldHook.ENTITY_SPAWN.ordinal)
}
