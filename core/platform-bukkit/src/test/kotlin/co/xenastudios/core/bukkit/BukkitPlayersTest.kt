package co.xenastudios.core.bukkit

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.testing.FakeCommandSource
import org.bukkit.World
import org.bukkit.command.CommandSender
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
    fun languagesComeFromTheClientAndTheConsoleHasNone() {
        val console = fake<CommandSender>("CONSOLE", emptyMap())
        val languages = BukkitLanguages.SpigotLanguages
        assertEquals("de_de", languages.clientLocale(fakePlayer("Hans", locale = "de_de")))
        assertNull(languages.clientLocale(console))
        assertNull(BukkitLanguages.NoLanguages.clientLocale(fakePlayer("Steve")))
    }
}
