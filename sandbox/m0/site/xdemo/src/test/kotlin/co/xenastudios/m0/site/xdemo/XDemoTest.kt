package co.xenastudios.m0.site.xdemo

import co.xenastudios.m0.site.core.Dispatcher
import co.xenastudios.m0.site.core.Sender
import co.xenastudios.m0.site.core.manifest
import kotlin.test.Test
import kotlin.test.assertEquals

// The declarations the docs read are the ones that run: the aliases, arguments, permissions
// and player-only flag in the manifest are the dispatcher's.
class XDemoTest {
    private val dispatcher = Dispatcher(XDemo.definition.commands)
    private val steve = Sender("Steve", isPlayer = true, permissions = setOf("xdemo.coins", "xdemo.pay"))
    private val console = Sender("CONSOLE", isPlayer = false, permissions = setOf("xdemo.admin.give", "xdemo.coins", "xdemo.pay"))

    @Test
    fun runsWhatTheDocsDescribe() {
        assertEquals("Steve: 100", dispatcher.run(steve, "/bal"))
        assertEquals("Only players can use this.", dispatcher.run(console, "/pay Alex 5"))
        assertEquals("Usage: /pay <player> <amount>", dispatcher.run(steve, "/pay Alex"))
        assertEquals("Invalid amount: 0", dispatcher.run(steve, "/pay Alex 0"))
        assertEquals("You don't have permission.", dispatcher.run(steve, "/xdemo give Alex 5"))
        assertEquals("Gave 5 to Alex", dispatcher.run(console, "/xdemo give Alex 5"))
        assertEquals("You don't have permission.", dispatcher.run(console, "/xdemo reload"))
    }

    @Test
    fun manifestMatchesTheTree() {
        val m = manifest(XDemo.definition, "2.0.0", emptyList(), emptyList())
        val pay = m.commands.single { it.path == "pay" }
        assertEquals("/pay <player> <amount>", pay.usage)
        assertEquals(true, pay.playerOnly)
        assertEquals(listOf("xdemo give", "xdemo reload", "xdemo debug"), m.commands.single { it.path == "xdemo" }.subcommands.map { it.path })
        assertEquals(mapOf("xdemo.admin.reload" to true, "xdemo.admin.debug" to true), m.permissions.single { it.node == "xdemo.admin" }.children)
    }
}
