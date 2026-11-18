package co.xenastudios.xteleport.storage;

import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.DatabaseException;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xteleport.api.Home;
import co.xenastudios.xteleport.api.Warp;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Homes, warps, the spawn and players' TPA setting, in the database. Everything runs on one
 * thread of its own, in the order it was asked for, so a home read after it was set is
 * always there, and the database is opened on that thread first, so a slow MySQL never
 * holds up the server starting.
 *
 * <p>Writes are rare (a player setting a home), so each opens its own connection. A write
 * that fails is logged and completes its future exceptionally; the caller undoes what it
 * changed in memory and tells the player.
 *
 * <p>Not final: the tests make one whose writes fail.
 */
public class Store {

    /** A player's homes and their TPA setting, as read when they join. */
    public record PlayerData(List<Home> homes, boolean tpaOff) {}

    /** What the whole server shares: warps and the spawn. */
    public record ServerData(List<Warp> warps, Warp spawn) {}

    /** How many rows an import added, and how many it skipped because they were already there. */
    public record Imported(int homes, int warps, boolean spawn, int skipped) {}

    static final String SPAWN = "spawn";

    private final Logger logger;
    private final ExecutorService thread;
    private final CompletableFuture<Database> opened = new CompletableFuture<>();

    protected Store(Logger logger) {
        this.logger = logger;
        this.thread = Executors.newSingleThreadExecutor(work -> {
            Thread t = new Thread(work, "xTeleport database");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts opening the database on the store's thread and returns at once. Everything
     * asked for before it's open waits its turn behind it.
     */
    public static Store open(StorageSettings settings, Path folder, Logger logger) {
        Store store = new Store(logger);
        store.start(settings, folder);
        return store;
    }

    /** Starts opening the database on the store's thread. Once, right after it's made. */
    protected final void start(StorageSettings settings, Path folder) {
        thread.execute(() -> {
            try {
                Database database = Database.open(settings, folder.resolve("xteleport.db"));
                database.migrate(Schema.STEPS);
                opened.complete(database);
            } catch (DatabaseException | RuntimeException e) {
                opened.completeExceptionally(e);
            }
        });
    }

    /** Completes when the database is open and up to date, or with why it couldn't be. */
    public CompletableFuture<Database> opened() {
        return opened;
    }

    /** Whether the database is open. Any thread. */
    public boolean ready() {
        return opened.isDone() && !opened.isCompletedExceptionally();
    }

    /** Where the data is, for the console, once open. */
    public String description() {
        return ready() ? opened.join().description() : "the database";
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Database database, Connection connection) throws SQLException;
    }

    // Runs on the store's thread once the database is open. A failure is logged and
    // completes the future exceptionally.
    private <T> CompletableFuture<T> submit(String what, Work<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            thread.execute(() -> {
                Database database;
                try {
                    database = opened.join();
                } catch (RuntimeException e) {
                    result.completeExceptionally(e);
                    return;
                }
                try (Connection connection = database.connect()) {
                    result.complete(work.run(database, connection));
                } catch (SQLException | RuntimeException e) {
                    logger.log(Level.WARNING, "Couldn't " + what + " in " + database.description() + ": " + e.getMessage());
                    result.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public CompletableFuture<PlayerData> loadPlayer(UUID player) {
        return submit("read a player's homes", (database, connection) -> {
            List<Home> homes = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(database.sql(
                "SELECT name, world, x, y, z, yaw, pitch FROM {p}homes WHERE owner = ? AND server = '' ORDER BY name_key"))) {
                query.setString(1, player.toString());
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        homes.add(new Home(player, rows.getString(1), rows.getString(2), rows.getDouble(3), rows.getDouble(4),
                            rows.getDouble(5), rows.getFloat(6), rows.getFloat(7)));
                    }
                }
            }
            boolean tpaOff = false;
            try (PreparedStatement query = connection.prepareStatement(database.sql("SELECT tpa_off FROM {p}players WHERE uuid = ?"))) {
                query.setString(1, player.toString());
                try (ResultSet rows = query.executeQuery()) {
                    tpaOff = rows.next() && rows.getInt(1) != 0;
                }
            }
            return new PlayerData(List.copyOf(homes), tpaOff);
        });
    }

    public CompletableFuture<ServerData> loadServer() {
        return submit("read the warps", (database, connection) -> {
            List<Warp> warps = readPlaces(database, connection, "SELECT name, world, x, y, z, yaw, pitch FROM {p}warps"
                + " WHERE server = '' ORDER BY name_key");
            List<Warp> spawns = readPlaces(database, connection, "SELECT name, world, x, y, z, yaw, pitch FROM {p}spawns"
                + " WHERE server = '' AND name_key = '" + SPAWN + "'");
            return new ServerData(List.copyOf(warps), spawns.isEmpty() ? null : spawns.getFirst());
        });
    }

    private static List<Warp> readPlaces(Database database, Connection connection, String sql) throws SQLException {
        List<Warp> places = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(database.sql(sql)); ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                places.add(new Warp(rows.getString(1), rows.getString(2), rows.getDouble(3), rows.getDouble(4), rows.getDouble(5),
                    rows.getFloat(6), rows.getFloat(7)));
            }
        }
        return places;
    }

    /** Sets a home, replacing one of the same name. */
    public CompletableFuture<Boolean> saveHome(Home home) {
        return submit("save a home", (database, connection) -> transaction(connection, () -> {
            try (PreparedStatement delete = connection.prepareStatement(database.sql(
                "DELETE FROM {p}homes WHERE owner = ? AND name_key = ?"))) {
                delete.setString(1, home.owner().toString());
                delete.setString(2, key(home.name()));
                delete.executeUpdate();
            }
            insertHome(database, connection, home, "INSERT");
            return true;
        }));
    }

    private static int insertHome(Database database, Connection connection, Home home, String insert) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(database.sql(insert
            + " INTO {p}homes (owner, name_key, name, server, world, x, y, z, yaw, pitch, created)"
            + " VALUES (?, ?, ?, '', ?, ?, ?, ?, ?, ?, ?)"))) {
            statement.setString(1, home.owner().toString());
            statement.setString(2, key(home.name()));
            statement.setString(3, home.name());
            place(statement, 4, home.world(), home.x(), home.y(), home.z(), home.yaw(), home.pitch());
            return statement.executeUpdate();
        }
    }

    // world, x, y, z, yaw, pitch, created, from the given index on.
    private static void place(PreparedStatement statement, int at, String world, double x, double y, double z, float yaw, float pitch)
        throws SQLException {
        statement.setString(at, world);
        statement.setDouble(at + 1, x);
        statement.setDouble(at + 2, y);
        statement.setDouble(at + 3, z);
        statement.setFloat(at + 4, yaw);
        statement.setFloat(at + 5, pitch);
        statement.setLong(at + 6, System.currentTimeMillis());
    }

    /** Deletes a home; true if there was one. */
    public CompletableFuture<Boolean> deleteHome(UUID owner, String name) {
        return submit("delete a home", (database, connection) -> {
            try (PreparedStatement delete = connection.prepareStatement(database.sql(
                "DELETE FROM {p}homes WHERE owner = ? AND name_key = ?"))) {
                delete.setString(1, owner.toString());
                delete.setString(2, key(name));
                return delete.executeUpdate() > 0;
            }
        });
    }

    public CompletableFuture<Boolean> setTpaOff(UUID player, boolean off) {
        return submit("save a player's TPA setting", (database, connection) -> transaction(connection, () -> {
            try (PreparedStatement delete = connection.prepareStatement(database.sql("DELETE FROM {p}players WHERE uuid = ?"))) {
                delete.setString(1, player.toString());
                delete.executeUpdate();
            }
            if (off) {
                try (PreparedStatement insert = connection.prepareStatement(database.sql(
                    "INSERT INTO {p}players (uuid, tpa_off) VALUES (?, 1)"))) {
                    insert.setString(1, player.toString());
                    insert.executeUpdate();
                }
            }
            return true;
        }));
    }

    public CompletableFuture<Boolean> saveWarp(Warp warp) {
        return savePlace("warps", warp.name(), warp);
    }

    public CompletableFuture<Boolean> deleteWarp(String name) {
        return submit("delete a warp", (database, connection) -> {
            try (PreparedStatement delete = connection.prepareStatement(database.sql(
                "DELETE FROM {p}warps WHERE name_key = ? AND server = ''"))) {
                delete.setString(1, key(name));
                return delete.executeUpdate() > 0;
            }
        });
    }

    /** Sets the spawn. Its name is always "spawn". */
    public CompletableFuture<Boolean> saveSpawn(Warp spawn) {
        return savePlace("spawns", SPAWN, spawn);
    }

    private CompletableFuture<Boolean> savePlace(String table, String name, Warp place) {
        return submit("save a place in " + table, (database, connection) -> transaction(connection, () -> {
            try (PreparedStatement delete = connection.prepareStatement(database.sql(
                "DELETE FROM {p}" + table + " WHERE name_key = ? AND server = ''"))) {
                delete.setString(1, key(name));
                delete.executeUpdate();
            }
            insertPlace(database, connection, table, name, place, "INSERT");
            return true;
        }));
    }

    private static int insertPlace(Database database, Connection connection, String table, String name, Warp place, String insert)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(database.sql(insert + " INTO {p}" + table
            + " (name_key, name, server, world, x, y, z, yaw, pitch, created) VALUES (?, ?, '', ?, ?, ?, ?, ?, ?, ?)"))) {
            statement.setString(1, key(name));
            statement.setString(2, name);
            place(statement, 3, place.world(), place.x(), place.y(), place.z(), place.yaw(), place.pitch());
            return statement.executeUpdate();
        }
    }

    /**
     * Adds imported homes, warps and a spawn in one transaction, keeping whatever is already
     * here: a home or warp with the same name, or a spawn already set, is skipped.
     */
    public CompletableFuture<Imported> importAll(List<Home> homes, List<Warp> warps, Warp spawn) {
        return submit("import homes and warps", (database, connection) -> transaction(connection, () -> {
            String insert = database.dialect().insertIgnore();
            int addedHomes = 0;
            int addedWarps = 0;
            for (Home home : homes) {
                addedHomes += insertHome(database, connection, home, insert);
            }
            for (Warp warp : warps) {
                addedWarps += insertPlace(database, connection, "warps", warp.name(), warp, insert);
            }
            boolean addedSpawn = spawn != null && insertPlace(database, connection, "spawns", SPAWN, spawn, insert) > 0;
            int skipped = homes.size() - addedHomes + warps.size() - addedWarps;
            return new Imported(addedHomes, addedWarps, addedSpawn, skipped);
        }));
    }

    @FunctionalInterface
    private interface Body<T> {
        T run() throws SQLException;
    }

    private static <T> T transaction(Connection connection, Body<T> body) throws SQLException {
        connection.setAutoCommit(false);
        try {
            T result = body.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Finishes what's queued, for up to the time given, then stops the thread. */
    public void close(long timeout, TimeUnit unit) {
        thread.shutdown();
        try {
            if (!thread.awaitTermination(timeout, unit)) {
                logger.warning("Stopped before every change was saved to the database.");
                thread.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            thread.shutdownNow();
        }
    }
}
