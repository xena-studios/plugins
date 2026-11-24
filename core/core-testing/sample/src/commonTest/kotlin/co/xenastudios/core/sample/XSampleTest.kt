package co.xenastudios.core.sample

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.command.CommandFeedback
import co.xenastudios.core.common.command.CommandResult
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.manifest.Manifest
import co.xenastudios.core.common.manifest.PlatformInfo
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** The sample plugin, run the way a real plugin's tests will run it: commands typed by fake players on the fake platform. */
class XSampleTest {

    private val platform = FakePlatform()
    private val steve = FakeCommandSource.player("Steve", SamplePermissions.pay.node)
    private val alex = FakeCommandSource.player("Alex", SamplePermissions.pay.node)
    private val console = FakeCommandSource.console()
    private val online = listOf(steve, alex)
    private val players = object : OnlinePlayers {
        override fun find(name: String, viewer: CommandSource): PlayerRef? = online.firstOrNull { it.name.equals(name, ignoreCase = true) }?.player
        override fun names(viewer: CommandSource): List<String> = online.map { it.name }
    }
    private val plugin = XSample(platform, players).apply { enable() }

    /** Runs [line] as [source] and shows them the result, as an adapter does. */
    private fun run(source: FakeCommandSource, line: String): CommandResult =
        plugin.dispatcher.dispatch(source, line).also { result -> CommandFeedback.ENGLISH.message(result)?.let(source::reply) }

    @Test
    fun playersPayEachOtherWithACooldown() {
        assertEquals(CommandResult.Ran, run(steve, "pay alex 30"))
        assertEquals(70, plugin.balance(steve.player!!))
        assertEquals(130, plugin.balance(alex.player!!))
        assertEquals(CommandResult.OnCooldown(10.seconds), run(steve, "pay alex 30"))
        platform.scheduler.advance(10.seconds)
        assertIs<CommandResult.Failed>(run(steve, "pay alex 500"))
        assertIs<CommandResult.Failed>(run(steve, "pay steve 5"))
        run(steve, "coins")
        assertEquals(
            listOf("Paid 30 coins.", "You can use that again in 10s.", "You don't have 500 coins.", "You can't pay yourself.", "You have 70 coins."),
            steve.replies,
        )
    }

    @Test
    fun theConsoleGivesAndReloads() {
        assertEquals(CommandResult.PlayersOnly, run(console, "xsample"))
        assertEquals(CommandResult.Ran, run(console, "xsample give Steve 50"))
        assertEquals(150, plugin.balance(steve.player!!))
        assertEquals(CommandResult.NoPermission("xsample.give"), run(alex, "xsample give Steve 50"))

        val before = plugin.dispatcher
        assertEquals(CommandResult.Ran, run(console, "xsample reload"))
        platform.scheduler.tick()
        assertEquals("Reloaded xSample: 1 module on.", console.replies.last())
        assertTrue(plugin.dispatcher !== before, "a reload builds the commands again, from the new config")
    }

    @Test
    fun interestIsPaidByTheModule() {
        run(steve, "pay alex 50")
        platform.scheduler.advance(1.hours)
        assertEquals(50, plugin.balance(steve.player!!))
        assertEquals(151, plugin.balance(alex.player!!))
        assertEquals("151", plugin.placeholders.resolve("balance", alex.player))
        assertEquals("151", plugin.placeholders.resolve("richest", null))
    }

    @Test
    fun theManifestDescribesTheTreeThatRan() {
        val text = Manifest.write(plugin.reference, "1.0.0", listOf(PlatformInfo.parse("paper;Paper;server;1.8.8;latest;8;release;1.0.0")))
        assertTrue("\"usage\": \"/pay <player> <amount>\"" in text)
        assertTrue("\"cooldown\": \"10s\"" in text)
        assertTrue("\"path\": \"xsample forget\"" in text)
        assertTrue("\"node\": \"xsample.admin.forget\"" in text)
    }
}
