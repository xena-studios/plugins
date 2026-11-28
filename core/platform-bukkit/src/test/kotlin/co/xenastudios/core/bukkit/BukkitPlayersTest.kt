package co.xenastudios.core.bukkit

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.testing.FakeCommandSource
import org.bukkit.World
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.bukkit.Location as BukkitLocation

class BukkitPlayersTest {

    @BeforeTest
    fun setUp() {
        FakeServer.reset()
    }

    @Test
    fun aHiddenPlayerCantBeNamedOrCompletedByThoseTheyreHiddenFrom() {
        val ghost = fakePlayer("Ghost")
        val steve = fakePlayer("Steve", canSee = { it !== ghost })
        FakeServer.players = listOf(steve, ghost)
        val players = BukkitPlayers(FakeServer.server)
        val asSteve = FakeCommandSource("Steve", steve.ref)

        assertEquals(listOf("Steve"), players.names(asSteve))
        assertNull(players.find("ghost", asSteve))
        assertEquals(listOf("Steve", "Ghost"), players.names(FakeCommandSource.console()))
        assertEquals(ghost.ref, players.find("GHOST", FakeCommandSource.console()))
        assertNull(players.find("Nobody", FakeCommandSource.console()))
    }

    @Test
    fun locationsAndRefsCrossBothWays() {
        val world = fake<World>("world", mapOf("getName" to { _ -> "world_nether" }))
        val core = BukkitLocation(world, 1.5, 64.0, -3.25, 90f, 10f).toCore()
        assertEquals(Location(WorldRef.of("world_nether"), 1.5, 64.0, -3.25, 90f, 10f), core)
        assertNull(core.toBukkit(FakeServer.server), "no world of that name is loaded")
        assertFailsWith<IllegalArgumentException> { BukkitLocation(null, 0.0, 0.0, 0.0).toCore() }
        val steve = fakePlayer("Steve")
        FakeServer.players = listOf(steve)
        assertEquals(steve, steve.ref.online(FakeServer.server))
    }

    @Test
    fun aWorldHasOneRefWhicheverObjectAsks() {
        val world = fake<World>("world", mapOf("getName" to { _ -> "world_the_end" }))
        val reloaded = fake<World>("world", mapOf("getName" to { _ -> "world_the_end" }))
        assertSame(world.ref, world.ref)
        assertSame(world.ref, reloaded.ref, "the same folder loaded again is the same world")
        assertEquals(WorldRef.of("world_the_end"), world.ref)
        assertEquals("world_the_end", BukkitLocation(world, 0.0, 0.0, 0.0).toCore().world.name)
    }

    @Test
    fun worldsMadeAndDroppedByTheThousandAreNotAllKept() {
        val first = fake<World>("world", mapOf("getName" to { _ -> "arena-0" })).ref
        for (i in 1..1000) {
            val name = "arena-$i"
            assertEquals(name, fake<World>("world", mapOf("getName" to { _ -> name })).ref.name)
        }
        val again = fake<World>("world", mapOf("getName" to { _ -> "arena-0" })).ref
        assertEquals(first, again)
        assertNotSame(first, again, "the cache was cleared on the way, not grown to a thousand")
    }

    @Test
    fun localesComeFromTheClientWhereTheServerSays() {
        assertEquals("de_de", ClientLocales.Spigot.of(fakePlayer("Hans", locale = "de_de")))
        assertNull(ClientLocales.None.of(fakePlayer("Steve")))
        assertFalse(ClientLocales.Spigot.listen {}, "Spigot before 1.12 never says the locale changed")
    }
}
