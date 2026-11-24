package co.xenastudios.core.common.command

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.ImportPage
import co.xenastudios.core.common.admin.Importer
import co.xenastudios.core.common.admin.Imports
import co.xenastudios.core.common.admin.PlayerDataRegistry
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
import kotlin.uuid.Uuid

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

    private val imports = Imports(platform, listOf(ListImporter("litebans", "LiteBans"), ListImporter("advancedban", "AdvancedBan")))
    private val playerData = PlayerDataRegistry { name -> pendingOf(if (name == "Notch") PlayerRef.of(Uuid.parse("069a79f4-44e9-4726-a5be-fca90e38aaf5")) else null) }
    private val forgotten = mutableListOf<PlayerRef>()

    init {
        playerData.register("homes") { player ->
            forgotten += player
            pendingOf(Outcome.Ok(3L))
        }
    }

    private val admin = AdminCommands(
        "xtest", "xTest", platform,
        ModuleReloader(modules, reloadFiles = { reloads++; fileProblems }, wanted = { wanted }),
        imports = imports,
        playerData = playerData,
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
            listOf("xtest.admin", "xtest.admin.reload", "xtest.admin.debug", "xtest.admin.import", "xtest.admin.forget", "xtest.admin.update"),
            admin.permissions.permissions.map { it.node },
        )
        assertEquals(admin.permissions.permissions.drop(1).associate { it.node to true }, admin.admin.children)
        assertEquals(admin.permission(AdminSubcommand.RELOAD), root.child("reload")!!.permission)
        assertEquals("/xtest import <source> [dry-run]", root.child("import")!!.usage)
        assertEquals("/xtest forget <player>", root.child("forget")!!.usage)
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
    fun importCompletesItsSourcesAndRuns() {
        val console = FakeCommandSource.console()
        assertIs<CommandResult.InvalidArgument>(dispatcher.dispatch(console, "xtest import essentials"))
        assertEquals(listOf("litebans", "advancedban"), dispatcher.suggest(console, "xtest import "))
        assertEquals(listOf("--dry-run"), dispatcher.suggest(console, "xtest import litebans "))
        assertEquals(CommandResult.Ran, dispatcher.dispatch(console, "xtest import LiteBans --dry-run"))
        platform.scheduler.advance(1.seconds)
        assertEquals(listOf("» Dry run from LiteBans: 3 of 3 records would be imported, 0 skipped. Nothing was changed."), console.replies)
    }

    @Test
    fun forgetAsksFirstThenDeletesByNameOrUuid() {
        val console = FakeCommandSource.console()
        // Forgetting a player can't be undone, so it asks first.
        assertIs<CommandResult.ConfirmNeeded>(dispatcher.dispatch(console, "xtest forget Notch"))
        assertEquals(CommandResult.Ran, dispatcher.dispatch(console, "xtest forget Notch"))
        platform.scheduler.advance(1.seconds)
        assertEquals(listOf("» Forgot Notch. Records deleted: 3."), console.replies)
        assertEquals(listOf(PlayerRef.of(Uuid.parse("069a79f4-44e9-4726-a5be-fca90e38aaf5"))), forgotten)

        console.clear()
        dispatcher.dispatch(console, "xtest forget Nobody")
        dispatcher.dispatch(console, "xtest forget Nobody")
        assertEquals(listOf("» No player called Nobody has played here."), console.replies)

        console.clear()
        dispatcher.dispatch(console, "xtest forget 853c80ef3c3749fdaa49938b674adae6")
        dispatcher.dispatch(console, "xtest forget 853c80ef3c3749fdaa49938b674adae6")
        platform.scheduler.advance(1.seconds)
        assertEquals(Uuid.parse("853c80ef-3c37-49fd-aa49-938b674adae6"), forgotten.last().uuid)
        assertIs<CommandResult.InvalidArgument>(dispatcher.dispatch(console, "xtest forget not-a-name!"))
    }

    @Test
    fun debugChecksThenSavesTheReportWhenThereIsNoPasteService() {
        val console = FakeCommandSource.console()
        assertEquals(CommandResult.NoPermission("xtest.admin.debug"), dispatcher.dispatch(owner, "xtest debug"))
        assertEquals(CommandResult.Ran, dispatcher.dispatch(console, "xtest debug"))
        platform.scheduler.advance(1.seconds)
        assertEquals(
            listOf(
                "» Checking xTest...",
                "✔ Threads: tasks run where they should (Folia's regions).",
                "» Debug report saved as debug/debug-2026-01-01T00-00-00.050Z.txt in the plugin's folder.",
            ),
            console.replies,
        )
        assertEquals(listOf("debug/debug-2026-01-01T00-00-00.050Z.txt"), platform.storage.paths)
    }

    @Test
    fun aPluginWithoutTheFeaturesHasOnlyReloadAndDebug() {
        val plain = AdminCommands("xtest", "xTest", platform, Reloader { error("") })
        assertEquals(listOf(AdminSubcommand.RELOAD, AdminSubcommand.DEBUG), plain.subcommands)
        assertFailsWith<IllegalArgumentException> { commands { root("xtest", "") { literal("sub", "") { mount(admin) } } } }
    }

    /** An importer of three records kept in memory. */
    private class ListImporter(id: String, name: String) : Importer<String>(id, name) {
        override fun detect(): Pending<Boolean> = pendingOf(true)
        override fun count(): Pending<Outcome<Long, String>> = pendingOf(Outcome.Ok(3L))
        override fun read(cursor: String?, limit: Int): Pending<Outcome<ImportPage<String>, String>> =
            pendingOf(Outcome.Ok(ImportPage(listOf("a", "b", "c"), null)))
        override fun describe(record: String): String = record
        override fun write(record: String): Pending<Outcome<Unit, String>> = pendingOf(Outcome.Ok(Unit))
    }
}
