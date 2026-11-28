package co.xenastudios.xplots.bukkit

import co.xenastudios.core.api.WorldRef
import co.xenastudios.xplots.api.PlotBox
import co.xenastudios.xplots.api.RegionBackend
import co.xenastudios.xplots.model.Box
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method

/*
 * WorldGuard and WorldEdit, 7.0.0 (1.13's) to the newest, called by name: their newer jars are
 * Java 21 class files whose vectors are records, which xPlots' Java 8 classes can't be compiled
 * against, and the calls here are the same in every version since 7.0 (WorldGuard.getInstance, the
 * region container, BukkitAdapter, BlockVector3, ProtectedCuboidRegion, the four flags, a session's
 * selection). Each is looked up once, through the plugin's own class loader, only when it's
 * installed. WorldGuard doesn't run on Folia, so there xPlots protects its fields alone.
 */

/**
 * WorldGuard's side of xPlots' fields (1.x's): one region over each field, plus one for each plot
 * made by itself outside its field, from the bottom of the world to the top, that allows building,
 * using, opening chests and interacting, so a world-wide `build deny` doesn't block plots. xPlots'
 * own protection decides who may. PvP is left alone.
 */
internal object WorldGuardHook {
    fun regions(plugin: Plugin): RegionBackend? {
        val wg = plugin.server.pluginManager.getPlugin("WorldGuard") ?: return null
        val we = plugin.server.pluginManager.getPlugin("WorldEdit") ?: plugin.server.pluginManager.getPlugin("FastAsyncWorldEdit")
        return try {
            Regions(plugin, wg.javaClass.classLoader, (we ?: wg).javaClass.classLoader)
        } catch (e: ReflectiveOperationException) {
            plugin.logger.warning("This WorldGuard isn't one xPlots can use ($e), so fields get no region.")
            null
        }
    }

    private class Regions(private val plugin: Plugin, wg: ClassLoader, we: ClassLoader) : RegionBackend {
        private val instance: Any = Class.forName("com.sk89q.worldguard.WorldGuard", true, wg).getMethod("getInstance").invoke(null)
        private val getPlatform: Method = instance.javaClass.getMethod("getPlatform")
        private val adapt: Method = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, we).getMethod("adapt", World::class.java)
        private val vector: Class<*> = Class.forName("com.sk89q.worldedit.math.BlockVector3", true, we)
        private val at: Method = vector.getMethod("at", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val cuboid = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion", true, wg).getConstructor(String::class.java, vector, vector)
        private val flags: Class<*> = Class.forName("com.sk89q.worldguard.protection.flags.Flags", true, wg)
        private val allow: Any = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag\$State", true, wg).getField("ALLOW").get(null)
        private val flagType: Class<*> = Class.forName("com.sk89q.worldguard.protection.flags.Flag", true, wg)
        private val regionType: Class<*> = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion", true, wg)
        private val setFlag: Method = regionType.getMethod("setFlag", flagType, Any::class.java)
        private val setPriority: Method = regionType.getMethod("setPriority", Int::class.javaPrimitiveType)

        init {
            // Fails here, not later, if this WorldGuard's API doesn't match.
            container()
        }

        private fun container(): Any {
            val platform = getPlatform.invoke(instance)
            return platform.javaClass.getMethod("getRegionContainer").invoke(platform)
        }

        private fun manager(world: World): Any? {
            val container = container()
            val weWorld = adapt.invoke(null, world)
            val get = container.javaClass.methods.first { it.name == "get" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(weWorld.javaClass) }
            return get.invoke(container, weWorld)
        }

        override fun define(id: String, world: WorldRef, columns: PlotBox) {
            val w = plugin.server.getWorld(world.name) ?: return
            val manager = manager(w)
            if (manager == null) {
                plugin.logger.warning("WorldGuard has regions turned off in ${w.name}, so $id wasn't made.")
                return
            }
            val region = cuboid.newInstance(id, at.invoke(null, columns.minX, minHeight(w), columns.minZ), at.invoke(null, columns.maxX, w.maxHeight - 1, columns.maxZ))
            for (flag in FLAGS) setFlag.invoke(region, flags.getField(flag).get(null), allow)
            setPriority.invoke(region, 1)
            manager.javaClass.getMethod("addRegion", regionType).invoke(manager, region)
        }

        override fun remove(id: String, world: WorldRef) {
            val w = plugin.server.getWorld(world.name) ?: return
            val manager = manager(w) ?: return
            manager.javaClass.getMethod("removeRegion", String::class.java).invoke(manager, id)
        }

        // World.getMinHeight came with 1.17's deeper worlds; before, the bottom is 0.
        private fun minHeight(world: World): Int = try {
            World::class.java.getMethod("getMinHeight").invoke(world) as Int
        } catch (e: ReflectiveOperationException) {
            0
        }

        private companion object {
            val FLAGS = listOf("BUILD", "USE", "CHEST_ACCESS", "INTERACT")
        }
    }
}

/** WorldEdit's (or FastAsyncWorldEdit's) selection, for `/xplots select` with no corners. */
internal object WorldEditHook {
    fun selection(plugin: Plugin, player: Player): Pair<String, Box>? = try {
        val we = (plugin.server.pluginManager.getPlugin("WorldEdit") ?: plugin.server.pluginManager.getPlugin("FastAsyncWorldEdit"))!!.javaClass.classLoader
        val adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, we)
        val actor = adapter.getMethod("adapt", Player::class.java).invoke(null, player)
        val worldEdit = Class.forName("com.sk89q.worldedit.WorldEdit", true, we).getMethod("getInstance").invoke(null)
        val sessions = worldEdit.javaClass.getMethod("getSessionManager").invoke(worldEdit)
        val session = sessions.javaClass.methods.first { it.name == "get" && it.parameterCount == 1 && it.parameterTypes[0].isInstance(actor) }.invoke(sessions, actor)
        val world = session.javaClass.getMethod("getSelectionWorld").invoke(session)
        if (world == null) {
            null
        } else {
            val region = session.javaClass.methods.first { it.name == "getSelection" && it.parameterCount == 1 }.invoke(session, world)
            val min = region.javaClass.getMethod("getMinimumPoint").invoke(region)
            val max = region.javaClass.getMethod("getMaximumPoint").invoke(region)
            fun n(v: Any, axis: String): Int = v.javaClass.getMethod("getBlock$axis").invoke(v) as Int
            val name = adapter.methods.first { it.name == "adapt" && it.parameterCount == 1 && it.returnType == World::class.java && it.parameterTypes[0].isInstance(world) }
                .invoke(null, world) as World
            name.name to Box.of(n(min, "X"), n(min, "Y"), n(min, "Z"), n(max, "X"), n(max, "Y"), n(max, "Z"))
        }
    } catch (e: Exception) {
        // No selection, an incomplete one, or a WorldEdit whose API isn't 7's.
        null
    }
}
