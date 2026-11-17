package co.xenastudios.core.db;

/**
 * What differs between the databases a plugin can use. Schema statements write these
 * as placeholders, which {@link Database#sql} fills in: {@code {rowid}}, {@code {blob}} and
 * {@code {table}}.
 */
public enum Dialect {
    // A plain INTEGER PRIMARY KEY is SQLite's own row id: no extra index, no AUTOINCREMENT table.
    SQLITE("INTEGER PRIMARY KEY", "BLOB", "INSERT OR IGNORE", ""),
    // utf8mb4 whatever the database's default: a heart on a sign, or an emoji in chat, would
    // otherwise fail every write it's in.
    MYSQL("BIGINT PRIMARY KEY AUTO_INCREMENT", "MEDIUMBLOB", "INSERT IGNORE", " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

    private final String rowId;
    private final String blob;
    private final String insertIgnore;
    private final String tableOptions;

    Dialect(String rowId, String blob, String insertIgnore, String tableOptions) {
        this.rowId = rowId;
        this.blob = blob;
        this.insertIgnore = insertIgnore;
        this.tableOptions = tableOptions;
    }

    /** What goes after a CREATE TABLE's columns: {@code {table}}. */
    public String tableOptions() {
        return tableOptions;
    }

    /** A key column that numbers new rows itself: {@code id {rowid}}. */
    public String rowId() {
        return rowId;
    }

    /** A column of bytes, up to 16 MiB on MySQL. */
    public String blob() {
        return blob;
    }

    /** {@code INSERT} that skips a row whose key is already there. */
    public String insertIgnore() {
        return insertIgnore;
    }
}
