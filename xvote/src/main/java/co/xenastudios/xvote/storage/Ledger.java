package co.xenastudios.xvote.storage;

import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.Dialect;
import co.xenastudios.xvote.vote.Facts;
import co.xenastudios.xvote.vote.IncomingVote;
import co.xenastudios.xvote.vote.Periods;
import co.xenastudios.xvote.vote.Voter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The SQL behind the ledger. Every method runs inside a {@link Store} job's transaction,
 * on the database thread.
 */
public final class Ledger {

    /** Unmatched votes matched per player join; a name with more waits for the next join. */
    static final int MATCH_LIMIT = 500;

    private final Database database;

    public Ledger(Database database) {
        this.database = database;
    }

    private String sql(String statement) {
        return database.sql(statement);
    }

    // Row locks on MySQL, so two servers counting one player's votes take turns. SQLite
    // has one writer at a time anyway.
    private String forUpdate() {
        return database.dialect() == Dialect.MYSQL ? " FOR UPDATE" : "";
    }

    /** A name as votes are matched by it. */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a vote repeats one already stored: from the same site for the same player
     * (or name, if unmatched) within the cooldown, or the same list timestamp within a day
     * (a replayed v1 vote).
     */
    public boolean isRepeat(Connection c, UUID player, String nameKey, IncomingVote vote, long cooldownMs) throws SQLException {
        long since = vote.receivedAt() - Math.max(cooldownMs, 0);
        long day = vote.receivedAt() - 86_400_000L;
        String who = player != null ? "uuid = ?" : "name_key = ? AND uuid IS NULL";
        try (PreparedStatement query = c.prepareStatement(sql("SELECT COUNT(*) FROM {p}votes WHERE " + who
            + " AND LOWER(site) = LOWER(?) AND (at > ? OR (at > ? AND list_time = ? AND list_time <> ''))"))) {
            query.setString(1, player != null ? player.toString() : nameKey);
            query.setString(2, vote.site());
            query.setLong(3, cooldownMs > 0 ? since : Long.MAX_VALUE);
            query.setLong(4, day);
            query.setString(5, vote.timestamp());
            try (ResultSet row = query.executeQuery()) {
                return row.next() && row.getLong(1) > 0;
            }
        }
    }

    /** Stores a vote, claimed by {@code claim} if this server pays it now. Returns its id. */
    public long insert(Connection c, IncomingVote vote, UUID player, String name, String claim) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(sql("INSERT INTO {p}votes (uuid, name, name_key, site, address,"
            + " list_time, source, at, claim, paid_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"), Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, player == null ? null : player.toString());
            insert.setString(2, name);
            insert.setString(3, key(name));
            insert.setString(4, vote.site());
            insert.setString(5, vote.address());
            insert.setString(6, vote.timestamp());
            insert.setString(7, vote.source().name());
            insert.setLong(8, vote.receivedAt());
            insert.setString(9, claim);
            if (claim == null) {
                insert.setNull(10, java.sql.Types.BIGINT);
            } else {
                insert.setLong(10, vote.receivedAt());
            }
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The database didn't say which id the vote got.");
                }
                return keys.getLong(1);
            }
        }
    }

    /**
     * Counts a vote that has its player: works out its place in their count, stores that on
     * the vote, and moves their totals and streak on.
     */
    public Facts count(Connection c, long id, UUID player, String name, String site, long at, Periods periods) throws SQLException {
        int total = 0;
        int streak = 0;
        int best = 0;
        int lastDay = Integer.MIN_VALUE;
        long lastVote = 0;
        boolean known = false;
        try (PreparedStatement query = c.prepareStatement(sql(
            "SELECT total, streak, best_streak, last_day, last_vote FROM {p}voters WHERE uuid = ?" + forUpdate()))) {
            query.setString(1, player.toString());
            try (ResultSet row = query.executeQuery()) {
                if (row.next()) {
                    known = true;
                    total = row.getInt(1);
                    streak = row.getInt(2);
                    best = row.getInt(3);
                    lastDay = row.getInt(4);
                    lastVote = row.getLong(5);
                }
            }
        }
        int day = periods.day(at);
        long dayStart = periods.dayStart(at);
        boolean firstToday;
        try (PreparedStatement query = c.prepareStatement(sql(
            "SELECT COUNT(*) FROM {p}votes WHERE uuid = ? AND at >= ? AND at < ? AND number IS NOT NULL AND id <> ?"))) {
            query.setString(1, player.toString());
            query.setLong(2, dayStart);
            query.setLong(3, periods.dayEnd(at));
            query.setLong(4, id);
            try (ResultSet row = query.executeQuery()) {
                firstToday = !row.next() || row.getLong(1) == 0;
            }
        }
        int number = total + 1;
        int nextStreak = known ? Periods.nextStreak(lastDay, streak, day) : 1;
        int nextBest = Math.max(best, nextStreak);
        if (known) {
            try (PreparedStatement update = c.prepareStatement(sql("UPDATE {p}voters SET name = ?, total = ?, streak = ?,"
                + " best_streak = ?, last_day = ?, last_vote = ? WHERE uuid = ?"))) {
                update.setString(1, name);
                update.setInt(2, number);
                update.setInt(3, nextStreak);
                update.setInt(4, nextBest);
                update.setInt(5, Math.max(lastDay, day));
                update.setLong(6, Math.max(lastVote, at));
                update.setString(7, player.toString());
                update.executeUpdate();
            }
        } else {
            try (PreparedStatement insert = c.prepareStatement(sql("INSERT INTO {p}voters (uuid, name, total, streak, best_streak,"
                + " last_day, last_vote) VALUES (?, ?, ?, ?, ?, ?, ?)"))) {
                insert.setString(1, player.toString());
                insert.setString(2, name);
                insert.setInt(3, number);
                insert.setInt(4, nextStreak);
                insert.setInt(5, nextBest);
                insert.setInt(6, day);
                insert.setLong(7, at);
                insert.executeUpdate();
            }
        }
        try (PreparedStatement update = c.prepareStatement(sql(
            "UPDATE {p}votes SET uuid = ?, number = ?, streak = ?, first_today = ? WHERE id = ?"))) {
            update.setString(1, player.toString());
            update.setInt(2, number);
            update.setInt(3, nextStreak);
            update.setInt(4, firstToday ? 1 : 0);
            update.setLong(5, id);
            update.executeUpdate();
        }
        return new Facts(id, player, name, site, number, nextStreak, firstToday, at);
    }

    /**
     * Gives unmatched votes for these names to the player and counts them, oldest first.
     * Each is taken with {@code WHERE uuid IS NULL}, so a vote is matched once even if two
     * servers try at the same moment.
     */
    public int match(Connection c, UUID player, String name, Collection<String> nameKeys, Periods periods) throws SQLException {
        List<long[]> found = new ArrayList<>();
        List<String> sites = new ArrayList<>();
        String marks = String.join(", ", java.util.Collections.nCopies(nameKeys.size(), "?"));
        try (PreparedStatement query = c.prepareStatement(sql("SELECT id, at, site FROM {p}votes WHERE uuid IS NULL AND name_key IN ("
            + marks + ") ORDER BY id LIMIT " + MATCH_LIMIT))) {
            int i = 1;
            for (String key : nameKeys) {
                query.setString(i++, key);
            }
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    found.add(new long[] {row.getLong(1), row.getLong(2)});
                    sites.add(row.getString(3));
                }
            }
        }
        int matched = 0;
        for (int i = 0; i < found.size(); i++) {
            long id = found.get(i)[0];
            try (PreparedStatement take = c.prepareStatement(sql("UPDATE {p}votes SET uuid = ? WHERE id = ? AND uuid IS NULL"))) {
                take.setString(1, player.toString());
                take.setLong(2, id);
                if (take.executeUpdate() == 0) {
                    continue;
                }
            }
            count(c, id, player, name, sites.get(i), found.get(i)[1], periods);
            matched++;
        }
        return matched;
    }

    /** Takes every unpaid vote of the player's for this server to pay. */
    public List<Facts> claimUnpaid(Connection c, UUID player, String claim, long now) throws SQLException {
        try (PreparedStatement update = c.prepareStatement(sql(
            "UPDATE {p}votes SET claim = ?, paid_at = ? WHERE uuid = ? AND claim IS NULL AND number IS NOT NULL"))) {
            update.setString(1, claim);
            update.setLong(2, now);
            update.setString(3, player.toString());
            if (update.executeUpdate() == 0) {
                return List.of();
            }
        }
        List<Facts> claimed = new ArrayList<>();
        try (PreparedStatement query = c.prepareStatement(sql(
            "SELECT id, name, site, number, streak, first_today, at FROM {p}votes WHERE claim = ? ORDER BY id"))) {
            query.setString(1, claim);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    claimed.add(new Facts(row.getLong(1), player, row.getString(2), row.getString(3), row.getInt(4),
                        row.getInt(5), row.getInt(6) == 1, row.getLong(7)));
                }
            }
        }
        return claimed;
    }

    /** Takes one vote to pay, if no server has yet. */
    public boolean claim(Connection c, long id, String claim, long now) throws SQLException {
        try (PreparedStatement update = c.prepareStatement(sql(
            "UPDATE {p}votes SET claim = ?, paid_at = ? WHERE id = ? AND claim IS NULL AND number IS NOT NULL"))) {
            update.setString(1, claim);
            update.setLong(2, now);
            update.setLong(3, id);
            return update.executeUpdate() == 1;
        }
    }

    /** Gives a claimed vote back, unpaid, for a player who left before it could be paid. */
    public void unclaim(Connection c, long id, String claim) throws SQLException {
        try (PreparedStatement update = c.prepareStatement(sql(
            "UPDATE {p}votes SET claim = NULL, paid_at = NULL WHERE id = ? AND claim = ?"))) {
            update.setLong(1, id);
            update.setString(2, claim);
            update.executeUpdate();
        }
    }

    /** A vote as stored, for another server's news of it. */
    public record Row(long id, UUID player, String name, String site, Integer number, Integer streak, boolean firstToday,
                      long at, String claim) {}

    public Row read(Connection c, long id) throws SQLException {
        try (PreparedStatement query = c.prepareStatement(sql(
            "SELECT uuid, name, site, number, streak, first_today, at, claim FROM {p}votes WHERE id = ?"))) {
            query.setLong(1, id);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                String uuid = row.getString(1);
                int number = row.getInt(4);
                Integer numberOrNull = row.wasNull() ? null : number;
                int streak = row.getInt(5);
                Integer streakOrNull = row.wasNull() ? null : streak;
                return new Row(id, uuid == null ? null : UUID.fromString(uuid), row.getString(2), row.getString(3),
                    numberOrNull, streakOrNull, row.getInt(6) == 1, row.getLong(7), row.getString(8));
            }
        }
    }

    // ---- what's kept in memory ----

    /** One player's totals, as {@link #loadAll} reads everyone's. */
    public Voter voter(Connection c, UUID player, Periods periods, long now, long siteWindowMs) throws SQLException {
        Map<UUID, Voter> one = load(c, periods, now, siteWindowMs, player);
        return one.get(player);
    }

    /** Every player who has voted, with their totals. Startup only. */
    public Map<UUID, Voter> loadAll(Connection c, Periods periods, long now, long siteWindowMs) throws SQLException {
        return load(c, periods, now, siteWindowMs, null);
    }

    private Map<UUID, Voter> load(Connection c, Periods periods, long now, long siteWindowMs, UUID only) throws SQLException {
        String where = only == null ? "" : " WHERE uuid = ?";
        String and = only == null ? "" : " AND uuid = ?";
        Map<UUID, long[]> base = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        try (PreparedStatement query = c.prepareStatement(sql(
            "SELECT uuid, name, total, streak, best_streak, last_day, last_vote FROM {p}voters" + where))) {
            if (only != null) {
                query.setString(1, only.toString());
            }
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    UUID id = UUID.fromString(row.getString(1));
                    names.put(id, row.getString(2));
                    base.put(id, new long[] {row.getInt(3), row.getInt(4), row.getInt(5), row.getInt(6), row.getLong(7), 0, 0, 0});
                }
            }
        }
        long dayStart = periods.dayStart(now);
        long weekStart = periods.weekStart(now);
        long monthStart = periods.monthStart(now);
        long from = Math.min(weekStart, monthStart);
        try (PreparedStatement query = c.prepareStatement(sql("SELECT uuid,"
            + " SUM(CASE WHEN at >= ? THEN 1 ELSE 0 END), SUM(CASE WHEN at >= ? THEN 1 ELSE 0 END), SUM(CASE WHEN at >= ? THEN 1 ELSE 0 END)"
            + " FROM {p}votes WHERE at >= ? AND uuid IS NOT NULL AND number IS NOT NULL" + and + " GROUP BY uuid"))) {
            query.setLong(1, dayStart);
            query.setLong(2, weekStart);
            query.setLong(3, monthStart);
            query.setLong(4, from);
            if (only != null) {
                query.setString(5, only.toString());
            }
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    long[] values = base.get(UUID.fromString(row.getString(1)));
                    if (values != null) {
                        values[5] = row.getLong(2);
                        values[6] = row.getLong(3);
                        values[7] = row.getLong(4);
                    }
                }
            }
        }
        Map<UUID, Map<String, Long>> sites = new HashMap<>();
        try (PreparedStatement query = c.prepareStatement(sql(
            "SELECT uuid, site, MAX(at) FROM {p}votes WHERE at >= ? AND uuid IS NOT NULL" + and + " GROUP BY uuid, site"))) {
            query.setLong(1, now - siteWindowMs);
            if (only != null) {
                query.setString(2, only.toString());
            }
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    sites.computeIfAbsent(UUID.fromString(row.getString(1)), key -> new HashMap<>())
                        .merge(key(row.getString(2)), row.getLong(3), Math::max);
                }
            }
        }
        int dayKey = periods.dayKey(now);
        int weekKey = periods.weekKey(now);
        int monthKey = periods.monthKey(now);
        Map<UUID, Voter> voters = new HashMap<>(base.size() * 2);
        base.forEach((id, v) -> voters.put(id, new Voter(id, names.get(id), (int) v[0], (int) v[1], (int) v[2], (int) v[3], v[4],
            dayKey, (int) v[5], weekKey, (int) v[6], monthKey, (int) v[7], Map.copyOf(sites.getOrDefault(id, Map.of())))));
        return voters;
    }

    // ---- the vote party ----

    /** The party's count and when the current round started. */
    public record PartyState(long votes, long since) {}

    public PartyState party(Connection c, boolean lock) throws SQLException {
        long votes = 0;
        long since = 0;
        try (PreparedStatement query = c.prepareStatement(sql(
            "SELECT name, value FROM {p}state WHERE name IN ('party_votes', 'party_since')" + (lock ? forUpdate() : "")));
             ResultSet row = query.executeQuery()) {
            while (row.next()) {
                if (row.getString(1).equals("party_votes")) {
                    votes = row.getLong(2);
                } else {
                    since = row.getLong(2);
                }
            }
        }
        return new PartyState(votes, since);
    }

    public void party(Connection c, PartyState state) throws SQLException {
        try (PreparedStatement update = c.prepareStatement(sql("UPDATE {p}state SET value = ? WHERE name = ?"))) {
            update.setLong(1, state.votes());
            update.setString(2, "party_votes");
            update.addBatch();
            update.setLong(1, state.since());
            update.setString(2, "party_since");
            update.addBatch();
            update.executeBatch();
        }
    }

    // ---- items that didn't fit ----

    public void hold(Connection c, UUID player, List<byte[]> items, long now) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(sql("INSERT INTO {p}held (uuid, item, created) VALUES (?, ?, ?)"))) {
            for (byte[] item : items) {
                insert.setString(1, player.toString());
                insert.setBytes(2, item);
                insert.setLong(3, now);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /** Takes the player's held items out, so they're handed out once. */
    public List<byte[]> takeHeld(Connection c, UUID player) throws SQLException {
        List<byte[]> items = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement query = c.prepareStatement(sql("SELECT id, item FROM {p}held WHERE uuid = ? ORDER BY id" + forUpdate()))) {
            query.setString(1, player.toString());
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    ids.add(row.getLong(1));
                    items.add(row.getBytes(2));
                }
            }
        }
        try (PreparedStatement delete = c.prepareStatement(sql("DELETE FROM {p}held WHERE id = ?"))) {
            for (long id : ids) {
                delete.setLong(1, id);
                delete.addBatch();
            }
            delete.executeBatch();
        }
        return items;
    }

    public int countHeld(Connection c, UUID player) throws SQLException {
        try (PreparedStatement query = c.prepareStatement(sql("SELECT COUNT(*) FROM {p}held WHERE uuid = ?"))) {
            query.setString(1, player.toString());
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? row.getInt(1) : 0;
            }
        }
    }

    // ---- upkeep and status ----

    /** Deletes votes for names that never joined, older than {@code before}. */
    public int forgetUnknown(Connection c, long before) throws SQLException {
        try (PreparedStatement delete = c.prepareStatement(sql("DELETE FROM {p}votes WHERE uuid IS NULL AND at < ?"))) {
            delete.setLong(1, before);
            return delete.executeUpdate();
        }
    }

    /** Votes waiting to be paid, and votes waiting for their name to join. */
    public long[] waiting(Connection c) throws SQLException {
        long unpaid;
        long unmatched;
        try (Statement statement = c.createStatement();
             ResultSet row = statement.executeQuery(sql("SELECT COUNT(*) FROM {p}votes WHERE claim IS NULL AND uuid IS NOT NULL"))) {
            unpaid = row.next() ? row.getLong(1) : 0;
        }
        try (Statement statement = c.createStatement();
             ResultSet row = statement.executeQuery(sql("SELECT COUNT(*) FROM {p}votes WHERE uuid IS NULL"))) {
            unmatched = row.next() ? row.getLong(1) : 0;
        }
        return new long[] {unpaid, unmatched};
    }

    /** The last votes: name, site, source, when. */
    public record Recent(String name, String site, String source, long at, boolean paid, boolean matched) {}

    public List<Recent> recent(Connection c, int limit) throws SQLException {
        List<Recent> recent = new ArrayList<>();
        try (Statement statement = c.createStatement();
             ResultSet row = statement.executeQuery(sql("SELECT name, site, source, at, claim, uuid FROM {p}votes ORDER BY id DESC LIMIT " + limit))) {
            while (row.next()) {
                recent.add(new Recent(row.getString(1), row.getString(2), row.getString(3), row.getLong(4),
                    row.getString(5) != null, row.getString(6) != null));
            }
        }
        return recent;
    }
}
