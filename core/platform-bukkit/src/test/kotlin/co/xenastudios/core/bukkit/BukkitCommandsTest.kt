package co.xenastudios.core.bukkit

import co.xenastudios.core.common.command.ArgumentTypes
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.core.common.platform.SystemClock
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BukkitCommandsTest {

    private object Perms : PermissionSet("xsample") {
        val give: Permission = permission("give", "Give coins.")
    }

    private val log = RecordingLog()
    private val ran = mutableListOf<String>()
    private var refreshes = 0
    private lateinit var registrar: BukkitCommands
    private val text = BukkitText.legacy(BukkitServer.detect({ false }, "1.8.8", "CraftBukkit", adventure = false))

    @BeforeTest
    fun setUp() {
        FakeServer.reset()
        registrar = BukkitCommands(
            FakeServer.plugin(), text, log, FakeServer.commandMap, BukkitCommands.knownCommands(FakeServer.commandMap),
            refresh = { refreshes++ },
        )
    }

    private fun dispatcher(aliases: List<String> = listOf("coins")) = CommandDispatcher(
        commands {
            root("xsample", "Coins.", aliases = aliases) {
                executes { ctx -> ctx.reply(Component.text("You have 100 coins.")) }
                literal("give", "Gives coins.", permission = Perms.give) {
                    val who = argument("player", ArgumentTypes.word, "Who.")
                    executes { ctx -> ran += "give ${ctx[who]}" }
                }
                literal("gift", "Also gives.") { executes { ran += "gift" } }
            }
        },
        SystemClock, log,
    )

    private fun sender(name: String, inbox: Inbox, vararg permissions: String): CommandSender = fakePlayer(name, inbox, permissions = permissions.toSet())

    private fun known(): Map<String, Command> = BukkitCommands.knownCommands(FakeServer.commandMap)

    @Test
    fun rootsAreRegisteredUnderTheirNamesAliasesAndThePluginsPrefix() {
        assertEquals(emptyList<String>(), registrar.sync(dispatcher()))
        val command = known().getValue("xsample")
        assertSame(command, known()["coins"])
        assertSame(command, known()["xsample:xsample"])
        assertSame(command, known()["xsample:coins"])
        assertEquals("Coins.", command.description)
    }

    @Test
    fun aLineRunsThroughTheDispatcherAndTheResultIsShown() {
        registrar.sync(dispatcher())
        val inbox = Inbox()
        val steve = sender("Steve", inbox, "xsample.give")
        assertTrue(FakeServer.commandMap.dispatch(steve, "coins give Alex"))
        assertTrue(FakeServer.commandMap.dispatch(steve, "xsample:xsample"))
        assertEquals(listOf("give Alex"), ran)
        assertEquals(listOf<Any>("You have 100 coins."), inbox.received)

        val alex = Inbox()
        FakeServer.commandMap.dispatch(sender("Alex", alex), "xsample give Steve")
        assertEquals(listOf<Any>("You don't have permission to do that."), alex.received)
    }

    @Test
    fun tabCompletionOffersWhatTheSourceMayUse() {
        registrar.sync(dispatcher())
        val command = known().getValue("xsample")
        assertEquals(listOf("give", "gift"), command.tabComplete(sender("Steve", Inbox(), "xsample.give"), "xsample", arrayOf("g")))
        assertEquals(listOf("gift"), command.tabComplete(sender("Alex", Inbox()), "coins", arrayOf("")))
    }

    @Test
    fun aSyncKeepsWhatStaysAndReRegistersWhatChanged() {
        registrar.sync(dispatcher())
        val first = known().getValue("xsample")
        registrar.sync(dispatcher())
        assertSame(first, known().getValue("xsample"), "an unchanged root is kept, with the new dispatcher")
        assertEquals(0, refreshes, "nothing changed, so clients aren't sent the commands again")

        registrar.sync(dispatcher(aliases = listOf("money")))
        assertNull(known()["coins"])
        assertNull(known()["xsample:coins"])
        assertSame(known().getValue("xsample"), known()["money"])
        assertEquals(1, refreshes)
    }

    @Test
    fun clearUnregistersEverything() {
        registrar.sync(dispatcher())
        registrar.clear()
        assertTrue(known().keys.none { it.startsWith("xsample") || it == "coins" })
    }

    @Test
    fun aNameAnotherPluginHasIsAWarningAndThePrefixStillWorks() {
        val other = object : Command("coins") {
            override fun execute(sender: CommandSender, label: String, args: Array<out String>) = true
        }
        FakeServer.commandMap.register("other", other)
        val warnings = registrar.sync(dispatcher())
        assertEquals(listOf("/coins is another plugin's, so XSample's is /xsample:coins"), warnings)
        assertSame(other, known()["coins"])
        assertSame(known().getValue("xsample"), known()["xsample:coins"])
    }
}
