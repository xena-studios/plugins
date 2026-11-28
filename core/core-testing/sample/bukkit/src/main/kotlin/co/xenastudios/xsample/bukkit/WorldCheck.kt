package co.xenastudios.xsample.bukkit

import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.bukkit.toCore
import co.xenastudios.core.bukkit.world.world
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.world.BlockChange
import co.xenastudios.core.common.world.ChangeCause
import co.xenastudios.core.common.world.EntitySpawn
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * The core's world hooks on a real server (DECISIONS.md, "The world hooks"), from 1.13: an
 * explosion destroys a block the check put down, and a logger at MONITOR hears it as the
 * explosion's; a zombie about to spawn is stopped, and doesn't; then, with the listeners gone,
 * the plugin has no Bukkit listener left for either. Before 1.13 the hooks must refuse to start.
 * Reports through [done].
 */
internal class WorldCheck(
    private val plugin: JavaPlugin,
    private val platform: BukkitPlatform,
    private val done: (Boolean, String) -> Unit,
) {
    fun start() {
        if (!platform.server.atLeast(1, 13)) {
            val refused = try {
                platform.world()
                false
            } catch (e: UnsupportedOperationException) {
                true
            }
            done(refused, "the world hooks started on ${platform.server.version}, before 1.13")
            return
        }
        val world = plugin.server.worlds.first()
        // High over spawn, which every server keeps loaded at startup, in the air.
        val at = world.spawnLocation.clone().add(0.0, 40.0, 0.0)
        platform.scheduler.run(Where.At(at.toCore())) { run(at) }
    }

    private fun run(at: org.bukkit.Location) {
        val logger = platform.world(EventPriority.MONITOR)
        val limiter = platform.world()
        val heard = ArrayList<BlockChange>()
        var stopped: EntitySpawn? = null
        val blocks = logger.onBlockChange(setOf(ChangeCause.EXPLOSION)) { heard += it }
        val spawns = limiter.onEntitySpawn { if (it.type == "minecraft:zombie") { stopped = it; it.isCancelled = true } }
        val block = at.block
        block.type = Material.STONE
        at.world!!.createExplosion(at.x + 0.5, at.y + 0.5, at.z + 0.5, 2f, false, true)
        val zombie = at.world!!.spawnEntity(at, EntityType.ZOMBIE)
        blocks.cancel()
        spawns.cancel()
        val stone = heard.firstOrNull { it.x == block.x && it.y == block.y && it.z == block.z }
        val left = EntityExplodeEvent.getHandlerList().registeredListeners.count { it.plugin === plugin } +
            EntitySpawnEvent.getHandlerList().registeredListeners.count { it.plugin === plugin }
        val problems = listOfNotNull(
            if (stone == null) "the explosion's changes didn't include the stone (heard ${heard.size})" else null,
            stone?.takeIf { it.before.type != "minecraft:stone" || !it.after.isAir || it.cause != ChangeCause.EXPLOSION || it.world != at.world!!.ref }
                ?.let { "the stone was heard as $it" },
            if (stopped == null) "the zombie's spawn wasn't heard" else null,
            if (zombie.isValid) { zombie.remove(); "the zombie spawned though a listener stopped it" } else null,
            if (logger.listening.isNotEmpty() || limiter.listening.isNotEmpty()) "still listening for ${logger.listening + limiter.listening}" else null,
            if (left != 0) "$left Bukkit listeners left after the last listener went" else null,
        )
        logger.close()
        limiter.close()
        if (problems.isEmpty()) platform.log.info("World hooks: heard ${heard.size} blocks the explosion destroyed and stopped a zombie's spawn")
        done(problems.isEmpty(), problems.joinToString("; "))
    }
}
