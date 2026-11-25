package co.xenastudios.xutilities

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.xutilities.config.Sound

/** A player on the [FakeServer], with everything xUtilities changes. */
class FakePlayer(val source: FakeCommandSource, var location: Location) {
    val ref: PlayerRef get() = source.player!!
    var mode = GameMode.SURVIVAL
    var allowFlight = false
    var flying = false
    var flySpeed = 0.1f
    var walkSpeed = 0.2f
    var health = 5.0
    var food = 3
    var fire = 100
    var dead = false
    var harmful = true
    var damaged = 2
    var inventory = 10
    var armor = 4
    var ping = 42
    var opened: String? = null
    val sounds = mutableListOf<String>()
    val messages = mutableListOf<String>()
}

/** A server for xUtilities' tests: players in memory, and the moves `/back` hears. */
class FakeServer(override var features: Features = Features()) : Server, OnlinePlayers, LanguageSource {
    val players = LinkedHashMap<PlayerRef, FakePlayer>()
    var listener: MoveListener? = null
    var watches = 0
    val loadedWorlds = mutableSetOf(WORLD)

    fun add(source: FakeCommandSource, at: Location = Location(WORLD, 0.5, 64.0, 0.5)): FakePlayer =
        FakePlayer(source, at).also { players[it.ref] = it }

    private fun of(player: PlayerRef) = players.getValue(player)

    override fun find(name: String, viewer: CommandSource): PlayerRef? = players.values.firstOrNull { it.source.name.equals(name, true) }?.ref
    override fun names(viewer: CommandSource): List<String> = players.values.map { it.source.name }
    override fun clientLocale(player: PlayerRef): String? = null

    override fun isOnline(player: PlayerRef): Boolean = player in players
    override fun name(player: PlayerRef): String? = players[player]?.source?.name
    override fun send(player: PlayerRef, message: Component) {
        players[player]?.let { it.messages += message.plain() }
    }
    override fun location(player: PlayerRef): Location? = players[player]?.location
    override fun isLoaded(world: WorldRef): Boolean = world in loadedWorlds
    override fun setGameMode(player: PlayerRef, mode: GameMode) {
        of(player).mode = mode
    }
    override fun toggleFlight(player: PlayerRef): Boolean {
        val p = of(player)
        p.allowFlight = !p.allowFlight
        if (!p.allowFlight) p.flying = false
        return p.allowFlight
    }
    override fun isFlying(player: PlayerRef): Boolean = of(player).flying
    override fun setSpeed(player: PlayerRef, flying: Boolean, speed: Float) {
        if (flying) of(player).flySpeed = speed else of(player).walkSpeed = speed
    }
    override fun setRotation(player: PlayerRef, yaw: Float, pitch: Float) {
        of(player).location = of(player).location.facing(yaw, pitch)
    }
    override fun ping(player: PlayerRef): Int = of(player).ping
    override fun heal(player: PlayerRef, hunger: Boolean, fire: Boolean, harmfulEffects: Boolean): Boolean {
        val p = of(player)
        if (p.dead) return false
        p.health = 20.0
        if (hunger) p.food = 20
        if (fire) p.fire = 0
        if (harmfulEffects) p.harmful = false
        return true
    }
    override fun feed(player: PlayerRef) {
        of(player).food = 20
    }
    override fun repair(player: PlayerRef, all: Boolean): Int {
        val p = of(player)
        val n = if (all) p.damaged else minOf(1, p.damaged)
        p.damaged -= n
        return n
    }
    override fun clear(player: PlayerRef, armor: Boolean) {
        of(player).inventory = 0
        if (armor) of(player).armor = 0
    }
    override fun openEnderChest(viewer: PlayerRef, owner: PlayerRef): Boolean {
        of(viewer).opened = "enderchest:${name(owner)}"
        return true
    }
    override fun openStation(player: PlayerRef, station: Station) {
        of(player).opened = station.command
    }
    override fun openTrash(player: PlayerRef, title: Component) {
        of(player).opened = "trash:${title.plain()}"
    }
    override fun playSound(player: PlayerRef, sound: Sound) {
        if (!sound.isNone) of(player).sounds += sound.name
    }
    override fun knowsSound(sound: Sound): Boolean? = !sound.name.startsWith("typo")

    var blocks: Map<Int, String> = mapOf(63 to "stone", 70 to "grass_block")

    override fun column(player: PlayerRef): Column = object : Column {
        override val top: Int = 100
        override val hasCeiling: Boolean = false
        override fun solid(y: Int): Boolean = y in blocks
        override fun passable(y: Int): Boolean = y !in blocks
        override fun type(y: Int): String = blocks[y] ?: "air"
    }

    override fun teleport(player: PlayerRef, to: Location, done: (Boolean) -> Unit) {
        val p = of(player)
        val from = p.location
        p.location = to
        if (features.teleportEvents) listener?.teleported(player, from)
        done(true)
    }

    override fun watch(listener: MoveListener): AutoCloseable {
        this.listener = listener
        watches++
        return AutoCloseable {
            this.listener = null
            watches--
        }
    }

    companion object {
        val WORLD: WorldRef = WorldRef.of("world")
    }
}
