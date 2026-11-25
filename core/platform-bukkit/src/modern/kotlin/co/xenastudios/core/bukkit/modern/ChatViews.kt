package co.xenastudios.core.bukkit.modern

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.chat.ChatRecipients
import co.xenastudios.core.common.chat.RecipientAction
import co.xenastudios.core.common.chat.RecipientFilter
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority as BukkitPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// What the chat bridge's two Bukkit paths share: Paper's (PaperChat, here) and Spigot's
// (SpigotChat, in main). Here rather than in main because PaperChat needs them and this source
// set can't see main's classes; they call only what Bukkit 1.8.8 has, so Spigot 1.8.8 loads them.

/** Bukkit's priority of the same name. */
internal fun bukkitPriority(priority: EventPriority): BukkitPriority = when (priority) {
    EventPriority.LOWEST -> BukkitPriority.LOWEST
    EventPriority.LOW -> BukkitPriority.LOW
    EventPriority.NORMAL -> BukkitPriority.NORMAL
    EventPriority.HIGH -> BukkitPriority.HIGH
    EventPriority.HIGHEST -> BukkitPriority.HIGHEST
    EventPriority.MONITOR -> BukkitPriority.MONITOR
}

/**
 * One [PlayerRef] per online player, so the chat path names recipients without making a
 * reference each time: looked up by the player's UUID (no allocation once it's there), and
 * dropped as they quit.
 */
internal class ChatRefs(plugin: Plugin) : Listener {
    private val refs = ConcurrentHashMap<UUID, PlayerRef>()

    init {
        plugin.server.pluginManager.registerEvent(
            PlayerQuitEvent::class.java, this, BukkitPriority.MONITOR,
            EventExecutor { _, event -> if (event is PlayerQuitEvent) refs.remove(event.player.uniqueId) }, plugin, false,
        )
    }

    fun of(player: Player): PlayerRef = refs.computeIfAbsent(player.uniqueId, MAKE)

    fun close() {
        HandlerList.unregisterAll(this)
        refs.clear()
    }

    private companion object {
        val MAKE: java.util.function.Function<UUID, PlayerRef> = java.util.function.Function { it.toPlayerRef() }
    }
}

/**
 * The chat bridge's view of a Bukkit set of recipients: Bukkit's players, or Paper's viewers,
 * which hold the console too, and other audiences a plugin added. Only players are recipients
 * here; the rest are left as they are.
 */
internal class BukkitRecipients<A>(
    private val set: MutableSet<A>,
    private val server: Server,
    private val refs: ChatRefs,
) : ChatRecipients {
    private val initial = set.size
    private var touched = false

    override val size: Int get() {
        var players = 0
        for (viewer in set) if (viewer is Player) players++
        return players
    }

    override fun contains(player: PlayerRef): Boolean {
        val online = server.getPlayer(player.javaUuid) ?: return false
        @Suppress("UNCHECKED_CAST")
        return (set as MutableSet<Any?>).contains(online)
    }

    override fun add(player: PlayerRef): Boolean {
        val online = server.getPlayer(player.javaUuid) ?: return false
        @Suppress("UNCHECKED_CAST")
        return set.add(online as A).also { if (it) touched = true }
    }

    override fun remove(player: PlayerRef): Boolean {
        val online = server.getPlayer(player.javaUuid) ?: return false
        @Suppress("UNCHECKED_CAST")
        return (set as MutableSet<Any?>).remove(online).also { if (it) touched = true }
    }

    override fun removeIf(filter: RecipientFilter): Int {
        var removed = 0
        val each = set.iterator()
        while (each.hasNext()) {
            val viewer = each.next()
            if (viewer is Player && filter.test(refs.of(viewer))) {
                each.remove()
                removed++
            }
        }
        if (removed > 0) touched = true
        return removed
    }

    override fun forEach(action: RecipientAction) {
        for (viewer in set) if (viewer is Player) action.accept(refs.of(viewer))
    }

    override val changed: Boolean get() = touched || set.size != initial
}
