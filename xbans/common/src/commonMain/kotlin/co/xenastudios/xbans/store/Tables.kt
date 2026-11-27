package co.xenastudios.xbans.store

import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.Migration
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.Statement

/**
 * xBans' tables: 1.x's, byte for byte, so either jar reads the other's database
 * (decisions/2026-11-26-xbans-2.md). `punishments` is append-only: a punishment is written once
 * and never changed. Revoking one adds a row to `removals`. The only deletes are
 * `/prunehistory`'s, which staff ask for, and `/xbans forget`'s IP records.
 *
 * Times are epoch milliseconds; `expires` is 0 for permanent. uuids are written out as their 36
 * characters, as 1.x wrote them. `ip` is the address, or its salted hash with `ips.hash` on.
 * 1.x's `meta` table (its schema version, `ip-mode` and `ip-salt-check`) is the core's
 * ([co.xenastudios.core.common.db.Migrations.Meta]), the same columns.
 */
public object Tables {
    /** 1.x's schema: version 1, made with these statements (its `Schema.STEPS`), `{rowid}` and `{table}` filled per database. */
    public val MIGRATIONS: List<Migration> = listOf(
        Migration(1, "the first tables", co.xenastudios.xbans.Startup.tables),
    )

    private val STEP_1 = listOf(
        // type: 1 ban, 2 mute, 3 warn, 4 kick. imported: where an imported row came from, such as
        // "litebans:bans:12", so importing twice adds nothing. staff_weight: the weight of whoever
        // made it, then (2147483647 for the console and plugins), which decides who may lift it.
        "CREATE TABLE {p}punishments (id {rowid}, type TINYINT NOT NULL, uuid CHAR(36), name VARCHAR(64) NOT NULL," +
            " ip VARCHAR(64), ip_based TINYINT NOT NULL, reason VARCHAR(1024) NOT NULL, staff_uuid CHAR(36)," +
            " staff_name VARCHAR(64) NOT NULL, staff_weight INT NOT NULL, created BIGINT NOT NULL, expires BIGINT NOT NULL," +
            " scope VARCHAR(64) NOT NULL, origin VARCHAR(64) NOT NULL, silent TINYINT NOT NULL," +
            " template VARCHAR(64), imported VARCHAR(96)){table}",
        // History pages, newest first, by key range: as fast for thousands as for ten.
        "CREATE INDEX {p}punishments_uuid ON {p}punishments (uuid, id)",
        "CREATE INDEX {p}punishments_staff ON {p}punishments (staff_uuid, id)",
        "CREATE INDEX {p}punishments_ip ON {p}punishments (ip, id)",
        // Loading what's in force at startup.
        "CREATE INDEX {p}punishments_active ON {p}punishments (type, expires)",
        "CREATE INDEX {p}punishments_imported ON {p}punishments (imported)",
        "CREATE TABLE {p}removals (punishment BIGINT NOT NULL PRIMARY KEY, staff_uuid CHAR(36)," +
            " staff_name VARCHAR(64) NOT NULL, removed BIGINT NOT NULL, reason VARCHAR(1024) NOT NULL){table}",
        // The last name, and the weight and exemption a player had when last seen, so offline
        // players are held to staff limits too.
        "CREATE TABLE {p}players (uuid CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL," +
            " first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, weight INT NOT NULL, exempt TINYINT NOT NULL){table}",
        "CREATE INDEX {p}players_name ON {p}players (name)",
        "CREATE TABLE {p}ips (uuid CHAR(36) NOT NULL, ip VARCHAR(64) NOT NULL, first_seen BIGINT NOT NULL," +
            " last_seen BIGINT NOT NULL, PRIMARY KEY (uuid, ip)){table}",
        "CREATE INDEX {p}ips_ip ON {p}ips (ip)",
    )

    internal fun statements(sql: Sql): List<Statement> = STEP_1.map { sql.raw(fill(it, sql.dialect)) }

    /** 1.x's placeholders: `{rowid}` a key the database numbers, `{table}` what follows a `CREATE TABLE`. */
    internal fun fill(text: String, dialect: Dialect): String = when (dialect) {
        is Dialect.Sqlite -> text.replace("{rowid}", "INTEGER PRIMARY KEY").replace("{table}", "")
        Dialect.MySql -> text.replace("{rowid}", "BIGINT PRIMARY KEY AUTO_INCREMENT").replace("{table}", " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    }

    /** `INSERT IGNORE` as each database says it. */
    internal fun insertIgnore(dialect: Dialect): String = if (dialect is Dialect.Sqlite) "INSERT OR IGNORE" else "INSERT IGNORE"

    /** A punishment with its removal, if any: the columns [Rows.read] reads, by these names. */
    internal const val SELECT: String = "SELECT p.id AS id, p.type AS type, p.uuid AS uuid, p.name AS name, p.ip AS ip, p.ip_based AS ip_based," +
        " p.reason AS reason, p.staff_uuid AS staff_uuid, p.staff_name AS staff_name, p.created AS created, p.expires AS expires," +
        " p.scope AS scope, p.silent AS silent, p.template AS template, r.staff_uuid AS r_staff_uuid, r.staff_name AS r_staff_name," +
        " r.removed AS r_removed, r.reason AS r_reason, p.staff_weight AS staff_weight" +
        " FROM {p}punishments p LEFT JOIN {p}removals r ON r.punishment = p.id"

    internal const val INSERT: String = "INSERT INTO {p}punishments (type, uuid, name, ip, ip_based, reason, staff_uuid," +
        " staff_name, created, expires, scope, origin, silent, template, imported, staff_weight)" +
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
}
