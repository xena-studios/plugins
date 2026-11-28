package co.xenastudios.xplots.store

import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.Migration
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.Statement

/**
 * xPlots' tables: 1.x's, byte for byte (its `Schema.STEPS`, schema 1), so either jar reads the
 * other's database. A grid field is stored as its selection, plot size and path, from which the
 * grid is worked out again; each plot keeps its own box and sign so either can change. 1.x's
 * `meta` table is the core's ([co.xenastudios.core.common.db.Migrations.Meta]), the same columns.
 *
 * 2.0's one addition, renewals, is two tables of its own made beside the schema ([RENEWAL] and
 * [NOTICE]), not a step of it: the schema stays at 1, so the 1.x jar still opens the database, and ignores it.
 */
public object Tables {
    public val MIGRATIONS: List<Migration> = listOf(
        Migration(1, "the first tables", co.xenastudios.xplots.Startup.tables),
    )

    private val STEP_1 = listOf(
        "CREATE TABLE {p}field (id INT PRIMARY KEY, name VARCHAR(64) NOT NULL, world VARCHAR(255) NOT NULL," +
            " min_x INT NOT NULL, min_y INT NOT NULL, min_z INT NOT NULL, max_x INT NOT NULL, max_y INT NOT NULL, max_z INT NOT NULL," +
            " size_x INT NOT NULL, size_z INT NOT NULL, path INT NOT NULL, settings TEXT, warp VARCHAR(255)){table}",
        "CREATE TABLE {p}plot (field INT NOT NULL, number INT NOT NULL," +
            " min_x INT NOT NULL, min_y INT NOT NULL, min_z INT NOT NULL, max_x INT NOT NULL, max_y INT NOT NULL, max_z INT NOT NULL," +
            " sign_x INT NOT NULL, sign_y INT NOT NULL, sign_z INT NOT NULL, sign_facing VARCHAR(16) NOT NULL," +
            " PRIMARY KEY (field, number)){table}",
        // Unix milliseconds. warned: 0 none yet, 1 the day before, 2 the hour before.
        "CREATE TABLE {p}lease (field INT NOT NULL, number INT NOT NULL, owner_most BIGINT NOT NULL, owner_least BIGINT NOT NULL," +
            " owner_name VARCHAR(16) NOT NULL, expires BIGINT NOT NULL, slots INT NOT NULL, warned TINYINT NOT NULL," +
            " PRIMARY KEY (field, number)){table}",
        "CREATE TABLE {p}member (field INT NOT NULL, number INT NOT NULL, uuid_most BIGINT NOT NULL, uuid_least BIGINT NOT NULL," +
            " name VARCHAR(16) NOT NULL, PRIMARY KEY (field, number, uuid_most, uuid_least)){table}",
        // The plot's blocks when it was made: a palette of block states and an index per block, deflated.
        "CREATE TABLE {p}snapshot (field INT NOT NULL, number INT NOT NULL, data {blob} NOT NULL," +
            " PRIMARY KEY (field, number)){table}",
    )

    /**
     * 2.0's renewals, one row per rented plot that has any: whether it renews on its own (`auto`,
     * 1 or 0), and the charge being made right now (`charge`, the expiry it extends from, or
     * `NULL`). A charge is written here before the money is taken and cleared in the same
     * transaction that extends the lease, so a crash in between is found at the next start
     * and settled once ([co.xenastudios.xplots.rent.Renewals]), never charged twice.
     */
    public const val RENEWAL: String = "CREATE TABLE IF NOT EXISTS {p}renewal (field INT NOT NULL, number INT NOT NULL, auto TINYINT NOT NULL," +
        " charge BIGINT, PRIMARY KEY (field, number)){table}"

    /**
     * 2.0's renewal notices for owners who were away: one row per plot and owner, the latest
     * (`kind` the message's key, `renewed` or `renew-failed`, and the price), delivered and
     * deleted as they next join. Its own table so a renewal table made before it is left as it is.
     */
    public const val NOTICE: String = "CREATE TABLE IF NOT EXISTS {p}notice (field INT NOT NULL, number INT NOT NULL," +
        " owner_most BIGINT NOT NULL, owner_least BIGINT NOT NULL, kind VARCHAR(16) NOT NULL, price DOUBLE NOT NULL," +
        " PRIMARY KEY (field, number, owner_most, owner_least)){table}"

    internal fun statements(sql: Sql): List<Statement> = STEP_1.map { sql.raw(fill(it, sql.dialect)) }

    /** The renewals' tables, made if they aren't there yet, after the schema is up to date. */
    public fun extras(sql: Sql): List<Statement> = listOf(sql.raw(fill(RENEWAL, sql.dialect)), sql.raw(fill(NOTICE, sql.dialect)))

    /** 1.x's placeholders: `{blob}` bytes, `{table}` what follows a `CREATE TABLE`. */
    internal fun fill(text: String, dialect: Dialect): String = when (dialect) {
        is Dialect.Sqlite -> text.replace("{blob}", "BLOB").replace("{table}", "")
        Dialect.MySql -> text.replace("{blob}", "MEDIUMBLOB").replace("{table}", " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    }
}
