package co.xenastudios.xteleport.storage;

import co.xenastudios.core.db.SchemaStep;
import java.util.List;

/**
 * xTeleport's tables. Names are kept as typed and keyed in lowercase, so {@code /home Base}
 * and {@code /home base} are one home. Every place has a {@code server} column, empty for
 * now, so homes and warps across a network can be added later without changing a table.
 */
final class Schema {

    private Schema() {}

    private static final String PLACE = "server VARCHAR(64) NOT NULL DEFAULT '', world VARCHAR(255) NOT NULL,"
        + " x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL,"
        + " created BIGINT NOT NULL";

    static final List<SchemaStep> STEPS = List.of(
        SchemaStep.of(1, "homes, warps, the spawn and players' settings",
            "CREATE TABLE IF NOT EXISTS {p}homes (owner CHAR(36) NOT NULL, name_key VARCHAR(32) NOT NULL,"
                + " name VARCHAR(32) NOT NULL, " + PLACE + ", PRIMARY KEY (owner, name_key)){table}",
            "CREATE TABLE IF NOT EXISTS {p}warps (name_key VARCHAR(32) NOT NULL, name VARCHAR(32) NOT NULL, "
                + PLACE + ", PRIMARY KEY (server, name_key)){table}",
            "CREATE TABLE IF NOT EXISTS {p}spawns (name_key VARCHAR(32) NOT NULL, name VARCHAR(32) NOT NULL, "
                + PLACE + ", PRIMARY KEY (server, name_key)){table}",
            "CREATE TABLE IF NOT EXISTS {p}players (uuid CHAR(36) NOT NULL PRIMARY KEY, tpa_off INT NOT NULL DEFAULT 0){table}"));
}
