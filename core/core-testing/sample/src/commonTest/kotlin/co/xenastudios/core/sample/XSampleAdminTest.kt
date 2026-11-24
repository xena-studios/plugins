package co.xenastudios.core.sample

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.ImportPage
import co.xenastudios.core.common.admin.Importer
import co.xenastudios.core.common.admin.PlayerLookup
import co.xenastudios.core.common.command.CommandFeedback
import co.xenastudios.core.common.command.CommandResult
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * An example importer, as a real plugin writes one: another economy plugin's balances, read a
 * page at a time from its data (here a CSV export kept in memory: `uuid,name,balance`), each
 * written with [XSample.setBalance], which replaces, so running it twice changes nothing.
 */
class OldEconomyImporter(private val plugin: () -> XSample, private val export: () -> String?) : Importer<OldEconomyImporter.Balance>("oldeconomy", "OldEconomy") {

    /** One line of the export. */
    class Balance(val line: Int, val uuid: String, val name: String, val balance: String)

    private fun lines(): List<String> = export()?.lines()?.drop(1)?.filter { it.isNotBlank() }.orEmpty()

    override fun detect(): Pending<Boolean> = pendingOf(export() != null)

    override fun count(): Pending<Outcome<Long, String>> = pendingOf(Outcome.Ok(lines().size.toLong()))

    override fun read(cursor: String?, limit: Int): Pending<Outcome<ImportPage<Balance>, String>> {
        val all = lines()
        val from = cursor?.toIntOrNull() ?: 0
        val page = all.drop(from).take(limit).mapIndexed { i, line ->
            val parts = line.split(',')
            Balance(from + i + 2, parts.getOrElse(0) { "" }, parts.getOrElse(1) { "?" }, parts.getOrElse(2) { "" })
        }
        val next = from + page.size
        return pendingOf(Outcome.Ok(ImportPage(page, if (next >= all.size) null else next.toString())))
    }

    override fun describe(record: Balance): String = "${record.name}'s balance (line ${record.line})"

    override fun skip(record: Balance): String? = if (record.balance == "0") "nothing to carry over" else null

    override fun write(record: Balance): Pending<Outcome<Unit, String>> {
        val uuid = runCatching { Uuid.parse(record.uuid) }.getOrNull() ?: return pendingOf(Outcome.Err("\"${record.uuid}\" isn't a UUID"))
        val coins = record.balance.toDoubleOrNull()?.toInt() ?: return pendingOf(Outcome.Err("\"${record.balance}\" isn't a number"))
        plugin().setBalance(PlayerRef.of(uuid), coins)
        return pendingOf(Outcome.Ok(Unit))
    }
}

/** xSample's admin subcommands, as an owner runs them. */
class XSampleAdminTest {

    private val platform = FakePlatform()
    private val console = FakeCommandSource.console()
    private val noOne = object : OnlinePlayers {
        override fun find(name: String, viewer: CommandSource): PlayerRef? = null
        override fun names(viewer: CommandSource): List<String> = emptyList()
    }
    private val notch = PlayerRef.of(Uuid.parse("069a79f4-44e9-4726-a5be-fca90e38aaf5"))
    private val jeb = PlayerRef.of(Uuid.parse("853c80ef-3c37-49fd-aa49-938b674adae6"))
    private var export: String? = """
        uuid,name,balance
        069a79f4-44e9-4726-a5be-fca90e38aaf5,Notch,1500.75
        853c80ef-3c37-49fd-aa49-938b674adae6,jeb_,320
        not-a-uuid,Ghost,10
        61699b2e-d327-4a01-9f1e-0ea8c3f06bc6,Dinnerbone,0
    """.trimIndent()

    // The importer writes to the plugin it's given to, so it's handed the plugin once it's made.
    private var made: XSample? = null
    private val plugin: XSample = XSample(
        platform, noOne,
        importers = listOf(OldEconomyImporter({ made!! }, { export })),
        lookup = PlayerLookup { name -> pendingOf(if (name.equals("notch", ignoreCase = true)) notch else null) },
    ).apply { enable() }.also { made = it }

    private fun run(line: String): CommandResult =
        plugin.dispatcher.dispatch(console, line).also { result -> CommandFeedback.ENGLISH.message(result)?.let(console::reply) }

    @Test
    fun importingAnotherPluginsBalances() {
        assertEquals(listOf("oldeconomy"), plugin.dispatcher.suggest(console, "xsample import "))
        run("xsample import oldeconomy --dry-run")
        platform.scheduler.advance(1.seconds)
        assertEquals(
            listOf("» Dry run from OldEconomy: 3 of 4 records would be imported, 1 skipped. Nothing was changed."),
            console.replies,
        )
        assertEquals(100, plugin.balance(notch), "a dry run changes nothing")

        console.clear()
        run("xsample import oldeconomy")
        platform.scheduler.advance(1.seconds)
        assertEquals(
            listOf(
                "» Importing from OldEconomy (records: 4)...",
                "» Skipped Ghost's balance (line 4): \"not-a-uuid\" isn't a UUID",
                "» Imported from OldEconomy: 2. Skipped: 1, failed: 1.",
            ),
            console.replies,
        )
        assertEquals(1500, plugin.balance(notch))
        assertEquals(320, plugin.balance(jeb))

        // Again: the same balances, not doubled.
        run("xsample import oldeconomy")
        platform.scheduler.advance(1.seconds)
        assertEquals(1500, plugin.balance(notch))

        console.clear()
        export = null
        run("xsample import oldeconomy")
        platform.scheduler.advance(1.seconds)
        assertEquals(listOf("» There's no OldEconomy data here to import."), console.replies)
    }

    @Test
    fun forgettingAPlayer() {
        plugin.setBalance(notch, 42)
        assertEquals(CommandResult.ConfirmNeeded("xsample forget notch", 30.seconds), run("xsample forget notch"))
        run("xsample forget notch")
        platform.scheduler.advance(1.seconds)
        assertEquals("» Forgot notch. Records deleted: 1.", console.replies.last())
        assertEquals(100, plugin.balance(notch), "back to the starting coins")
    }

    @Test
    fun debugRunsTheSamplesChecks() {
        run("xsample debug")
        platform.scheduler.advance(1.seconds)
        assertEquals(
            listOf(
                "» Checking xSample...",
                "✔ Threads: tasks run where they should (Folia's regions).",
                "✔ Modules: 1 on.",
                "» Debug report saved as debug/debug-2026-01-01T00-00-00.050Z.txt in the plugin's folder.",
            ),
            console.replies,
        )
    }
}
