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
 * Runs a {@link Query} against the database, newest first, across every table it
 * covers. Off the server's threads only: it waits on the database.
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
        List<Sql> tables = new ArrayList<>();
        if (query.wantsBlocks()) {
            tables.add(blocks(database, query, center, false));
        }
        if (query.wantsContainers()) {
            tables.add(containers(database, query, center, false));
        }
        if (query.wantsKills()) {
            tables.add(kills(database, query, center));
        }
        if (query.wantsText()) {
            tables.add(texts(database, query, center));
        }
        if (query.wantsSessions()) {
            tables.add(sessions(database, query, center));
        }
        try (Connection connection = database.connect()) {
            List<Row> rows = new ArrayList<>();
            long total = 0;
            for (Sql sql : tables) {
                total += count(connection, sql);
                rows.addAll(rows(connection, sql, " ORDER BY b.time DESC, b.id DESC LIMIT " + (skip + perPage)));
            }
            rows.sort(Comparator.comparingInt(Row::time).thenComparingLong(Row::id).reversed());
            List<Row> shown = rows.subList(Math.min(skip, rows.size()), Math.min(skip + perPage, rows.size()));
            return new Result(List.copyOf(shown), total);
        }
    }

    /**
     * Every block change matching the query, oldest first, with contents, for a rollback
     * or restore. By time, not id: history imported from CoreProtect has new ids but old
     * times.
     */
    public static List<Row> allBlocks(Database database, Query query, Center center, boolean rolledBack) throws SQLException {
        return all(database, blocks(database, query, center, true), rolledBack);
    }

    /** Every container change matching the query, oldest first, with its item, for a rollback or restore. */
    public static List<Row> allContainers(Database database, Query query, Center center, boolean rolledBack) throws SQLException {
        return all(database, containers(database, query, center, true), rolledBack);
    }

    private static List<Row> all(Database database, Sql sql, boolean rolledBack) throws SQLException {
        sql.where.append(" AND b.rolled_back = ?");
        sql.params.add(rolledBack ? 1 : 0);
        try (Connection connection = database.connect()) {
            return rows(connection, sql, " ORDER BY b.time, b.id");
        }
    }

    // A SELECT's columns, its FROM and WHERE, and the values for the WHERE's ?s.
    private static final class Sql {
        final Row.Table table;
        final String select;
        final String from;
        final StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        final List<Object> params = new ArrayList<>();

        Sql(Row.Table table, String select, String from) {
            this.table = table;
            this.select = select;
            this.from = from;
        }

        void bind(PreparedStatement statement) throws SQLException {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
        }
    }

    // Every SELECT has the same columns: id, time, user, world, x, y, z, action, thing, after,
    // rolled_back, amount, extra.
    private static Sql blocks(Database database, Query query, Center center, boolean withContents) {
        String from = database.sql(" FROM {p}block b JOIN {p}user u ON u.id = b.user JOIN {p}world w ON w.id = b.world"
            + " JOIN {p}state os ON os.id = b.old_state JOIN {p}state ns ON ns.id = b.new_state"
            + (withContents ? " LEFT JOIN {p}item it ON it.id = b.extra" : ""));
        Sql sql = new Sql(Row.Table.BLOCK, "SELECT b.id, b.time, u.name, w.name, b.x, b.y, b.z, b.action, os.data, ns.data,"
            + " b.rolled_back, 0, " + (withContents ? "it.data" : "NULL"), from);
        common(sql, query, center);
        List<Integer> actions = new ArrayList<>();
        if (query.wants(Query.Action.BREAK)) {
            actions.add((int) Actions.BREAK);
        }
        if (query.wants(Query.Action.PLACE)) {
            actions.add((int) Actions.PLACE);
        }
        if (query.wants(Query.Action.CLICK)) {
            actions.add((int) Actions.CLICK);
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

    private static Sql containers(Database database, Query query, Center center, boolean withItems) {
        String from = database.sql(" FROM {p}container b JOIN {p}user u ON u.id = b.user JOIN {p}world w ON w.id = b.world"
            + " JOIN {p}state m ON m.id = b.material" + (withItems ? " JOIN {p}item it ON it.id = b.item" : ""));
        Sql sql = new Sql(Row.Table.CONTAINER, "SELECT b.id, b.time, u.name, w.name, b.x, b.y, b.z, b.action, m.data, NULL,"
            + " b.rolled_back, b.amount, " + (withItems ? "it.data" : "NULL"), from);
        common(sql, query, center);
        List<Integer> actions = new ArrayList<>();
        if (query.wants(Query.Action.ADD)) {
            actions.add((int) Actions.ADD);
        }
        if (query.wants(Query.Action.REMOVE)) {
            actions.add((int) Actions.REMOVE);
        }
        in(sql, "b.action", actions, false);
        names(sql, "m.data", query);
        return sql;
    }

    private static Sql kills(Database database, Query query, Center center) {
        String from = database.sql(" FROM {p}entity b JOIN {p}user u ON u.id = b.user JOIN {p}world w ON w.id = b.world"
            + " JOIN {p}state t ON t.id = b.type");
        Sql sql = new Sql(Row.Table.KILL, "SELECT b.id, b.time, u.name, w.name, b.x, b.y, b.z, b.action, t.data, NULL,"
            + " b.rolled_back, 0, NULL", from);
        common(sql, query, center);
        return sql;
    }

    private static Sql texts(Database database, Query query, Center center) {
        String from = database.sql(" FROM {p}text b JOIN {p}user u ON u.id = b.user JOIN {p}world w ON w.id = b.world");
        Sql sql = new Sql(Row.Table.TEXT, "SELECT b.id, b.time, u.name, w.name, b.x, b.y, b.z, b.kind, b.message, NULL,"
            + " 0, 0, NULL", from);
        common(sql, query, center);
        List<Integer> kinds = new ArrayList<>();
        if (query.wants(Query.Action.SIGN)) {
            kinds.add((int) Actions.SIGN);
        }
        if (query.wants(Query.Action.CHAT)) {
            kinds.add((int) Actions.CHAT);
        }
        if (query.wants(Query.Action.COMMAND)) {
            kinds.add((int) Actions.COMMAND);
        }
        in(sql, "b.kind", kinds, false);
        return sql;
    }

    private static Sql sessions(Database database, Query query, Center center) {
        String from = database.sql(" FROM {p}session b JOIN {p}user u ON u.id = b.user JOIN {p}world w ON w.id = b.world");
        Sql sql = new Sql(Row.Table.SESSION, "SELECT b.id, b.time, u.name, w.name, b.x, b.y, b.z, b.action, NULL, NULL,"
            + " 0, 0, NULL", from);
        common(sql, query, center);
        List<Integer> actions = new ArrayList<>();
        if (query.wants(Query.Action.JOIN)) {
            actions.add((int) Actions.JOIN);
        }
        if (query.wants(Query.Action.QUIT)) {
            actions.add((int) Actions.QUIT);
        }
        in(sql, "b.action", actions, false);
        return sql;
    }

    // Time, place and who: the same for every table.
    private static void common(Sql sql, Query query, Center center) {
        if (query.seconds() > 0) {
            sql.where.append(" AND b.time >= ?");
            sql.params.add((int) Math.max(Integer.MIN_VALUE, Clock.now() - query.seconds()));
        }
        String world = query.world() != null ? query.world() : center == null ? null : center.world();
        if (query.radius() != Query.GLOBAL && world != null) {
            sql.where.append(" AND w.name = ?");
            sql.params.add(world);
        }
        if (query.radius() >= 0 && center != null) {
            int r = query.radius();
            sql.where.append(" AND b.x BETWEEN ? AND ? AND b.z BETWEEN ? AND ? AND b.y BETWEEN ? AND ?");
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

    // i: and e: on a table whose rows are about one named thing (an item).
    private static void names(Sql sql, String column, Query query) {
        if (!query.blocks().isEmpty()) {
            sql.where.append(" AND ");
            anyOf(sql, column, query.blocks());
        }
        if (!query.excludedBlocks().isEmpty()) {
            sql.where.append(" AND NOT ");
            anyOf(sql, column, query.excludedBlocks());
        }
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
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*)" + sql.from + sql.where)) {
            sql.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    private static List<Row> rows(Connection connection, Sql sql, String tail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql.select + sql.from + sql.where + tail)) {
            sql.bind(statement);
            List<Row> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new Row(sql.table, result.getLong(1), result.getInt(2), result.getString(3), result.getString(4),
                        result.getInt(5), result.getInt(6), result.getInt(7), result.getByte(8), result.getString(9),
                        result.getString(10), result.getInt(11) != 0, result.getInt(12), result.getBytes(13)));
                }
            }
            return rows;
        }
    }
}
