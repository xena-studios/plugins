package co.xenastudios.xvanish

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.VirtualScheduler

/**
 * A server for xVanish's tests: who's online, who's hidden from whom (as Bukkit's `canSee`), what
 * each was sent, their effects, and the switches' listeners. Hiding checks it's on the viewer's
 * thread, as Folia insists.
 */
class FakeVanishServer(private val scheduler: VirtualScheduler, private val byDefault: Boolean = false) : VanishServer, OnlinePlayers, LanguageSource {
    val online = LinkedHashMap<PlayerRef, FakeCommandSource>()
    val sent = HashMap<PlayerRef, MutableList<String>>()
    val consoleLines = ArrayList<String>()
    val inverted = HashMap<PlayerRef, MutableSet<PlayerRef>>()
    val hiddenFromAll = HashSet<PlayerRef>()
    val effects = HashMap<PlayerRef, Effects>()
    val listening = HashMap<Int, VanishView>()
    var view: VanishView? = null

    override val features = VanishServer.Features("the test's", (0..12).toSet())

    fun join(source: FakeCommandSource): Boolean {
        val player = source.player!!
        online[player] = source
        scheduler.spawn(player.uuid, Location(WorldRef.of("world"), 0.0, 64.0, 0.0))
        return scheduler.runAs(co.xenastudios.core.common.scheduler.Where.on(player)) { listening[Switch.PLAYERS]!!.joining(player, source.name) }
    }

    fun quit(source: FakeCommandSource): Boolean {
        val player = source.player!!
        val was = scheduler.runAs(co.xenastudios.core.common.scheduler.Where.on(player)) { listening[Switch.PLAYERS]!!.quitting(player) }
        online.remove(player)
        scheduler.despawn(player.uuid)
        inverted.remove(player)
        return was
    }

    /** Whether [viewer] sees [target], as Bukkit's `canSee` would: hidden by default or not, then each viewer's inversions. */
    fun sees(viewer: FakeCommandSource, target: FakeCommandSource): Boolean {
        val t = target.player!!
        return (t !in hiddenFromAll) != (inverted[viewer.player!!]?.contains(t) == true)
    }

    fun heard(source: FakeCommandSource): List<String> = sent[source.player!!].orEmpty()

    override fun online(): Collection<PlayerRef> = online.keys.toList()

    override fun name(player: PlayerRef): String? = online[player]?.name

    override fun hasPermission(player: PlayerRef, node: String): Boolean = online[player]?.hasPermission(node) == true

    override fun send(player: PlayerRef, message: Component) {
        sent.getOrPut(player) { ArrayList() } += message.plain()
    }

    override fun console(message: Component) {
        consoleLines += message.plain()
    }

    private fun onViewer(viewer: PlayerRef) {
        check(scheduler.owns(co.xenastudios.core.common.scheduler.Where.on(viewer))) { "hiding off the viewer's thread" }
    }

    override fun hide(viewer: PlayerRef, target: PlayerRef) {
        onViewer(viewer)
        val set = inverted.getOrPut(viewer) { HashSet() }
        if (target in hiddenFromAll) set -= target else set += target
    }

    override fun show(viewer: PlayerRef, target: PlayerRef) {
        onViewer(viewer)
        val set = inverted.getOrPut(viewer) { HashSet() }
        if (target in hiddenFromAll) set += target else set -= target
    }

    override fun hiddenByDefault(target: PlayerRef, hidden: Boolean): Boolean {
        if (!byDefault) return false
        if (hidden) hiddenFromAll += target else hiddenFromAll -= target
        for (set in inverted.values) set -= target
        return true
    }

    override fun effects(player: PlayerRef, effects: Effects?) {
        if (effects == null) this.effects.remove(player) else this.effects[player] = effects
    }

    override fun listen(switch: Int, view: VanishView): AutoCloseable {
        listening[switch] = view
        this.view = view
        return AutoCloseable { listening.remove(switch) }
    }

    // OnlinePlayers: as Bukkit's, the vanished are found only by those who see them.
    override fun find(name: String, viewer: CommandSource): PlayerRef? {
        val found = online.values.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        val seer = viewer.player?.let { online[it] } ?: return found.player
        return if (sees(seer, found)) found.player else null
    }

    override fun names(viewer: CommandSource): List<String> {
        val seer = viewer.player?.let { online[it] }
        return online.values.filter { seer == null || sees(seer, it) }.map { it.name }
    }

    override fun clientLocale(player: PlayerRef): String? = null
}
