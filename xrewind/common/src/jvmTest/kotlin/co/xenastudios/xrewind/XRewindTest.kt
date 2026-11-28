package co.xenastudios.xrewind

import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.world.ChangeCause
import co.xenastudios.core.common.world.WorldHook
import co.xenastudios.xrewind.Harness.Companion.at
import co.xenastudios.xrewind.api.Action
import co.xenastudios.xrewind.api.Actor
import co.xenastudios.xrewind.api.Plan
import co.xenastudios.xrewind.api.Query
import co.xenastudios.xrewind.api.event.RollbackEvent
import co.xenastudios.xrewind.query.Center
import co.xenastudios.xrewind.rollback.Rollbacks
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class XRewindTest {
    private val harnesses = ArrayList<Harness>()

    private fun harness(config: String = "", folder: java.nio.file.Path? = null, platform: TestPlatform = TestPlatform()): Harness =
        (if (folder == null) Harness(config = config, platform = platform) else Harness(folder, config, platform)).start().also { harnesses += it }

    @AfterTest
    fun stop() {
        harnesses.forEach { runCatching { it.stop() } }
    }

    @Test
    fun aPlayersBreakIsLoggedLookedUpAndRolledBackWithWhatTheChestHeld() {
        val h = harness()
        val alex = h.server.join("Alex", Center("world", 10, 64, 10))
        h.server.blocks[Spot("world", 10, 64, 10)] = "minecraft:chest[facing=north]"
        h.server.contents[Spot("world", 10, 64, 10)] = byteArrayOf(1, 2, 3)
        val event = h.server.world.change(at(10, 64, 10), "minecraft:chest[facing=north]", "minecraft:air", ChangeCause.PLAYER, alex, "Alex")
        assertNotNull(event, "a player's breaks are heard")
        h.server.blocks.remove(Spot("world", 10, 64, 10))
        h.server.contents.remove(Spot("world", 10, 64, 10))

        val staff = h.server.source(alex)
        h.run("xrewind lookup t:1h", staff) { h.replies(staff).any { "broke" in it } }
        assertTrue(h.replies(staff).any { it.contains("Lookup: 1 changes") }, h.replies(staff).toString())
        assertTrue(h.replies(staff).any { "Alex broke chest at 10 64 10" in it }, h.replies(staff).toString())

        h.run("xrewind rollback u:Alex t:1h", staff) { h.replies(staff).any { "Rolled back" in it } }
        assertEquals("minecraft:chest[facing=north]", h.server.state("world", 10, 64, 10))
        assertTrue(h.server.contents[Spot("world", 10, 64, 10)].contentEquals(byteArrayOf(1, 2, 3)), "what the chest held came back")

        // A second rollback finds nothing: the row is marked rolled back.
        h.run("xrewind rollback u:Alex t:1h", staff) { h.replies(staff).any { "Nothing to change" in it } }

        h.run("xrewind undo", staff) { h.replies(staff).any { "Undid" in it } }
        assertEquals("minecraft:air", h.server.state("world", 10, 64, 10))

        h.run("xrewind restore u:Alex t:1h", staff) { h.replies(staff).count { "Restored" in it || "Nothing" in it } >= 1 }
    }

    @Test
    fun onlyWhatIsSwitchedOnIsListenedFor() {
        val h = harness("log:\n  explosions: false\n  pistons: false\n  mobs: false\n  fire: false\n  liquids: false\n  containers: false\n  kills: false\n")
        assertEquals(setOf(WorldHook.BLOCK_PLAYER), h.server.world.active)
        h.reload("log:\n  block-break: false\n  block-place: false\n  explosions: false\n  pistons: false\n  mobs: false\n  fire: false\n  liquids: false\n  containers: false\n  kills: false\n")
        assertEquals(emptySet(), h.server.world.active)
    }

    @Test
    fun explosionsAreTheSourcesAndExcludedWorldsArentLogged() {
        val h = harness("log:\n  excluded-worlds: [nether]\n")
        h.server.world.change(at(1, 64, 1), "minecraft:stone", "minecraft:air", ChangeCause.EXPLOSION, source = "minecraft:tnt")
        h.server.world.change(FakeWorldSpot.nether, "minecraft:stone", "minecraft:air", ChangeCause.EXPLOSION, source = "minecraft:creeper")
        h.server.world.change(at(2, 64, 1), "minecraft:oak_leaves", "minecraft:air", ChangeCause.DECAY, source = "minecraft:oak_leaves")
        h.flush()
        val entries = h.platform.await(h.plugin.lookup(Query.builder().build()))
        assertEquals(listOf("#tnt"), entries.map { it.actor }, "leaf decay is off by default, the nether excluded")
        assertEquals(Action.BREAK, entries.single().action)
    }

    @Test
    fun containersAndKillsAreLogged() {
        val h = harness()
        val alex = h.server.join("Alex")
        h.server.world.container(at(5, 64, 5), "minecraft:diamond", -3, alex, "Alex")
        h.server.world.death(at(6, 64, 6), "minecraft:zombie", alex, "Alex")
        h.flush()
        val entries = h.platform.await(h.plugin.lookup(Query.builder().users("Alex").build()))
        assertEquals(setOf(Action.REMOVE, Action.KILL), entries.map { it.action }.toSet())
        val took = entries.single { it.action == Action.REMOVE }
        assertEquals("minecraft:diamond", took.target)
        assertEquals(3, took.amount)
        // Taken items are put back by a rollback.
        val plan = h.platform.await(h.plugin.planRollback(Query.builder().users("Alex").time(3600).build()))
        h.platform.await(h.plugin.apply(plan))
        assertEquals(3, h.server.items[Spot("world", 5, 64, 5)]?.get("minecraft:diamond"))
    }

    @Test
    fun theApiLogsLooksUpAndRollsBackWithinOneRegion() {
        val h = harness()
        val world = WorldRef.of("world")
        val plots = Actor.source("xplots")
        for (x in 0..20) {
            h.server.blocks[Spot("world", x, 64, 0)] = "minecraft:air"
            h.plugin.logRemoval(plots, world, x, 64, 0, "minecraft:gold_block")
        }
        val inBox = Query.builder().users("#xplots").time(3600).area(world, 0, 0, 0, 9, 255, 0).build()
        assertEquals(10, h.platform.await(h.plugin.lookup(inBox)).size)
        // A plot that's an L: a box, and its shape as a filter.
        val shaped = Query.builder().users("#xplots").time(3600).area(world, 0, 0, 0, 20, 255, 0).filter { _, x, _, _ -> x % 2 == 0 }.build()
        val plan = h.platform.await(h.plugin.planRollback(shaped))
        assertEquals(11, plan.blocks.size)
        assertEquals(Plan.Kind.ROLLBACK, plan.kind)
        val result = h.platform.await(h.plugin.apply(plan))
        assertEquals(11, result.applied)
        assertEquals("minecraft:gold_block", h.server.state("world", 4, 64, 0))
        assertEquals("minecraft:air", h.server.state("world", 5, 64, 0))
        // Applied once only; its undo puts it back.
        kotlin.test.assertFailsWith<IllegalStateException> { h.plugin.apply(plan) }
        h.platform.await(h.plugin.apply(result.undo))
        assertEquals("minecraft:air", h.server.state("world", 4, 64, 0))
    }

    @Test
    fun aRollbackEventListenerCanStopIt() {
        val h = harness()
        h.plugin.bus.subscribe(RollbackEvent.TYPE) { it.isCancelled = true }
        h.plugin.logRemoval(Actor.source("test"), WorldRef.of("world"), 1, 1, 1, "minecraft:stone")
        h.run("xrewind rollback u:#test t:1h r:#global") { h.console.replies.any { "Another plugin stopped it" in it } }
        assertEquals("minecraft:air", h.server.state("world", 1, 1, 1))
        assertFalse(h.plugin.rollbacks.isRunning)
    }

    @Test
    fun aRollbackStoppedByARestartCarriesOnAtTheNextStart() {
        val folder = java.nio.file.Files.createTempDirectory("xrewind")
        val storage = co.xenastudios.core.testing.MemoryStorage()
        val first = harness(folder = folder, platform = TestPlatform(memory = storage))
        val world = WorldRef.of("world")
        // Two chunks' worth: the second won't load until the server has stopped.
        for (x in listOf(1, 2, 3, 40, 41)) first.plugin.logRemoval(Actor.source("grief"), world, x, 64, 0, "minecraft:stone")
        first.server.holding += "world|2|0"
        first.run("xrewind rollback u:#grief t:1h r:#global") { first.server.state("world", 3, 64, 0) == "minecraft:stone" }
        assertTrue(storage.read(Rollbacks.JOB).let { it is co.xenastudios.core.common.Outcome.Ok && it.value != null }, "it's written down while it runs")
        first.stop()
        harnesses.remove(first)
        assertEquals("minecraft:air", first.server.state("world", 40, 64, 0))

        val second = harness(folder = folder, platform = TestPlatform(memory = storage))
        // The new server's world is where the first left it: the first chunk done.
        for (x in listOf(1, 2, 3)) second.server.blocks[Spot("world", x, 64, 0)] = "minecraft:stone"
        second.platform.until("the rollback to carry on") { second.server.state("world", 41, 64, 0) == "minecraft:stone" }
        assertEquals("minecraft:stone", second.server.state("world", 40, 64, 0))
        second.platform.until("the job file to go") { (storage.read(Rollbacks.JOB) as co.xenastudios.core.common.Outcome.Ok).value == null }
        assertTrue(second.platform.log.lines.any { "stopped by a restart" in it.message }, second.platform.log.lines.toString())
    }

    @Test
    fun theConsoleIsToldToNameAWorld() {
        val h = harness()
        h.run("xrewind lookup t:1h")
        assertTrue(h.console.replies.last().contains("From the console, add w:<world> or r:#global."), h.console.replies.toString())
        h.run("xrewind rollback u:Steve")
        assertTrue(h.console.replies.last().contains("Add a time"), h.console.replies.toString())
        h.run("xrewind rollback t:1h")
        assertTrue(h.console.replies.last().contains("r:#world with w:<world>"), h.console.replies.toString())
        h.run("xrewind undo")
        assertTrue(h.console.replies.last().contains("Nothing to undo"))
        h.run("xrewind status")
        assertTrue(h.console.replies.last().contains("Problem: none"), h.console.replies.toString())
        h.run("xrewind lookup x:1")
        assertTrue(h.console.replies.last().contains("x: isn't a parameter"), h.console.replies.toString())
    }

    @Test
    fun aPurgeDeletesWhatsOlderAndKeepsTheRest() {
        val h = harness()
        h.plugin.logRemoval(Actor.source("old"), WorldRef.of("world"), 1, 1, 1, "minecraft:stone")
        h.flush()
        h.platform.advance((3 * 86_400).seconds)
        h.plugin.logRemoval(Actor.source("new"), WorldRef.of("world"), 2, 1, 1, "minecraft:stone")
        h.run("xrewind purge t:1h") { h.console.replies.any { "at least a day" in it } }
        h.run("xrewind purge t:2d") { h.console.replies.any { "Purged" in it } }
        assertTrue(h.console.replies.any { "Purged 1 changes" in it }, h.console.replies.toString())
        assertEquals(listOf("#new"), h.platform.await(h.plugin.lookup(Query.builder().build())).map { it.actor })
    }

    @Test
    fun theInspectorShowsABlocksHistoryAndListensOnlyWhileOn() {
        val h = harness()
        val alex = h.server.join("Alex")
        h.server.world.change(at(3, 64, 3), "minecraft:air", "minecraft:stone", ChangeCause.PLAYER, alex, "Alex")
        val source = h.server.source(alex)
        assertNull(h.server.inspecting)
        h.run("xrewind inspect", source)
        val inspecting = assertNotNull(h.server.inspecting)
        assertTrue(inspecting.isInspecting(alex))
        inspecting.inspect(alex, Spot("world", 3, 64, 3))
        h.platform.until("the history") { h.replies(source).any { "placed stone" in it } }
        assertTrue(h.replies(source).any { "History of 3 64 3 in world" in it })
        h.run("xrewind inspect", source)
        assertNull(h.server.inspecting)
    }

    @Test
    fun aPreviewIsShownThenAppliedOrCancelled() {
        val h = harness()
        val alex = h.server.join("Alex", Center("world", 0, 64, 0))
        h.plugin.logRemoval(Actor.source("grief"), WorldRef.of("world"), 1, 64, 1, "minecraft:stone")
        val source = h.server.source(alex)
        h.run("xrewind rollback u:#grief t:1h #preview", source) { h.replies(source).any { "Previewing" in it } }
        assertEquals("minecraft:stone", h.server.previews[alex]?.single()?.state)
        assertEquals("minecraft:air", h.server.state("world", 1, 64, 1), "a preview changes nothing")
        h.run("xrewind cancel", source) { h.replies(source).any { "Preview cancelled" in it } }
        h.run("xrewind apply", source) { h.replies(source).any { "no preview" in it } }
        h.run("xrewind rollback u:#grief t:1h #preview", source) { h.replies(source).count { "Previewing" in it } == 2 }
        h.run("xrewind apply", source) { h.replies(source).any { "Rolled back" in it } }
        assertEquals("minecraft:stone", h.server.state("world", 1, 64, 1))
    }

    @Test
    fun aSessionAndChatAreLoggedOnlyWhenOn() {
        val h = harness("log:\n  chat: true\n")
        val alex = h.server.join("Alex")
        val events = assertNotNull(h.server.events)
        events.joined(alex, "Alex", Spot("world", 1, 2, 3))
        events.chat(alex, "Alex", "world", "hello there")
        h.flush()
        val all = h.platform.await(h.plugin.lookup(Query.builder().actions(Action.JOIN, Action.CHAT).build()))
        assertEquals(setOf(Action.JOIN, Action.CHAT), all.map { it.action }.toSet())
        assertEquals("hello there", all.single { it.action == Action.CHAT }.target)
        // Chat needs its own permission to look up.
        val source = h.server.source(alex, "xrewind.lookup")
        h.run("xrewind lookup a:chat r:#global", source)
        assertTrue(h.replies(source).last().contains("permission"), h.replies(source).toString())
    }
}

private object FakeWorldSpot {
    val nether = co.xenastudios.core.testing.FakeWorld.at("nether", 1, 64, 1)
}
