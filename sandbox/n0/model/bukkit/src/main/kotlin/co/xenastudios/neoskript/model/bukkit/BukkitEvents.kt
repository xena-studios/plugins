package co.xenastudios.neoskript.model.bukkit

import co.xenastudios.neoskript.model.ChangeableEventValue
import co.xenastudios.neoskript.model.EventBinding
import co.xenastudios.neoskript.model.EventKind
import co.xenastudios.neoskript.model.EventValue
import co.xenastudios.neoskript.model.EventValueKey
import co.xenastudios.neoskript.model.Events
import co.xenastudios.neoskript.model.Priority
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.EventExecutor

/**
 * The model's events on Bukkit: each kind is Bukkit's own event, which triggers get as it is,
 * so Skript's add-ons and other plugins see the same object. Values are getters on it.
 */
public class BukkitEvents internal constructor(private val game: BukkitGame) : Events {

    private val legacy = LegacyComponentSerializer.legacySection()

    private class Binding<E : Event>(
        override val kind: EventKind,
        val type: Class<E>,
        override val async: Boolean = false,
        val values: Map<EventValueKey, EventValue>,
        val owner: (E) -> Any?,
    ) : EventBinding {
        override fun value(key: EventValueKey): EventValue? = values[key]
        override val cancellable: Boolean = Cancellable::class.java.isAssignableFrom(type)
        override fun isCancelled(event: Any): Boolean = (event as? Cancellable)?.isCancelled ?: false
        override fun setCancelled(event: Any, cancelled: Boolean) {
            (event as? Cancellable)?.isCancelled = cancelled
        }
        @Suppress("UNCHECKED_CAST")
        override fun owner(event: Any): Any? = owner(event as E)
    }

    private val player = EventValue { (it as PlayerEvent).player }
    private val playerWorld = EventValue { (it as PlayerEvent).player.world }

    internal val damageValue: ChangeableEventValue = object : ChangeableEventValue {
        override fun get(event: Any): Any = (event as EntityDamageEvent).damage
        override fun set(event: Any, value: Any?) {
            (event as EntityDamageEvent).damage = (value as Number).toDouble()
        }
    }

    private val bindings: Map<EventKind, Binding<*>> = listOf(
        Binding(EventKind.JOIN, PlayerJoinEvent::class.java, values = mapOf(EventValueKey.PLAYER to player, EventValueKey.WORLD to playerWorld)) { it.player },
        Binding(EventKind.QUIT, PlayerQuitEvent::class.java, values = mapOf(EventValueKey.PLAYER to player, EventValueKey.WORLD to playerWorld)) { it.player },
        // Paper's chat is async: the trigger runs off the server's threads.
        Binding(
            EventKind.CHAT, AsyncChatEvent::class.java, async = true,
            values = mapOf(
                EventValueKey.PLAYER to player,
                EventValueKey.MESSAGE to EventValue { legacy.serialize((it as AsyncChatEvent).message()) },
            ),
        ) { it.player },
        Binding(
            EventKind.BLOCK_BREAK, BlockBreakEvent::class.java,
            values = mapOf(
                EventValueKey.PLAYER to EventValue { (it as BlockBreakEvent).player },
                EventValueKey.BLOCK to EventValue { (it as BlockBreakEvent).block },
                EventValueKey.WORLD to EventValue { (it as BlockBreakEvent).block.world },
            ),
        ) { it.block },
        Binding(
            EventKind.BLOCK_PLACE, BlockPlaceEvent::class.java,
            values = mapOf(
                EventValueKey.PLAYER to EventValue { (it as BlockPlaceEvent).player },
                EventValueKey.BLOCK to EventValue { (it as BlockPlaceEvent).blockPlaced },
                EventValueKey.WORLD to EventValue { (it as BlockPlaceEvent).block.world },
            ),
        ) { it.block },
        Binding(
            EventKind.DAMAGE, EntityDamageEvent::class.java,
            values = mapOf(
                EventValueKey.VICTIM to EventValue { (it as EntityDamageEvent).entity },
                EventValueKey.ATTACKER to EventValue { (it as? EntityDamageByEntityEvent)?.damager ?: (it as EntityDamageEvent).damageSource.causingEntity },
                EventValueKey.DAMAGE to damageValue,
            ),
        ) { it.entity },
        Binding(
            EventKind.DEATH, EntityDeathEvent::class.java,
            values = mapOf(
                EventValueKey.VICTIM to EventValue { (it as EntityDeathEvent).entity },
                EventValueKey.ATTACKER to EventValue { (it as EntityDeathEvent).entity.killer },
            ),
        ) { it.entity },
    ).associateBy { it.kind }

    override fun binding(kind: EventKind): EventBinding? = bindings[kind]

    override fun listen(kind: EventKind, priority: Priority, ignoreCancelled: Boolean, trigger: (Any) -> Unit): AutoCloseable {
        val binding = bindings[kind] ?: throw UnsupportedOperationException("$kind can't be heard on ${game.platform}")
        val listener = object : Listener {}
        val type = binding.type
        // registerEvent, not annotated methods: no reflection, and one listener per kind to unregister.
        game.plugin.server.pluginManager.registerEvent(type, listener, EventPriority.valueOf(priority.name), EventExecutor { _, event ->
            // Bukkit also passes subclasses that share the handler list (EntityDamageByEntityEvent).
            if (type.isInstance(event)) trigger(event)
        }, game.plugin, ignoreCancelled)
        return AutoCloseable { HandlerList.unregisterAll(listener) }
    }
}
