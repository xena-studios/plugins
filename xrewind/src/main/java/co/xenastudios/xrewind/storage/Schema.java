package co.xenastudios.xrewind.storage;

import co.xenastudios.core.db.SchemaStep;
import java.util.List;

/**
 * xRewind's tables. Every row is a handful of numbers: names, worlds, block states and
 * items are each stored once in a dictionary table and referred to by id. Times are
 * {@linkplain Clock seconds since 2026}, which fit an INT until 2094.
 *
 * <p>Indexes cost every write, so there are only the ones a lookup needs: by place, by
 * player, and by time (for purges).
 *
 * <p>Until 1.0 is released, step 1 may still change; after, changes are new steps.
 */
public final class Schema {

    private Schema() {}

    public static final List<SchemaStep> STEPS = List.of(SchemaStep.of(1, "the first tables",
        // Players have a uuid; sources like #tnt and #creeper don't.
        "CREATE TABLE {p}user (id INTEGER PRIMARY KEY, uuid_most BIGINT, uuid_least BIGINT, name VARCHAR(64) NOT NULL)",
        "CREATE TABLE {p}world (id INTEGER PRIMARY KEY, name VARCHAR(255) NOT NULL)",
        // A block state as Minecraft writes it: minecraft:oak_door[facing=north,half=lower,...]
        "CREATE TABLE {p}state (id INTEGER PRIMARY KEY, data TEXT NOT NULL)",
        // An item stack, or a block's contents (a chest's items, a sign's text), as bytes.
        // Paper's item format carries its data version, so items upgrade themselves.
        "CREATE TABLE {p}item (id INTEGER PRIMARY KEY, hash BIGINT NOT NULL, data {blob} NOT NULL)",

        "CREATE TABLE {p}block (id {rowid}, time INT NOT NULL, user INT NOT NULL, world SMALLINT NOT NULL,"
            + " x INT NOT NULL, y SMALLINT NOT NULL, z INT NOT NULL, action TINYINT NOT NULL,"
            + " old_state INT NOT NULL, new_state INT NOT NULL, extra INT, rolled_back TINYINT NOT NULL DEFAULT 0)",
        "CREATE INDEX {p}block_place ON {p}block (world, x, z, time)",
        "CREATE INDEX {p}block_user ON {p}block (user, time)",
        "CREATE INDEX {p}block_time ON {p}block (time)",

        // Items put in or taken out of a container. material is the item's type, as a state
        // id, for lookups; item is the whole stack, for rollbacks.
        "CREATE TABLE {p}container (id {rowid}, time INT NOT NULL, user INT NOT NULL, world SMALLINT NOT NULL,"
            + " x INT NOT NULL, y SMALLINT NOT NULL, z INT NOT NULL, action TINYINT NOT NULL,"
            + " material INT NOT NULL, item INT NOT NULL, amount INT NOT NULL, rolled_back TINYINT NOT NULL DEFAULT 0)",
        "CREATE INDEX {p}container_place ON {p}container (world, x, z, time)",
        "CREATE INDEX {p}container_user ON {p}container (user, time)",
        "CREATE INDEX {p}container_time ON {p}container (time)",

        // Kills, and later other things done to entities. type is the entity's type, as a
        // state id.
        "CREATE TABLE {p}entity (id {rowid}, time INT NOT NULL, user INT NOT NULL, world SMALLINT NOT NULL,"
            + " x INT NOT NULL, y SMALLINT NOT NULL, z INT NOT NULL, action TINYINT NOT NULL,"
            + " type INT NOT NULL, extra INT, rolled_back TINYINT NOT NULL DEFAULT 0)",
        "CREATE INDEX {p}entity_place ON {p}entity (world, x, z, time)",
        "CREATE INDEX {p}entity_user ON {p}entity (user, time)",
        "CREATE INDEX {p}entity_time ON {p}entity (time)",

        // Sign text, and chat and commands where they're logged.
        "CREATE TABLE {p}text (id {rowid}, time INT NOT NULL, user INT NOT NULL, world SMALLINT NOT NULL,"
            + " x INT NOT NULL, y SMALLINT NOT NULL, z INT NOT NULL, kind TINYINT NOT NULL, message TEXT NOT NULL)",
        "CREATE INDEX {p}text_user ON {p}text (user, time)",
        "CREATE INDEX {p}text_time ON {p}text (time)",

        // Joins and quits, with where.
        "CREATE TABLE {p}session (id {rowid}, time INT NOT NULL, user INT NOT NULL, world SMALLINT NOT NULL,"
            + " x INT NOT NULL, y SMALLINT NOT NULL, z INT NOT NULL, action TINYINT NOT NULL)",
        "CREATE INDEX {p}session_user ON {p}session (user, time)",
        "CREATE INDEX {p}session_time ON {p}session (time)"));
}
