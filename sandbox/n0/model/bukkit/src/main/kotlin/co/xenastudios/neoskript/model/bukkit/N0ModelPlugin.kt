package co.xenastudios.neoskript.model.bukkit

import co.xenastudios.neoskript.model.Check
import co.xenastudios.neoskript.model.EventKind
import co.xenastudios.neoskript.model.EventValueKey
import co.xenastudios.neoskript.model.SpikeScript
import co.xenastudios.neoskript.model.Timing
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.event.Cancellable
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * The spike on a real Paper or Folia server: loads the hand-written script through the model as
 * the plugin enables, then on the first tick runs [Check] (`MODEL PASS` or `MODEL FAIL`) and the
 * timing loop (`BENCH` lines), on the thread that owns the world's middle.
 */
public class N0ModelPlugin : JavaPlugin() {

    private lateinit var game: BukkitGame
    private lateinit var script: SpikeScript

    override fun onEnable() {
        game = BukkitGame(this)
        script = SpikeScript(game) { logger.info(it) }
        script.load()
        logger.info("N0 model on ${game.platform}: ${game.blockTypes.size} block types, ${game.itemTypes.size} item types, ${game.entityTypes.size} entity types")
        val world = Bukkit.getWorlds().first()
        val start = { _: ScheduledTask? ->
            val problems = Check.run(game, script)
            if (problems.isEmpty()) logger.info("MODEL PASS on ${game.platform}") else logger.severe("MODEL FAIL: " + problems.joinToString("; "))
            when {
                java.lang.Boolean.getBoolean("n0.nobench") -> {}
                // Folia's watchdog reports a region held for 5 s, and the loop takes about 10: Paper's numbers stand for both.
                game.folia -> logger.info("BENCH done (skipped on Folia: the loop would hold its region past the watchdog)")
                else -> bench(world)
            }
        }
        if (game.folia) {
            Bukkit.getRegionScheduler().run(this, world, 0, 0) { start(it) }
        } else {
            Bukkit.getScheduler().runTask(this, Runnable { start(null) })
        }
    }

    override fun onDisable() {
        script.unload()
    }

    /**
     * The model against Bukkit's own calls, per call, on this server's real objects. Both sides
     * start from an [Any], as NeoSkript's compiled code does (its values are `Object`s).
     */
    private fun bench(world: World) {
        val n = Integer.getInteger("n0.bench", 2_000_000)
        val zombies = List(2) { world.spawnEntity(org.bukkit.Location(world, 0.5 + it, 201.0, 0.5), org.bukkit.entity.EntityType.ZOMBIE) }
        val stones = List(2) { world.getBlockAt(it, 200, 0).also { b -> b.type = Material.STONE } }
        @Suppress("UnstableApiUsage")
        val damageEvents = zombies.map { EntityDamageEvent(it, EntityDamageEvent.DamageCause.CUSTOM, DamageSource.builder(DamageType.GENERIC).build(), 3.0) }
        // Two of each, taking turns, read back as Any from an array in each loop: so the JIT can't
        // hoist a read out of the loop or smart-cast, and NeoSkript's compiled code holds every value
        // as an Object and casts it where it's used.
        val v: Array<Any> = arrayOf(zombies[0], zombies[1], stones[0], stones[1], world, damageEvents[0], damageEvents[1])
        val binding = game.events.binding(EventKind.DAMAGE)!!
        val victim = binding.value(EventValueKey.VICTIM)!!
        val damage = binding.value(EventValueKey.DAMAGE)!!
        val e = game.entities
        val b = game.blocks
        val stone = game.blockTypes.byId("stone")!!
        val stoneMaterial = Material.STONE

        logger.info(Timing.line("health of", Timing.pair(n, direct = { (v[it and 1] as LivingEntity).health.toInt() }, model = { e.health(v[it and 1]).toInt() })))
        logger.info(Timing.line("type of block is stone", Timing.pair(n, direct = { if ((v[2 + (it and 1)] as Block).type == stoneMaterial) 1 else 0 }, model = { if (b.type(v[2 + (it and 1)]) === stone) 1 else 0 })))
        logger.info(Timing.line("block at", Timing.pair(n, direct = { (v[4] as World).getBlockAt(it and 15, 200, 0).x }, model = { (b.at(v[4], it and 15, 200, 0) as Block).x })))
        logger.info(Timing.line("block at, then its type", Timing.pair(n, direct = { if ((v[4] as World).getBlockAt(it and 15, 200, 0).type == stoneMaterial) 1 else 0 }, model = { if (b.type(b.at(v[4], it and 15, 200, 0)) === stone) 1 else 0 })))
        logger.info(Timing.line("location of", Timing.pair(n, direct = { (v[it and 1] as Entity).location.blockY }, model = { e.location(v[it and 1]).blockY })))
        logger.info(Timing.line("name of", Timing.pair(n, direct = { (v[it and 1] as Entity).name.length }, model = { e.name(v[it and 1]).length })))
        logger.info(Timing.line("event-entity (victim)", Timing.pair(n, direct = { (v[5 + (it and 1)] as EntityDamageEvent).entity.hashCode() }, model = { victim.get(v[5 + (it and 1)]).hashCode() })))
        logger.info(Timing.line("the damage", Timing.pair(n, direct = { (v[5 + (it and 1)] as EntityDamageEvent).damage.toInt() }, model = { (damage.get(v[5 + (it and 1)]) as Double).toInt() })))
        logger.info(Timing.line("cancel event", Timing.pair(n, direct = { (v[5 + (it and 1)] as Cancellable).isCancelled = it and 1 == 0; 1 }, model = { binding.setCancelled(v[5 + (it and 1)], it and 1 == 0); 1 })))
        logger.info(Timing.line("thread check", Timing.pair(n, direct = { if (Bukkit.isPrimaryThread()) 1 else 0 }, model = { if (game.threads.owns(v[it and 1])) 1 else 0 })))
        // The alternative the README weighs: a wrapper object per value.
        logger.info(Timing.line("health of, through a wrapper", Timing.pair(n, direct = { (v[it and 1] as LivingEntity).health.toInt() }, model = { Wrapped(v[it and 1] as LivingEntity).health().toInt() })))
        logger.info("BENCH done (${Timing.sink and 1}) on ${game.platform}, $n calls each, best of 7 rounds")
        zombies.forEach { it.remove() }
        stones.forEach { it.type = Material.AIR }
    }

    /** A wrapper per value, the alternative to passing Bukkit's objects through. */
    private class Wrapped(private val entity: LivingEntity) {
        fun health(): Double = entity.health
    }
}
