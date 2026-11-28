package co.xenastudios.core.common.world

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.testing.FakeWorld
import co.xenastudios.core.testing.RecordingLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class WorldHooksTest {
    private val alex = PlayerRef.of(Uuid.random())
    private val spot = FakeWorld.at("world", 17, 64, -3)

    @Test
    fun nothingIsHeardUntilAListenerAsks() {
        val world = FakeWorld(RecordingLog())
        assertTrue(world.started.isEmpty())
        assertNull(world.change(spot, "minecraft:stone", "minecraft:air"))
        assertNull(world.spawn(spot, "minecraft:zombie"))
        assertFalse(world.container(spot, "minecraft:diamond", 1))
    }

    @Test
    fun onlyTheCausesAskedForAreHeard() {
        val world = FakeWorld(RecordingLog())
        val heard = ArrayList<BlockChange>()
        world.onBlockChange(setOf(ChangeCause.PLAYER, ChangeCause.EXPLOSION)) { heard += it }
        assertEquals(listOf(WorldHook.BLOCK_PLAYER, WorldHook.BLOCK_EXPLOSION), world.started)
        assertNull(world.change(spot, "minecraft:water", "minecraft:air", ChangeCause.FLUID), "fluids aren't listened to")
        val change = world.change(spot, "minecraft:chest[facing=north]", "minecraft:air", player = alex, playerName = "Alex")!!
        assertEquals(listOf(change), heard)
        assertTrue(change.isBreak)
        assertEquals("minecraft:chest", change.before.type)
        assertEquals(1, change.chunkX)
        assertEquals(-1, change.chunkZ)
    }

    @Test
    fun theLastListenerLeavingStopsTheHook() {
        val world = FakeWorld(RecordingLog())
        val one = world.onBlockChange(setOf(ChangeCause.FLUID)) {}
        val two = world.onBlockChange(setOf(ChangeCause.FLUID)) {}
        assertEquals(setOf(WorldHook.BLOCK_FLUID), world.active)
        one.cancel()
        one.cancel()
        assertEquals(setOf(WorldHook.BLOCK_FLUID), world.active, "one listener is still there")
        two.cancel()
        assertTrue(world.active.isEmpty())
        world.onBlockChange(setOf(ChangeCause.FLUID)) {}
        assertEquals(listOf(WorldHook.BLOCK_FLUID, WorldHook.BLOCK_FLUID), world.started, "started again for a new listener")
    }

    @Test
    fun aCancelledChangeSkipsListenersThatIgnoreCancelledOnesAndMonitorSeesIt() {
        val world = FakeWorld(RecordingLog())
        val seen = ArrayList<String>()
        world.onBlockChange(setOf(ChangeCause.PLAYER), EventPriority.MONITOR, ignoreCancelled = false) { seen += "monitor ${it.isCancelled}" }
        world.onBlockChange(setOf(ChangeCause.PLAYER), EventPriority.HIGH) { seen += "high" }
        world.onBlockChange(setOf(ChangeCause.PLAYER), EventPriority.LOW) { it.isCancelled = true }
        val change = world.change(spot, "minecraft:air", "minecraft:hopper", player = alex, playerName = "Alex")!!
        assertTrue(change.isCancelled)
        assertTrue(change.isPlace)
        assertEquals(listOf("monitor true"), seen)
    }

    @Test
    fun aChangeThatCantBeStoppedStaysOn() {
        val world = FakeWorld(RecordingLog())
        world.onBlockChange(setOf(ChangeCause.EXPLOSION)) { it.isCancelled = true }
        val change = world.change(spot, "minecraft:stone", "minecraft:air", ChangeCause.EXPLOSION, source = "minecraft:creeper", cancellable = false)!!
        assertFalse(change.isCancelled)
    }

    @Test
    fun closingStopsEverythingAndRefusesNewListeners() {
        val world = FakeWorld(RecordingLog())
        world.onEntitySpawn { it.isCancelled = it.type == "minecraft:zombie" }
        world.onEntityDeath {}
        assertTrue(world.spawn(spot, "minecraft:zombie", SpawnReason.SPAWNER)!!.isCancelled)
        world.close()
        assertTrue(world.active.isEmpty())
        assertFalse(world.isOpen)
        assertNull(world.spawn(spot, "minecraft:zombie"))
        kotlin.test.assertFailsWith<IllegalStateException> { world.onEntityDeath {} }
    }

    @Test
    fun aHookThePlatformLacksIsNeverStarted() {
        val world = FakeWorld(RecordingLog(), supported = setOf(WorldHook.BLOCK_PLAYER))
        val subscription = world.onBlockChange(setOf(ChangeCause.PLAYER, ChangeCause.FLUID)) {}
        assertEquals(listOf(WorldHook.BLOCK_PLAYER), world.started)
        assertNull(world.change(spot, "minecraft:torch", "minecraft:air", ChangeCause.FLUID))
        subscription.cancel()
        assertTrue(world.active.isEmpty())
    }

    @Test
    fun aListenerThatThrowsIsLoggedAndTheOthersStillHear() {
        val log = RecordingLog()
        val world = FakeWorld(log)
        var heard = 0
        world.onEntityChange { error("broken") }
        world.onEntityChange(EventPriority.HIGH) { heard++ }
        world.tame(spot, "minecraft:wolf", alex, "Alex")
        assertEquals(1, heard)
        assertTrue(log.errors.isNotEmpty())
    }
}
