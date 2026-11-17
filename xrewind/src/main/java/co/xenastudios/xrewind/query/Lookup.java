package co.xenastudios.xrewind.query;

import co.xenastudios.core.db.Database;
import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.storage.Clock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runs a {@link Query} against the database, newest first. Off the server's threads
 * only: it waits on the database.
 */
public final class Lookup {

    /** Where a radius is measured from: the sender, or the block being inspected. */
    public record Center(String world, int x, int y, int z) {}

    /** A page of rows, and how many match in all. */
    public record Result(List<Row> rows, long total) {}

    private Lookup() {}

    /**
     * One page of the changes matching the query.
     *
     * @param center where a numeric radius is measured from; null if there's none
     * @param page from 1
     */
    public static Result page(Database database, Query query, Center center, int page, int perPage) throws SQLException {
        int skip = (page - 1) * perPage;
        try (Connection connection = database.connect()) {
            List<Row> rows = new ArrayList<>();
            long total = 0;
            if (query.wantsBlocks()) {
                Sql sql = blocks(database, query, center);
                total += count(connection, sql);
                rows.addAll(rows(connection, sql, skip + perPage, Row.Table.BLOCK));
            }
            if (query.wantsSessions()) {
                Sql sql = sessions(database, query, center);
                total += count(connection, sql);
                rows.addAll(rows(connection, sql, skip + perPage, Row.Table.SESSION));
            }
            rows.sort(Comparator.comparingInt(Row::time).thenComparingLong(Row::id).reversed());
            List<Row> shown = rows.subList(Math.min(skip, rows.size()), Math.min(skip + perPage, rows.size()));
            return new Result(List.copyOf(shown), total);
        }
    }

    /**
     * Every block change matching the query, oldest first, for a rollback or restore.
     * By time, not id: history imported from CoreProtect has new ids but old times.
     */
    public static List<Row> allBlocks(Database database, Query query, Center center, boolean rolledBack) throws SQLException {
        Sql sql = blocks(database, query, center);
        sql.where.append(" AND b.rolled_back = ?");
        sql.params.add(rolledBack ? 1 : 0);
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql.select + sql.where + " ORDER BY b.time, b.id")) {
            sql.bind(statement);
            List<Row> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(row(result, Row.Table.BLOCK));
                }
            }
            return rows;
        }
    }

    // A SELECT's columns, its FROM and WHERE, and the values for the WHERE's ?s.
    private static final class Sql {
        final String select;
        final StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        final List<Object> params = new ArrayList<>();
        final String count;

        Sql(String select, String count) {
            this.select = select;
            this.count = count;
        }

        void bind(PreparedStatement statement) throws SQLException {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
        }
    }

    private static Sql blocks(Database database, Query query, Center center) {
        String from = database.sql(" FROM {p}block b JOIN {p}user u ON u.id = b.user JOIN {p}world w ON w.id = b.world"
            + " JOIN {p}state os ON os.id = b.old_state JOIN {p}state ns ON ns.id = b.new_state");
        Sql sql = new Sql("SELECT b.id, b.time, u.name, w.name, b.x, b.y, b.z, b.action, os.data, ns.data, b.rolled_back" + from,
            "SELECT COUNT(*)" + from);
        common(sql, query, center, "b");
        List<Integer> actions = new ArrayList<>();
        if (query.actions().isEmpty() || query.actions().contains(Query.Action.BREAK)) {
            actions.add((int) Actions.BREAK);
        }
        if (query.actions().isEmpty() || query.actions().contains(Query.Action.PLACE)) {
            actions.add((int) Actions.PLACE);
        }
        in(sql, "b.action", actions, false);
        if (!query.blocks().isEmpty()) {
            sql.where.append(" AND ");
            involved(sql, query.blocks());
        }
        if (!query.excludedBlocks().isEmpty()) {
            sql.where.append(" AND NOT ");
            involved(sql, query.excludedBlocks());
        }
        return sql;
    }

    private static Sql sessions(Database database, Query query, Center center) {
        String from = database.sql(" FROM {p}session b JOIN {p}user u ON u.id = b.user JOIN {p}world w ON w.id = b.world");
        Sql sql = new Sql("SELECT b.id, b.time, u.name, w.name, b.x, b.y, b.z, b.action, NULL, NULL, 0" + from,
            "SELECT COUNT(*)" + from);
        common(sql, query, center, "b");
        List<Integer> actions = new ArrayList<>();
        if (query.actions().contains(Query.Action.JOIN)) {
            actions.add((int) Actions.JOIN);
        }
        if (query.actions().contains(Query.Action.QUIT)) {
            actions.add((int) Actions.QUIT);
        }
        in(sql, "b.action", actions, false);
        return sql;
    }

    // Time, place and who: the same for every table.
    private static void common(Sql sql, Query query, Center center, String t) {
        if (query.seconds() > 0) {
            sql.where.append(" AND ").append(t).append(".time >= ?");
            sql.params.add((int) Math.max(Integer.MIN_VALUE, Clock.now() - query.seconds()));
        }
        String world = query.world() != null ? query.world() : center == null ? null : center.world();
        if (query.radius() != Query.GLOBAL && world != null) {
            sql.where.append(" AND w.name = ?");
            sql.params.add(world);
        }
        if (query.radius() >= 0 && center != null) {
            int r = query.radius();
            sql.where.append(" AND ").append(t).append(".x BETWEEN ? AND ? AND ").append(t).append(".z BETWEEN ? AND ? AND ")
                .append(t).append(".y BETWEEN ? AND ?");
            sql.params.add(center.x() - r);
            sql.params.add(center.x() + r);
            sql.params.add(center.z() - r);
            sql.params.add(center.z() + r);
            sql.params.add(center.y() - r);
            sql.params.add(center.y() + r);
        }
        in(sql, "LOWER(u.name)", query.users(), false);
        in(sql, "LOWER(u.name)", query.excludedUsers(), true);
    }

    private static void in(Sql sql, String column, List<?> values, boolean not) {
        if (values.isEmpty()) {
            return;
        }
        sql.where.append(" AND ").append(column).append(not ? " NOT IN (" : " IN (");
        for (int i = 0; i < values.size(); i++) {
            sql.where.append(i == 0 ? "?" : ", ?");
            sql.params.add(values.get(i));
        }
        sql.where.append(")");
    }

    // The block a change is about: what was broken, or what was placed.
    private static void involved(Sql sql, List<String> blocks) {
        sql.where.append("((b.action = ").append(Actions.BREAK).append(" AND ");
        anyOf(sql, "os.data", blocks);
        sql.where.append(") OR (b.action <> ").append(Actions.BREAK).append(" AND ");
        anyOf(sql, "ns.data", blocks);
        sql.where.append("))");
    }

    // minecraft:oak_log, with or without [properties]. LIKE's _ is escaped.
    private static void anyOf(Sql sql, String column, List<String> blocks) {
        sql.where.append("(");
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) {
                sql.where.append(" OR ");
            }
            sql.where.append(column).append(" = ? OR ").append(column).append(" LIKE ? ESCAPE '!'");
            String name = "minecraft:" + blocks.get(i);
            sql.params.add(name);
            sql.params.add(name.replace("!", "!!").replace("_", "!_").replace("%", "!%") + "[%");
        }
        sql.where.append(")");
    }

    private static long count(Connection connection, Sql sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql.count + sql.where)) {
            sql.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    private static List<Row> rows(Connection connection, Sql sql, int limit, Row.Table table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql.select + sql.where + " ORDER BY b.time DESC, b.id DESC LIMIT ?")) {
            sql.bind(statement);
            statement.setInt(sql.params.size() + 1, limit);
            List<Row> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(row(result, table));
                }
            }
            return rows;
        }
    }

    private static Row row(ResultSet result, Row.Table table) throws SQLException {
        return new Row(table, result.getLong(1), result.getInt(2), result.getString(3), result.getString(4),
            result.getInt(5), result.getInt(6), result.getInt(7), result.getByte(8), result.getString(9),
            result.getString(10), result.getInt(11) != 0);
    }
}
