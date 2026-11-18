package co.xenastudios.xbans.importer;

import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.api.Removal;
import co.xenastudios.xbans.ip.IpKeys;
import co.xenastudios.xbans.model.Entry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * LiteBans' database: its bans, mutes, warnings and kicks with who revoked them, and its
 * login history for names, IPs and alts. Read from MySQL or MariaDB with the settings in
 * {@code plugins/LiteBans/config.yml}.
 *
 * <p>LiteBans' default database is H2, which Paper has no driver for. Its own import moves
 * an H2 database to MySQL or MariaDB first (the docs' "Switching from LiteBans" page).
 */
final class LiteBansSource implements Source {

    @Override
    public String name() {
        return "litebans";
    }

    @Override
    public Haul read(Path server, Path plugins, String scope) throws ImportException {
        Path file = plugins.resolve("LiteBans/config.yml");
        if (!Files.exists(file)) {
            throw new ImportException("there's no plugins/LiteBans/config.yml.");
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
        String driver = config.getString("sql.driver", "H2").toLowerCase(Locale.ROOT);
        if (!driver.equals("mysql") && !driver.equals("mariadb")) {
            throw new ImportException("LiteBans uses " + config.getString("sql.driver", "H2") + ". xBans reads LiteBans from"
                + " MySQL or MariaDB: move it there with LiteBans' own import first, as the docs' Switching from LiteBans page says.");
        }
        String address = config.getString("sql.address", "localhost:3306");
        String database = config.getString("sql.database", "litebans");
        String prefix = config.getString("sql.table_prefix", "litebans_");
        if (!prefix.matches("[A-Za-z0-9_]*")) {
            throw new ImportException("LiteBans' table_prefix has characters xBans won't put in a query.");
        }
        Properties properties = new Properties();
        properties.setProperty("user", config.getString("sql.username", "root"));
        properties.setProperty("password", config.getString("sql.password", ""));
        String url = "jdbc:mysql://" + address + "/" + database + "?characterEncoding=utf8";
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new ImportException("this server has no MySQL driver; Paper and Folia include one.", e);
        }
        try (Connection connection = DriverManager.getConnection(url, properties)) {
            return read(connection, prefix, scope);
        } catch (SQLException e) {
            throw new ImportException("couldn't read LiteBans' database at " + address + "/" + database + ": " + e.getMessage(), e);
        }
    }

    /** Reads LiteBans' tables from an open connection. */
    static Haul read(Connection connection, String prefix, String scope) throws SQLException {
        Haul haul = new Haul();
        Map<UUID, String> names = history(connection, prefix, haul);
        long now = System.currentTimeMillis();
        table(connection, prefix, "bans", PunishmentType.BAN, names, scope, now, haul);
        table(connection, prefix, "mutes", PunishmentType.MUTE, names, scope, now, haul);
        table(connection, prefix, "warnings", PunishmentType.WARN, names, scope, now, haul);
        table(connection, prefix, "kicks", PunishmentType.KICK, names, scope, now, haul);
        return haul;
    }

    // Logins: each account's newest name, and the IPs it used.
    private static Map<UUID, String> history(Connection connection, String prefix, Haul haul) throws SQLException {
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, Long> newest = new HashMap<>();
        Map<String, long[]> uses = new HashMap<>();
        Set<UUID> withIp = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM " + prefix + "history")) {
            Set<String> columns = columns(rows);
            while (rows.next()) {
                UUID id = VanillaSource.uuid(rows.getString("uuid"));
                String name = rows.getString("name");
                if (id == null || name == null) {
                    continue;
                }
                long at = millis(rows, columns, "date");
                if (at >= newest.getOrDefault(id, Long.MIN_VALUE)) {
                    newest.put(id, at);
                    names.put(id, name);
                }
                String ip = columns.contains("ip") ? rows.getString("ip") : null;
                if (ip != null && IpKeys.punishable(ip)) {
                    withIp.add(id);
                    long[] span = uses.computeIfAbsent(id + "|" + ip, key -> new long[] {at, at});
                    span[0] = Math.min(span[0], at);
                    span[1] = Math.max(span[1], at);
                }
            }
        } catch (SQLException e) {
            if (!missingTable(e)) {
                throw e;
            }
        }
        for (Map.Entry<String, long[]> use : uses.entrySet()) {
            String[] parts = use.getKey().split("\\|", 2);
            UUID id = UUID.fromString(parts[0]);
            haul.seen(id, names.get(id), parts[1], use.getValue()[0], use.getValue()[1]);
        }
        for (Map.Entry<UUID, String> name : names.entrySet()) {
            if (!withIp.contains(name.getKey())) {
                long at = newest.get(name.getKey());
                haul.seen(name.getKey(), name.getValue(), null, at, at);
            }
        }
        return names;
    }

    private static void table(Connection connection, String prefix, String table, PunishmentType type, Map<UUID, String> names,
                              String scope, long now, Haul haul) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM " + prefix + table + " ORDER BY id")) {
            Set<String> columns = columns(rows);
            while (rows.next()) {
                if (flag(rows, columns, "ipban_wildcard")) {
                    // 192.168.1.*: xBans matches whole addresses only, so it would match nobody.
                    haul.wildcard();
                    continue;
                }
                haul.add(row(rows, columns, type, names, scope, now), "litebans:" + table + ":" + rows.getLong("id"));
            }
        } catch (SQLException e) {
            if (!missingTable(e)) {
                throw e;
            }
        }
    }

    static Entry row(ResultSet rows, Set<String> columns, PunishmentType type, Map<UUID, String> names, String defaultScope,
                     long now) throws SQLException {
        // defaultScope is unused: every LiteBans punishment names its scope.
        UUID target = VanillaSource.uuid(text(rows, columns, "uuid"));
        String ip = text(rows, columns, "ip");
        boolean ipBan = flag(rows, columns, "ipban") && ip != null && IpKeys.punishable(ip);
        String name = target != null ? names.getOrDefault(target, target.toString()) : IpKeys.label(ip);
        String reason = text(rows, columns, "reason");
        UUID staff = VanillaSource.uuid(text(rows, columns, "banned_by_uuid"));
        String staffName = text(rows, columns, "banned_by_name");
        long time = millis(rows, columns, "time");
        long until = columns.contains("until") ? rows.getLong("until") : 0;
        Instant created = Instant.ofEpochMilli(time > 0 ? time : now);
        Instant expires = type == PunishmentType.KICK || until <= 0 ? null : Instant.ofEpochMilli(until);
        // LiteBans' '*' is every server: global here too, whatever this server's default-scope.
        String scope = text(rows, columns, "server_scope");
        if (scope == null || scope.equals("*") || scope.equalsIgnoreCase("global")) {
            scope = Punishment.GLOBAL;
        }
        Removal removal = null;
        if (type.lasts()) {
            boolean active = !columns.contains("active") || flag(rows, columns, "active");
            String removedBy = text(rows, columns, "removed_by_name");
            boolean expired = "#expired".equals(removedBy);
            boolean wouldBeActive = expires == null || expires.toEpochMilli() > now;
            if (!active && !expired && (removedBy != null || wouldBeActive)) {
                long removed = millis(rows, columns, "removed_by_date");
                String why = text(rows, columns, "removed_by_reason");
                removal = new Removal(VanillaSource.uuid(text(rows, columns, "removed_by_uuid")),
                    removedBy == null ? "LiteBans" : removedBy, Instant.ofEpochMilli(removed > 0 ? removed : time),
                    why == null ? "" : why);
            }
        }
        Punishment punishment = new Punishment(0, type, target, name, ipBan, reason == null ? "" : reason, staff,
            staffName == null ? "Console" : staffName, created, expires, scope, flag(rows, columns, "silent"), null, removal);
        return new Entry(punishment, ipBan ? ip : null);
    }

    private static Set<String> columns(ResultSet rows) throws SQLException {
        ResultSetMetaData meta = rows.getMetaData();
        Set<String> columns = new HashSet<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(meta.getColumnLabel(i).toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    private static String text(ResultSet rows, Set<String> columns, String column) throws SQLException {
        if (!columns.contains(column)) {
            return null;
        }
        String value = rows.getString(column);
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean flag(ResultSet rows, Set<String> columns, String column) throws SQLException {
        return columns.contains(column) && rows.getBoolean(column);
    }

    // A time, written as epoch milliseconds or as a timestamp, depending on the column.
    private static long millis(ResultSet rows, Set<String> columns, String column) throws SQLException {
        if (!columns.contains(column)) {
            return 0;
        }
        Object value = rows.getObject(column);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.getTime();
        }
        if (value instanceof java.time.LocalDateTime local) {
            return Timestamp.valueOf(local).getTime();
        }
        return 0;
    }

    private static boolean missingTable(SQLException e) {
        // MySQL's "table doesn't exist" (1146), and its SQL state; SQLite's message, for tests.
        return e.getErrorCode() == 1146 || "42S02".equals(e.getSQLState())
            || e.getMessage() != null && e.getMessage().contains("no such table");
    }
}
