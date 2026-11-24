package co.xenastudios.core.common.command

import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleContext
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.every
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AdminCommandsTest {

    private val platform = FakePlatform()
    private val modules = ModuleSet(platform)
    private var wanted = mapOf<String, () -> Module>("ticker" to { Ticker() })
    private var fileProblems = emptyList<String>()
    private var reloads = 0
    private var ticks = 0

    private inner class Ticker : Module {
        override fun enable(context: ModuleContext) {
            context.scheduler.every(Where.Global, 1.seconds) { ticks++ }
        }
    }

    private object Broken : Module {
        override fun enable(context: ModuleContext) {
            error("no database")
        }
    }

    private val admin = AdminCommands(
        "xtest", "xTest", platform,
        ModuleReloader(modules, reloadFiles = { reloads++; fileProblems }, wanted = { wanted }),
        extra = setOf(AdminSubcommand.IMPORT, AdminSubcommand.FORGET),
        importSources = listOf("litebans", "advancedban"),
    )
    private val tree = commands {
        root("xtest", "xTest's commands.") { mount(admin) }
    }
    private val dispatcher = CommandDispatcher(tree, platform.clock, platform.log)
    private val owner = FakeCommandSource.player("Owner", "xtest.admin.reload")

    @Test
    fun theAdminTreeHasItsOwnPermissionPerSubcommand() {
        val root = tree.root("xtest")!!
        assertEquals(listOf("reload", "debug", "import", "forget"), root.children.map { it.name })
        assertTrue(root.children.all { it.builtIn })
        assertEquals(
            listOf("xtest.admin", "xtest.admin.reload", "xtest.admin.debug", "xtest.admin.import", "xtest.admin.forget"),
            admin.permissions.permissions.map { it.node },
        )
        assertEquals(admin.permissions.permissions.drop(1).associate { it.node to true }, admin.admin.children)
        assertEquals(admin.permission(AdminSubcommand.RELOAD), root.child("reload")!!.permission)
        assertEquals("/xtest import <source>", root.child("import")!!.usage)
        assertFailsWith<IllegalArgumentException> { admin.permission(AdminSubcommand.MIGRATE) }
    }

    @Test
    fun reloadRereadsTheFilesAndSyncsTheModulesOnTheGlobalThread() {
        modules.sync(wanted)
        platform.scheduler.advance(2.seconds)
        assertEquals(2, ticks)

        wanted = mapOf("broken" to { Broken })
        fileProblems = listOf("config.yml line 3: limit must be at least 1")
        assertEquals(CommandResult.Ran, dispatcher.dispatch(owner, "xtest reload"))
        // Not yet: on the next tick, outside the command.
        assertEquals(0, reloads)
        platform.scheduler.tick()
        assertEquals(1, reloads)
        assertEquals(emptyList(), modules.active)
        assertEquals(
            listOf(
                "Reloaded xTest: 0 modules on.\n" +
                    "Problem: config.yml line 3: limit must be at least 1\n" +
                    "Module broken is off: it threw IllegalStateException: no database",
            ),
            owner.replies,
        )
        platform.scheduler.advance(5.seconds)
        assertEquals(2, ticks)

        owner.clear()
        wanted = mapOf("ticker" to { Ticker() })
        fileProblems = emptyList()
        dispatcher.dispatch(owner, "xtest reload")
        platform.scheduler.tick()
        assertEquals(listOf("Reloaded xTest: 1 module on."), owner.replies)
        assertEquals(listOf("ticker"), modules.active)
    }

    @Test
    fun aReloadThatThrowsIsLoggedAndSaysSo() {
        val failing = AdminCommands("xtest", "xTest", platform, Reloader { error("disk on fire") })
        val dispatcher = CommandDispatcher(commands { root("xtest", "") { mount(failing) } }, platform.clock, platform.log)
        dispatcher.dispatch(owner, "xtest reload")
        platform.scheduler.tick()
        assertEquals(listOf("Reloading xTest failed; the console has the details."), owner.replies)
        assertEquals(listOf("Reloading xTest failed"), platform.log.errors)
    }

    @Test
    fun theOtherSubcommandsAreDeclaredButNotAvailableYet() {
        val console = FakeCommandSource.console()
        assertEquals(CommandResult.NoPermission("xtest.admin.debug"), dispatcher.dispatch(owner, "xtest debug"))
        val debug = dispatcher.dispatch(console, "xtest debug")
        assertIs<CommandResult.Failed>(debug)
        assertEquals("/xtest debug isn't available yet.", debug.message.plain())
        assertIs<CommandResult.InvalidArgument>(dispatcher.dispatch(console, "xtest import essentials"))
        assertEquals(listOf("litebans", "advancedban"), dispatcher.suggest(console, "xtest import "))
        // Forgetting a player can't be undone, so it asks first.
        assertIs<CommandResult.ConfirmNeeded>(dispatcher.dispatch(console, "xtest forget Notch"))
        assertIs<CommandResult.Failed>(dispatcher.dispatch(console, "xtest forget Notch"))
    }

    @Test
    fun importNeedsItsSources() {
        assertFailsWith<IllegalArgumentException> { AdminCommands("xtest", "xTest", platform, Reloader { error("") }, extra = setOf(AdminSubcommand.IMPORT)) }
        assertFailsWith<IllegalArgumentException> { commands { root("xtest", "") { literal("sub", "") { mount(admin) } } } }
    }
}
