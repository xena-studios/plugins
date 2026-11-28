package co.xenastudios.xlimits.bukkit

import co.xenastudios.xlimits.Category
import co.xenastudios.xlimits.EntityKind
import org.bukkit.Material
import org.bukkit.entity.Ageable
import org.bukkit.entity.Ambient
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Ghast
import org.bukkit.entity.Golem
import org.bukkit.entity.Hanging
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Monster
import org.bukkit.entity.Phantom
import org.bukkit.entity.Player
import org.bukkit.entity.Slime
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Vehicle
import org.bukkit.entity.WaterMob
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Each entity type as xLimits groups it ([EntityKind]): its Minecraft id and its built-in groups,
 * from the type's Bukkit class. Made once per type, the first time one is seen, and the same
 * object every time after, so the rules it matches are worked out once per config.
 */
internal class Kinds {
    private val types = EntityType.values()
    private val kinds = AtomicReferenceArray<EntityKind>(types.size)

    fun of(entity: Entity): EntityKind = of(entity.type)

    fun of(type: EntityType): EntityKind {
        val i = type.ordinal
        kinds.get(i)?.let { return it }
        val made = make(type)
        kinds.compareAndSet(i, null, made)
        return kinds.get(i)
    }

    private fun make(type: EntityType): EntityKind {
        val cls: Class<*>? = type.entityClass
        fun isA(of: Class<*>) = cls != null && of.isAssignableFrom(cls)
        var categories = 0
        val id = id(type)
        if (isA(Player::class.java)) categories = categories or Category.PLAYER
        if (isA(org.bukkit.entity.Animals::class.java)) categories = categories or Category.ANIMAL
        if (isA(Monster::class.java) || isA(Slime::class.java) || isA(Ghast::class.java) || isA(Phantom::class.java) || id == "shulker" || id == "ender_dragon") {
            categories = categories or Category.MONSTER
        }
        if (id == "villager" || id == "wandering_trader") categories = categories or Category.VILLAGER
        if (isA(WaterMob::class.java)) categories = categories or Category.WATER
        if (isA(Ambient::class.java)) categories = categories or Category.AMBIENT
        if (isA(Golem::class.java) && id != "shulker") categories = categories or Category.GOLEM
        if (isA(Vehicle::class.java) && !isA(LivingEntity::class.java)) categories = categories or Category.VEHICLE
        if (isA(ArmorStand::class.java)) categories = categories or Category.ARMOR_STAND
        if (isA(Hanging::class.java)) categories = categories or Category.ITEM_FRAME
        if (isA(TNTPrimed::class.java)) categories = categories or Category.TNT
        if (isA(FallingBlock::class.java)) categories = categories or Category.FALLING
        return EntityKind(id, categories)
    }

    companion object {
        /** A type's Minecraft id: its name as the server keys it (`cow`), with 1.13's older names brought up to date. */
        @Suppress("DEPRECATION")
        fun id(type: EntityType): String {
            val name = try {
                type.name
            } catch (e: Throwable) {
                null
            }
            val raw = (try { type.getName() } catch (e: Throwable) { null } ?: name ?: "unknown").lowercase()
            return RENAMED[raw] ?: raw
        }

        // 1.13 to 1.15's names for what's called otherwise since.
        private val RENAMED = mapOf("zombie_pigman" to "zombified_piglin", "snowman" to "snow_golem", "pig_zombie" to "zombified_piglin")

        /** A block's Minecraft id, `hopper`. */
        fun id(material: Material): String = material.name.lowercase()

        /** Whether [entity] is a grown animal that can breed: farm slowing's no-breeding is for those. */
        fun breeds(entity: Entity): Boolean = entity is Ageable && entity.isAdult
    }
}
