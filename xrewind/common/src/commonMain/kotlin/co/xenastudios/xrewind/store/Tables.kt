package co.xenastudios.xrewind.store

import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.Migration
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.Statement

/**
 * xRewind's tables: 1.x's, byte for byte (its `Schema.STEPS`, schema 1), so either jar reads the
 * other's database (decisions/2026-11-27-xrewind-2.md). Every row is a handful of numbers: names,
 * worlds, block states and items are each stored once in a dictionary table and referred to by
 * id. Times are seconds since 2026 ([co.xenastudios.xrewind.log.StoredTime]). Indexes cost every
 * write, so there are only the ones a lookup needs: by place, by player, and by time (purges).
 * 1.x's `meta` table (its schema version and `coreprotect-import`) is the core's
 * ([co.xenastudios.core.common.db.Migrations.Meta]), the same columns.
 */
public object Tables {
    public val MIGRATIONS: List<Migration> = listOf(
        Migration(1, "the first tables", co.xenastudios.xrewind.Startup.tables),
    )

    /** The tables with history, in the order a purge goes through them. */
    public val HISTORY: List<String> = listOf("block", "container", "entity", "text", "session")

    private val STEP_1 = listOf(
        // Players have a uuid; sources like #tnt and #creeper don't.
        "CREATE TABLE {p}user (id INTEGER PRIMARY KEY, uuid_most BIGINT, uuid_least BIGINT, name VARCHAR(64) NOT NULL){table}",
        "CREATE TABLE {p}world (id INTEGER PRIMARY KEY, name VARCHAR(255) NOT NULL){table}",
        // A block state as Minecraft writes it: minecraft:oak_door[facing=north,half=lower,...]
        "CREATE TABLE {p}state (id INTEGER PRIMARY KEY, data TEXT NOT NULL){table}",
        // An item stack, or a block's contents (a chest's items, a sign's text), as bytes.
        "CREATE TABLE {p}item (id INTEGER PRIMARY KEY, hash BIGINT NOT NULL, data {blob} NOT NULL){table}",
        "CREATE TABLE {p}block (id {rowid}, time INT NOT NULL, user INT NOT NULL, world SMALLINT NOT NULL," +
            " x INT NOT NULL, y SMALLINT NOT NULL, z INT NOT NULL, action TINYINT NOT NULL," +
            " old_state INT NOT NULL, new_state INT NOT NULL, extra INT, rolled_back TINYINT NOT NULL DEFAULT 0){table}",
        "CREATE INDEX {p}block_place ON {p}block (world, x, z, time)",
        "CREATE INDEX {p}block_user ON {p}block (user, time)",
        "CREATE INDEX {p}block_time ON {p}block (time)",
        "CREATE TABLE {p}container (id {rowid}, time INT NOT NULL, user INT NOT NULL, world SMALLINT NOT NULL," +
            " x INT NOT NULL, y SMALLINT NOT NULL, z INT NOT NULL, action TINYINT NOT NULL," +
            " material INT NOT NULL, item INT NOT NULL, amount INT NOT NULL, rolled_back TINYINT NOT NULL DEFAULT 0){table}",
        "CREATE INDEX {p}container_place ON {p}container (world, x, z, time)",
        "CREATE INDEX {p}container_user ON {p}container (user, time)",
        "CREATE INDEX {p}container_time ON {p}container (time)",
        "CREATE TABLE {p}entity (id {rowid}, time INT NOT NULL, user INT NOT NULL, world SMALLINT NOT NULL," +
            " x INT NOT NULL, y SMALLINT NOT NULL, z INT NOT NULL, action TINYINT NOT NULL," +
            " type INT NOT NULL, extra INT, rolled_back TINYINT NOT NULL DEFAULT 0){table}",
        "CREATE INDEX {p}entity_place ON {p}entity (world, x, z, time)",
        "CREATE INDEX {p}entity_user ON {p}entity (user, time)",
        "CREATE INDEX {p}entity_time ON {p}entity (time)",
        "CREATE TABLE {p}text (id {rowid}, time INT NOT NULL, user INT NOT NULL, world SMALLINT NOT NULL," +
            " x INT NOT NULL, y SMALLINT NOT NULL, z INT NOT NULL, kind TINYINT NOT NULL, message TEXT NOT NULL){table}",
        "CREATE INDEX {p}text_user ON {p}text (user, time)",
        "CREATE INDEX {p}text_time ON {p}text (time)",
        "CREATE TABLE {p}session (id {rowid}, time INT NOT NULL, user INT NOT NULL, world SMALLINT NOT NULL," +
            " x INT NOT NULL, y SMALLINT NOT NULL, z INT NOT NULL, action TINYINT NOT NULL){table}",
        "CREATE INDEX {p}session_user ON {p}session (user, time)",
        "CREATE INDEX {p}session_time ON {p}session (time)",
    )

    internal fun statements(sql: Sql): List<Statement> = STEP_1.map { sql.raw(fill(it, sql.dialect)) }

    /** 1.x's placeholders: `{rowid}` a key the database numbers, `{blob}` bytes, `{table}` what follows a `CREATE TABLE`. */
    internal fun fill(text: String, dialect: Dialect): String = when (dialect) {
        is Dialect.Sqlite -> text.replace("{rowid}", "INTEGER PRIMARY KEY").replace("{blob}", "BLOB").replace("{table}", "")
        Dialect.MySql -> text.replace("{rowid}", "BIGINT PRIMARY KEY AUTO_INCREMENT").replace("{blob}", "MEDIUMBLOB")
            .replace("{table}", " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    }
}
