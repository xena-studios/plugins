package co.xenastudios.xlimbo

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.VirtualScheduler

/**
 * A server for xLimbo's tests: who's online and in the limbo world, what each was sent, where the
 * proxy was asked to send them and where they were teleported, who's vanished, and the switches.
 */
class FakeLimboServer(private val scheduler: VirtualScheduler) : LimboServer, OnlinePlayers, LanguageSource {
    val online = LinkedHashMap<PlayerRef, FakeCommandSource>()
    val inLimbo = HashSet<PlayerRef>()
    val sent = HashMap<PlayerRef, MutableList<String>>()
    val bars = HashMap<PlayerRef, MutableList<String>>()
    val connected = ArrayList<Pair<PlayerRef, String>>()
    val teleported = ArrayList<Pair<PlayerRef, String>>()
    val vanished = HashSet<PlayerRef>()
    val staff = HashSet<PlayerRef>()
    val equipped = ArrayList<PlayerRef>()
    val listening = HashMap<Int, XLimboPlugin>()
    var syncs = 0
    var sweeps = 0
    var ready = true
    var teleportWorks = true
    var plugin: XLimboPlugin? = null

    /** A player joining, as the adapter's arrivals see it: placed in limbo first with the proxy route, or wherever they were. */
    fun join(source: FakeCommandSource, intoLimbo: Boolean = true) {
        val player = source.player!!
        online[player] = source
        scheduler.spawn(player.uuid, Location(WorldRef.of("xlimbo"), 0.0, 65.0, 0.0))
        if (intoLimbo) inLimbo += player
        scheduler.runAs(Where.Global) { if (player in inLimbo) plugin!!.arrived(player, joined = true) }
    }

    fun quit(source: FakeCommandSource) {
        val player = source.player!!
        scheduler.runAs(Where.Global) { plugin!!.left(player, quit = true) }
        online.remove(player)
        inLimbo.remove(player)
        scheduler.despawn(player.uuid)
    }

    fun messages(source: FakeCommandSource): List<String> = sent[source.player!!].orEmpty()

    override fun online(): List<PlayerRef> = online.keys.toList()

    override fun name(player: PlayerRef): String? = online[player]?.name

    override fun isInLimbo(player: PlayerRef): Boolean = player in inLimbo

    override fun isReady(): Boolean = ready

    override fun hasPermission(player: PlayerRef, node: String): Boolean = online[player]?.hasPermission(node) == true

    override fun send(player: PlayerRef, message: Component) {
        sent.getOrPut(player) { ArrayList() } += message.plain()
    }

    override fun actionBar(player: PlayerRef, message: Component) {
        bars.getOrPut(player) { ArrayList() } += message.plain()
    }

    override fun clearChat(player: PlayerRef) {
        send(player, "(cleared)".let { Component.text(it) })
    }

    override fun equip(player: PlayerRef, config: Config) {
        equipped += player
    }

    override fun connect(player: PlayerRef, server: String) {
        connected += player to server
    }

    override fun teleportBack(player: PlayerRef, destination: String): Pending<Boolean> {
        if (!teleportWorks) return pendingOf(false)
        teleported += player to destination
        inLimbo -= player
        scheduler.runAs(Where.Global) { plugin!!.left(player, quit = false) }
        return pendingOf(true)
    }

    override fun enterLimbo(player: PlayerRef): Pending<Boolean> {
        inLimbo += player
        scheduler.runAs(Where.Global) { plugin!!.arrived(player, joined = false) }
        return pendingOf(true)
    }

    override fun sync(config: Config) {
        syncs++
    }

    override fun sweep() {
        sweeps++
    }

    override fun listen(switch: Int, plugin: XLimboPlugin): AutoCloseable {
        listening[switch] = plugin
        return AutoCloseable { listening.remove(switch) }
    }

    override fun vanishedFrom(viewer: PlayerRef?, target: PlayerRef): Boolean = target in vanished && viewer !in staff && viewer != target

    // ---- OnlinePlayers ----

    override fun find(name: String, viewer: CommandSource): PlayerRef? =
        online.entries.firstOrNull { it.value.name.equals(name, ignoreCase = true) && !vanishedFrom(viewer.player, it.key) }?.key

    override fun names(viewer: CommandSource): List<String> = online.filter { !vanishedFrom(viewer.player, it.key) }.values.map { it.name }

    // ---- LanguageSource ----

    override fun clientLocale(player: PlayerRef): String? = null
}
