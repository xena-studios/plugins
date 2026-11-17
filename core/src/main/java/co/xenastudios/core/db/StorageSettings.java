package co.xenastudios.core.db;

import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.Range;

/** The {@code storage} section of a plugin that keeps data in a database. */
public record StorageSettings(
    @Comment({
        "sqlite keeps everything in one file in the plugin's folder, with nothing to set up.",
        "mysql (or MariaDB) is for networks, or servers with a lot of history."})
    Type type,
    @Comment("Put in front of every table's name, so plugins and servers can share one database.")
    String tablePrefix,
    @Comment("Only for type: mysql.") MySql mysql) {

    public enum Type { SQLITE, MYSQL }

    public record MySql(
        String host,
        @Range(min = 1, max = 65535) int port,
        String database,
        String username,
        String password,
        @Comment("Extra connection settings, as in a JDBC URL: useSSL=true&serverTimezone=UTC.")
        String properties) {}

    /** Defaults for a plugin whose tables start with {@code prefix}, such as {@code "xrewind_"}. */
    public static StorageSettings defaults(String prefix) {
        return new StorageSettings(Type.SQLITE, prefix,
            new MySql("localhost", 3306, "minecraft", "minecraft", "", ""));
    }
}
