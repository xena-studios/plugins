//? if !forge {
package co.xenastudios.xsample.neoforge

import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.common.world.BlockChange
import co.xenastudios.core.common.world.ChangeCause
import co.xenastudios.core.common.world.EntitySpawn
import co.xenastudios.core.neoforge.NeoForgePlatform
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks

/**
 * The core's world hooks on a real NeoForge server (DECISIONS.md, "The world hooks"), as the server
 * starts, which is what its mixins are checked by on each Minecraft line: a block set with no one
 * behind it isn't heard; an explosion destroys it and a logger hears that as the explosion's; an
 * item about to be added to the world is stopped and isn't; with the listeners gone nothing is
 * heard. Logs `WORLD PASS`, or `WORLD FAIL` as an error, which fails the boot.
 */
internal object WorldCheck {
    fun run(platform: NeoForgePlatform, server: MinecraftServer) {
        val logger = platform.world(EventPriority.MONITOR)
        val limiter = platform.world()
        val heard = ArrayList<BlockChange>()
        val stopped = ArrayList<EntitySpawn>()
        val blocks = logger.onBlockChange(ChangeCause.entries.toSet()) { heard += it }
        val spawns = limiter.onEntitySpawn { if (it.type == "minecraft:item") { stopped += it; it.isCancelled = true } }
        val level = server.overworld()
        val pos = BlockPos(0, 200, 0)
        level.getChunk(pos.x shr 4, pos.z shr 4)
        level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3)
        val unattributed = heard.size
        level.explode(null, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 2f, Level.ExplosionInteraction.TNT)
        // The explosion dropped the stone, an item stopped too: only the one added next counts.
        stopped.clear()
        val added = level.addFreshEntity(ItemEntity(level, pos.x + 0.5, pos.y + 2.0, pos.z + 0.5, ItemStack(Items.STONE)))
        blocks.cancel()
        spawns.cancel()
        val stone = heard.firstOrNull { it.x == pos.x && it.y == pos.y && it.z == pos.z }
        val problems = listOfNotNull(
            if (unattributed != 0) "a block set with no one behind it was heard: ${heard.first()}" else null,
            if (stone == null) "the explosion's changes didn't include the stone (heard ${heard.size})" else null,
            stone?.takeIf { it.before.type != "minecraft:stone" || !it.after.isAir || it.cause != ChangeCause.EXPLOSION || it.source != "minecraft:explosion" }
                ?.let { "the stone was heard as $it" },
            if (stopped.size != 1 || added) "the item's spawn wasn't stopped (heard ${stopped.size}, added $added)" else null,
            if (logger.listening.isNotEmpty() || limiter.listening.isNotEmpty()) "still listening for ${logger.listening + limiter.listening}" else null,
        )
        logger.close()
        limiter.close()
        if (problems.isEmpty()) {
            platform.log.info("WORLD PASS: heard ${heard.size} blocks the explosion destroyed, as ${stone?.before?.data}, and stopped an item's spawn")
        } else {
            platform.log.error("WORLD FAIL: " + problems.joinToString("; "))
        }
    }
}
//?}
