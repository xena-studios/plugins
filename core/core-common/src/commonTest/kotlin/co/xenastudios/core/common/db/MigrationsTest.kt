package co.xenastudios.core.common.db

import co.xenastudios.core.common.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MigrationsTest {
    private val migrations = listOf(
        Migration(1, "homes") { it.createTable(Homes) },
        Migration(2, "events") { it.createTable(Events) },
    )

    /** A session whose meta table holds [schema], and which records the upserts that change it. */
    private fun session(schema: String?, dialect: Dialect = Dialect.Sqlite.CURRENT): FakeSession {
        val session = FakeSession(dialect)
        var stored = schema
        session.answer = { statement ->
            when {
                statement.sql.startsWith("SELECT value FROM t_meta") ->
                    Outcome.Ok(listOfNotNull(stored?.let { row("value" to SqlValue.Text(it)) }))
                statement.sql.startsWith("INSERT INTO t_meta") -> {
                    stored = (statement.args[1] as SqlValue.Text).value
                    Outcome.Ok(emptyList())
                }
                else -> Outcome.Ok(emptyList())
            }
        }
        return session
    }

    private val sql = Sql(Dialect.Sqlite.CURRENT, "t_")

    @Test
    fun aNewDatabaseGetsEveryStepEachInItsOwnTransaction() {
        val session = session(null)
        assertEquals(2, Migrations.run(session, sql, migrations).ok())
        val log = session.log.map { it.substringBefore(" (") }
        assertEquals(
            listOf(
                "CREATE TABLE IF NOT EXISTS t_meta",
                "SELECT value FROM t_meta WHERE name = ? [Text(value=schema)]",
                "BEGIN", "CREATE TABLE IF NOT EXISTS t_homes", "CREATE INDEX IF NOT EXISTS t_homes_name ON t_homes", "INSERT INTO t_meta", "COMMIT",
                "BEGIN", "CREATE TABLE IF NOT EXISTS t_events", "INSERT INTO t_meta", "COMMIT",
            ),
            log,
        )
    }

    @Test
    fun aDatabaseAlreadyUpToDateIsOnlyRead() {
        val session = session("2")
        assertEquals(2, Migrations.run(session, sql, migrations).ok())
        assertTrue(session.log.none { it == "BEGIN" })
    }

    @Test
    fun onlyTheMissingStepsRun() {
        val session = session("1")
        assertEquals(2, Migrations.run(session, sql, migrations).ok())
        assertEquals(1, session.log.count { it == "BEGIN" })
        assertTrue(session.log.any { it.startsWith("CREATE TABLE IF NOT EXISTS t_events") })
        assertTrue(session.log.none { it.startsWith("CREATE TABLE IF NOT EXISTS t_homes") })
    }

    @Test
    fun aNewerDatabaseIsRefusedAndLeftAlone() {
        val session = session("3")
        val result = Migrations.run(session, sql, migrations)
        assertEquals(DbError.Kind.NEWER_SCHEMA, (result as Outcome.Err).error.kind)
        assertTrue(session.log.none { it == "BEGIN" })
    }

    @Test
    fun whatIsAlreadyThereIsSkippedSoAStepCanRunAgain() {
        // MySQL committed the table before the step failed last time; the index is what's left.
        val session = session(null, Dialect.MySql)
        val inner = session.answer
        session.answer = { statement ->
            if (statement.sql.startsWith("CREATE TABLE IF NOT EXISTS t_homes") || statement.sql.startsWith("CREATE INDEX")) {
                Outcome.Err(DbError(DbError.Kind.ALREADY_EXISTS, "already there", code = 1061))
            } else {
                inner(statement)
            }
        }
        assertEquals(2, Migrations.run(session, Sql(Dialect.MySql, "t_"), migrations).ok())
    }

    @Test
    fun aFailingStepRollsBackAndSaysWhichStep() {
        val session = session("1")
        val inner = session.answer
        session.answer = { statement ->
            if (statement.sql.contains("t_events")) Outcome.Err(DbError(DbError.Kind.BAD_STATEMENT, "syntax")) else inner(statement)
        }
        val error = (Migrations.run(session, sql, migrations) as Outcome.Err).error
        assertEquals(DbError.Kind.BAD_STATEMENT, error.kind)
        assertEquals("schema 2 (events): syntax", error.detail)
        assertEquals("ROLLBACK", session.log.last())
    }

    @Test
    fun stepsAreNumberedFromOne() {
        assertFailsWith<IllegalArgumentException> { Migrations.run(session(null), sql, listOf(Migration(2, "x") { emptyList() })) }
    }

    @Test
    fun anUnreadableVersionIsCorrupt() {
        assertEquals(DbError.Kind.CORRUPT, (Migrations.run(session("two"), sql, migrations) as Outcome.Err).error.kind)
    }
}
