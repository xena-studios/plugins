package co.xenastudios.xmoney.ledger;

import co.xenastudios.core.db.SchemaStep;
import java.util.List;

/**
 * xMoney's tables. Amounts are {@code BIGINT}s of units (ten-thousandths, see
 * {@link co.xenastudios.xmoney.money.Money}) and times are epoch milliseconds.
 *
 * <ul>
 *   <li>{@code accounts}: each account's last known name, and whether it's a player's.</li>
 *   <li>{@code balances}: one row per account and currency. Only {@code default} in 1.0; the
 *       column is there so more currencies need no migration.</li>
 *   <li>{@code transactions}: the ledger, append-only. Every change is a row here, written in
 *       the same database transaction as the balance it changed, so the two always agree.
 *       {@code commit_id} is set on the first row of each database transaction: a retry after a
 *       commit whose answer was lost finds it there and isn't applied twice.</li>
 * </ul>
 */
public final class Schema {

    public static final int NAME_LENGTH = 64;
    public static final int SOURCE_LENGTH = 64;
    public static final int REASON_LENGTH = 100;

    public static final List<SchemaStep> STEPS = List.of(
        SchemaStep.of(1, "accounts, balances and the ledger",
            "CREATE TABLE IF NOT EXISTS {p}accounts (uuid CHAR(36) PRIMARY KEY, name VARCHAR(" + NAME_LENGTH + ") NOT NULL,"
                + " player SMALLINT NOT NULL, created BIGINT NOT NULL){table}",
            "CREATE TABLE IF NOT EXISTS {p}balances (uuid CHAR(36) NOT NULL, currency VARCHAR(32) NOT NULL,"
                + " balance BIGINT NOT NULL, PRIMARY KEY (uuid, currency)){table}",
            "CREATE TABLE IF NOT EXISTS {p}transactions (id {rowid}, time BIGINT NOT NULL, uuid CHAR(36) NOT NULL,"
                + " currency VARCHAR(32) NOT NULL, amount BIGINT NOT NULL, balance BIGINT NOT NULL, kind VARCHAR(16) NOT NULL,"
                + " source VARCHAR(" + SOURCE_LENGTH + ") NOT NULL, reason VARCHAR(" + REASON_LENGTH + ") NOT NULL,"
                + " other CHAR(36), commit_id BIGINT){table}",
            "CREATE INDEX {p}transactions_uuid ON {p}transactions (uuid, id)",
            "CREATE INDEX {p}transactions_time ON {p}transactions (time)",
            "CREATE UNIQUE INDEX {p}transactions_commit ON {p}transactions (commit_id)"));

    private Schema() {}
}
