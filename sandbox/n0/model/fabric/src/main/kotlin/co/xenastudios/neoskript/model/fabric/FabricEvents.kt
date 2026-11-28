package co.xenastudios.neoskript.model.fabric

import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.common.world.BlockChange
import co.xenastudios.core.common.world.ChangeCause
import co.xenastudios.core.common.world.WorldHooks
import co.xenastudios.neoskript.model.EventBinding
import co.xenastudios.neoskript.model.EventKind
import co.xenastudios.neoskript.model.EventValue
import co.xenastudios.neoskript.model.EventValueKey
import co.xenastudios.neoskript.model.Events
import co.xenastudios.neoskript.model.Priority
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.toJavaUuid

/** A player joined or left: Fabric API's callback has no event object, so this is ours. */
public class FabricPlayerEvent(public val player: ServerPlayer)

/** A player chatted; [cancelled] stops the message (Fabric API's `ALLOW_CHAT_MESSAGE`). */
public class FabricChatEvent(public val player: ServerPlayer, public val message: String) {
    public var cancelled: Boolean = false
}

/** A living entity is about to be hurt; [cancelled] stops it (`ALLOW_DAMAGE`). The amount can't be changed there. */
public class FabricDamageEvent(public val victim: LivingEntity, public val source: DamageSource, public val amount: Float) {
    public var cancelled: Boolean = false
}

/** A living entity died (`AFTER_DEATH`). */
public class FabricDeathEvent(public val victim: LivingEntity, public val source: DamageSource)

/**
 * The model's events on Fabric. Joins, quits, chat, damage and death are Fabric API callbacks,
 * registered once each on first use (Fabric's callbacks can't be unregistered, so scripts'
 * triggers come and go on lists of ours); block breaks and places are the core's world hooks
 * (its mixins: Fabric API has no event for a place), whose own event, [BlockChange], is the one
 * triggers get.
 */
public class FabricEvents internal constructor(private val game: FabricGame) : Events {

    private class Listener(val priority: Priority, val ignoreCancelled: Boolean, val trigger: (Any) -> Unit)

    /** One kind's listeners, in priority order, called by its one Fabric callback. */
    private class Dispatch {
        @Volatile
        var registered = false
        val listeners = CopyOnWriteArrayList<Listener>()

        fun add(listener: Listener): AutoCloseable {
            listeners += listener
            // Few listeners and rare changes: sort on add, so the call needs none.
            val sorted = listeners.sortedBy { it.priority.ordinal }
            listeners.clear()
            listeners.addAll(sorted)
            return AutoCloseable { listeners.remove(listener) }
        }

        inline fun fire(event: Any, cancelled: () -> Boolean) {
            for (listener in listeners) {
                if (listener.ignoreCancelled && cancelled()) continue
                listener.trigger(event)
            }
        }
    }

    private val joins = Dispatch()
    private val quits = Dispatch()
    private val chats = Dispatch()
    private val damages = Dispatch()
    private val deaths = Dispatch()

    private fun register(dispatch: Dispatch, register: () -> Unit) {
        synchronized(dispatch) {
            if (!dispatch.registered) {
                register()
                dispatch.registered = true
            }
        }
    }

    // The core's world hooks for this mod's scripts; nothing is heard until a trigger listens.
    private val hooks: WorldHooks by lazy { game.core.world(EventPriority.NORMAL) }

    private open class Binding(
        override val kind: EventKind,
        val values: Map<EventValueKey, EventValue>,
        override val cancellable: Boolean,
        val owner: (Any) -> Any?,
    ) : EventBinding {
        override fun value(key: EventValueKey): EventValue? = values[key]
        override val async: Boolean get() = false
        override fun owner(event: Any): Any? = owner.invoke(event)
        override fun isCancelled(event: Any): Boolean = when (event) {
            is FabricChatEvent -> event.cancelled
            is FabricDamageEvent -> event.cancelled
            is BlockChange -> event.isCancelled
            else -> false
        }
        override fun setCancelled(event: Any, cancelled: Boolean) {
            when (event) {
                is FabricChatEvent -> event.cancelled = cancelled
                is FabricDamageEvent -> event.cancelled = cancelled
                is BlockChange -> event.isCancelled = cancelled
            }
        }
    }

    private val playerOf = EventValue { (it as FabricPlayerEvent).player }
    private val playerWorld = EventValue { (it as FabricPlayerEvent).player.level() }

    // A world hook names its player by UUID: they're online, as they just did it.
    private val changer = EventValue { game.server.playerList.getPlayer((it as BlockChange).player!!.uuid.toJavaUuid()) }
    // A place is heard before the block is in the world: the event's block is the one it leaves.
    private val changed = EventValue {
        val c = it as BlockChange
        FabricBlock(c.nativeWorld as ServerLevel, BlockPos(c.x, c.y, c.z), if (c.isPlace) c.after.native as BlockState else null)
    }
    private val changedWorld = EventValue { (it as BlockChange).nativeWorld }

    private val bindings: Map<EventKind, Binding> = listOf(
        Binding(EventKind.JOIN, mapOf(EventValueKey.PLAYER to playerOf, EventValueKey.WORLD to playerWorld), false) { (it as FabricPlayerEvent).player },
        Binding(EventKind.QUIT, mapOf(EventValueKey.PLAYER to playerOf, EventValueKey.WORLD to playerWorld), false) { (it as FabricPlayerEvent).player },
        Binding(
            EventKind.CHAT,
            mapOf(EventValueKey.PLAYER to EventValue { (it as FabricChatEvent).player }, EventValueKey.MESSAGE to EventValue { (it as FabricChatEvent).message }),
            true,
        ) { (it as FabricChatEvent).player },
        Binding(EventKind.BLOCK_BREAK, mapOf(EventValueKey.PLAYER to changer, EventValueKey.BLOCK to changed, EventValueKey.WORLD to changedWorld), true) { changed.get(it) },
        Binding(EventKind.BLOCK_PLACE, mapOf(EventValueKey.PLAYER to changer, EventValueKey.BLOCK to changed, EventValueKey.WORLD to changedWorld), true) { changed.get(it) },
        Binding(
            EventKind.DAMAGE,
            mapOf(
                EventValueKey.VICTIM to EventValue { (it as FabricDamageEvent).victim },
                EventValueKey.ATTACKER to EventValue { (it as FabricDamageEvent).source.entity },
                // Read-only here: ALLOW_DAMAGE can stop a hit but not change it (a mixin could).
                EventValueKey.DAMAGE to EventValue { (it as FabricDamageEvent).amount.toDouble() },
            ),
            true,
        ) { (it as FabricDamageEvent).victim },
        Binding(
            EventKind.DEATH,
            mapOf(
                EventValueKey.VICTIM to EventValue { (it as FabricDeathEvent).victim },
                EventValueKey.ATTACKER to EventValue { (it as FabricDeathEvent).source.entity },
            ),
            false,
        ) { (it as FabricDeathEvent).victim },
    ).associateBy { it.kind }

    override fun binding(kind: EventKind): EventBinding? = bindings[kind]

    override fun listen(kind: EventKind, priority: Priority, ignoreCancelled: Boolean, trigger: (Any) -> Unit): AutoCloseable {
        val listener = Listener(priority, ignoreCancelled, trigger)
        return when (kind) {
            EventKind.JOIN -> {
                register(joins) { ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> joins.fire(FabricPlayerEvent(handler.player)) { false } } }
                joins.add(listener)
            }
            EventKind.QUIT -> {
                // DISCONNECT can come on a network thread (it did on 1.21.11 when a client quit):
                // triggers run on the server thread, as everywhere else.
                register(quits) {
                    ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
                        val event = FabricPlayerEvent(handler.player)
                        if (server.isSameThread) quits.fire(event) { false } else server.execute { quits.fire(event) { false } }
                    }
                }
                quits.add(listener)
            }
            EventKind.CHAT -> {
                register(chats) {
                    ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { message, sender, _ ->
                        val event = FabricChatEvent(sender, message.signedContent())
                        chats.fire(event) { event.cancelled }
                        !event.cancelled
                    }
                }
                chats.add(listener)
            }
            EventKind.DAMAGE -> {
                register(damages) {
                    ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, amount ->
                        val event = FabricDamageEvent(entity, source, amount)
                        damages.fire(event) { event.cancelled }
                        !event.cancelled
                    }
                }
                damages.add(listener)
            }
            EventKind.DEATH -> {
                register(deaths) { ServerLivingEntityEvents.AFTER_DEATH.register { entity, source -> deaths.fire(FabricDeathEvent(entity, source)) { false } } }
                deaths.add(listener)
            }
            EventKind.BLOCK_BREAK, EventKind.BLOCK_PLACE -> {
                val breaks = kind == EventKind.BLOCK_BREAK
                val subscription = hooks.onBlockChange(setOf(ChangeCause.PLAYER), EventPriority.valueOf(priority.name), ignoreCancelled) { change ->
                    if (change.player != null && (if (breaks) change.isBreak else change.isPlace)) trigger(change)
                }
                AutoCloseable { subscription.cancel() }
            }
            else -> throw UnsupportedOperationException("$kind can't be heard on ${game.platform}")
        }
    }
}
