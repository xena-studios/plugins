package co.xenastudios.neoskript.model

import co.xenastudios.core.api.Location

/**
 * The spike's boot check: the same calls through the model on every platform, on a real server
 * as it starts, with [script] loaded, so the platform's own events reach its triggers. Needs no
 * player: blocks, items, a zombie, its damage (one hit the script cancels, one it doesn't), its
 * teleport and its death. Players' events are the client's to check (`client.js --plugin n0model`).
 *
 * Returns what went wrong; empty means it all worked. Runs on the thread that owns the world's
 * middle: the server thread, or that region's on Folia.
 */
public object Check {
    public fun run(game: Game, script: SpikeScript): List<String> {
        val problems = ArrayList<String>()
        fun expect(ok: Boolean, what: () -> String) {
            if (!ok) problems += what()
        }
        val stone = game.blockTypes.byId("minecraft:stone") ?: return listOf("no stone")
        val air = game.blockTypes.byId("air") ?: return listOf("no air")
        val zombieType = game.entityTypes.byId("zombie") ?: return listOf("no zombie")
        val dirt = game.itemTypes.byId("dirt") ?: return listOf("no dirt")

        val world = game.worlds.all().first()
        val ref = game.worlds.ref(world)
        expect(game.worlds.byRef(ref) == world) { "the world $ref isn't found by its ref" }

        // set block at ... to stone; type of block; block at location of block
        val block = game.blocks.at(world, 0, 200, 0)
        game.blocks.setType(block, stone)
        expect(game.blocks.type(block) === stone) { "set block: it's ${game.blocks.type(block)}" }
        val again = game.blocks.at(game.blocks.location(block))
        expect(again == block) { "block at its own location is another block: $again" }
        expect(again != null && game.blocks.type(again) === stone) { "block at location: ${again?.let { game.blocks.type(it) }}" }
        expect(game.worlds.ref(game.blocks.world(block)) == ref) { "world of block" }

        // items: 16 dirt, item amount, set item amount
        val item = game.items.create(dirt, 16)
        expect(game.items.type(item) === dirt && game.items.amount(item) == 16) { "16 dirt is ${game.items.amount(item)} ${game.items.type(item)}" }
        game.items.setAmount(item, 5)
        expect(game.items.amount(item) == 5) { "set item amount: ${game.items.amount(item)}" }

        // an entity: spawn, type, name, health, world, location
        val zombie = game.entities.spawn(zombieType, Location(ref, 0.5, 201.0, 0.5)) ?: return problems + "couldn't spawn a zombie"
        expect(game.entities.isEntity(zombie) && game.entities.isLiving(zombie) && !game.players.isPlayer(zombie)) { "the zombie isn't a living non-player" }
        expect(game.entities.type(zombie) === zombieType) { "type of zombie: ${game.entities.type(zombie)}" }
        expect(game.entities.name(zombie) == "Zombie") { "name of zombie: ${game.entities.name(zombie)}" }
        expect(game.entities.health(zombie) == 20.0) { "health of zombie: ${game.entities.health(zombie)}" }
        expect(game.worlds.ref(game.entities.world(zombie)) == ref) { "world of zombie" }
        expect(game.entities.location(zombie).blockY == 201) { "location of zombie: ${game.entities.location(zombie)}" }
        expect(game.threads.owns(zombie)) { "this thread doesn't own the zombie" }

        // damage, through the platform's damage event: 7 the script cancels, 3 it doesn't
        val before = script.heard.size
        game.entities.damage(zombie, 7.0)
        expect(game.entities.health(zombie) == 20.0) { "the cancelled hit still hurt: ${game.entities.health(zombie)}" }
        game.entities.damage(zombie, 3.0)
        expect(game.entities.health(zombie) == 17.0) { "after a hit of 3: ${game.entities.health(zombie)}" }
        val damages = script.heard.drop(before)
        expect(damages == listOf("damage 7.0 cancelled", "damage 3.0 to minecraft:zombie")) { "the damage triggers heard $damages" }

        // teleport
        var teleported: Boolean? = null
        game.entities.teleport(zombie, Location(ref, 4.5, 201.0, 4.5)) { teleported = it }
        // With regions (Folia) a teleport finishes later, on the entity's new thread: not checked here.
        if (!game.threads.regions || teleported != null) {
            expect(teleported == true) { "teleport finished as $teleported" }
            expect(game.entities.location(zombie).blockX == 4) { "location after teleport: ${game.entities.location(zombie)}" }
        }

        // kill, through the platform's death event
        val beforeDeath = script.heard.size
        game.entities.kill(zombie)
        expect(!game.entities.isValid(zombie)) { "the zombie is still valid after kill" }
        // Fabric kills with a hit of its own, which the damage trigger hears first: only deaths count here.
        val deaths = script.heard.drop(beforeDeath).filter { it.startsWith("death") }
        expect(deaths == listOf("death minecraft:zombie")) { "the death trigger heard ${script.heard.drop(beforeDeath)}" }

        game.blocks.setType(block, air)
        expect(game.blocks.type(block) === air) { "set block back to air: ${game.blocks.type(block)}" }
        return problems
    }
}

/**
 * Time per call, in nanoseconds, of a platform's own call ([direct]) and the model's ([model]),
 * each run [count] times per round, the best of [rounds] after a warm-up round, the two taking
 * turns to go first: a loop on a real server, so the platform's own classes are the ones the JIT
 * sees. Each call's result is folded into [sink] so it can't be optimised away. `inline`, so each
 * measurement is its own loop and call site, as compiled script code is.
 */
public object Timing {
    public var sink: Int = 0

    public inline fun pair(count: Int, rounds: Int = 7, direct: (Int) -> Int, model: (Int) -> Int): DoubleArray {
        val best = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE)
        repeat(rounds + 1) { round ->
            for (turn in 0..1) {
                val which = (turn + round) % 2
                val start = kotlin.time.TimeSource.Monotonic.markNow()
                var acc = 0
                if (which == 0) {
                    for (i in 0 until count) acc += direct(i)
                } else {
                    for (i in 0 until count) acc += model(i)
                }
                val took = start.elapsedNow().inWholeNanoseconds.toDouble() / count
                sink += acc
                if (round > 0 && took < best[which]) best[which] = took
            }
        }
        return best
    }

    /** One line of results, as the boot logs print them. */
    public fun line(name: String, times: DoubleArray): String {
        fun f(x: Double): String = ((x * 10).toLong() / 10.0).toString()
        return "BENCH ${name.padEnd(30)} direct ${f(times[0]).padStart(6)} ns  model ${f(times[1]).padStart(6)} ns"
    }
}
