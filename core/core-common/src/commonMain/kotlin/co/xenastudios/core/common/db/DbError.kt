package co.xenastudios.core.common.db

/**
 * Why the database couldn't do something: the [kind], for code to decide on, and the driver's
 * [detail], for the console. Nothing in the database layer throws; this comes back in an
 * [co.xenastudios.core.common.Outcome] instead.
 */
public data class DbError(
    public val kind: Kind,
    public val detail: String,
    /** The driver's SQLState, when it gave one. */
    public val sqlState: String? = null,
    /** The database's own error number (MySQL's 1062, SQLite's 19), when it gave one. */
    public val code: Int? = null,
) {
    /** The kinds of failure. */
    public enum class Kind {
        /** The database can't be reached, or the connection broke. Waiting and trying again helps. */
        UNAVAILABLE,

        /** Another writer holds a lock (SQLite's busy, MySQL's deadlock or lock wait). Trying again helps. */
        BUSY,

        /** A key, unique or not-null rule refused the row. Trying again won't help. */
        CONSTRAINT,

        /** What a statement makes is already there: a migration run again after it got partway. */
        ALREADY_EXISTS,

        /** The statement is wrong for this database: a bug, not the database's fault. */
        BAD_STATEMENT,

        /** The database was made by a newer version of the plugin, so it's left alone. */
        NEWER_SCHEMA,

        /** Too many writes are waiting for the database, so this one was refused, not queued. */
        OVERLOADED,

        /** The database was closed, so this was never run. */
        CLOSED,

        /** The server has no driver for this database, and none could be fetched. */
        NO_DRIVER,

        /** The stored data isn't what the code expected: a column of the wrong type, a file that doesn't parse. */
        CORRUPT,

        /** Anything else the driver said. */
        OTHER,
    }

    /** Whether running the same thing again later could work. */
    public val transient: Boolean get() = kind == Kind.UNAVAILABLE || kind == Kind.BUSY

    override fun toString(): String = "$kind: $detail"
}
