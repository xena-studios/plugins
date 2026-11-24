package co.xenastudios.core.bukkit

import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.platform.SystemClock
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.RecordingLog
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.concurrent.ConcurrentMap
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

class BukkitFamilyTest {

    // Runs what's scheduled when the test says, on the test's thread.
    private val queued = mutableListOf<() -> Unit>()
    private val scheduler = object : Scheduler {
        override fun run(where: Where, delay: Duration, period: Duration?, retired: (() -> Unit)?, action: (Task) -> Unit): Task {
            val task = object : Task {
                override val isCancelled: Boolean get() = false

                override fun cancel() {}
            }
            queued += { action(task) }
            return task
        }

        override fun owns(where: Where): Boolean = true
    }

    @BeforeTest
    fun setUp() {
        FakeServer.reset()
    }

    @Test
    fun everyPluginFindsTheFirstOnesBoardAndRegistersItToo() {
        val first = FakeServer.plugin("xSample")
        val second = FakeServer.plugin("xBans")
        val board = BukkitFamily.board(first)
        assertSame(board.root, BukkitFamily.board(second).root)
        val registrations = FakeServer.services.getRegistrations(ConcurrentMap::class.java)
        assertEquals(setOf(first, second), registrations.map { it.plugin }.toSet())

        // The first is disabled: its registration goes, the board stays found.
        FakeServer.services.unregisterAll(first)
        assertSame(board.root, BukkitFamily.board(FakeServer.plugin("xVote")).root)
    }

    @Test
    fun joinsQuitsAndLanguagesAreRecordedInTheCache() {
        val plugin = FakeServer.plugin()
        val cache = PlayerCache(BukkitFamily.board(plugin), PlayerStore.None, scheduler, SystemClock, RecordingLog(), "xsample")
        var locale = "en_us"
        val already = fakePlayer("Alex", locale = "fr_fr")
        FakeServer.players = listOf(already)
        val locales = object : ClientLocales() {
            override fun of(player: Player): String? = if (player === already) "fr_fr" else locale
        }
        val recording = BukkitFamily.record(plugin, cache, locales, scheduler)
        assertEquals("fr_fr", cache[already.ref]?.language, "players online when the plugin starts are recorded")

        val steve = fakePlayer("Steve")
        FakeServer.plugins.callEvent(PlayerJoinEvent(steve, "joined"))
        val ref = steve.uniqueId.toPlayerRef()
        assertTrue(cache[ref]!!.online)
        assertEquals("en_us", cache[ref]!!.language)

        // Before 1.12 the server never says; the platform looks again once the client's settings are in.
        locale = "de_de"
        queued.toList().forEach { it() }
        assertEquals("de_de", cache[ref]!!.language)

        FakeServer.plugins.callEvent(PlayerQuitEvent(steve, "left"))
        assertFalse(cache[ref]!!.online)

        recording.close()
        FakeServer.plugins.callEvent(PlayerJoinEvent(steve, "joined"))
        assertFalse(cache[ref]!!.online, "nothing is recorded once it's closed")
    }

    @Test
    fun deliveryFindsThePlayerOrTheConsole() {
        val text = BukkitText.legacy(BukkitServer.detect({ false }, "1.8.8", "CraftBukkit", adventure = false), FakeServer.server)
        val inbox = Inbox()
        val steve = fakePlayer("Steve", inbox)
        FakeServer.players = listOf(steve)
        text.delivery.deliver(steve.ref, "hi")
        text.delivery.deliver(null, "console")
        text.delivery.deliver(fakePlayer("Gone").ref, "nobody")
        assertEquals(listOf<Any>("hi"), inbox.received)
        assertEquals(listOf<Any>("console"), FakeServer.console.received)
    }
}
