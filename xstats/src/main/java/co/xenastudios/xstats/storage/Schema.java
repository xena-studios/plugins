package co.xenastudios.xstats.storage;

import co.xenastudios.core.db.SchemaStep;
import java.util.List;

/**
 * xStats' tables, documented for owners who build their own site on them
 * (docs: xstats/database). Rows hold numbers only: players, stats and servers are stored
 * once each and referred to by id.
 *
 * <ul>
 *   <li>{@code totals}: each player's all-time value of each stat on each server, and the
 *       last reading of Minecraft's own counter ({@code raw}, -1 for stats that aren't one).</li>
 *   <li>{@code daily}: how much a stat went up on a day, only for stats with periods and
 *       only on days it changed. {@code day} is days since 1970-01-01 in the config's time zone.</li>
 *   <li>{@code monthly}: daily rows older than {@code periods.keep-daily-days}, added up per
 *       month. {@code month} is {@code year * 12 + month - 1}.</li>
 * </ul>
 */
public final class Schema {

    private Schema() {}

    public static final List<SchemaStep> STEPS = List.of(
        SchemaStep.of(1, "players, stats, totals, daily and monthly rows, boards",
            "CREATE TABLE IF NOT EXISTS {p}servers (id {rowid}, name VARCHAR(64) NOT NULL UNIQUE){table}",
            "CREATE TABLE IF NOT EXISTS {p}stats (id {rowid}, name VARCHAR(64) NOT NULL UNIQUE){table}",
            "CREATE TABLE IF NOT EXISTS {p}players (id {rowid}, uuid CHAR(36) NOT NULL UNIQUE, name VARCHAR(16),"
                + " name_lower VARCHAR(16), first_join BIGINT NOT NULL, last_seen BIGINT NOT NULL,"
                + " hidden SMALLINT NOT NULL, excluded SMALLINT NOT NULL){table}",
            "CREATE INDEX {p}players_name ON {p}players (name_lower)",
            "CREATE TABLE IF NOT EXISTS {p}totals (player INT NOT NULL, stat INT NOT NULL, server INT NOT NULL,"
                + " value BIGINT NOT NULL, raw BIGINT NOT NULL, PRIMARY KEY (player, stat, server)){table}",
            // For owners' own queries, such as a server's top players of a stat.
            "CREATE INDEX {p}totals_stat ON {p}totals (stat, server, value)",
            "CREATE TABLE IF NOT EXISTS {p}daily (player INT NOT NULL, stat INT NOT NULL, server INT NOT NULL,"
                + " day INT NOT NULL, delta BIGINT NOT NULL, PRIMARY KEY (player, stat, server, day)){table}",
            "CREATE INDEX {p}daily_day ON {p}daily (day)",
            "CREATE TABLE IF NOT EXISTS {p}monthly (player INT NOT NULL, stat INT NOT NULL, server INT NOT NULL,"
                + " month INT NOT NULL, delta BIGINT NOT NULL, PRIMARY KEY (player, stat, server, month)){table}",
            "CREATE TABLE IF NOT EXISTS {p}boards (id {rowid}, server INT NOT NULL, world VARCHAR(64) NOT NULL,"
                + " x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL,"
                + " stat VARCHAR(64) NOT NULL, period VARCHAR(16) NOT NULL){table}"));
}
