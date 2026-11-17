package co.xenastudios.core.db;

/**
 * What differs between the databases a plugin can use. Schema statements write these
 * as placeholders, which {@link Database#sql} fills in: {@code {rowid}} and {@code {blob}}.
 */
public enum Dialect {
    // A plain INTEGER PRIMARY KEY is SQLite's own row id: no extra index, no AUTOINCREMENT table.
    SQLITE("INTEGER PRIMARY KEY", "BLOB", "INSERT OR IGNORE"),
    MYSQL("BIGINT PRIMARY KEY AUTO_INCREMENT", "MEDIUMBLOB", "INSERT IGNORE");

    private final String rowId;
    private final String blob;
    private final String insertIgnore;

    Dialect(String rowId, String blob, String insertIgnore) {
        this.rowId = rowId;
        this.blob = blob;
        this.insertIgnore = insertIgnore;
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
