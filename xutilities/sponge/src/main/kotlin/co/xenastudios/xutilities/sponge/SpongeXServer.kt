package co.xenastudios.xutilities.sponge

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.config.Sound
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.jvm.text.AdventureRenderer
import co.xenastudios.core.sponge.PlayerQuitEvent
import co.xenastudios.core.sponge.SpongePlatform
import co.xenastudios.core.sponge.location
import co.xenastudios.core.sponge.player
import co.xenastudios.core.sponge.ref
import co.xenastudios.core.sponge.world
import co.xenastudios.xutilities.Column
import co.xenastudios.xutilities.Features
import co.xenastudios.xutilities.GameMode
import co.xenastudios.xutilities.MoveListener
import co.xenastudios.xutilities.Server
import co.xenastudios.xutilities.Station
import net.kyori.adventure.key.Key
import org.spongepowered.api.Sponge
import org.spongepowered.api.data.Keys
import org.spongepowered.api.data.type.HandTypes
import org.spongepowered.api.entity.living.player.gamemode.GameModes
import org.spongepowered.api.entity.living.player.server.ServerPlayer
import org.spongepowered.api.event.entity.DestructEntityEvent
import org.spongepowered.api.item.inventory.ContainerTypes
import org.spongepowered.api.item.inventory.ItemStack
import org.spongepowered.api.item.inventory.Slot
import org.spongepowered.api.item.inventory.type.ViewableInventory
import org.spongepowered.api.registry.RegistryTypes
import org.spongepowered.api.util.Ticks
import org.spongepowered.api.world.HeightTypes
import org.spongepowered.api.world.server.ServerLocation
import org.spongepowered.math.vector.Vector3d
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlin.concurrent.Volatile
import net.kyori.adventure.sound.Sound as AdventureSound

/**
 * What xUtilities does to players on a Sponge server, through SpongeAPI's data keys and
 * inventories, one jar for API 8 on. Every call is on the server thread, Sponge's only one.
 *
 * As on Fabric, `/back` hears deaths, xUtilities' own teleports and those other plugins report
 * through the API ([Features.teleportEvents] is off): Sponge's move event fires for every step a
 * player takes, which `/back` doesn't need. SpongeAPI has no workstation menus that work without
 * their block (a custom inventory of an anvil's shape has none of its logic), so the stations say
 * so. Two calls changed shape after API 8 and are looked up once ([Calls]): a slot's `set` (an
 * `ItemStackLike` from API 17) and a player's latency (`connectionState()` from API 17).
 */
internal class SpongeXServer(private val platform: SpongePlatform) : Server {

    override val features: Features = Features(
        stations = Station.entries.associateWith { "SpongeAPI has no ${it.command} menu that works without its block" },
        ping = if (Calls.latency == null) "this Sponge has no player latency SpongeAPI 8 to 21 knows" else null,
        teleportEvents = false,
    )

    @Volatile
    private var listener: MoveListener? = null

    init {
        // Sponge's listeners can't be taken back one by one: registered once, doing nothing while /back is off.
        platform.listen(DestructEntityEvent.Death::class.java) { event ->
            val watching = listener
            val entity = event.entity()
            if (watching != null && entity is ServerPlayer) watching.died(entity.ref, entity.location)
        }
    }

    private fun player(ref: PlayerRef): ServerPlayer? =
        if (Sponge.isServerAvailable()) Sponge.server().player(ref) else null

    override fun isOnline(player: PlayerRef): Boolean = player(player) != null

    override fun name(player: PlayerRef): String? = player(player)?.name()

    override fun send(player: PlayerRef, message: Component) {
        platform.delivery.deliver(player, AdventureRenderer.draw(message))
    }

    override fun location(player: PlayerRef): Location? = player(player)?.location

    override fun isLoaded(world: WorldRef): Boolean = Sponge.isServerAvailable() && Sponge.server().world(world) != null

    override fun setGameMode(player: PlayerRef, mode: GameMode) {
        val type = when (mode) {
            GameMode.SURVIVAL -> GameModes.SURVIVAL
            GameMode.CREATIVE -> GameModes.CREATIVE
            GameMode.ADVENTURE -> GameModes.ADVENTURE
            GameMode.SPECTATOR -> GameModes.SPECTATOR
        }
        player(player)?.offer(Keys.GAME_MODE, type.get())
    }

    override fun toggleFlight(player: PlayerRef): Boolean {
        val p = player(player) ?: return false
        val on = !p.get(Keys.CAN_FLY).orElse(false)
        p.offer(Keys.CAN_FLY, on)
        if (!on) p.offer(Keys.IS_FLYING, false)
        return on
    }

    override fun isFlying(player: PlayerRef): Boolean = player(player)?.get(Keys.IS_FLYING)?.orElse(false) == true

    override fun setSpeed(player: PlayerRef, flying: Boolean, speed: Float) {
        val p = player(player) ?: return
        // Minecraft's abilities keep half the speed Bukkit's 0 to 1 scale shows (walking is 0.1, flying 0.05).
        p.offer(if (flying) Keys.FLYING_SPEED else Keys.WALKING_SPEED, (speed / 2).toDouble())
    }

    override fun setRotation(player: PlayerRef, yaw: Float, pitch: Float) {
        // Sponge's rotation is (pitch, yaw, roll).
        player(player)?.setRotation(Vector3d(pitch.toDouble(), yaw.toDouble(), 0.0))
    }

    override fun ping(player: PlayerRef): Int {
        val p = player(player) ?: return 0
        return Calls.latency?.invoke(p) ?: 0
    }

    override fun heal(player: PlayerRef, hunger: Boolean, fire: Boolean, harmfulEffects: Boolean): Boolean {
        val p = player(player) ?: return false
        if (p.get(Keys.HEALTH).orElse(0.0) <= 0.0) return false
        p.offer(Keys.HEALTH, p.get(Keys.MAX_HEALTH).orElse(20.0))
        if (hunger) feed(p)
        if (fire) p.offer(Keys.FIRE_TICKS, Ticks.zero())
        if (harmfulEffects) {
            val effects = p.get(Keys.POTION_EFFECTS).orElse(emptyList())
            val kept = effects.filter { effect -> RegistryTypes.POTION_EFFECT_TYPE.get().valueKey(effect.type()).value() !in HARMFUL }
            if (kept.size != effects.size) p.offer(Keys.POTION_EFFECTS, kept)
        }
        return true
    }

    override fun feed(player: PlayerRef) {
        player(player)?.let(::feed)
    }

    private fun feed(p: ServerPlayer) {
        p.offer(Keys.FOOD_LEVEL, 20)
        p.offer(Keys.SATURATION, 20.0)
    }

    override fun repair(player: PlayerRef, all: Boolean): Int {
        val p = player(player) ?: return 0
        if (!all) {
            val held = p.itemInHand(HandTypes.MAIN_HAND.get())
            val slot = p.inventory().primary().hotbar().slot(p.inventory().primary().hotbar().selectedSlotIndex()).orElse(null) ?: return 0
            return if (!held.isEmpty && repair(slot)) 1 else 0
        }
        return p.inventory().slots().count(::repair)
    }

    private fun repair(slot: Slot): Boolean {
        val item = slot.peek()
        if (item.isEmpty) return false
        val max = item.get(Keys.MAX_DURABILITY).orElse(0)
        val now = item.get(Keys.ITEM_DURABILITY).orElse(max)
        if (max <= 0 || now >= max || item.get(Keys.IS_UNBREAKABLE).orElse(false)) return false
        item.offer(Keys.ITEM_DURABILITY, max)
        Calls.set(slot, item)
        return true
    }

    override fun clear(player: PlayerRef, armor: Boolean) {
        val inventory = player(player)?.inventory() ?: return
        // Without armor, the hotbar and the 27 slots above it.
        if (armor) inventory.clear() else inventory.primary().clear()
    }

    override fun openEnderChest(viewer: PlayerRef, owner: PlayerRef): Boolean {
        val v = player(viewer) ?: return false
        val o = player(owner) ?: return false
        v.openInventory(o.enderChestInventory())
        return true
    }

    override fun openStation(player: PlayerRef, station: Station) {
        // Never called: every station has a reason in [features].
    }

    override fun openTrash(player: PlayerRef, title: Component) {
        val p = player(player) ?: return
        // A chest of its own nothing holds: whatever's in it is gone once it closes.
        // Built through SpongeAPI's erased Builder.build(): EndStep.build()'s return type changed in API 17.
        val builder: org.spongepowered.api.util.Builder<*, *> =
            ViewableInventory.builder().type(ContainerTypes.GENERIC_9X4).completeStructure().plugin(platform.bootstrap.container)
        val menu = (builder.build() as ViewableInventory).asMenu()
        menu.setTitle(AdventureRenderer.draw(title))
        menu.open(p)
    }

    override fun playSound(player: PlayerRef, sound: Sound) {
        if (sound.isNone) return
        val p = player(player) ?: return
        val key = try {
            Key.key(sound.name)
        } catch (_: RuntimeException) {
            return
        }
        p.playSound(AdventureSound.sound(key, AdventureSound.Source.BLOCK, sound.volume, sound.pitch))
    }

    // SpongeAPI's sound registry changed shape across lines; a typo plays nothing, as on Bukkit.
    override fun knowsSound(sound: Sound): Boolean? = null

    override fun column(player: PlayerRef): co.xenastudios.xutilities.Column? {
        val p = player(player) ?: return null
        val world = p.world()
        val position = p.blockPosition()
        val x = position.x()
        val z = position.z()
        val type = world.worldType()
        val ceiling = type.hasCeiling()
        val top = if (ceiling) type.logicalHeight() else world.height(HeightTypes.MOTION_BLOCKING.get(), x, z)
        return object : Column {
            override val top: Int = top
            override val hasCeiling: Boolean = ceiling
            override fun solid(y: Int): Boolean = !passable(y)
            override fun passable(y: Int): Boolean = world.block(x, y, z).get(Keys.IS_PASSABLE).orElse(true)
            override fun type(y: Int): String = RegistryTypes.BLOCK_TYPE.get().valueKey(world.block(x, y, z).type()).formatted()
        }
    }

    override fun teleport(player: PlayerRef, to: Location, done: (Boolean) -> Unit) {
        val p = player(player) ?: return done(false)
        val world = Sponge.server().world(to.world) ?: return done(false)
        done(p.setLocationAndRotation(ServerLocation.of(world, to.x, to.y, to.z), Vector3d(to.pitch.toDouble(), to.yaw.toDouble(), 0.0)))
    }

    override fun watch(listener: MoveListener): AutoCloseable {
        this.listener = listener
        val quits = platform.events.subscribe(PlayerQuitEvent.TYPE) { listener.quit(it.player) }
        return AutoCloseable {
            this.listener = null
            quits.cancel()
        }
    }

    private companion object {
        // Vanilla's harmful effects (MobEffectCategory.HARMFUL), by key: SpongeAPI doesn't say which.
        val HARMFUL = setOf(
            "slowness", "mining_fatigue", "instant_damage", "nausea", "blindness", "hunger", "weakness", "poison",
            "wither", "levitation", "unluck", "bad_omen", "darkness", "trial_omen", "raid_omen", "wind_charged",
            "weaving", "oozing", "infested",
        )
    }
}

/**
 * The two calls whose shape changed after API 8, looked up once: on API 8 a slot takes an
 * `ItemStack` and a player's latency is `connection().latency()`; from API 17 a slot takes an
 * `ItemStackLike` (which `ItemStack` is) and the latency is `connectionState().latency()`.
 */
private object Calls {
    private val lookup = MethodHandles.publicLookup()

    private val slotSet: MethodHandle = Slot::class.java.methods
        .first { it.name == "set" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(ItemStack::class.java) }
        .let(lookup::unreflect)

    fun set(slot: Slot, item: ItemStack) {
        slotSet.invoke(slot, item)
    }

    private val connection: MethodHandle? = sequenceOf("connectionState", "connection").firstNotNullOfOrNull { name ->
        ServerPlayer::class.java.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.let(lookup::unreflect)
    }

    private val latencyOf: MethodHandle? = connection?.type()?.returnType()?.let { type ->
        type.methods.firstOrNull { it.name == "latency" && it.parameterCount == 0 && it.returnType == Int::class.javaPrimitiveType }?.let(lookup::unreflect)
    }

    val latency: ((ServerPlayer) -> Int)? = if (connection != null && latencyOf != null) {
        { player -> latencyOf.invoke(connection.invoke(player)) as Int }
    } else {
        null
    }
}
