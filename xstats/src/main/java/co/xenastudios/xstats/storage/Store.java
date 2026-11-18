package co.xenastudios.xstats.storage;

import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.DatabaseException;
import co.xenastudios.core.db.Dialect;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xstats.data.Calendar;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * xStats' database: SQLite in the plugin's folder, or a MySQL or MariaDB shared by a
 * network. Every write comes from xStats' own thread, one save at a time; reads for the
 * website's profile pages may come from any thread and open their own connection.
 */
public final class Store {

    /** The SQLite file, in the plugin's folder. */
    public static final String FILE = "stats.db";

    private final Database database;
    private final int server;
    private final String serverName;
    private final Map<String, Integer> statIds = new ConcurrentHashMap<>();

    // ---- what a save writes ----

    /** A player whose row is new or changed. {@code dbId} is 0 for a player not stored yet. */
    public record PlayerRow(UUID uuid, int dbId, String name, long firstJoin, long lastSeen, boolean hidden, boolean excluded) {}

    /** This server's all-time value of one stat for one player. {@code player} is an index into the save's players. */
    public record TotalRow(int player, String stat, long value, long raw) {}

    /** How much a stat went up today. */
    public record DailyRow(int player, String stat, long delta) {}

    /** @param at when it's saved, epoch milliseconds: written to each total row as when it was updated */
    public record Save(List<PlayerRow> players, List<TotalRow> totals, List<DailyRow> daily, int day, long at) {}

    // ---- what loading reads ----

    public record LoadedPlayer(int dbId, UUID uuid, String name, long firstJoin, long lastSeen, boolean hidden, boolean excluded) {}

    /**
     * A value for a player and stat: all time, or one day's rise.
     *
     * @param value added up across servers (in network mode)
     * @param latest the value of the server that wrote it last, for stats that aren't counters (a balance)
     */
    public record Value(int player, String stat, int day, long value, long latest) {}

    public record Loaded(List<LoadedPlayer> players, List<Value> totals, List<Value> days) {}

    /** This server's row for one stat of one player. */
    public record Local(String stat, long value, long raw) {}

    private Store(Database database, int server, String serverName) {
        this.database = database;
        this.server = server;
        this.serverName = serverName;
    }

    /** Opens the database, brings its tables up to date and finds this server's id. Blocks: call off the server's threads. */
    public static Store open(StorageSettings settings, Path folder, String serverName) throws DatabaseException {
        Database database = Database.open(settings, folder.resolve(FILE));
        database.migrate(Schema.STEPS);
        try (Connection connection = database.connect()) {
            int server = dictionary(connection, database, "servers", serverName);
            return new Store(database, server, serverName);
        } catch (SQLException e) {
            throw new DatabaseException("Couldn't read " + database.description() + ": " + e.getMessage(), e);
        }
    }

    public Database database() {
        return database;
    }

    public String description() {
        return database.description();
    }

    public int server() {
        return server;
    }

    public String serverName() {
        return serverName;
    }

    public Connection connect() throws SQLException {
        return database.connect();
    }

    private String sql(String statement) {
        return database.sql(statement);
    }

    /** An id from a two-column dictionary table (servers, stats), adding the name if it's new. */
    private static int dictionary(Connection connection, Database database, String table, String name) throws SQLException {
        String select = database.sql("SELECT id FROM {p}" + table + " WHERE name = ?");
        try (PreparedStatement query = connection.prepareStatement(select)) {
            query.setString(1, name);
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) {
                    return result.getInt(1);
                }
            }
        }
        // Another server may add it at the same moment: ignore that, then read whichever won.
        try (PreparedStatement insert = connection.prepareStatement(
            database.sql(database.dialect().insertIgnore() + " INTO {p}" + table + " (name) VALUES (?)"))) {
            insert.setString(1, name);
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement(select)) {
            query.setString(1, name);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("couldn't add " + name + " to " + table);
                }
                return result.getInt(1);
            }
        }
    }

    /**
     * The database id of a stat, adding it the first time. A new id goes in {@code added},
     * and is only remembered once its transaction commits: one rolled back doesn't exist.
     */
    int statId(Connection connection, String stat, Map<String, Integer> added) throws SQLException {
        Integer known = statIds.get(stat);
        if (known == null) {
            known = added.get(stat);
        }
        if (known != null) {
            return known;
        }
        int id = dictionary(connection, database, "stats", stat);
        added.put(stat, id);
        return id;
    }

    /** Stat names by database id, for reading rows back. */
    private Map<Integer, String> statNames(Connection connection) throws SQLException {
        Map<Integer, String> names = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql("SELECT id, name FROM {p}stats"))) {
            while (result.next()) {
                names.put(result.getInt(1), result.getString(2));
                statIds.put(result.getString(2), result.getInt(1));
            }
        }
        return names;
    }

    // ---- loading ----

    /**
     * Every player, their all-time values and their daily rows from {@code fromDay} on.
     *
     * @param network add up every server's rows; otherwise only this server's
     */
    public Loaded load(boolean network, int fromDay) throws SQLException {
        try (Connection connection = connect()) {
            Map<Integer, String> stats = statNames(connection);
            List<LoadedPlayer> players = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql(
                     "SELECT id, uuid, name, first_join, last_seen, hidden, excluded FROM {p}players"))) {
                while (result.next()) {
                    players.add(new LoadedPlayer(result.getInt(1), UUID.fromString(result.getString(2)), result.getString(3),
                        result.getLong(4), result.getLong(5), result.getInt(6) != 0, result.getInt(7) != 0));
                }
            }
            // On a network: every server's rows added up, and the one written last (by the
            // primary key's first two columns, so a lookup per player and stat, not a scan).
            String totalsSql = network
                ? "SELECT t.player, t.stat, SUM(t.value), (SELECT l.value FROM {p}totals l"
                    + " WHERE l.player = t.player AND l.stat = t.stat ORDER BY l.updated DESC, l.server DESC LIMIT 1)"
                    + " FROM {p}totals t GROUP BY t.player, t.stat"
                : "SELECT player, stat, value, value FROM {p}totals WHERE server = " + server;
            List<Value> totals = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql(totalsSql))) {
                while (result.next()) {
                    String stat = stats.get(result.getInt(2));
                    if (stat != null) {
                        totals.add(new Value(result.getInt(1), stat, 0, result.getLong(3), result.getLong(4)));
                    }
                }
            }
            List<Value> days = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(sql(
                "SELECT player, stat, day, SUM(delta) FROM {p}daily WHERE day >= ?" + (network ? "" : " AND server = " + server)
                    + " GROUP BY player, stat, day"))) {
                query.setInt(1, fromDay);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        String stat = stats.get(result.getInt(2));
                        if (stat != null) {
                            long delta = result.getLong(4);
                            days.add(new Value(result.getInt(1), stat, result.getInt(3), delta, delta));
                        }
                    }
                }
            }
            return new Loaded(players, totals, days);
        }
    }

    /** One player's rows on this server, for working out what changed since. */
    public List<Local> local(int player) throws SQLException {
        try (Connection connection = connect()) {
            Map<Integer, String> stats = statNames(connection);
            List<Local> rows = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(sql(
                "SELECT stat, value, raw FROM {p}totals WHERE player = ? AND server = ?"))) {
                query.setInt(1, player);
                query.setInt(2, server);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        String stat = stats.get(result.getInt(1));
                        if (stat != null) {
                            rows.add(new Local(stat, result.getLong(2), result.getLong(3)));
                        }
                    }
                }
            }
            return rows;
        }
    }

    // ---- saving ----

    /**
     * Writes one save in one transaction, and tells the other servers of a network about it
     * in the same one.
     *
     * @return the database id of each of the save's players, in order (new players get theirs here)
     */
    public int[] save(Save save, ChangeFeed feed) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                Map<String, Integer> added = new HashMap<>();
                int[] ids = players(connection, save.players());
                totals(connection, save, ids, added);
                daily(connection, save, ids, added);
                if (!save.players().isEmpty()) {
                    feed.publish(connection, "saved", serverName);
                }
                connection.commit();
                statIds.putAll(added);
                return ids;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private int[] players(Connection connection, List<PlayerRow> rows) throws SQLException {
        int[] ids = new int[rows.size()];
        try (PreparedStatement update = connection.prepareStatement(sql(
            "UPDATE {p}players SET name = ?, name_lower = ?, first_join = ?, last_seen = ?, hidden = ?, excluded = ? WHERE id = ?"))) {
            for (int i = 0; i < rows.size(); i++) {
                PlayerRow row = rows.get(i);
                if (row.dbId() == 0) {
                    ids[i] = insertPlayer(connection, row);
                    continue;
                }
                ids[i] = row.dbId();
                fill(update, row);
                update.setInt(7, row.dbId());
                update.addBatch();
            }
            update.executeBatch();
        }
        return ids;
    }

    private static void fill(PreparedStatement statement, PlayerRow row) throws SQLException {
        statement.setString(1, row.name());
        statement.setString(2, row.name() == null ? null : row.name().toLowerCase(Locale.ROOT));
        statement.setLong(3, row.firstJoin());
        statement.setLong(4, row.lastSeen());
        statement.setInt(5, row.hidden() ? 1 : 0);
        statement.setInt(6, row.excluded() ? 1 : 0);
    }

    /** A player's id, adding them if no server has yet. */
    private int insertPlayer(Connection connection, PlayerRow row) throws SQLException {
        int existing = playerId(connection, row.uuid());
        if (existing > 0) {
            try (PreparedStatement update = connection.prepareStatement(sql(
                "UPDATE {p}players SET name = ?, name_lower = ?, first_join = ?, last_seen = ?, hidden = ?, excluded = ? WHERE id = ?"))) {
                fill(update, row);
                update.setInt(7, existing);
                update.executeUpdate();
            }
            return existing;
        }
        try (PreparedStatement insert = connection.prepareStatement(sql(database.dialect().insertIgnore()
            + " INTO {p}players (uuid, name, name_lower, first_join, last_seen, hidden, excluded) VALUES (?, ?, ?, ?, ?, ?, ?)"))) {
            insert.setString(1, row.uuid().toString());
            insert.setString(2, row.name());
            insert.setString(3, row.name() == null ? null : row.name().toLowerCase(Locale.ROOT));
            insert.setLong(4, row.firstJoin());
            insert.setLong(5, row.lastSeen());
            insert.setInt(6, row.hidden() ? 1 : 0);
            insert.setInt(7, row.excluded() ? 1 : 0);
            insert.executeUpdate();
        }
        int id = playerId(connection, row.uuid());
        if (id == 0) {
            throw new SQLException("couldn't add the player " + row.uuid());
        }
        return id;
    }

    private int playerId(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql("SELECT id FROM {p}players WHERE uuid = ?"))) {
            query.setString(1, uuid.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private void totals(Connection connection, Save save, int[] ids, Map<String, Integer> added) throws SQLException {
        if (save.totals().isEmpty()) {
            return;
        }
        String upsert = database.dialect() == Dialect.MYSQL
            ? "INSERT INTO {p}totals (player, stat, server, value, raw, updated) VALUES (?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE value = VALUES(value), raw = VALUES(raw), updated = VALUES(updated)"
            : "INSERT INTO {p}totals (player, stat, server, value, raw, updated) VALUES (?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (player, stat, server) DO UPDATE SET value = excluded.value, raw = excluded.raw,"
                + " updated = excluded.updated";
        try (PreparedStatement statement = connection.prepareStatement(sql(upsert))) {
            for (TotalRow row : save.totals()) {
                statement.setInt(1, ids[row.player()]);
                statement.setInt(2, statId(connection, row.stat(), added));
                statement.setInt(3, server);
                statement.setLong(4, row.value());
                statement.setLong(5, row.raw());
                statement.setLong(6, save.at());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void daily(Connection connection, Save save, int[] ids, Map<String, Integer> added) throws SQLException {
        if (save.daily().isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql(addTo("daily", "day")))) {
            for (DailyRow row : save.daily()) {
                statement.setInt(1, ids[row.player()]);
                statement.setInt(2, statId(connection, row.stat(), added));
                statement.setInt(3, server);
                statement.setInt(4, save.day());
                statement.setLong(5, row.delta());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Adds to a daily or monthly row, making it if it's new. */
    private String addTo(String table, String column) {
        String insert = "INSERT INTO {p}" + table + " (player, stat, server, " + column + ", delta) VALUES (?, ?, ?, ?, ?)";
        return database.dialect() == Dialect.MYSQL
            ? insert + " ON DUPLICATE KEY UPDATE delta = delta + VALUES(delta)"
            : insert + " ON CONFLICT (player, stat, server, " + column + ") DO UPDATE SET delta = delta + excluded.delta";
    }

    /** Sets whether a stored player is hidden from the website, right away, and tells the network. */
    public void hide(int player, UUID uuid, boolean hidden, ChangeFeed feed) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement(sql("UPDATE {p}players SET hidden = ? WHERE id = ?"))) {
                update.setInt(1, hidden ? 1 : 0);
                update.setInt(2, player);
                update.executeUpdate();
                feed.publish(connection, "player", uuid.toString());
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** Whether a player is hidden, as stored: for a change another server made. */
    public Boolean hidden(UUID uuid) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement query = connection.prepareStatement(sql("SELECT hidden FROM {p}players WHERE uuid = ?"))) {
            query.setString(1, uuid.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getInt(1) != 0 : null;
            }
        }
    }

    // ---- folding old days into months ----

    /**
     * Adds this server's daily rows from before {@code beforeDay} into monthly rows and
     * deletes them, a day per transaction. Each server folds only its own rows, so two
     * servers never add the same day twice.
     *
     * @return how many days were folded
     */
    public int fold(int beforeDay, int maxDays) throws SQLException {
        List<Integer> days = new ArrayList<>();
        try (Connection connection = connect()) {
            try (PreparedStatement query = connection.prepareStatement(sql(
                "SELECT DISTINCT day FROM {p}daily WHERE server = ? AND day < ? ORDER BY day"))) {
                query.setInt(1, server);
                query.setInt(2, beforeDay);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next() && days.size() < maxDays) {
                        days.add(result.getInt(1));
                    }
                }
            }
            for (int day : days) {
                foldDay(connection, day);
            }
        }
        return days.size();
    }

    private void foldDay(Connection connection, int day) throws SQLException {
        connection.setAutoCommit(false);
        try {
            List<long[]> rows = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(sql(
                "SELECT player, stat, delta FROM {p}daily WHERE server = ? AND day = ?"))) {
                query.setInt(1, server);
                query.setInt(2, day);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        rows.add(new long[] {result.getInt(1), result.getInt(2), result.getLong(3)});
                    }
                }
            }
            int month = Calendar.month(day);
            try (PreparedStatement add = connection.prepareStatement(sql(addTo("monthly", "month")))) {
                for (long[] row : rows) {
                    add.setInt(1, (int) row[0]);
                    add.setInt(2, (int) row[1]);
                    add.setInt(3, server);
                    add.setInt(4, month);
                    add.setLong(5, row[2]);
                    add.addBatch();
                }
                add.executeBatch();
            }
            try (PreparedStatement delete = connection.prepareStatement(sql("DELETE FROM {p}daily WHERE server = ? AND day = ?"))) {
                delete.setInt(1, server);
                delete.setInt(2, day);
                delete.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    // ---- for profile pages ----

    /** One day's rise of one stat, for a profile's history. */
    public record Day(int day, String stat, long delta) {}

    /** A profile's history and, in network mode, each server's numbers. */
    public record History(List<Day> days, List<ServerValue> servers) {}

    /**
     * A player's daily rows from {@code fromDay}, added up across servers in network mode,
     * and then each server's all-time values: one connection for both.
     */
    public History history(int player, int fromDay, boolean network) throws SQLException {
        try (Connection connection = connect()) {
            Map<Integer, String> stats = statNames(connection);
            return new History(days(connection, stats, player, fromDay, network),
                network ? perServer(connection, stats, player) : List.of());
        }
    }

    private List<Day> days(Connection connection, Map<Integer, String> stats, int player, int fromDay, boolean network)
        throws SQLException {
        List<Day> days = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(sql(
            "SELECT day, stat, SUM(delta) FROM {p}daily WHERE player = ? AND day >= ?"
                + (network ? "" : " AND server = " + server) + " GROUP BY day, stat ORDER BY day"))) {
            query.setInt(1, player);
            query.setInt(2, fromDay);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    String stat = stats.get(result.getInt(2));
                    if (stat != null) {
                        days.add(new Day(result.getInt(1), stat, result.getLong(3)));
                    }
                }
            }
        }
        return days;
    }

    /** One server's all-time value of one stat, for a profile in network mode. */
    public record ServerValue(String server, String stat, long value) {}

    public List<ServerValue> perServer(int player) throws SQLException {
        try (Connection connection = connect()) {
            return perServer(connection, statNames(connection), player);
        }
    }

    private List<ServerValue> perServer(Connection connection, Map<Integer, String> stats, int player) throws SQLException {
        List<ServerValue> values = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(sql(
            "SELECT s.name, t.stat, t.value FROM {p}totals t JOIN {p}servers s ON s.id = t.server WHERE t.player = ?"))) {
            query.setInt(1, player);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    String stat = stats.get(result.getInt(2));
                    if (stat != null) {
                        values.add(new ServerValue(result.getString(1), stat, result.getLong(3)));
                    }
                }
            }
        }
        return values;
    }

    // ---- the import ----

    /** One player from a stats file: values by stat id. */
    public record Imported(UUID uuid, String name, long lastSeen, Map<String, Long> values, Map<String, Boolean> counters) {}

    /**
     * Adds imported players and their values, never over a row already there: a player
     * xStats already counts keeps their numbers.
     *
     * @return how many values were added
     */
    public int importRows(List<Imported> players) throws SQLException {
        try (Connection connection = connect()) {
            // Counted before and after: MySQL's batched inserts don't say how many each one added.
            long before = totalRows(connection);
            connection.setAutoCommit(false);
            Map<String, Integer> added = new HashMap<>();
            try (PreparedStatement insert = connection.prepareStatement(sql(database.dialect().insertIgnore()
                + " INTO {p}totals (player, stat, server, value, raw, updated) VALUES (?, ?, ?, ?, ?, ?)"))) {
                for (Imported player : players) {
                    int id = importPlayer(connection, player);
                    for (Map.Entry<String, Long> value : player.values().entrySet()) {
                        insert.setInt(1, id);
                        insert.setInt(2, statId(connection, value.getKey(), added));
                        insert.setInt(3, server);
                        insert.setLong(4, value.getValue());
                        insert.setLong(5, Boolean.TRUE.equals(player.counters().get(value.getKey())) ? value.getValue() : -1);
                        // Older than anything counted since: when they were last seen in the files.
                        insert.setLong(6, player.lastSeen());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
                statIds.putAll(added);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
            connection.setAutoCommit(true);
            return (int) (totalRows(connection) - before);
        }
    }

    /** An imported player's id: a player already stored is left exactly as they are. */
    private int importPlayer(Connection connection, Imported player) throws SQLException {
        int existing = playerId(connection, player.uuid());
        if (existing > 0) {
            return existing;
        }
        return insertPlayer(connection, new PlayerRow(player.uuid(), 0, player.name(), 0, player.lastSeen(), false, false));
    }

    private long totalRows(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql("SELECT COUNT(*) FROM {p}totals WHERE server = ?"))) {
            query.setInt(1, server);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    /** A flag kept in the meta table, such as whether the import has run on this server. */
    public boolean flag(String name) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement query = connection.prepareStatement(sql("SELECT value FROM {p}meta WHERE name = ?"))) {
            query.setString(1, name + ":" + server);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    public void setFlag(String name) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement insert = connection.prepareStatement(sql(database.dialect().insertIgnore()
                 + " INTO {p}meta (name, value) VALUES (?, '1')"))) {
            insert.setString(1, name + ":" + server);
            insert.executeUpdate();
        }
    }

    // ---- in-world boards ----

    /** A board placed in a world. */
    public record BoardRow(int id, String world, double x, double y, double z, float yaw, String stat, String period) {}

    public List<BoardRow> boards() throws SQLException {
        List<BoardRow> boards = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement query = connection.prepareStatement(sql(
                 "SELECT id, world, x, y, z, yaw, stat, period FROM {p}boards WHERE server = ? ORDER BY id"))) {
            query.setInt(1, server);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    boards.add(new BoardRow(result.getInt(1), result.getString(2), result.getDouble(3), result.getDouble(4),
                        result.getDouble(5), result.getFloat(6), result.getString(7), result.getString(8)));
                }
            }
        }
        return boards;
    }

    public int addBoard(BoardRow board) throws SQLException {
        try (Connection connection = connect()) {
            try (PreparedStatement insert = connection.prepareStatement(sql(
                "INSERT INTO {p}boards (server, world, x, y, z, yaw, stat, period) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"),
                Statement.RETURN_GENERATED_KEYS)) {
                insert.setInt(1, server);
                insert.setString(2, board.world());
                insert.setDouble(3, board.x());
                insert.setDouble(4, board.y());
                insert.setDouble(5, board.z());
                insert.setFloat(6, board.yaw());
                insert.setString(7, board.stat());
                insert.setString(8, board.period());
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getInt(1);
                    }
                }
            }
            throw new SQLException("the new board got no id");
        }
    }

    public boolean removeBoard(int id) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement delete = connection.prepareStatement(sql("DELETE FROM {p}boards WHERE id = ? AND server = ?"))) {
            delete.setInt(1, id);
            delete.setInt(2, server);
            return delete.executeUpdate() > 0;
        }
    }

    /** Rows in the daily and monthly tables, for /xstats status. */
    public long[] rowCounts() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            long[] counts = new long[3];
            String[] tables = {"totals", "daily", "monthly"};
            for (int i = 0; i < tables.length; i++) {
                try (ResultSet result = statement.executeQuery(sql("SELECT COUNT(*) FROM {p}" + tables[i]))) {
                    counts[i] = result.next() ? result.getLong(1) : 0;
                }
            }
            return counts;
        }
    }
}
