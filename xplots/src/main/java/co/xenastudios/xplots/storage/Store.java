package co.xenastudios.xplots.storage;

import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.DatabaseException;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xplots.model.Box;
import co.xenastudios.xplots.model.Field;
import co.xenastudios.xplots.model.Layout;
import co.xenastudios.xplots.model.Lease;
import co.xenastudios.xplots.model.Plot;
import co.xenastudios.xplots.model.Plots;
import co.xenastudios.xplots.model.Settings;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.block.BlockFace;

/**
 * Where plots survive a restart. Everything is read once at startup; after that the
 * plugin works from memory and hands changes here, which writes them on its own thread
 * in the order they were made. A click never waits on the disk.
 *
 * <p>Each change copies what it writes when it's made, on the main thread, so the writer
 * never reads the live model. A change that fails is retried, after a pause that grows to
 * 30 seconds, and the changes after it wait their turn, so the database never ends up with
 * a later change but not an earlier one. One the database refuses three times on a
 * working connection is dropped, with the reason in the console.
 */
public final class Store {

    /** The SQLite file, in the plugin's folder. */
    static final String FILE = "plots.db";

    /** One change, run in its own transaction. */
    @FunctionalInterface
    interface Op {
        void run(Connection connection) throws SQLException;
    }

    private final Database database;
    private final Logger logger;
    private final ExecutorService writer;
    // Writer thread only.
    private Connection connection;
    private volatile boolean stopping;

    private Store(Database database, Logger logger) {
        this.database = database;
        this.logger = logger;
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "xPlots database");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Opens the database and brings its tables up to date. Blocks: call once, at startup. */
    public static Store open(StorageSettings settings, Path folder, Logger logger) throws DatabaseException {
        Database database = Database.open(settings, folder.resolve(FILE));
        database.migrate(Schema.STEPS);
        return new Store(database, logger);
    }

    public String description() {
        return database.description();
    }

    /** Reads every field, plot and lease. Blocks: call once, at startup. */
    public Plots load() throws DatabaseException {
        Plots plots = new Plots();
        try (Connection c = database.connect()) {
            Map<Integer, Field> fields = new HashMap<>();
            try (PreparedStatement query = c.prepareStatement(database.sql(
                "SELECT id, name, world, min_x, min_y, min_z, max_x, max_y, max_z, size_x, size_z, path, settings, warp FROM {p}field"));
                 ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    Box area = new Box(row.getInt(4), row.getInt(5), row.getInt(6), row.getInt(7), row.getInt(8), row.getInt(9));
                    Layout layout = Layout.of(area, row.getInt(10), row.getInt(11), row.getInt(12));
                    if (layout == null) {
                        logger.warning("Field " + row.getString(2) + " is stored with a plot size that doesn't fit it; skipped.");
                        continue;
                    }
                    Field field = new Field(row.getInt(1), row.getString(2), row.getString(3), layout);
                    Settings.decode(row.getString(13)).forEach(field::override);
                    field.warp(row.getString(14));
                    fields.put(field.id(), field);
                }
            }
            Map<Long, Plot> byKey = new HashMap<>();
            try (PreparedStatement query = c.prepareStatement(database.sql(
                "SELECT field, number, min_x, min_y, min_z, max_x, max_y, max_z, sign_x, sign_y, sign_z, sign_facing FROM {p}plot"));
                 ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    Field field = fields.get(row.getInt(1));
                    if (field == null) {
                        continue;
                    }
                    BlockFace facing;
                    try {
                        facing = BlockFace.valueOf(row.getString(12));
                    } catch (IllegalArgumentException e) {
                        facing = BlockFace.SOUTH;
                    }
                    Plot plot = new Plot(field, row.getInt(2),
                        new Box(row.getInt(3), row.getInt(4), row.getInt(5), row.getInt(6), row.getInt(7), row.getInt(8)),
                        row.getInt(9), row.getInt(10), row.getInt(11), facing);
                    field.add(plot);
                    byKey.put(key(field.id(), plot.number()), plot);
                }
            }
            try (PreparedStatement query = c.prepareStatement(database.sql(
                "SELECT field, number, owner_most, owner_least, owner_name, expires, slots, warned FROM {p}lease"));
                 ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    Plot plot = byKey.get(key(row.getInt(1), row.getInt(2)));
                    if (plot != null) {
                        plot.lease(new Lease(new UUID(row.getLong(3), row.getLong(4)), row.getString(5),
                            row.getLong(6), row.getInt(7), row.getInt(8)));
                    }
                }
            }
            try (PreparedStatement query = c.prepareStatement(database.sql(
                "SELECT field, number, uuid_most, uuid_least, name FROM {p}member"));
                 ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    Plot plot = byKey.get(key(row.getInt(1), row.getInt(2)));
                    if (plot != null && plot.lease() != null) {
                        plot.lease().members().put(new UUID(row.getLong(3), row.getLong(4)), row.getString(5));
                    }
                }
            }
            fields.values().forEach(plots::index);
        } catch (SQLException e) {
            throw new DatabaseException("Couldn't read plots from " + database.description() + ": " + e.getMessage(), e);
        }
        return plots;
    }

    /** The highest field id stored, so new fields get the next one. */
    public static int maxFieldId(Plots plots) {
        int max = 0;
        for (Field field : plots.fields()) {
            max = Math.max(max, field.id());
        }
        return max;
    }

    // ---- changes: each copies what it needs now, and writes it later ----

    /** A new field and all its plots. */
    public void saveField(Field field) {
        FieldRow row = FieldRow.of(field);
        List<PlotRow> plots = field.plots().stream().map(PlotRow::of).toList();
        submit(c -> {
            row.insert(c, database);
            for (PlotRow plot : plots) {
                plot.insert(c, database);
            }
        });
    }

    /** A field's settings or warp. */
    public void updateField(Field field) {
        FieldRow row = FieldRow.of(field);
        submit(c -> {
            try (PreparedStatement update = c.prepareStatement(database.sql("UPDATE {p}field SET settings = ?, warp = ? WHERE id = ?"))) {
                update.setString(1, row.settings);
                update.setString(2, row.warp);
                update.setInt(3, row.id);
                update.executeUpdate();
            }
        });
    }

    public void deleteField(Field field) {
        int id = field.id();
        submit(c -> {
            for (String table : List.of("member", "lease", "snapshot", "plot")) {
                try (PreparedStatement delete = c.prepareStatement(database.sql("DELETE FROM {p}" + table + " WHERE field = ?"))) {
                    delete.setInt(1, id);
                    delete.executeUpdate();
                }
            }
            try (PreparedStatement delete = c.prepareStatement(database.sql("DELETE FROM {p}field WHERE id = ?"))) {
                delete.setInt(1, id);
                delete.executeUpdate();
            }
        });
    }

    /** A plot's row, new or changed (its sign moved). */
    public void savePlot(Plot plot) {
        PlotRow row = PlotRow.of(plot);
        submit(c -> {
            deletePlotRow(c, "plot", row.field, row.number);
            row.insert(c, database);
        });
    }

    public void deletePlot(Plot plot) {
        int field = plot.field().id();
        int number = plot.number();
        submit(c -> {
            for (String table : List.of("member", "lease", "snapshot", "plot")) {
                deletePlotRow(c, table, field, number);
            }
        });
    }

    /** A plot's lease and members as they are now, or its lack of one. */
    public void saveLease(Plot plot) {
        int field = plot.field().id();
        int number = plot.number();
        Lease lease = plot.lease();
        if (lease == null) {
            submit(c -> {
                deletePlotRow(c, "member", field, number);
                deletePlotRow(c, "lease", field, number);
            });
            return;
        }
        UUID owner = lease.owner();
        String ownerName = lease.ownerName();
        long expires = lease.expires();
        int slots = lease.slots();
        int warned = lease.warned();
        Map<UUID, String> members = Map.copyOf(lease.members());
        submit(c -> {
            deletePlotRow(c, "member", field, number);
            deletePlotRow(c, "lease", field, number);
            try (PreparedStatement insert = c.prepareStatement(database.sql(
                "INSERT INTO {p}lease (field, number, owner_most, owner_least, owner_name, expires, slots, warned) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"))) {
                insert.setInt(1, field);
                insert.setInt(2, number);
                insert.setLong(3, owner.getMostSignificantBits());
                insert.setLong(4, owner.getLeastSignificantBits());
                insert.setString(5, ownerName);
                insert.setLong(6, expires);
                insert.setInt(7, slots);
                insert.setInt(8, warned);
                insert.executeUpdate();
            }
            if (!members.isEmpty()) {
                try (PreparedStatement insert = c.prepareStatement(database.sql(
                    "INSERT INTO {p}member (field, number, uuid_most, uuid_least, name) VALUES (?, ?, ?, ?, ?)"))) {
                    for (Map.Entry<UUID, String> member : members.entrySet()) {
                        insert.setInt(1, field);
                        insert.setInt(2, number);
                        insert.setLong(3, member.getKey().getMostSignificantBits());
                        insert.setLong(4, member.getKey().getLeastSignificantBits());
                        insert.setString(5, member.getValue());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }
        });
    }

    public void saveSnapshot(Plot plot, byte[] data) {
        int field = plot.field().id();
        int number = plot.number();
        submit(c -> {
            deletePlotRow(c, "snapshot", field, number);
            try (PreparedStatement insert = c.prepareStatement(database.sql(
                "INSERT INTO {p}snapshot (field, number, data) VALUES (?, ?, ?)"))) {
                insert.setInt(1, field);
                insert.setInt(2, number);
                insert.setBytes(3, data);
                insert.executeUpdate();
            }
        });
    }

    /**
     * Reads a plot's snapshot on the writer thread, after every change made before this
     * call, and hands it (or null, if it has none) to {@code then} on that thread.
     */
    public void loadSnapshot(Plot plot, Consumer<byte[]> then) {
        int field = plot.field().id();
        int number = plot.number();
        submit(c -> {
            byte[] data = null;
            try (PreparedStatement query = c.prepareStatement(database.sql(
                "SELECT data FROM {p}snapshot WHERE field = ? AND number = ?"))) {
                query.setInt(1, field);
                query.setInt(2, number);
                try (ResultSet row = query.executeQuery()) {
                    if (row.next()) {
                        data = row.getBytes(1);
                    }
                }
            }
            then.accept(data);
        });
    }

    /** Writes what's waiting, for up to the timeout, then lets go of the database. */
    public void close(long timeout, TimeUnit unit) {
        stopping = true;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(timeout, unit)) {
                logger.severe("Gave up waiting for the database: the last changes to plots may be lost.");
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // The writer has stopped, so its connection is free to close here.
        closeConnection();
    }

    private void submit(Op op) {
        try {
            writer.execute(() -> run(op));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.warning("A change to plots came in after xPlots stopped, and wasn't saved.");
        }
    }

    // Writer thread: runs the change until it's written, or the database refuses it for good.
    private void run(Op op) {
        long pause = 1000;
        int refused = 0;
        boolean warned = false;
        while (true) {
            try {
                Connection c = connection();
                c.setAutoCommit(false);
                try {
                    op.run(c);
                    c.commit();
                } catch (SQLException | RuntimeException e) {
                    try {
                        c.rollback();
                    } catch (SQLException ignored) {
                        // The connection is likely gone; it's replaced below.
                    }
                    throw e;
                } finally {
                    try {
                        c.setAutoCommit(true);
                    } catch (SQLException ignored) {
                        // Same.
                    }
                }
                if (warned) {
                    logger.info("The database is back: plots are being saved again.");
                }
                return;
            } catch (SQLException | RuntimeException e) {
                boolean live = isLive();
                if (live && ++refused >= 3) {
                    logger.log(Level.SEVERE, "The database refused a change to plots three times, so it was skipped", e);
                    return;
                }
                if (!warned) {
                    logger.severe("Couldn't save a change to plots (" + e.getMessage() + "). Retrying; nothing is lost while xPlots runs.");
                    warned = true;
                }
                if (!live) {
                    closeConnection();
                }
                if (stopping) {
                    logger.severe("Stopping while the database is down: the last changes to plots weren't saved.");
                    return;
                }
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                pause = Math.min(pause * 2, 30_000);
            }
        }
    }

    private Connection connection() throws SQLException {
        if (connection == null) {
            connection = database.connect();
        }
        return connection;
    }

    private boolean isLive() {
        try {
            return connection != null && connection.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    private void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Already gone.
            }
            connection = null;
        }
    }

    private void deletePlotRow(Connection c, String table, int field, int number) throws SQLException {
        try (PreparedStatement delete = c.prepareStatement(database.sql("DELETE FROM {p}" + table + " WHERE field = ? AND number = ?"))) {
            delete.setInt(1, field);
            delete.setInt(2, number);
            delete.executeUpdate();
        }
    }

    private static long key(int field, int number) {
        return (long) field << 32 | (number & 0xFFFFFFFFL);
    }

    private record FieldRow(int id, String name, String world, Box area, int sizeX, int sizeZ, int path, String settings, String warp) {
        static FieldRow of(Field field) {
            Layout layout = field.layout();
            String settings = Settings.encode(field.overrides());
            return new FieldRow(field.id(), field.name(), field.world(), layout.area(), layout.sizeX(), layout.sizeZ(),
                layout.path(), settings.isEmpty() ? null : settings, field.warp());
        }

        void insert(Connection c, Database database) throws SQLException {
            try (PreparedStatement insert = c.prepareStatement(database.sql(
                "INSERT INTO {p}field (id, name, world, min_x, min_y, min_z, max_x, max_y, max_z, size_x, size_z, path, settings, warp)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"))) {
                insert.setInt(1, id);
                insert.setString(2, name);
                insert.setString(3, world);
                insert.setInt(4, area.minX());
                insert.setInt(5, area.minY());
                insert.setInt(6, area.minZ());
                insert.setInt(7, area.maxX());
                insert.setInt(8, area.maxY());
                insert.setInt(9, area.maxZ());
                insert.setInt(10, sizeX);
                insert.setInt(11, sizeZ);
                insert.setInt(12, path);
                insert.setString(13, settings);
                insert.setString(14, warp);
                insert.executeUpdate();
            }
        }
    }

    private record PlotRow(int field, int number, Box box, int signX, int signY, int signZ, String facing) {
        static PlotRow of(Plot plot) {
            return new PlotRow(plot.field().id(), plot.number(), plot.box(), plot.signX(), plot.signY(), plot.signZ(),
                plot.signFacing().name());
        }

        void insert(Connection c, Database database) throws SQLException {
            try (PreparedStatement insert = c.prepareStatement(database.sql(
                "INSERT INTO {p}plot (field, number, min_x, min_y, min_z, max_x, max_y, max_z, sign_x, sign_y, sign_z, sign_facing)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"))) {
                insert.setInt(1, field);
                insert.setInt(2, number);
                insert.setInt(3, box.minX());
                insert.setInt(4, box.minY());
                insert.setInt(5, box.minZ());
                insert.setInt(6, box.maxX());
                insert.setInt(7, box.maxY());
                insert.setInt(8, box.maxZ());
                insert.setInt(9, signX);
                insert.setInt(10, signY);
                insert.setInt(11, signZ);
                insert.setString(12, facing);
                insert.executeUpdate();
            }
        }
    }
}
