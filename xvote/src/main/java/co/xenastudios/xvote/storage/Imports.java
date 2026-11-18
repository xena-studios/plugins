package co.xenastudios.xvote.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Vote totals from the plugins owners switch from: VotingPlugin (its SQLite {@code Users}
 * table) and SuperbVote ({@code votes.json}, or its MySQL table). Only all-time totals and
 * last-vote times come across: neither keeps a ledger of single votes, so day, week and
 * month totals start from the switch. Read off the server's threads.
 */
public final class Imports {

    private Imports() {}

    /** One player's total from another plugin. */
    public record Imported(UUID id, String name, int total, long lastVote) {}

    /** What was read, and from where, for the reply. */
    public record Found(String source, String from, List<Imported> players) {}

    /**
     * Reads SuperbVote's totals from {@code plugins/SuperbVote}: its JSON file, or its
     * MySQL table when its config says {@code database: mysql}.
     */
    public static Found superbVote(Path plugins) throws IOException {
        Path folder = plugins.resolve("SuperbVote");
        Path configFile = folder.resolve("config.yml");
        YamlConfiguration config = new YamlConfiguration();
        if (Files.isRegularFile(configFile)) {
            try {
                config.load(configFile.toFile());
            } catch (InvalidConfigurationException e) {
                throw new IOException("SuperbVote's config.yml isn't valid YAML: " + e.getMessage(), e);
            }
        }
        if (config.getString("storage.database", "json").equalsIgnoreCase("mysql")) {
            return superbVoteMysql(config);
        }
        Path file = folder.resolve(config.getString("storage.json.file", "votes.json"));
        if (!Files.isRegularFile(file)) {
            throw new IOException("there's no plugins/SuperbVote/" + folder.relativize(file) + ".");
        }
        return new Found("SuperbVote", "plugins/SuperbVote/" + folder.relativize(file), superbVoteJson(Files.readString(file)));
    }

    /** SuperbVote's JSON: {@code {"version": 2, "records": {"<uuid>": {"lastKnownUsername", "votes", "lastVoted"}}}}. */
    static List<Imported> superbVoteJson(String json) throws IOException {
        List<Imported> players = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject records = root.has("records") ? root.getAsJsonObject("records") : root;
            for (Map.Entry<String, JsonElement> entry : records.entrySet()) {
                UUID id = uuid(entry.getKey());
                if (id == null) {
                    continue;
                }
                JsonElement value = entry.getValue();
                // SuperbVote 0.x kept only a number per player.
                if (value.isJsonPrimitive()) {
                    players.add(new Imported(id, "", value.getAsInt(), 0));
                    continue;
                }
                JsonObject record = value.getAsJsonObject();
                String name = record.has("lastKnownUsername") && !record.get("lastKnownUsername").isJsonNull()
                    ? record.get("lastKnownUsername").getAsString() : "";
                int votes = record.has("votes") ? record.get("votes").getAsInt() : 0;
                long last = record.has("lastVoted") ? record.get("lastVoted").getAsLong() : 0;
                if (votes > 0) {
                    players.add(new Imported(id, name, votes, last));
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("SuperbVote's votes file isn't the JSON it writes: " + e.getMessage(), e);
        }
        return players;
    }

    private static Found superbVoteMysql(YamlConfiguration config) throws IOException {
        String host = config.getString("storage.mysql.host", "127.0.0.1");
        String table = config.getString("storage.mysql.table", "votes");
        if (!table.matches("\\w+")) {
            throw new IOException("SuperbVote's storage.mysql.table isn't a plain table name.");
        }
        String url = "jdbc:mysql://" + (host.contains(":") ? host : host + ":3306") + "/" + config.getString("storage.mysql.database", "superbvote");
        Properties login = new Properties();
        login.setProperty("user", config.getString("storage.mysql.username", "root"));
        login.setProperty("password", config.getString("storage.mysql.password", ""));
        try (Connection connection = DriverManager.getConnection(url, login)) {
            List<Imported> players = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("SELECT uuid, last_name, votes, last_vote FROM " + table + " WHERE votes > 0")) {
                while (row.next()) {
                    UUID id = uuid(row.getString(1));
                    java.sql.Timestamp last = row.getTimestamp(4);
                    if (id != null) {
                        players.add(new Imported(id, row.getString(2) == null ? "" : row.getString(2), row.getInt(3),
                            last == null ? 0 : last.getTime()));
                    }
                }
            }
            return new Found("SuperbVote", "MySQL at " + host + ", table " + table, players);
        } catch (SQLException e) {
            throw new IOException("couldn't read SuperbVote's MySQL table: " + e.getMessage(), e);
        }
    }

    /** VotingPlugin's SQLite database: {@code Users.db}, table {@code Users}, in its folder or its Data folder. */
    public static Found votingPlugin(Path plugins) throws IOException {
        Path folder = plugins.resolve("VotingPlugin");
        for (Path file : List.of(folder.resolve("Users.db"), folder.resolve("Data").resolve("Users.db"))) {
            if (Files.isRegularFile(file)) {
                return new Found("VotingPlugin", "plugins/VotingPlugin/" + folder.relativize(file), votingPluginSqlite(file));
            }
        }
        throw new IOException("there's no plugins/VotingPlugin/Users.db. Only VotingPlugin's SQLite storage can be imported;"
            + " for MySQL, switch it to SQLite first, or ask us.");
    }

    static List<Imported> votingPluginSqlite(Path file) throws IOException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            String table = null;
            try (ResultSet tables = connection.getMetaData().getTables(null, null, "%", null)) {
                while (tables.next()) {
                    String name = tables.getString("TABLE_NAME");
                    if (name.equalsIgnoreCase("Users") || name.equalsIgnoreCase("VotingPlugin_Users")) {
                        table = name;
                    }
                }
            }
            if (table == null) {
                throw new IOException(file.getFileName() + " has no Users table.");
            }
            List<Imported> players = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("SELECT * FROM \"" + table + "\"")) {
                ResultSetMetaData meta = row.getMetaData();
                int uuid = 0;
                int name = 0;
                int total = 0;
                int last = 0;
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    switch (meta.getColumnName(i).toLowerCase(Locale.ROOT)) {
                        case "uuid" -> uuid = i;
                        case "playername" -> name = i;
                        case "alltimetotal" -> total = i;
                        case "lastvotetime", "time" -> last = i;
                        default -> {
                            // Not needed.
                        }
                    }
                }
                if (uuid == 0 || total == 0) {
                    throw new IOException("VotingPlugin's Users table has no uuid or AllTimeTotal column.");
                }
                while (row.next()) {
                    UUID id = uuid(row.getString(uuid));
                    int votes = number(row.getString(total));
                    if (id != null && votes > 0) {
                        players.add(new Imported(id, name == 0 || row.getString(name) == null ? "" : row.getString(name), votes,
                            last == 0 ? 0 : number(row.getString(last))));
                    }
                }
            }
            return players;
        } catch (SQLException e) {
            throw new IOException("couldn't read VotingPlugin's " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    // VotingPlugin keeps numbers as text.
    private static int number(String text) {
        if (text == null) {
            return 0;
        }
        try {
            return (int) Math.min(Integer.MAX_VALUE, Long.parseLong(text.strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
