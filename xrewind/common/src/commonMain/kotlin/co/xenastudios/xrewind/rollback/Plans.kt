package co.xenastudios.xrewind.rollback

import co.xenastudios.core.common.locked
import co.xenastudios.xrewind.Spot
import co.xenastudios.xrewind.api.BlockFilter
import co.xenastudios.xrewind.api.Plan
import co.xenastudios.xrewind.api.PlanBlock
import co.xenastudios.xrewind.api.PlanItem
import co.xenastudios.xrewind.log.Actions
import co.xenastudios.xrewind.store.LoggedRow

/**
 * One thing to do at a block: set it to [target] (and put [contents] in it), or, for a container
 * change, add [delta] of the item in [item] (taking away when negative). Each carries the rows it
 * covers ([ids]), so only the rows of changes really made are marked.
 */
public class Change(
    public val spot: Spot,
    public val target: String?,
    public val contents: ByteArray?,
    public val item: ByteArray?,
    public val itemType: String?,
    public val delta: Int,
    public val ids: LongArray,
) {
    /** A container change (possibly of nothing, when what was added and taken evens out). */
    public val isItems: Boolean get() = target == null

    public companion object {
        public fun block(spot: Spot, target: String, contents: ByteArray?, ids: LongArray): Change = Change(spot, target, contents, null, null, 0, ids)

        public fun items(spot: Spot, item: ByteArray?, itemType: String?, delta: Int, ids: LongArray): Change = Change(spot, null, null, item, itemType, delta, ids)
    }
}

/**
 * What a rollback, restore or undo will do, as xRewind holds it: the API's [Plan], plus each
 * change's rows and what the rows' rolled_back flag becomes once it's applied ([markRolledBack]).
 * An API plan is applied once ([claim]); the plans events show are [spent] and can't be.
 */
public class RewindPlan(
    override val kind: Plan.Kind,
    public val changes: List<Change>,
    public val markRolledBack: Boolean,
    override val unreadable: Int,
    /** Shown in an event, or the sender's `/xrewind undo`: not the API's to apply. */
    public val spent: Boolean = false,
) : Plan {
    override val blocks: List<PlanBlock> by lazy {
        changes.filter { !it.isItems }.map { PlanBlock(it.spot.world, it.spot.x, it.spot.y, it.spot.z, it.target!!) }
    }

    override val items: List<PlanItem> by lazy {
        changes.filter { it.isItems && it.delta != 0 && it.itemType != null }.map { PlanItem(it.spot.world, it.spot.x, it.spot.y, it.spot.z, it.itemType!!, it.delta) }
    }

    private var claimed = false

    /** Claims it for applying: `false` if it's been applied, or is being. */
    public fun claim(): Boolean = locked(this) { if (claimed) false else true.also { claimed = true } }

    /** Gives a claim back: it wasn't applied after all. */
    public fun unclaim() {
        locked(this) { claimed = false }
    }

    /** The same plan, for an event: it can't be applied. */
    public fun asSpent(): RewindPlan = if (spent) this else RewindPlan(kind, changes, markRolledBack, unreadable, spent = true)

    override fun toString(): String = "Plan($kind, ${changes.size} changes, $unreadable unreadable)"
}

/**
 * Turns matching rows into a plan, each block's changes collapsed into one: a block changed 200
 * times is set once. Item changes to a container add up per item (1.x's `Planner`). Off the
 * server's threads. [readState] is the state as this server reads it, [itemType] an item's type
 * from its bytes, and [filter] the region the plan keeps to.
 */
public object Planner {
    /**
     * [blocks] and [containers] oldest first. With [restore] false, a rollback: each block goes back
     * to how it was before its earliest matching change, with what it held then, items taken put
     * back and items added taken out. With [restore], each block to how it was after its latest
     * change, and items moved again.
     */
    public fun plan(
        blocks: List<LoggedRow>,
        containers: List<LoggedRow>,
        restore: Boolean,
        readState: (String) -> String?,
        itemType: (ByteArray) -> String?,
        filter: BlockFilter? = null,
    ): RewindPlan {
        class Target(var state: String?, var contents: ByteArray?, val ids: ArrayList<Long>)

        val targets = LinkedHashMap<Spot, Target>()
        val spots = HashMap<String, Spot>()
        fun spot(world: String, x: Int, y: Int, z: Int): Spot {
            val key = "$world|$x|$y|$z"
            return spots.getOrPut(key) { Spot(world, x, y, z) }
        }
        for (row in blocks) {
            if (filter != null && !filter.includes(row.world, row.x, row.y, row.z)) continue
            val at = spot(row.world, row.x, row.y, row.z)
            val known = targets[at]
            if (known == null) {
                // What a broken block held goes back with it.
                targets[at] = if (restore) Target(row.after, null, arrayListOf(row.id))
                else Target(row.thing, if (row.action == Actions.BREAK) row.extra else null, arrayListOf(row.id))
            } else {
                known.ids += row.id
                if (restore) known.state = row.after
            }
        }
        val parsed = HashMap<String, String?>()
        val changes = ArrayList<Change>(targets.size)
        var skipped = 0
        for ((at, target) in targets) {
            val state = target.state?.let { parsed.getOrPut(it) { readState(it) } }
            if (state == null) {
                skipped++
                continue
            }
            changes += Change.block(at, state, target.contents, target.ids.toLongArray())
        }

        class Stack(val at: Spot, val item: ByteArray) {
            override fun equals(other: Any?) = other is Stack && other.at == at && other.item.contentEquals(item)
            override fun hashCode() = at.hashCode() * 31 + item.contentHashCode()
        }
        val deltas = LinkedHashMap<Stack, Int>()
        val stackIds = HashMap<Stack, ArrayList<Long>>()
        for (row in containers) {
            if (filter != null && !filter.includes(row.world, row.x, row.y, row.z)) continue
            val item = row.extra ?: continue
            val amount = if (row.action == Actions.ADD) row.amount else -row.amount
            val stack = Stack(spot(row.world, row.x, row.y, row.z), item)
            deltas[stack] = (deltas[stack] ?: 0) + if (restore) amount else -amount
            stackIds.getOrPut(stack) { ArrayList() } += row.id
        }
        for ((stack, delta) in deltas) {
            val ids = stackIds.getValue(stack).toLongArray()
            if (delta == 0) {
                // Added and taken in equal measure: nothing to do, but every row is accounted for.
                changes += Change.items(stack.at, null, null, 0, ids)
                continue
            }
            val type = itemType(stack.item)
            if (type == null) {
                skipped++
                continue
            }
            changes += Change.items(stack.at, stack.item, type, delta, ids)
        }
        return RewindPlan(if (restore) Plan.Kind.RESTORE else Plan.Kind.ROLLBACK, changes, !restore, skipped)
    }
}
