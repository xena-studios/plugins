package co.xenastudios.xplots.storage;

import co.xenastudios.core.db.SchemaStep;
import java.util.List;

/**
 * xPlots' tables. Everything is also held in memory; the database is where it survives a
 * restart. A grid field is stored as its selection, plot size and path, from which the
 * grid is worked out again; each plot keeps its own box and sign so either can change.
 *
 * <p>Until 1.0 is released, step 1 may still change; after, changes are new steps.
 */
public final class Schema {

    private Schema() {}

    public static final List<SchemaStep> STEPS = List.of(SchemaStep.of(1, "the first tables",
        "CREATE TABLE {p}field (id INT PRIMARY KEY, name VARCHAR(64) NOT NULL, world VARCHAR(255) NOT NULL,"
            + " min_x INT NOT NULL, min_y INT NOT NULL, min_z INT NOT NULL, max_x INT NOT NULL, max_y INT NOT NULL, max_z INT NOT NULL,"
            + " size_x INT NOT NULL, size_z INT NOT NULL, path INT NOT NULL, settings TEXT, warp VARCHAR(255)){table}",
        "CREATE TABLE {p}plot (field INT NOT NULL, number INT NOT NULL,"
            + " min_x INT NOT NULL, min_y INT NOT NULL, min_z INT NOT NULL, max_x INT NOT NULL, max_y INT NOT NULL, max_z INT NOT NULL,"
            + " sign_x INT NOT NULL, sign_y INT NOT NULL, sign_z INT NOT NULL, sign_facing VARCHAR(16) NOT NULL,"
            + " PRIMARY KEY (field, number)){table}",
        // Unix milliseconds. warned: 0 none yet, 1 the day before, 2 the hour before.
        "CREATE TABLE {p}lease (field INT NOT NULL, number INT NOT NULL, owner_most BIGINT NOT NULL, owner_least BIGINT NOT NULL,"
            + " owner_name VARCHAR(16) NOT NULL, expires BIGINT NOT NULL, slots INT NOT NULL, warned TINYINT NOT NULL,"
            + " PRIMARY KEY (field, number)){table}",
        "CREATE TABLE {p}member (field INT NOT NULL, number INT NOT NULL, uuid_most BIGINT NOT NULL, uuid_least BIGINT NOT NULL,"
            + " name VARCHAR(16) NOT NULL, PRIMARY KEY (field, number, uuid_most, uuid_least)){table}",
        // The plot's blocks when it was made: a palette of block states and an index per block, deflated.
        "CREATE TABLE {p}snapshot (field INT NOT NULL, number INT NOT NULL, data {blob} NOT NULL,"
            + " PRIMARY KEY (field, number)){table}"));
}
