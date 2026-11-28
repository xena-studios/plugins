package co.xenastudios.xutilities.neoforge

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.neoforge.NeoForgePlatform
import co.xenastudios.core.neoforge.MinecraftRenderer
import co.xenastudios.core.neoforge.PlayerQuitEvent
import co.xenastudios.core.neoforge.level
import co.xenastudios.core.neoforge.location
import co.xenastudios.core.neoforge.player
import co.xenastudios.core.neoforge.ref
import co.xenastudios.core.common.text.Component
import co.xenastudios.xutilities.Column
import co.xenastudios.xutilities.Features
import co.xenastudios.xutilities.GameMode
import co.xenastudios.xutilities.MoveListener
import co.xenastudios.xutilities.Server
import co.xenastudios.xutilities.Station
import co.xenastudios.core.common.config.Sound
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.AnvilMenu
import net.minecraft.world.inventory.CartographyTableMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.CraftingMenu
import net.minecraft.world.inventory.GrindstoneMenu
import net.minecraft.world.inventory.LoomMenu
import net.minecraft.world.inventory.SmithingMenu
import net.minecraft.world.inventory.StonecutterMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.concurrent.Volatile
//? if identifier {
import net.minecraft.resources.Identifier
//?} else
//import net.minecraft.resources.ResourceLocation as Identifier
//? if modern_teleport
import net.minecraft.world.entity.Relative
import net.minecraft.network.chat.Component as McComponent

/**
 * What xUtilities does to players on a NeoForge server, with Minecraft's own classes and
 * NeoForge's events, no mixins: xUtilities' Fabric adapter (FabricXServer) with NeoForge's death
 * event. One Minecraft line per jar (Stonecutter switches the few names that differ). Every call
 * is on the server thread, NeoForge's only one.
 *
 * NeoForge's teleport event covers only some teleports (commands, ender pearls), not every
 * mod's, so, as on Fabric, `/back` hears deaths, xUtilities' own teleports
 * (`/top`, `/back`) and those other mods report through the API ([Features.teleportEvents]).
 * The workstation menus are Minecraft's own, made with a check that they're still near their
 * block that always passes, so they open anywhere.
 */
internal class NeoForgeXServer(private val platform: NeoForgePlatform) : Server {

    override val features: Features = Features(teleportEvents = false)

    @Volatile
    private var listener: MoveListener? = null

    init {
        // A listener on NeoForge's bus is registered once, and does nothing while /back is off
        // ([watch]). Last, and only when no other mod stopped the death, as Fabric's AFTER_DEATH.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingDeathEvent::class.java) { event ->
            val watching = listener
            val entity = event.entity
            if (watching != null && entity is ServerPlayer) watching.died(entity.ref, entity.location)
        }
    }

    private fun player(ref: PlayerRef): ServerPlayer? = platform.server?.player(ref)

    override fun isOnline(player: PlayerRef): Boolean = player(player) != null

    override fun name(player: PlayerRef): String? = player(player)?.scoreboardName

    override fun send(player: PlayerRef, message: Component) {
        val text = MinecraftRenderer.draw(message)
        val server = platform.server ?: return
        if (server.isSameThread) player(player)?.sendSystemMessage(text) else server.execute { player(player)?.sendSystemMessage(text) }
    }

    override fun location(player: PlayerRef): Location? = player(player)?.location

    override fun isLoaded(world: WorldRef): Boolean = platform.server?.level(world) != null

    override fun setGameMode(player: PlayerRef, mode: GameMode) {
        player(player)?.setGameMode(
            when (mode) {
                GameMode.SURVIVAL -> GameType.SURVIVAL
                GameMode.CREATIVE -> GameType.CREATIVE
                GameMode.ADVENTURE -> GameType.ADVENTURE
                GameMode.SPECTATOR -> GameType.SPECTATOR
            },
        )
    }

    // NeoForge deprecates the mayfly field for its creative-flight attribute, but its mayFly()
    // still reads the field first (field or attribute), as vanilla's checks do.
    @Suppress("DEPRECATION")
    override fun toggleFlight(player: PlayerRef): Boolean {
        val p = player(player) ?: return false
        val abilities = p.abilities
        val on = !abilities.mayfly
        abilities.mayfly = on
        if (!on) abilities.flying = false
        p.onUpdateAbilities()
        return on
    }

    override fun isFlying(player: PlayerRef): Boolean = player(player)?.abilities?.flying == true

    override fun setSpeed(player: PlayerRef, flying: Boolean, speed: Float) {
        val p = player(player) ?: return
        // Minecraft's abilities keep half the speed Bukkit's 0 to 1 scale shows (walking is 0.1, flying 0.05).
        if (flying) {
            p.abilities.setFlyingSpeed(speed / 2)
        } else {
            p.abilities.setWalkingSpeed(speed / 2)
            // What the server moves the player by, as Bukkit's setWalkSpeed sets it too.
            p.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = (speed / 2).toDouble()
        }
        p.onUpdateAbilities()
    }

    override fun setRotation(player: PlayerRef, yaw: Float, pitch: Float) {
        val p = player(player) ?: return
        teleport(p, level(p), p.x, p.y, p.z, yaw, pitch)
    }

    override fun ping(player: PlayerRef): Int {
        val p = player(player) ?: return 0
        //? if client_information {
        return p.connection.latency()
        //?} else
        //return p.latency
    }

    override fun heal(player: PlayerRef, hunger: Boolean, fire: Boolean, harmfulEffects: Boolean): Boolean {
        val p = player(player) ?: return false
        if (p.isDeadOrDying) return false
        p.health = p.maxHealth
        if (hunger) feed(p)
        if (fire) p.clearFire()
        if (harmfulEffects) {
            for (effect in p.activeEffects.toList()) {
                //? if effect_holders {
                if (effect.effect.value().category == MobEffectCategory.HARMFUL) p.removeEffect(effect.effect)
                //?} else
                //if (effect.effect.category == MobEffectCategory.HARMFUL) p.removeEffect(effect.effect)
            }
        }
        return true
    }

    override fun feed(player: PlayerRef) {
        player(player)?.let(::feed)
    }

    private fun feed(p: ServerPlayer) {
        p.foodData.foodLevel = 20
        p.foodData.setSaturation(20f)
    }

    override fun repair(player: PlayerRef, all: Boolean): Int {
        val p = player(player) ?: return 0
        if (!all) return if (repair(p.mainHandItem)) 1 else 0
        val inventory = p.inventory
        var repaired = 0
        for (slot in 0 until inventory.containerSize) if (repair(inventory.getItem(slot))) repaired++
        return repaired
    }

    private fun repair(item: ItemStack): Boolean {
        if (item.isEmpty || !item.isDamageableItem || !item.isDamaged) return false
        item.damageValue = 0
        return true
    }

    override fun clear(player: PlayerRef, armor: Boolean) {
        val inventory = player(player)?.inventory ?: return
        if (armor) {
            inventory.clearContent()
        } else {
            // The hotbar and the 27 slots above it.
            for (slot in 0 until 36) inventory.setItem(slot, ItemStack.EMPTY)
        }
    }

    override fun openEnderChest(viewer: PlayerRef, owner: PlayerRef): Boolean {
        val v = player(viewer) ?: return false
        val o = player(owner) ?: return false
        v.openMenu(SimpleMenuProvider({ id, inventory, _ -> ChestMenu.threeRows(id, inventory, o.enderChestInventory) }, McComponent.translatable("container.enderchest")))
        return true
    }

    override fun openStation(player: PlayerRef, station: Station) {
        val p = player(player) ?: return
        val access = ContainerLevelAccess.create(level(p), p.blockPosition())
        val (title, make) = when (station) {
            Station.ANVIL -> "container.repair" to { id: Int, inv: Inventory -> Anvil(id, inv, access) as AbstractContainerMenu }
            Station.CRAFT -> "container.crafting" to { id: Int, inv: Inventory -> Crafting(id, inv, access) }
            Station.GRINDER -> "container.grindstone_title" to { id: Int, inv: Inventory -> Grindstone(id, inv, access) }
            Station.LOOM -> "container.loom" to { id: Int, inv: Inventory -> Loom(id, inv, access) }
            Station.SMITH -> "container.upgrade" to { id: Int, inv: Inventory -> Smithing(id, inv, access) }
            Station.STONECUTTER -> "container.stonecutter" to { id: Int, inv: Inventory -> Stonecutter(id, inv, access) }
            Station.CARTOGRAPHY -> "container.cartography_table" to { id: Int, inv: Inventory -> Cartography(id, inv, access) }
        }
        p.openMenu(SimpleMenuProvider({ id, inventory, _ -> make(id, inventory) }, McComponent.translatable(title)))
    }

    override fun openTrash(player: PlayerRef, title: Component) {
        val p = player(player) ?: return
        // A chest of its own nothing holds: whatever's in it is gone once it closes.
        p.openMenu(SimpleMenuProvider({ id, inventory, _ -> ChestMenu.fourRows(id, inventory) }, MinecraftRenderer.draw(title)))
    }

    override fun playSound(player: PlayerRef, sound: Sound) {
        if (sound.isNone) return
        val p = player(player) ?: return
        val id = Identifier.tryParse(sound.name) ?: return
        val event = SoundEvent.createVariableRangeEvent(id)
        p.connection.send(ClientboundSoundPacket(Holder.direct(event), SoundSource.BLOCKS, p.x, p.y, p.z, sound.volume, sound.pitch, p.random.nextLong()))
    }

    override fun knowsSound(sound: Sound): Boolean? {
        val id = Identifier.tryParse(sound.name) ?: return false
        return BuiltInRegistries.SOUND_EVENT.containsKey(id)
    }

    override fun column(player: PlayerRef): Column? {
        val p = player(player) ?: return null
        val level = level(p)
        val x = p.blockX
        val z = p.blockZ
        val ceiling = level.dimensionType().hasCeiling()
        val top = if (ceiling) level.dimensionType().logicalHeight() else level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
        return object : Column {
            override val top: Int = top
            override val hasCeiling: Boolean = ceiling
            // Something to stand on is something with a collision box; what has none, a player walks through.
            override fun solid(y: Int): Boolean = !passable(y)
            override fun passable(y: Int): Boolean {
                val pos = BlockPos(x, y, z)
                return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty
            }
            override fun type(y: Int): String = BuiltInRegistries.BLOCK.getKey(level.getBlockState(BlockPos(x, y, z)).block).toString()
        }
    }

    override fun teleport(player: PlayerRef, to: Location, done: (Boolean) -> Unit) {
        val p = player(player) ?: return done(false)
        val level = platform.server?.level(to.world) ?: return done(false)
        teleport(p, level, to.x, to.y, to.z, to.yaw, to.pitch)
        done(true)
    }

    override fun watch(listener: MoveListener): AutoCloseable {
        this.listener = listener
        val quits = platform.events.subscribe(PlayerQuitEvent.TYPE) { listener.quit(it.player) }
        return AutoCloseable {
            this.listener = null
            quits.cancel()
        }
    }

    private fun level(p: ServerPlayer): ServerLevel =
        //? if modern_teleport {
        p.level()
        //?} else
        //p.serverLevel()

    private fun teleport(p: ServerPlayer, level: ServerLevel, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        //? if modern_teleport {
        p.teleportTo(level, x, y, z, emptySet<Relative>(), yaw, pitch, true)
        //?} else
        //p.teleportTo(level, x, y, z, yaw, pitch)
    }

    // Minecraft's menus, but still valid away from their block: opened by a command, there's none.
    private class Anvil(id: Int, inventory: Inventory, access: ContainerLevelAccess) : AnvilMenu(id, inventory, access) {
        override fun stillValid(player: Player): Boolean = true
    }

    private class Crafting(id: Int, inventory: Inventory, access: ContainerLevelAccess) : CraftingMenu(id, inventory, access) {
        override fun stillValid(player: Player): Boolean = true
    }

    private class Grindstone(id: Int, inventory: Inventory, access: ContainerLevelAccess) : GrindstoneMenu(id, inventory, access) {
        override fun stillValid(player: Player): Boolean = true
    }

    private class Loom(id: Int, inventory: Inventory, access: ContainerLevelAccess) : LoomMenu(id, inventory, access) {
        override fun stillValid(player: Player): Boolean = true
    }

    private class Smithing(id: Int, inventory: Inventory, access: ContainerLevelAccess) : SmithingMenu(id, inventory, access) {
        override fun stillValid(player: Player): Boolean = true
    }

    private class Stonecutter(id: Int, inventory: Inventory, access: ContainerLevelAccess) : StonecutterMenu(id, inventory, access) {
        override fun stillValid(player: Player): Boolean = true
    }

    private class Cartography(id: Int, inventory: Inventory, access: ContainerLevelAccess) : CartographyTableMenu(id, inventory, access) {
        override fun stillValid(player: Player): Boolean = true
    }
}
