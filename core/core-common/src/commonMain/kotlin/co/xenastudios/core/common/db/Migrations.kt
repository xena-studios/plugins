package co.xenastudios.core.common.db

import co.xenastudios.core.common.Outcome

/**
 * One change to a plugin's tables, numbered from 1, as statements built for the database it
 * runs on: `Migration(1, "homes") { sql -> sql.createTable(Homes) }`.
 */
public class Migration(
    public val version: Int,
    /** For the console, if it fails. */
    public val description: String,
    public val statements: (Sql) -> List<Statement>,
)

/**
 * Brings a plugin's tables up to date: runs every [Migration] newer than the version recorded in
 * its `{prefix}meta` table, in order, each in its own transaction with the new version, so a
 * step that fails leaves the database as the step before left it.
 *
 * **Running again is safe.** MySQL commits each table change as it goes, so a step that failed
 * there partway leaves some of what it made; run again, a statement whose table, column or index
 * is already there ([DbError.Kind.ALREADY_EXISTS]) is skipped, and [Sql.createTable] says
 * `IF NOT EXISTS`. So a step should only make things, or be safe to run twice.
 *
 * **A newer database is left alone.** One whose version is above the last migration was made by a
 * newer version of the plugin, and is refused with [DbError.Kind.NEWER_SCHEMA], never changed.
 */
public object Migrations {
    /** The table each plugin records its version in, beside its own. */
    public object Meta : Table("meta") {
        /** What the row is about: `schema`, or a writer's marker. Called `name` in the table. */
        public val key: Column<String> = column("name", ColumnType.text(64))
        public val value: Column<String> = column("value", ColumnType.text(255))

        init {
            primaryKey(key)
        }
    }

    private const val SCHEMA = "schema"

    /** Runs what [migrations] [session]'s database doesn't have yet; returns the version it's at now. */
    public fun run(session: SqlSession, sql: Sql, migrations: List<Migration>): Outcome<Int, DbError> {
        var latest = 0
        for (migration in migrations) {
            require(migration.version == latest + 1) { "migrations are numbered 1, 2, 3...; found ${migration.version} after $latest" }
            latest = migration.version
        }
        ignoringExisting(session, sql.createTable(Meta)).let { if (it is Outcome.Err) return it }
        val current = when (val read = version(session, sql)) {
            is Outcome.Ok -> read.value
            is Outcome.Err -> return read
        }
        if (current > latest) {
            return Outcome.Err(DbError(DbError.Kind.NEWER_SCHEMA,
                "it was made by a newer version (schema $current; this one knows up to $latest). " +
                    "Update the plugin, or point it at another database."))
        }
        for (migration in migrations.subList(current, latest)) {
            val step = session.transaction { tx ->
                when (val ran = ignoringExisting(tx, migration.statements(sql))) {
                    is Outcome.Err -> ran
                    is Outcome.Ok -> tx.executeAll(sql.upsert(values(Meta) {
                        it[Meta.key] = SCHEMA
                        it[Meta.value] = migration.version.toString()
                    }))
                }
            }
            if (step is Outcome.Err) {
                return Outcome.Err(step.error.copy(detail = "schema ${migration.version} (${migration.description}): ${step.error.detail}"))
            }
        }
        return Outcome.Ok(latest)
    }

    /** The version [session]'s database is at: 0 if it has no tables of this plugin's yet. */
    public fun version(session: SqlSession, sql: Sql): Outcome<Int, DbError> =
        when (val row = session.queryOne(sql.select(Meta, Meta.key eq SCHEMA, listOf(Meta.value))) { it[Meta.value] }) {
            is Outcome.Ok -> {
                val text = row.value ?: return Outcome.Ok(0)
                text.toIntOrNull()?.let { Outcome.Ok(it) }
                    ?: Outcome.Err(DbError(DbError.Kind.CORRUPT, "${sql.name(Meta)} says the schema is \"$text\""))
            }
            is Outcome.Err -> row
        }

    private fun ignoringExisting(session: SqlSession, statements: List<Statement>): Outcome<Unit, DbError> {
        for (statement in statements) {
            val result = session.execute(statement)
            if (result is Outcome.Err && result.error.kind != DbError.Kind.ALREADY_EXISTS) return result
        }
        return Outcome.Ok(Unit)
    }
}
