package co.xenastudios.core.common.family

/**
 * How the family's plugins hand their data to a backup (ROADMAP.md, The family: "every plugin
 * registers its data and pauses its database for a backup";
 * decisions/2026-11-27-the-familys-backup-seam.md). Only names and positions: nothing here is
 * code that runs, and every value is a constant the compiler copies in, so using it loads no
 * class.
 *
 * **On the board.** Each plugin's database puts one entry in the board's [MAP] as it opens and
 * takes it out as it closes, without the plugin doing anything (on the JVM, `JdbcDatabase` does,
 * through the board its platform found). The key is the plugin's name, a space, and [WHERE]; the
 * value an `Array<Any?>` (Java's `Object[]`), the board's rule, read by position:
 * - [KIND]: [SQLITE] or [MYSQL];
 * - [PLUGIN]: the plugin's name, `xMoney`;
 * - [WHERE]: for SQLite the file's absolute path, for MySQL `host:port/database`;
 * - [DESCRIPTION]: for the console, `the SQLite database money.db`;
 * - [PREFIX]: the plugin's table prefix, `xmoney_`;
 * - [HANDLE]: for SQLite, a `java.util.function.Function<Any?, Any?>` the backup calls; for
 *   MySQL, `null`.
 *
 * **The handle** is the one thing on the board that runs, so it's a JDK interface, the same class
 * in every plugin's copy of the core and of Kotlin, and only JDK values go in and out. Asked
 * `arrayOf(SNAPSHOT, "<absolute path of a file that doesn't exist yet>")`, it answers at once
 * with a `java.util.concurrent.CompletableFuture`: the plugin's own database writer, after every
 * write queued before the request and before any queued after it, writes one consistent copy of
 * the database there, then carries on. Writes queue behind it meanwhile, as they do behind any
 * batch: nothing waits on a game thread. The future completes with `null` once the copy is
 * whole on disk, or with a `String` saying why there's none (the database closed, the disk full);
 * then the file isn't there. Anything else asked answers [UNSUPPORTED] at once.
 *
 * **MySQL** is outside the server, so a backup of the server can't hold it: the entry says where
 * it is, for the backup to say so; dumping it is the database's own tools' job.
 */
public object FamilyBackups {
    /** The board's map of databases, by plugin and place. A new layout is a new name. */
    public const val MAP: String = "xena.backups.v1"

    /** Position in an entry: [SQLITE] or [MYSQL]. */
    public const val KIND: Int = 0

    /** Position in an entry: the plugin's name. */
    public const val PLUGIN: Int = 1

    /** Position in an entry: the SQLite file's absolute path, or MySQL's `host:port/database`. */
    public const val WHERE: Int = 2

    /** Position in an entry: the database as the console names it. */
    public const val DESCRIPTION: Int = 3

    /** Position in an entry: the plugin's table prefix. */
    public const val PREFIX: Int = 4

    /** Position in an entry: the handle (SQLite), or `null`. */
    public const val HANDLE: Int = 5

    /** How many positions an entry has. */
    public const val SIZE: Int = 6

    /** [KIND] of a SQLite file on this server. */
    public const val SQLITE: String = "sqlite"

    /** [KIND] of a MySQL or MariaDB database, outside the server. */
    public const val MYSQL: String = "mysql"

    /** The handle's one request: a consistent copy of the database to a path. */
    public const val SNAPSHOT: String = "snapshot"

    /** What the handle answers a request it doesn't know. */
    public const val UNSUPPORTED: String = "unsupported"
}
