package co.xenastudios.core.common.admin

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.AdminCommands
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.Reloader
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.FakeDatabase
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleContext
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.global
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DebugTest {

    private val platform = FakePlatform()
    private val errors = RecentErrors(platform.log, platform.clock, capacity = 2)
    private val database = FakeDatabase()
    private val modules = ModuleSet(platform)
    private val uploaded = mutableListOf<String>()
    private var answer: Outcome<String, String> = Outcome.Ok("https://mclo.gs/abc123")
    private val paste = object : PasteService {
        override val name: String get() = "fake paste"

        override fun upload(text: String): Pending<Outcome<String, String>> {
            uploaded += text
            return pendingOf(answer)
        }
    }
    private val console = FakeCommandSource.console()

    private object Broken : Module {
        override fun enable(context: ModuleContext) {
            error("no database")
        }
    }

    private fun debug(paste: PasteService? = this.paste, checks: List<HealthCheck>? = null) = Debug(
        platform, "xTest 1.2.0",
        checks ?: listOf(
            HealthChecks.database(platform) { database },
            HealthChecks.dependencies { listOf(Dependency("Vault", present = true, required = true), Dependency("PlaceholderAPI", present = false)) },
            HealthChecks.threading(platform),
            HealthChecks.config { listOf("config.yml line 4: limit must be at least 1") },
            HealthChecks.modules(modules),
        ),
        about = { mapOf("Java" to "21.0.5") },
        errors = errors,
        paste = paste,
    )

    private fun run(debug: Debug): List<String> {
        val admin = AdminCommands("xtest", "xTest", platform, Reloader { error("") }, debug = debug)
        CommandDispatcher(commands { root("xtest", "") { mount(admin) } }, platform.clock, platform.log).dispatch(console, "xtest debug")
        platform.scheduler.advance(20.seconds)
        return console.replies
    }

    @Test
    fun checksAreShownThenTheReportIsUploaded() {
        database.session.answer = { Outcome.Ok(listOf(Row(listOf("1"), listOf(SqlValue.Integer(1))))) }
        platform.scheduler.global { modules.sync(mapOf("broken" to { Broken })) }
        platform.scheduler.tick()
        platform.storage.writeText(StoragePath.of("config.yml"), "storage:\n  mysql:\n    password: hunter2\nlimit: 0\n")
        errors.warn("an old warning")
        errors.error("A write to mysql://root:toor@db/mc failed", IllegalStateException("boom"))
        errors.error("third")

        assertEquals(
            listOf(
                "» Checking xTest...",
                "✔ Database: the fake database answered in 0 ms.",
                "! Dependencies: PlaceholderAPI not installed, so what needs them is off.",
                "✔ Threads: tasks run where they should (Folia's regions).",
                "! Config: problems found (1), the first: config.yml line 4: limit must be at least 1",
                "✘ Modules: broken turned off after failing.",
                "» Debug report: https://mclo.gs/abc123. Share this link when asking for help; passwords and tokens are removed.",
            ),
            run(debug()),
        )
        val report = uploaded.single()
        assertTrue(report.startsWith("xTest 1.2.0 debug report, 2026-01-01T00:00:00.100Z\nPlatform: Fake (Folia's regions, development build)\nJava: 21.0.5\n"), report)
        assertTrue("  FAILED  ✘ Modules: broken turned off after failing." in report, report)
        assertTrue("Recent warnings and errors (2)" in report, report)
        assertTrue("an old warning" !in report, "only the last two are kept")
        assertTrue("mysql://<removed>@db/mc" in report && "IllegalStateException: boom" in report, report)
        assertTrue("    password: <removed>\n" in report && "hunter2" !in report && "toor" !in report, report)
        assertEquals(emptyList(), platform.storage.paths.filter { it.startsWith("debug/") })
    }

    @Test
    fun aFailedUploadIsWrittenToAFileInstead() {
        answer = Outcome.Err("mclo.gs answered HTTP 503")
        val replies = run(debug(checks = emptyList()))
        assertEquals(
            "» Couldn't upload the debug report (mclo.gs answered HTTP 503), so it's in debug/debug-2026-01-01T00-00-00Z.txt in the plugin's folder.",
            replies.last(),
        )
        val saved = platform.storage.readText(StoragePath.of("debug/debug-2026-01-01T00-00-00Z.txt")).getOrNull()!!
        assertTrue("(no file)" in saved, saved)
    }

    @Test
    fun aDatabaseThatFailsOrACheckThatNeverAnswersFailsItsLine() {
        database.session.answer = { Outcome.Err(DbError(DbError.Kind.UNAVAILABLE, "Connection refused")) }
        val silent = HealthCheck("silent") { newPending() }
        val throwing = HealthCheck("throwing") { error("oops") }
        val replies = run(debug(paste = null, checks = listOf(HealthChecks.database(platform) { database }, silent, throwing)))
        assertEquals(
            listOf(
                "» Checking xTest...",
                "✘ Database: the fake database can't be reached: UNAVAILABLE: Connection refused",
                "✘ silent: it didn't answer within 10s",
                "✘ throwing: it threw IllegalStateException: oops",
                "» Debug report saved as debug/debug-2026-01-01T00-00-10Z.txt in the plugin's folder.",
            ),
            replies,
        )
    }

    @Test
    fun threadingFailsWhenTasksRunWhereTheyShouldNot() {
        // A scheduler that runs async work on the global thread: what picking Bukkit's on Folia looks like.
        val wrong = FakePlatform(threading = Threading.MAIN_THREAD)
        val check = HealthChecks.threading(object : co.xenastudios.core.common.platform.Platform by wrong {
            override val scheduler = object : co.xenastudios.core.common.scheduler.Scheduler by wrong.scheduler {
                override fun run(
                    where: Where,
                    delay: kotlin.time.Duration,
                    period: kotlin.time.Duration?,
                    retired: (() -> Unit)?,
                    action: (co.xenastudios.core.common.scheduler.Task) -> Unit,
                ) = wrong.scheduler.run(if (where == Where.Async) Where.Global else where, delay, period, retired, action)
            }
        })
        var result: Health? = null
        check.run().onSettled { health, _ -> result = health }
        wrong.scheduler.advance(1.seconds)
        assertEquals("✘ Threads: an async task ran on the global thread", result?.english())
    }
}

