package co.xenastudios.xbans.storage;

import co.xenastudios.core.db.SchemaStep;
import java.util.List;

/**
 * xBans' tables. {@code punishments} is append-only: a punishment is written once and never
 * changed. Revoking one (an unban, unmute or unwarn) adds a row to {@code removals} with
 * who, when and why. The only deletes are {@code /prunehistory}'s, which staff ask for.
 *
 * <p>Times are epoch milliseconds; {@code expires} is 0 for permanent. uuids are written out,
 * so the tables read easily and importers need no conversion. {@code ip} is the address, or
 * its salted hash with {@code ips.hash} on.
 *
 * <p>Until 1.0 is released, step 1 may still change; after, changes are new steps.
 */
public final class Schema {

    private Schema() {}

    public static final List<SchemaStep> STEPS = List.of(SchemaStep.of(1, "the first tables",
        // type: 1 ban, 2 mute, 3 warn, 4 kick. imported: where an imported row came from, such as
        // "litebans:bans:12", so importing twice adds nothing. staff_weight: the weight of whoever
        // made it, then (2147483647 for the console and plugins), which decides who may lift it.
        "CREATE TABLE {p}punishments (id {rowid}, type TINYINT NOT NULL, uuid CHAR(36), name VARCHAR(64) NOT NULL,"
            + " ip VARCHAR(64), ip_based TINYINT NOT NULL, reason VARCHAR(1024) NOT NULL, staff_uuid CHAR(36),"
            + " staff_name VARCHAR(64) NOT NULL, staff_weight INT NOT NULL, created BIGINT NOT NULL, expires BIGINT NOT NULL,"
            + " scope VARCHAR(64) NOT NULL, origin VARCHAR(64) NOT NULL, silent TINYINT NOT NULL,"
            + " template VARCHAR(64), imported VARCHAR(96)){table}",
        // History pages, newest first, by key range: as fast for thousands as for ten.
        "CREATE INDEX {p}punishments_uuid ON {p}punishments (uuid, id)",
        "CREATE INDEX {p}punishments_staff ON {p}punishments (staff_uuid, id)",
        "CREATE INDEX {p}punishments_ip ON {p}punishments (ip, id)",
        // Loading what's in force at startup.
        "CREATE INDEX {p}punishments_active ON {p}punishments (type, expires)",
        "CREATE INDEX {p}punishments_imported ON {p}punishments (imported)",
        "CREATE TABLE {p}removals (punishment BIGINT NOT NULL PRIMARY KEY, staff_uuid CHAR(36),"
            + " staff_name VARCHAR(64) NOT NULL, removed BIGINT NOT NULL, reason VARCHAR(1024) NOT NULL){table}",
        // The last name, and the weight and exemption a player had when last seen, so offline
        // players are held to staff limits too.
        "CREATE TABLE {p}players (uuid CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL,"
            + " first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, weight INT NOT NULL, exempt TINYINT NOT NULL){table}",
        "CREATE INDEX {p}players_name ON {p}players (name)",
        "CREATE TABLE {p}ips (uuid CHAR(36) NOT NULL, ip VARCHAR(64) NOT NULL, first_seen BIGINT NOT NULL,"
            + " last_seen BIGINT NOT NULL, PRIMARY KEY (uuid, ip)){table}",
        "CREATE INDEX {p}ips_ip ON {p}ips (ip)"));
}
