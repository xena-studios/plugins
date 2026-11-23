package co.xenastudios.xvote.storage;

import co.xenastudios.core.db.SchemaStep;
import java.util.List;

/**
 * xVote's tables.
 *
 * <p>{@code votes} is the ledger: one row per vote, never deleted except unmatched names
 * past {@code counting.unknown-name-days}. {@code uuid} is empty until the name is matched
 * to a player; {@code number}, {@code streak} and {@code first_today} are worked out when
 * it is (the vote's place in the player's count), so whichever server pays it gives the
 * same rewards. {@code claim} is empty until a server takes the vote to pay it: taking
 * it is one {@code UPDATE ... WHERE claim IS NULL}, so two servers can never both pay it.
 *
 * <p>{@code voters} holds each player's running totals and streak, kept in step with the
 * ledger in the same transaction. {@code held} keeps reward items that didn't fit, for
 * {@code /vote claim}. {@code state} keeps the vote party's count. Times are epoch
 * milliseconds; {@code last_day} is a day number in the configured time zone.
 *
 * <p>{@code address} is always empty: xVote never needed the voter's IP address, which a
 * list sends with each vote, so since 1.0.1 it isn't kept (step 2 cleared those 1.0.0 kept).
 * The event still carries it, for plugins that want it. Changes are new steps.
 */
public final class Schema {

    private Schema() {}

    public static final List<SchemaStep> STEPS = List.of(SchemaStep.of(1, "the first tables",
        "CREATE TABLE {p}votes (id {rowid}, uuid VARCHAR(36), name VARCHAR(64) NOT NULL, name_key VARCHAR(64) NOT NULL,"
            + " site VARCHAR(64) NOT NULL, address VARCHAR(64) NOT NULL, list_time VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL,"
            + " at BIGINT NOT NULL, number INT, streak INT, first_today INT, claim VARCHAR(40), paid_at BIGINT){table}",
        "CREATE INDEX {p}votes_player ON {p}votes (uuid, at)",
        "CREATE INDEX {p}votes_name ON {p}votes (name_key, site)",
        "CREATE INDEX {p}votes_at ON {p}votes (at)",
        "CREATE TABLE {p}voters (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, total INT NOT NULL,"
            + " streak INT NOT NULL, best_streak INT NOT NULL, last_day INT NOT NULL, last_vote BIGINT NOT NULL){table}",
        "CREATE TABLE {p}held (id {rowid}, uuid VARCHAR(36) NOT NULL, item {blob} NOT NULL, created BIGINT NOT NULL){table}",
        "CREATE INDEX {p}held_player ON {p}held (uuid)",
        "CREATE TABLE {p}state (name VARCHAR(32) PRIMARY KEY, value BIGINT NOT NULL){table}",
        "INSERT INTO {p}state (name, value) VALUES ('party_votes', 0)",
        "INSERT INTO {p}state (name, value) VALUES ('party_since', 0)"),
        SchemaStep.of(2, "voters' addresses aren't kept", "UPDATE {p}votes SET address = '' WHERE address <> ''"));
}
