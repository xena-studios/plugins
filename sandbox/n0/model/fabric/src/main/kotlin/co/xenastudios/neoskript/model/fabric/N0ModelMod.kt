package co.xenastudios.neoskript.model.fabric

import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.neoskript.model.Check
import co.xenastudios.neoskript.model.EventKind
import co.xenastudios.neoskript.model.EventValueKey
import co.xenastudios.neoskript.model.SpikeScript
import co.xenastudios.neoskript.model.Timing
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.block.Blocks

/**
 * The spike on a real Fabric server: as the server starts, loads the hand-written script through
 * the model, runs [Check] (`MODEL PASS` or `MODEL FAIL`) and the timing loop (`BENCH` lines), on
 * the server thread. The same script and check as on Paper.
 */
public object N0ModelMod : ModInitializer {
    override fun onInitialize() {
        val platform = FabricPlatform("n0model", "N0Model")
        var script: SpikeScript? = null
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            val game = FabricGame(platform, server)
            val loaded = SpikeScript(game) { platform.log.info(it) }
            loaded.load()
            script = loaded
            platform.log.info("N0 model on ${game.platform}: ${game.blockTypes.size} block types, ${game.itemTypes.size} item types, ${game.entityTypes.size} entity types")
            val problems = Check.run(game, loaded)
            if (problems.isEmpty()) platform.log.info("MODEL PASS on ${game.platform}") else platform.log.error("MODEL FAIL: " + problems.joinToString("; "))
            if (!java.lang.Boolean.getBoolean("n0.nobench")) bench(game, server, platform)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ -> script?.unload() }
    }

    /** The model against Minecraft's own calls, per call, on this server's real objects; both sides start from an [Any]. */
    private fun bench(game: FabricGame, server: MinecraftServer, platform: FabricPlatform) {
        val n = Integer.getInteger("n0.bench", 2_000_000)
        val level = server.overworld()
        val positions = List(2) { BlockPos(it, 200, 0) }
        positions.forEach { level.setBlock(it, Blocks.STONE.defaultBlockState(), 3) }
        val zombies = List(2) {
            net.minecraft.world.entity.EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND)!!.also { z ->
                z.setPos(0.5 + it, 201.0, 0.5)
                level.addFreshEntity(z)
            }
        }
        val damageEvents = zombies.map { FabricDamageEvent(it, level.damageSources().generic(), 3f) }
        // Two of each, taking turns, read back as Any from an array in each loop, as on Paper:
        // zombies, blocks, the level, events, positions.
        val v: Array<Any> = arrayOf(zombies[0], zombies[1], FabricBlock(level, positions[0]), FabricBlock(level, positions[1]), level,
            damageEvents[0], damageEvents[1], positions[0], positions[1])
        val binding = game.events.binding(EventKind.DAMAGE)!!
        val victim = binding.value(EventValueKey.VICTIM)!!
        val damage = binding.value(EventValueKey.DAMAGE)!!
        val e = game.entities
        val b = game.blocks
        val stone = game.blockTypes.byId("stone")!!
        val log = platform.log

        log.info(Timing.line("health of", Timing.pair(n, direct = { (v[it and 1] as LivingEntity).health.toInt() }, model = { e.health(v[it and 1]).toInt() })))
        // Minecraft has no block object: its direct form is the level and a position.
        log.info(Timing.line("type of block is stone", Timing.pair(n, direct = { if ((v[4] as ServerLevel).getBlockState(v[7 + (it and 1)] as BlockPos).block === Blocks.STONE) 1 else 0 }, model = { if (b.type(v[2 + (it and 1)]) === stone) 1 else 0 })))
        log.info(Timing.line("block at, then its type", Timing.pair(n, direct = { if ((v[4] as ServerLevel).getBlockState(BlockPos(it and 15, 200, 0)).block === Blocks.STONE) 1 else 0 }, model = { if (b.type(b.at(v[4], it and 15, 200, 0)) === stone) 1 else 0 })))
        log.info(Timing.line("location of", Timing.pair(n, direct = { (v[it and 1] as Entity).position().y.toInt() }, model = { e.location(v[it and 1]).blockY })))
        log.info(Timing.line("name of", Timing.pair(n, direct = { (v[it and 1] as Entity).name.string.length }, model = { e.name(v[it and 1]).length })))
        log.info(Timing.line("event-entity (victim)", Timing.pair(n, direct = { (v[5 + (it and 1)] as FabricDamageEvent).victim.hashCode() }, model = { victim.get(v[5 + (it and 1)]).hashCode() })))
        log.info(Timing.line("the damage", Timing.pair(n, direct = { (v[5 + (it and 1)] as FabricDamageEvent).amount.toInt() }, model = { (damage.get(v[5 + (it and 1)]) as Double).toInt() })))
        log.info(Timing.line("cancel event", Timing.pair(n, direct = { (v[5 + (it and 1)] as FabricDamageEvent).cancelled = it and 1 == 0; 1 }, model = { binding.setCancelled(v[5 + (it and 1)], it and 1 == 0); 1 })))
        log.info(Timing.line("thread check", Timing.pair(n, direct = { if (server.isSameThread) 1 else 0 }, model = { if (game.threads.owns(v[it and 1])) 1 else 0 })))
        log.info("BENCH done (${Timing.sink and 1}) on ${game.platform}, $n calls each, best of 7 rounds")
        zombies.forEach { it.discard() }
        positions.forEach { level.setBlock(it, Blocks.AIR.defaultBlockState(), 3) }
    }
}
