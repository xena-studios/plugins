package co.xenastudios.xrewind.maintain;

import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.log.Actor;
import co.xenastudios.xrewind.log.Entry;
import co.xenastudios.xrewind.log.LogQueue;
import co.xenastudios.xrewind.storage.Clock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * Reads CoreProtect's history (SQLite or MySQL, wherever its config points) and hands it
 * to xRewind's writer, so the writer stays the only thing that writes. It waits whenever
 * the queue is long, so logging carries on alongside. Off the server's threads only.
 *
 * <p>What comes across: block changes (with their rolled-back flags), kills, container
 * changes, joins and quits, sign text, chat and commands. Container items come across as
 * their type only: CoreProtect's own format for enchantments and names isn't read.
 */
public final class CoreProtectImport {

    /** The queue is kept below this while importing, so live changes never wait behind it. */
    private static final int QUEUE_ROOM = 50_000;
    private static final String AIR = "minecraft:air";

    /** Where CoreProtect keeps its history. */
    public record Source(String description, String url, Properties properties, String prefix, boolean mysql) {

        Connection connect() throws SQLException {
            return DriverManager.getConnection(url, properties);
        }
    }

    private final Source source;
    private final LogQueue queue;
    private final Logger logger;
    private final Map<Integer, Actor> users = new HashMap<>();
    private final Map<Integer, String> worlds = new HashMap<>();
    private final Map<Integer, String> materials = new HashMap<>();
    private final Map<Integer, String> blockData = new HashMap<>();
    private final Map<Integer, String> entities = new HashMap<>();
    private final Map<String, byte[]> items = new HashMap<>();
    private long imported;
    private long total;
    private long lastReport = System.nanoTime();

    public CoreProtectImport(Source source, LogQueue queue, Logger logger) {
        this.source = source;
        this.queue = queue;
        this.logger = logger;
    }

    /**
     * Finds CoreProtect's database from its config, in {@code plugins/CoreProtect/}.
     *
     * @throws IllegalStateException with a reason for the console if there's none
     */
    public static Source find(Path pluginsFolder) {
        Path folder = pluginsFolder.resolve("CoreProtect");
        Path config = folder.resolve("config.yml");
        if (!Files.isRegularFile(config)) {
            throw new IllegalStateException("there's no plugins/CoreProtect/config.yml.");
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(config.toFile());
        String prefix = yaml.getString("table-prefix", "co_");
        if (!prefix.matches("[A-Za-z0-9_]*")) {
            throw new IllegalStateException("CoreProtect's table-prefix isn't a plain name.");
        }
        Properties properties = new Properties();
        if (yaml.getBoolean("use-mysql", false)) {
            String host = yaml.getString("mysql-host", "127.0.0.1");
            int port = yaml.getInt("mysql-port", 3306);
            String database = yaml.getString("mysql-database", "database");
            properties.setProperty("user", yaml.getString("mysql-username", "root"));
            properties.setProperty("password", yaml.getString("mysql-password", ""));
            return new Source("MySQL at " + host + ":" + port + "/" + database,
                "jdbc:mysql://" + host + ":" + port + "/" + database + "?characterEncoding=utf8", properties, prefix, true);
        }
        Path file = folder.resolve("database.db");
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("there's no plugins/CoreProtect/database.db.");
        }
        return new Source("plugins/CoreProtect/database.db", "jdbc:sqlite:" + file.toAbsolutePath(), properties, prefix, false);
    }

    /** Imports everything. @return how many changes */
    public long run() throws SQLException, InterruptedException {
        try (Connection connection = source.connect()) {
            loadNames(connection);
            for (String table : List.of("block", "container", "session", "sign", "chat", "command")) {
                total += count(connection, table);
            }
            logger.info("Importing " + String.format("%,d", total) + " changes from CoreProtect (" + source.description() + ").");
            blocks(connection);
            containers(connection);
            sessions(connection);
            signs(connection);
            texts(connection, "chat", Actions.CHAT);
            texts(connection, "command", Actions.COMMAND);
        }
        return imported;
    }

    private String table(String name) {
        return source.prefix() + name;
    }

    private void loadNames(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // MySQL tables number rows in rowid; SQLite's user table in id.
            try (ResultSet rows = statement.executeQuery("SELECT " + (source.mysql() ? "rowid" : "id") + ", user, uuid FROM " + table("user"))) {
                while (rows.next()) {
                    users.put(rows.getInt(1), new Actor(uuid(rows.getString(3)), rows.getString(2)));
                }
            }
            map(statement, "SELECT id, world FROM " + table("world"), worlds);
            map(statement, "SELECT id, material FROM " + table("material_map"), materials);
            map(statement, "SELECT id, data FROM " + table("blockdata_map"), blockData);
            map(statement, "SELECT id, entity FROM " + table("entity_map"), entities);
        }
    }

    private static void map(Statement statement, String sql, Map<Integer, String> into) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                into.put(rows.getInt(1), rows.getString(2));
            }
        }
    }

    private void blocks(Connection connection) throws SQLException, InterruptedException {
        try (Statement statement = stream(connection);
             ResultSet rows = statement.executeQuery("SELECT time, user, wid, x, y, z, type, blockdata, action, rolled_back FROM " + table("block"))) {
            while (rows.next()) {
                Actor actor = users.get(rows.getInt(2));
                String world = worlds.get(rows.getInt(3));
                int action = rows.getInt(9);
                if (actor == null || world == null) {
                    continue;
                }
                int time = Clock.fromUnixSeconds(rows.getLong(1));
                int x = rows.getInt(4);
                int y = rows.getInt(5);
                int z = rows.getInt(6);
                if (action == 3) {
                    // A kill: the type is an entity.
                    String entity = entities.get(rows.getInt(7));
                    if (entity != null) {
                        add(new Entry.Kill(time, actor, world, x, y, z, key(entity)));
                    }
                    continue;
                }
                String state = state(rows.getInt(7), rows.getBytes(8));
                if (state == null || action < 0 || action > 2) {
                    continue;
                }
                boolean undone = rows.getInt(10) != 0;
                byte ours = action == 0 ? Actions.BREAK : action == 1 ? Actions.PLACE : Actions.CLICK;
                String before = action == 1 ? AIR : state;
                String after = action == 0 ? AIR : state;
                add(new Entry.ImportedBlock(time, actor, world, x, y, z, ours, before, after, undone));
            }
        }
    }

    private void containers(Connection connection) throws SQLException, InterruptedException {
        try (Statement statement = stream(connection);
             ResultSet rows = statement.executeQuery("SELECT time, user, wid, x, y, z, type, amount, action, rolled_back FROM " + table("container"))) {
            while (rows.next()) {
                Actor actor = users.get(rows.getInt(2));
                String world = worlds.get(rows.getInt(3));
                String material = materials.get(rows.getInt(7));
                byte[] item = material == null ? null : item(material);
                if (actor == null || world == null || item == null) {
                    continue;
                }
                add(new Entry.ImportedContainer(Clock.fromUnixSeconds(rows.getLong(1)), actor, world, rows.getInt(4), rows.getInt(5),
                    rows.getInt(6), rows.getInt(9) == 1 ? Actions.ADD : Actions.REMOVE, key(material), item, rows.getInt(8),
                    rows.getInt(10) != 0));
            }
        }
    }

    private void sessions(Connection connection) throws SQLException, InterruptedException {
        try (Statement statement = stream(connection);
             ResultSet rows = statement.executeQuery("SELECT time, user, wid, x, y, z, action FROM " + table("session"))) {
            while (rows.next()) {
                Actor actor = users.get(rows.getInt(2));
                String world = worlds.get(rows.getInt(3));
                if (actor != null && world != null) {
                    add(new Entry.Session(Clock.fromUnixSeconds(rows.getLong(1)), actor, world, rows.getInt(4), rows.getInt(5),
                        rows.getInt(6), rows.getInt(7) == 1 ? Actions.JOIN : Actions.QUIT));
                }
            }
        }
    }

    private void signs(Connection connection) throws SQLException, InterruptedException {
        try (Statement statement = stream(connection);
             ResultSet rows = statement.executeQuery("SELECT time, user, wid, x, y, z, line_1, line_2, line_3, line_4, line_5,"
                 + " line_6, line_7, line_8 FROM " + table("sign"))) {
            while (rows.next()) {
                Actor actor = users.get(rows.getInt(2));
                String world = worlds.get(rows.getInt(3));
                List<String> lines = new ArrayList<>();
                for (int i = 7; i <= 14; i++) {
                    String line = rows.getString(i);
                    if (line != null && !line.isBlank()) {
                        lines.add(line.strip());
                    }
                }
                if (actor != null && world != null && !lines.isEmpty()) {
                    add(new Entry.Text(Clock.fromUnixSeconds(rows.getLong(1)), actor, world, rows.getInt(4), rows.getInt(5),
                        rows.getInt(6), Actions.SIGN, String.join(" | ", lines)));
                }
            }
        }
    }

    private void texts(Connection connection, String name, byte kind) throws SQLException, InterruptedException {
        try (Statement statement = stream(connection);
             ResultSet rows = statement.executeQuery("SELECT time, user, wid, x, y, z, message FROM " + table(name))) {
            while (rows.next()) {
                Actor actor = users.get(rows.getInt(2));
                String world = worlds.get(rows.getInt(3));
                if (actor != null && world != null && rows.getString(7) != null) {
                    add(new Entry.Text(Clock.fromUnixSeconds(rows.getLong(1)), actor, world, rows.getInt(4), rows.getInt(5),
                        rows.getInt(6), kind, rows.getString(7)));
                }
            }
        }
    }

    // A block state as Minecraft writes it, from CoreProtect's material and property ids.
    String state(int type, byte[] data) {
        String material = materials.get(type);
        if (material == null) {
            return null;
        }
        material = key(material);
        if (data == null || data.length == 0) {
            return material;
        }
        List<String> properties = new ArrayList<>();
        for (String id : new String(data, StandardCharsets.UTF_8).split(",")) {
            if (id.isBlank()) {
                continue;
            }
            try {
                String property = blockData.get(Integer.parseInt(id.strip()));
                if (property != null) {
                    properties.add(property);
                }
            } catch (NumberFormatException ignored) {
                // Not an id: skip it.
            }
        }
        return properties.isEmpty() ? material : material + "[" + String.join(",", properties) + "]";
    }

    // One of the item, as bytes, or null if it isn't an item this server knows.
    private byte[] item(String material) {
        return items.computeIfAbsent(material, key -> {
            Material type = Material.matchMaterial(key);
            return type == null || !type.isItem() || type.isAir() ? null : new ItemStack(type).serializeAsBytes();
        });
    }

    private static String key(String name) {
        return name.contains(":") ? name : "minecraft:" + name;
    }

    private static UUID uuid(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Results read as they come, not all at once: MySQL needs asking for that.
    private Statement stream(Connection connection) throws SQLException {
        Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        statement.setFetchSize(source.mysql() ? Integer.MIN_VALUE : 1_000);
        return statement;
    }

    private long count(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table(name))) {
            return rows.next() ? rows.getLong(1) : 0;
        } catch (SQLException e) {
            return 0; // An old CoreProtect without this table.
        }
    }

    private void add(Entry entry) throws InterruptedException {
        while (queue.size() > QUEUE_ROOM) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        queue.add(entry);
        imported++;
        if (System.nanoTime() - lastReport > TimeUnit.SECONDS.toNanos(10)) {
            lastReport = System.nanoTime();
            logger.info("Imported " + String.format("%,d", imported) + " of " + String.format("%,d", total) + " changes from CoreProtect...");
        }
    }
}
