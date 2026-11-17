package co.xenastudios.xrewind.log;

import co.xenastudios.core.db.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.block.data.BlockData;

/**
 * Players, sources, worlds and block states as the numbers rows store. Owned by the
 * writer thread alone, so plain maps, no locks. A new name gets the next id at once;
 * its row is written in the same transaction as the first rows that use it, and kept
 * waiting until that commits, so a failed write is retried with the same ids.
 */
final class Dictionaries {

    private record Player(int id, String name) {}

    private final Map<UUID, Player> players = new HashMap<>();
    private final Map<String, Integer> sources = new HashMap<>();
    private final Map<String, Integer> worlds = new HashMap<>();
    private final Map<String, Integer> states = new HashMap<>();
    private final Map<Long, Integer> items = new HashMap<>();
    private int nextItem = 1;
    private int nextUser = 1;
    private int nextWorld = 1;
    private int nextState = 1;

    // Rows not yet committed.
    private final List<Object[]> newUsers = new ArrayList<>();
    private final List<Object[]> renamedUsers = new ArrayList<>();
    private final List<Object[]> newWorlds = new ArrayList<>();
    private final List<Object[]> newStates = new ArrayList<>();
    private final List<Object[]> newItems = new ArrayList<>();

    /** Reads every name the database already has. */
    void load(Connection connection, Database database) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(database.sql("SELECT id, uuid_most, uuid_least, name FROM {p}user"))) {
                while (rows.next()) {
                    int id = rows.getInt(1);
                    long most = rows.getLong(2);
                    boolean player = !rows.wasNull();
                    long least = rows.getLong(3);
                    if (player) {
                        players.put(new UUID(most, least), new Player(id, rows.getString(4)));
                    } else {
                        sources.put(rows.getString(4), id);
                    }
                    nextUser = Math.max(nextUser, id + 1);
                }
            }
            try (ResultSet rows = statement.executeQuery(database.sql("SELECT id, name FROM {p}world"))) {
                while (rows.next()) {
                    worlds.put(rows.getString(2), rows.getInt(1));
                    nextWorld = Math.max(nextWorld, rows.getInt(1) + 1);
                }
            }
            try (ResultSet rows = statement.executeQuery(database.sql("SELECT id, hash FROM {p}item"))) {
                while (rows.next()) {
                    items.put(rows.getLong(2), rows.getInt(1));
                    nextItem = Math.max(nextItem, rows.getInt(1) + 1);
                }
            }
            try (ResultSet rows = statement.executeQuery(database.sql("SELECT id, data FROM {p}state"))) {
                while (rows.next()) {
                    states.put(rows.getString(2), rows.getInt(1));
                    nextState = Math.max(nextState, rows.getInt(1) + 1);
                }
            }
        }
    }

    int user(Actor actor) {
        if (actor.uuid() == null) {
            Integer id = sources.get(actor.name());
            if (id == null) {
                id = nextUser++;
                sources.put(actor.name(), id);
                newUsers.add(new Object[] {id, null, null, actor.name()});
            }
            return id;
        }
        Player known = players.get(actor.uuid());
        if (known == null) {
            int id = nextUser++;
            players.put(actor.uuid(), new Player(id, actor.name()));
            newUsers.add(new Object[] {id, actor.uuid().getMostSignificantBits(), actor.uuid().getLeastSignificantBits(), actor.name()});
            return id;
        }
        if (!known.name().equals(actor.name())) {
            // Renamed since: lookups show the name they have now.
            players.put(actor.uuid(), new Player(known.id(), actor.name()));
            renamedUsers.add(new Object[] {actor.name(), known.id()});
        }
        return known.id();
    }

    int world(String name) {
        Integer id = worlds.get(name);
        if (id == null) {
            id = nextWorld++;
            worlds.put(name, id);
            newWorlds.add(new Object[] {id, name});
        }
        return id;
    }

    int state(BlockData data) {
        String text = data.getAsString();
        Integer id = states.get(text);
        if (id == null) {
            id = nextState++;
            states.put(text, id);
            newStates.add(new Object[] {id, text});
        }
        return id;
    }

    /** A state id for any name: an entity or item type, such as {@code minecraft:zombie}. */
    int name(String key) {
        Integer id = states.get(key);
        if (id == null) {
            id = nextState++;
            states.put(key, id);
            newStates.add(new Object[] {id, key});
        }
        return id;
    }

    /** Bytes stored once however often they come up: an item, or a block's contents. */
    int bytes(byte[] data) {
        long hash = hash(data);
        Integer id = items.get(hash);
        if (id == null) {
            id = nextItem++;
            items.put(hash, id);
            newItems.add(new Object[] {id, hash, data});
        }
        return id;
    }

    // 64-bit FNV-1a: collisions between different items are vanishingly unlikely.
    private static long hash(byte[] data) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : data) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** Writes the rows not yet committed, as part of the caller's transaction. */
    void write(Connection connection, Database database) throws SQLException {
        insert(connection, database.sql("INSERT INTO {p}user (id, uuid_most, uuid_least, name) VALUES (?, ?, ?, ?)"), newUsers);
        insert(connection, database.sql("UPDATE {p}user SET name = ? WHERE id = ?"), renamedUsers);
        insert(connection, database.sql("INSERT INTO {p}world (id, name) VALUES (?, ?)"), newWorlds);
        insert(connection, database.sql("INSERT INTO {p}state (id, data) VALUES (?, ?)"), newStates);
        insert(connection, database.sql("INSERT INTO {p}item (id, hash, data) VALUES (?, ?, ?)"), newItems);
    }

    /** The transaction committed: those rows are in. */
    void committed() {
        newUsers.clear();
        renamedUsers.clear();
        newWorlds.clear();
        newStates.clear();
        newItems.clear();
    }

    private static void insert(Connection connection, String sql, List<Object[]> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Object[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    statement.setObject(i + 1, row[i]);
                }
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
