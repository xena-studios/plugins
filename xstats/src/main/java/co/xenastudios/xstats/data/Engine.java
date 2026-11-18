package co.xenastudios.xstats.data;

import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.xstats.api.Period;
import co.xenastudios.xstats.stat.Catalog;
import co.xenastudios.xstats.stat.StatDef;
import co.xenastudios.xstats.storage.Store;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every player's stats in memory, and the save that keeps them and the database in step.
 * Everything here that changes runs on xStats' own thread ({@link Worker}); other threads
 * read players and leaderboards straight from memory.
 *
 * <p>Readings, joins and other plugins' changes queue up as {@link Event}s and are applied
 * in order at the next save. A save then writes what changed in one transaction: every
 * changed all-time row, and one daily row per stat that rose (for stats with periods),
 * then works out leaderboards again.
 *
 * <p>Minecraft's own stats are stored as running totals alongside the last reading of the
 * counter. A save lost to a crash is made up at the next reading, since the counter kept
 * counting; a counter that went backwards (its stats file deleted) is taken to have
 * restarted from 0, so the all-time number keeps growing.
 */
public final class Engine {

    /** What the engine needs from the config, swapped whole on reload. */
    public record Settings(Calendar calendar, boolean afkAware, long saveEveryMillis, int keepDailyDays, boolean network) {}

    /** How the last save went, for /xstats status. */
    public record LastSave(long at, int players, long millis, String problem) {}

    private final Catalog catalog;
    private final Logger logger;
    private final Map<UUID, PlayerRecord> players = new ConcurrentHashMap<>();
    private final Map<String, PlayerRecord> byName = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
    private final Leaderboards leaderboards = new Leaderboards();
    private final BitSet changedSinceBoards = new BitSet();

    private volatile Settings settings;
    private volatile Store store;
    private volatile ChangeFeed feed = ChangeFeed.off();
    private volatile boolean ready;
    private volatile boolean networkDirty;
    private volatile Set<UUID> banned = Set.of();
    private volatile String[] sortedNames = new String[0];
    private volatile PlayerRecord[] sortedPlayers = new PlayerRecord[0];
    private volatile LastSave lastSave = new LastSave(0, 0, 0, null);
    private boolean namesChanged = true;
    private boolean failing;
    private int lastFold = -1;
    private BiConsumer<Board, PlayerRecord> onNewLeader = (board, previous) -> { };

    public Engine(Catalog catalog, Settings settings, Logger logger) {
        this.catalog = catalog;
        this.settings = settings;
        this.logger = logger;
    }

    // ---- reading, from any thread ----

    public boolean ready() {
        return ready;
    }

    public Settings settings() {
        return settings;
    }

    public void settings(Settings settings) {
        this.settings = settings;
    }

    public Catalog catalog() {
        return catalog;
    }

    public Leaderboards leaderboards() {
        return leaderboards;
    }

    public Store store() {
        return store;
    }

    public ChangeFeed feed() {
        return feed;
    }

    public LastSave lastSave() {
        return lastSave;
    }

    public int today() {
        return settings.calendar().today(System.currentTimeMillis());
    }

    public PlayerRecord player(UUID id) {
        return players.get(id);
    }

    public PlayerRecord byName(String name) {
        return byName.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<PlayerRecord> players() {
        return players.values();
    }

    public int playerCount() {
        return players.size();
    }

    public long value(PlayerRecord player, StatDef stat, Period period) {
        Settings current = settings;
        return player.value(stat.index(), period, current.calendar(), current.calendar().today(System.currentTimeMillis()));
    }

    /** A player's place on a board, or 0. Players left out of boards have none. */
    public int rank(PlayerRecord player, StatDef stat, Period period) {
        Board board = leaderboards.get(stat.id(), period);
        if (board == null || player.excluded() || banned.contains(player.id)) {
            return 0;
        }
        return board.rankOf(value(player, stat, period));
    }

    /** Names starting with {@code prefix}, ignoring case, at most {@code limit}, skipping players {@code skip} says to. */
    public List<PlayerRecord> search(String prefix, int limit, java.util.function.Predicate<PlayerRecord> skip) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        String[] names = sortedNames;
        PlayerRecord[] records = sortedPlayers;
        int at = Arrays.binarySearch(names, lower);
        if (at < 0) {
            at = -at - 1;
        }
        List<PlayerRecord> found = new ArrayList<>();
        for (int i = at; i < names.length && found.size() < limit && names[i].startsWith(lower); i++) {
            if (!skip.test(records[i])) {
                found.add(records[i]);
            }
        }
        return found;
    }

    public boolean banned(UUID id) {
        return banned.contains(id);
    }

    // ---- queueing, from any thread ----

    public void queue(Event event) {
        events.add(event);
    }

    public void bannedPlayers(Set<UUID> banned) {
        this.banned = Set.copyOf(banned);
    }

    /** Another server of the network saved: read the network's numbers at the next save. */
    public void networkChanged() {
        networkDirty = true;
    }

    public void onNewLeader(BiConsumer<Board, PlayerRecord> listener) {
        this.onNewLeader = listener;
    }

    // ---- xStats' thread ----

    /** Uses this database from now on and loads every player from it. On xStats' thread. */
    public void open(Store store, ChangeFeed feed) throws SQLException {
        this.store = store;
        this.feed = feed;
        reload(true);
        ready = true;
    }

    /** Reads every player's all-time and period numbers again, keeping what's only in memory. */
    void reload(boolean everything) throws SQLException {
        Settings current = settings;
        int today = current.calendar().today(System.currentTimeMillis());
        Store.Loaded loaded = store.load(current.network(), current.calendar().earliestNeeded(today));
        Catalog.Snapshot stats = catalog.snapshot();
        int width = stats.width();
        Map<Integer, PlayerRecord> byDbId = new HashMap<>();
        for (Store.LoadedPlayer row : loaded.players()) {
            PlayerRecord player = players.computeIfAbsent(row.uuid(), id -> new PlayerRecord(id, row.name()));
            player.dbId = row.dbId();
            if (player.name == null || !player.online) {
                rename(player, row.name());
            }
            player.hidden = row.hidden();
            player.excluded = row.excluded();
            player.firstJoin = player.firstJoin == 0 ? row.firstJoin() : Math.min(player.firstJoin, Math.max(1, row.firstJoin()));
            player.lastSeen = Math.max(player.lastSeen, row.lastSeen());
            byDbId.put(row.dbId(), player);
        }
        Map<PlayerRecord, long[]> totals = new HashMap<>();
        for (Store.Value value : loaded.totals()) {
            PlayerRecord player = byDbId.get(value.player());
            StatDef stat = stats.byId().get(value.stat());
            if (player != null && stat != null) {
                totals.computeIfAbsent(player, key -> new long[width])[stat.index()] = value.value();
            }
        }
        for (Map.Entry<PlayerRecord, long[]> entry : totals.entrySet()) {
            PlayerRecord player = entry.getKey();
            long[] values = entry.getValue();
            // Rises not saved yet are only in memory; keep them on top of what was read.
            for (int i = 0; i < values.length && i < player.pendingDaily.length; i++) {
                values[i] += player.pendingDaily[i];
            }
            player.values = values;
        }
        Calendar calendar = current.calendar();
        Map<PlayerRecord, long[][]> periods = new HashMap<>();
        int week = calendar.week(today);
        int month = Calendar.month(today);
        for (Store.Value value : loaded.days()) {
            PlayerRecord player = byDbId.get(value.player());
            StatDef stat = stats.byId().get(value.stat());
            if (player == null || stat == null) {
                continue;
            }
            long[][] buckets = periods.computeIfAbsent(player, key -> new long[][] {new long[width], new long[width], new long[width]});
            if (value.day() == today) {
                buckets[0][stat.index()] += value.value();
            }
            if (calendar.week(value.day()) == week) {
                buckets[1][stat.index()] += value.value();
            }
            if (Calendar.month(value.day()) == month) {
                buckets[2][stat.index()] += value.value();
            }
        }
        for (PlayerRecord player : players.values()) {
            long[][] buckets = periods.get(player);
            boolean pending = false;
            for (long rise : player.pendingDaily) {
                pending |= rise > 0;
            }
            if (buckets == null && !pending) {
                // Most players did nothing this month: empty buckets, grown when they do.
                player.setBucket(Period.DAILY, new Bucket(today, new long[0]));
                player.setBucket(Period.WEEKLY, new Bucket(week, new long[0]));
                player.setBucket(Period.MONTHLY, new Bucket(month, new long[0]));
                player.ensureWidth(width);
                continue;
            }
            if (buckets == null) {
                buckets = new long[][] {new long[width], new long[width], new long[width]};
            }
            for (int i = 0; i < width && i < player.pendingDaily.length; i++) {
                buckets[0][i] += player.pendingDaily[i];
                buckets[1][i] += player.pendingDaily[i];
                buckets[2][i] += player.pendingDaily[i];
            }
            player.setBucket(Period.DAILY, new Bucket(today, buckets[0]));
            player.setBucket(Period.WEEKLY, new Bucket(week, buckets[1]));
            player.setBucket(Period.MONTHLY, new Bucket(month, buckets[2]));
            player.ensureWidth(width);
        }
        namesChanged = true;
        boards(everything, today);
    }

    private void rename(PlayerRecord player, String name) {
        if (name == null || name.equals(player.name) && byName.get(name.toLowerCase(Locale.ROOT)) == player) {
            return;
        }
        String old = player.name;
        if (old != null) {
            byName.remove(old.toLowerCase(Locale.ROOT), player);
        }
        player.name = name;
        byName.put(name.toLowerCase(Locale.ROOT), player);
        namesChanged = true;
    }

    /**
     * Applies every queued event, writes what changed, and works out leaderboards again.
     * On xStats' thread.
     *
     * @param started when the save's first reading was asked for, for how long it took
     * @return whether the database took it; if not, what changed stays queued for the next one
     */
    public boolean save(long started) {
        if (store == null) {
            return false;
        }
        Settings current = settings;
        long now = System.currentTimeMillis();
        int today = current.calendar().today(now);
        Catalog.Snapshot stats = catalog.snapshot();
        Set<PlayerRecord> touched = new HashSet<>();
        Event event;
        while ((event = events.poll()) != null) {
            try {
                PlayerRecord player = apply(event, stats, current, today);
                if (player != null) {
                    touched.add(player);
                }
            } catch (SQLException e) {
                // Couldn't read their rows: try the event again next time.
                events.add(event);
                problem("Couldn't read a player's stats from " + store.description() + ": " + e.getMessage());
                return false;
            }
        }
        boolean written = write(stats, today);
        if (written && lastFold != today) {
            fold(today, current);
        }
        if (written && networkDirty && current.network()) {
            networkDirty = false;
            try {
                reload(false);
            } catch (SQLException e) {
                networkDirty = true;
                logger.warning("Couldn't read the network's stats: " + e.getMessage());
            }
        }
        boards(false, today);
        long took = System.currentTimeMillis() - started;
        lastSave = new LastSave(now, touched.size(), took, written ? null : lastSave.problem());
        return written;
    }

    private PlayerRecord apply(Event event, Catalog.Snapshot stats, Settings current, int today) throws SQLException {
        int width = stats.width();
        switch (event) {
            case Event.Joined joined -> {
                PlayerRecord player = players.computeIfAbsent(joined.player(), id -> new PlayerRecord(id, joined.name()));
                prepare(player, width);
                rename(player, joined.name());
                long first = joined.firstPlayed() > 0 ? joined.firstPlayed() : joined.at();
                player.firstJoin = player.firstJoin == 0 ? first : Math.min(player.firstJoin, first);
                player.lastSeen = joined.at();
                player.online = true;
                player.lastSample = joined.at();
                player.yaw = joined.yaw();
                player.pitch = joined.pitch();
                player.rowChanged = true;
                for (StatDef stat : stats.stats()) {
                    if (stat.source() instanceof StatDef.Tracked tracked && tracked.kind() == StatDef.Tracked.Kind.SESSIONS) {
                        add(player, stat, 1, current, today, width);
                    }
                }
                return player;
            }
            case Event.Sample sample -> {
                PlayerRecord player = players.computeIfAbsent(sample.player(), id -> new PlayerRecord(id, sample.name()));
                prepare(player, width);
                rename(player, sample.name());
                if (player.excluded != sample.excluded()) {
                    player.excluded = sample.excluded();
                    player.rowChanged = true;
                }
                player.lastSeen = Math.max(player.lastSeen, sample.at());
                player.rowChanged = true;
                for (StatDef stat : stats.stats()) {
                    int index = stat.index();
                    if (index >= sample.raw().length) {
                        continue;
                    }
                    long reading = sample.raw()[index];
                    if (reading != Event.Sample.NOT_READ) {
                        reading(player, stat, reading, current, today, width);
                    } else if (stat.source() instanceof StatDef.Tracked tracked && tracked.kind() == StatDef.Tracked.Kind.PLAYTIME) {
                        playtime(player, stat, sample, current, today, width);
                    }
                }
                if (!stats.stats().stream().anyMatch(stat -> stat.source() instanceof StatDef.Tracked tracked
                    && tracked.kind() == StatDef.Tracked.Kind.PLAYTIME)) {
                    look(player, sample);
                }
                if (sample.quit()) {
                    player.online = false;
                }
                return player;
            }
            case Event.Increment increment -> {
                PlayerRecord player = players.get(increment.player());
                StatDef stat = stats.byId().get(increment.stat());
                if (player == null || stat == null) {
                    return null;
                }
                prepare(player, width);
                add(player, stat, increment.amount(), current, today, width);
                return player;
            }
            case Event.Set set -> {
                PlayerRecord player = players.get(set.player());
                StatDef stat = stats.byId().get(set.stat());
                if (player == null || stat == null) {
                    return null;
                }
                prepare(player, width);
                gauge(player, stat, set.value(), current, today, width);
                return player;
            }
        }
    }

    /** Makes sure a player's own rows on this server are in memory, reading them the first time. */
    private void prepare(PlayerRecord player, int width) throws SQLException {
        player.ensureWidth(width);
        if (player.localLoaded) {
            player.ensureWidth(width);
            return;
        }
        if (player.dbId > 0) {
            List<Store.Local> rows = store.local(player.dbId);
            player.startLocal(width);
            Catalog.Snapshot stats = catalog.snapshot();
            for (Store.Local row : rows) {
                StatDef stat = stats.byId().get(row.stat());
                if (stat != null && stat.index() < width) {
                    player.local[stat.index()] = row.value();
                    player.raw[stat.index()] = row.raw();
                }
            }
        } else {
            player.startLocal(width);
        }
    }

    /** A reading of a counter (Minecraft's stats) or a gauge (a placeholder). */
    private void reading(PlayerRecord player, StatDef stat, long reading, Settings current, int today, int width) {
        int index = stat.index();
        if (!stat.counter()) {
            gauge(player, stat, reading, current, today, width);
            return;
        }
        long last = player.raw[index];
        if (last < 0) {
            // The first reading: what they had before xStats counted them is all time, not today.
            long rise = reading - player.local[index];
            if (rise > 0) {
                player.local[index] += rise;
                player.values[index] += rise;
            }
            player.raw[index] = reading;
            player.changed.set(index);
            changedSinceBoards.set(index);
            return;
        }
        if (reading == last) {
            return;
        }
        // Lower than last time: the counter restarted from 0, as when a stats file is deleted.
        long rise = reading > last ? reading - last : reading;
        player.raw[index] = reading;
        player.changed.set(index);
        if (rise > 0) {
            add(player, stat, rise, current, today, width);
        }
    }

    /** A value set outright: all time follows it, and only a rise counts towards periods. */
    private void gauge(PlayerRecord player, StatDef stat, long value, Settings current, int today, int width) {
        int index = stat.index();
        long old = player.local[index];
        // Never set before (on this server): what it is now isn't a rise today, as with a counter's first reading.
        boolean first = player.raw[index] < 0;
        if (value == old && !first) {
            return;
        }
        player.raw[index] = Math.max(0, value);
        player.local[index] = value;
        player.values[index] += value - old;
        player.changed.set(index);
        changedSinceBoards.set(index);
        if (!first && value > old && stat.periods()) {
            period(player, stat, value - old, current, today, width);
        }
    }

    /** Adds to a stat: all time, and today, this week and this month for a stat with periods. */
    private void add(PlayerRecord player, StatDef stat, long amount, Settings current, int today, int width) {
        int index = stat.index();
        player.local[index] += amount;
        player.values[index] += amount;
        player.changed.set(index);
        changedSinceBoards.set(index);
        if (stat.periods()) {
            period(player, stat, amount, current, today, width);
        }
    }

    private static void period(PlayerRecord player, StatDef stat, long amount, Settings current, int today, int width) {
        int index = stat.index();
        player.pendingDaily[index] += amount;
        Calendar calendar = current.calendar();
        player.addPeriod(Period.DAILY, today, index, amount, width);
        player.addPeriod(Period.WEEKLY, calendar.week(today), index, amount, width);
        player.addPeriod(Period.MONTHLY, Calendar.month(today), index, amount, width);
    }

    /**
     * Time online since the last reading, counted only if they looked around since: the
     * save's position check that makes playtime AFK-aware, with no movement listener.
     */
    private void playtime(PlayerRecord player, StatDef stat, Event.Sample sample, Settings current, int today, int width) {
        if (player.online && player.lastSample > 0) {
            // Never more than one and a half saves, so a stall or a clock change can't add hours.
            long elapsed = Math.min(sample.at() - player.lastSample, current.saveEveryMillis() * 3 / 2);
            boolean active = !current.afkAware() || player.yaw != sample.yaw() || player.pitch != sample.pitch();
            if (elapsed > 0 && active) {
                player.playMillis += elapsed;
                long seconds = player.playMillis / 1000;
                player.playMillis %= 1000;
                if (seconds > 0) {
                    add(player, stat, seconds, current, today, width);
                }
            }
        }
        look(player, sample);
    }

    private static void look(PlayerRecord player, Event.Sample sample) {
        player.yaw = sample.yaw();
        player.pitch = sample.pitch();
        player.lastSample = sample.at();
        player.online = !sample.quit();
    }

    /** Writes every changed row in one transaction. */
    private boolean write(Catalog.Snapshot stats, int today) {
        List<PlayerRecord> changed = new ArrayList<>();
        List<Store.PlayerRow> rows = new ArrayList<>();
        List<Store.TotalRow> totals = new ArrayList<>();
        List<Store.DailyRow> daily = new ArrayList<>();
        for (PlayerRecord player : players.values()) {
            if (!player.rowChanged && player.changed.isEmpty()) {
                continue;
            }
            int at = changed.size();
            changed.add(player);
            rows.add(new Store.PlayerRow(player.id, player.dbId, player.name, player.firstJoin, player.lastSeen,
                player.hidden, player.excluded));
            for (StatDef stat : stats.stats()) {
                int index = stat.index();
                if (player.local != null && player.changed.get(index) && index < player.local.length) {
                    totals.add(new Store.TotalRow(at, stat.id(), player.local[index], stat.counter() && stat.source() instanceof StatDef.Vanillas
                        ? player.raw[index] : -1));
                }
                if (index < player.pendingDaily.length && player.pendingDaily[index] > 0 && stat.periods()) {
                    daily.add(new Store.DailyRow(at, stat.id(), player.pendingDaily[index]));
                }
            }
        }
        if (changed.isEmpty()) {
            return true;
        }
        try {
            int[] ids = store.save(new Store.Save(rows, totals, daily, today), feed);
            for (int i = 0; i < changed.size(); i++) {
                PlayerRecord player = changed.get(i);
                player.dbId = ids[i];
                player.rowChanged = false;
                player.changed.clear();
                Arrays.fill(player.pendingDaily, 0);
            }
            if (failing) {
                failing = false;
                logger.info("Saving works again.");
            }
            return true;
        } catch (SQLException | RuntimeException e) {
            problem("Couldn't save stats to " + store.description() + ": " + e.getMessage()
                + ". They're kept in memory and saved at the next save.");
            return false;
        }
    }

    private void problem(String message) {
        lastSave = new LastSave(lastSave.at(), lastSave.players(), lastSave.millis(), message);
        if (!failing) {
            failing = true;
            logger.warning(message);
        }
    }

    private void fold(int today, Settings current) {
        try {
            int folded = store.fold(today - current.keepDailyDays(), 31);
            if (folded < 31) {
                lastFold = today;
            }
            if (folded > 0) {
                logger.fine("Added " + folded + " old days into their months.");
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Couldn't add old days into their months; trying again tomorrow", e);
            lastFold = today;
        }
    }

    private void boards(boolean everything, int today) {
        Catalog.Snapshot stats = catalog.snapshot();
        Set<UUID> leftOut = new HashSet<>(banned);
        for (PlayerRecord player : players.values()) {
            if (player.excluded) {
                leftOut.add(player.id);
            }
        }
        BitSet changed = (BitSet) changedSinceBoards.clone();
        changedSinceBoards.clear();
        leaderboards.compute(players.values(), stats.stats(), settings.calendar(), today, changed, everything, leftOut,
            (board, previous) -> onNewLeader.accept(board, previous));
        if (namesChanged) {
            namesChanged = false;
            List<PlayerRecord> named = new ArrayList<>();
            for (PlayerRecord player : players.values()) {
                if (player.name != null) {
                    named.add(player);
                }
            }
            named.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            String[] names = new String[named.size()];
            for (int i = 0; i < names.length; i++) {
                names[i] = named.get(i).name.toLowerCase(Locale.ROOT);
            }
            sortedPlayers = named.toArray(PlayerRecord[]::new);
            sortedNames = names;
        }
    }

    /** Works out every board again, as after a reload changed the stats. On xStats' thread. */
    public void recomputeBoards() {
        boards(true, today());
    }

    /** The player's record, made now if xStats hasn't seen them yet. On xStats' thread. */
    public PlayerRecord ensure(UUID id, String name) {
        PlayerRecord player = players.computeIfAbsent(id, key -> new PlayerRecord(key, name));
        rename(player, name);
        return player;
    }

    /** Hides a player from the website, or shows them again, and saves it right away. On xStats' thread. */
    public void hide(PlayerRecord player, boolean hidden) {
        player.hidden = hidden;
        if (player.dbId == 0 || store == null) {
            player.rowChanged = true;
            return;
        }
        try {
            store.hide(player.dbId, player.id, hidden, feed);
        } catch (SQLException e) {
            player.rowChanged = true;
            logger.warning("Couldn't save /stats private for " + player.name + " now; it's saved with the next save: " + e.getMessage());
        }
    }

    /** Another server changed whether a player is hidden. On xStats' thread. */
    public void refreshHidden(UUID id) {
        PlayerRecord player = players.get(id);
        if (player == null || store == null) {
            return;
        }
        try {
            Boolean hidden = store.hidden(id);
            if (hidden != null) {
                player.hidden = hidden;
            }
        } catch (SQLException e) {
            logger.fine("Couldn't read whether " + id + " is hidden: " + e.getMessage());
        }
    }

    /** After an import: read everything again, keeping what's in memory. On xStats' thread. */
    public void afterImport() throws SQLException {
        for (PlayerRecord player : players.values()) {
            if (!player.online) {
                player.localLoaded = false;
            }
        }
        reload(true);
    }
}
