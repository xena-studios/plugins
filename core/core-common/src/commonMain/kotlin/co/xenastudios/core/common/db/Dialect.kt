package co.xenastudios.core.common.db

/**
 * The database a plugin is talking to, which decides how statements are written. Found once
 * when the database opens; [Sql] asks it, nothing else does.
 */
public sealed class Dialect {
    /** Words for logs and `/<plugin> debug`, such as `SQLite 3.49.1`. */
    public abstract val description: String

    /** Whether it can tell servers about changes ([ChangeFeed]): only a database servers can share. */
    public abstract val shared: Boolean

    /**
     * SQLite, at the [version] the driver reports. Old servers bundle old drivers: Spigot 1.8.8's
     * is sqlite-jdbc 3.7.2, older than SQLite's upsert (3.24, 2018), so [nativeUpsert] decides
     * how [Sql.upsert] is written.
     */
    public data class Sqlite(public val version: SqlVersion) : Dialect() {
        override val description: String get() = "SQLite $version"
        override val shared: Boolean get() = false

        /** `INSERT ... ON CONFLICT DO UPDATE`, from 3.24.0. Before that, two statements do it. */
        public val nativeUpsert: Boolean get() = version >= UPSERT

        public companion object {
            /** The first SQLite with `ON CONFLICT ... DO UPDATE`. */
            public val UPSERT: SqlVersion = SqlVersion(3, 24, 0)

            /**
             * The oldest SQLite the core uses: WAL arrived in 3.7.0, so readers never wait for the
             * writer. Spigot 1.8.8's 3.7.2 is newer; a driver older than this is passed over for
             * a fetched one.
             */
            public val MINIMUM: SqlVersion = SqlVersion(3, 7, 0)

            /** A recent SQLite, for tests and for statements built before a database is open. */
            public val CURRENT: Sqlite = Sqlite(SqlVersion(3, 49, 1))
        }
    }

    /**
     * MySQL or MariaDB: the same SQL for everything the core writes. Upserts are
     * `ON DUPLICATE KEY UPDATE c = VALUES(c)`, which MySQL 8.0.20+ calls deprecated but still
     * runs, and which MariaDB's alias-free grammar needs.
     */
    public data object MySql : Dialect() {
        override val description: String get() = "MySQL"
        override val shared: Boolean get() = true
    }
}

/** A version number, `3.24.0`, compared part by part. */
public data class SqlVersion(public val major: Int, public val minor: Int, public val patch: Int) : Comparable<SqlVersion> {
    override fun compareTo(other: SqlVersion): Int =
        compareValuesBy(this, other, SqlVersion::major, SqlVersion::minor, SqlVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    public companion object {
        /** The first three numbers of a version string such as `3.49.1` or `3.7.2-SNAPSHOT`, or `null`. */
        public fun parse(text: String): SqlVersion? {
            val parts = text.trim().split('.', '-', ' ').map { it.toIntOrNull() }
            val major = parts.getOrNull(0) ?: return null
            return SqlVersion(major, parts.getOrNull(1) ?: 0, parts.getOrNull(2) ?: 0)
        }
    }
}
