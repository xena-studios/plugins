package co.xenastudios.xstats;

import co.xenastudios.core.Module;
import co.xenastudios.xstats.api.CustomStat;
import co.xenastudios.xstats.api.LeaderboardEntry;
import co.xenastudios.xstats.api.Period;
import co.xenastudios.xstats.api.ProfileSectionProvider;
import co.xenastudios.xstats.api.StatInfo;
import co.xenastudios.xstats.api.StatUnit;
import co.xenastudios.xstats.api.StatsProfile;
import co.xenastudios.xstats.api.XStatsApi;
import co.xenastudios.xstats.api.event.LeaderboardChangeEvent;
import co.xenastudios.xstats.api.event.StatsSavedEvent;
import co.xenastudios.xstats.data.Board;
import co.xenastudios.xstats.data.Engine;
import co.xenastudios.xstats.data.Event;
import co.xenastudios.xstats.data.PlayerRecord;
import co.xenastudios.xstats.profile.Sections;
import co.xenastudios.xstats.stat.Definitions;
import co.xenastudios.xstats.stat.StatDef;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

/**
 * xStats' developer API, offered through the services manager while xStats is enabled.
 * Reads answer from the same memory placeholders use; writes queue for the next save.
 * It adds no listener or task: events are only built when something listens.
 */
final class Api implements XStatsApi, Module {

    private final XStats plugin;

    Api(XStats plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        Bukkit.getServicesManager().register(XStatsApi.class, this, plugin, ServicePriority.Normal);
    }

    @Override
    public void disable() {
        Bukkit.getServicesManager().unregister(XStatsApi.class, this);
    }

    private Engine engine() {
        return plugin.engine();
    }

    private StatDef stat(String id) {
        return plugin.catalog().snapshot().byId().get(Objects.requireNonNull(id, "stat"));
    }

    @Override
    public List<StatInfo> getStats() {
        return plugin.catalog().snapshot().stats().stream().map(StatDef::info).toList();
    }

    @Override
    public Optional<StatInfo> getStatInfo(String stat) {
        return Optional.ofNullable(stat(stat)).map(StatDef::info);
    }

    @Override
    public long getStat(UUID player, String stat, Period period) {
        StatDef def = stat(stat);
        PlayerRecord record = engine().player(Objects.requireNonNull(player, "player"));
        if (def == null || record == null || period != Period.ALL && !def.periods()) {
            return 0;
        }
        return engine().value(record, def, Objects.requireNonNull(period, "period"));
    }

    @Override
    public int getRank(UUID player, String stat, Period period) {
        StatDef def = stat(stat);
        PlayerRecord record = engine().player(Objects.requireNonNull(player, "player"));
        return def == null || record == null ? 0 : engine().rank(record, def, Objects.requireNonNull(period, "period"));
    }

    @Override
    public List<LeaderboardEntry> getTop(String stat, Period period, int count) {
        Board board = engine().leaderboards().get(Objects.requireNonNull(stat, "stat"), Objects.requireNonNull(period, "period"));
        return board == null || count <= 0 ? List.of() : board.top(Math.min(count, plugin.config().leaderboards().size()), false);
    }

    @Override
    public CompletableFuture<Optional<StatsProfile>> getProfile(UUID player) {
        PlayerRecord record = engine().player(Objects.requireNonNull(player, "player"));
        return CompletableFuture.completedFuture(Optional.ofNullable(record).map(this::profile));
    }

    StatsProfile profile(PlayerRecord record) {
        Map<Period, Map<String, Long>> values = new EnumMap<>(Period.class);
        for (Period period : Period.values()) {
            Map<String, Long> map = new HashMap<>();
            for (StatDef stat : plugin.catalog().snapshot().stats()) {
                if (period == Period.ALL || stat.periods()) {
                    long value = engine().value(record, stat, period);
                    if (value != 0) {
                        map.put(stat.id(), value);
                    }
                }
            }
            values.put(period, Map.copyOf(map));
        }
        return new StatsProfile(record.id, record.name() == null ? "" : record.name(), record.firstJoin(), record.lastSeen(),
            record.hidden(), Map.copyOf(values));
    }

    @Override
    public boolean isPrivate(UUID player) {
        PlayerRecord record = engine().player(Objects.requireNonNull(player, "player"));
        return record != null && record.hidden();
    }

    /** {@code <plugin>_<id>}, with anything a stat id can't hold in a plugin's name made _. */
    static String key(Plugin plugin, String id) {
        return plugin.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_") + "_" + id;
    }

    @Override
    public CustomStat registerStat(Plugin plugin, String id, String displayName, StatUnit unit) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(unit, "unit");
        if (id == null || !Definitions.ID.matcher(id).matches()) {
            throw new IllegalArgumentException("A stat id is 1 to 32 lowercase letters, digits and _: " + id);
        }
        String key = key(plugin, id);
        if (key.length() > 64 || this.plugin.config().stats().containsKey(key)) {
            throw new IllegalArgumentException("xStats' config already has a stat called " + key + ", or the id is too long.");
        }
        String clash = Definitions.clash(key);
        if (clash != null) {
            throw new IllegalArgumentException(key + ": " + clash);
        }
        this.plugin.register(new Definitions.Registered(key, displayName, unit, plugin.getName()));
        return new Custom(key);
    }

    @Override
    public void registerProfileSection(Plugin plugin, String id, String title, ProfileSectionProvider provider) {
        Objects.requireNonNull(plugin, "plugin");
        this.plugin.sections().add(new Sections.Section(plugin, key(plugin, Objects.requireNonNull(id, "id")),
            Objects.requireNonNull(title, "title"), Objects.requireNonNull(provider, "provider")));
    }

    @Override
    public void unregisterProfileSection(Plugin plugin, String id) {
        this.plugin.sections().remove(key(Objects.requireNonNull(plugin, "plugin"), Objects.requireNonNull(id, "id")));
    }

    private final class Custom implements CustomStat {
        private final String id;

        Custom(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void increment(UUID player, long amount) {
            if (amount <= 0) {
                throw new IllegalArgumentException("An increment is above 0: " + amount);
            }
            engine().queue(new Event.Increment(Objects.requireNonNull(player, "player"), id, amount));
        }

        @Override
        public void set(UUID player, long value) {
            if (value < 0) {
                throw new IllegalArgumentException("A stat's value is 0 or more: " + value);
            }
            engine().queue(new Event.Set(Objects.requireNonNull(player, "player"), id, value));
        }
    }

    // ---- events, on xStats' thread ----

    /** After a save: drops stats and profile sections of plugins that were disabled, and tells listeners. */
    void saved() {
        plugin.sections().removeDisabled();
        for (Definitions.Registered stat : plugin.registered()) {
            Plugin owner = Bukkit.getPluginManager().getPlugin(stat.plugin());
            if (owner == null || !owner.isEnabled()) {
                plugin.unregister(stat.plugin());
            }
        }
        if (StatsSavedEvent.getHandlerList().getRegisteredListeners().length > 0) {
            Engine.LastSave last = engine().lastSave();
            Bukkit.getPluginManager().callEvent(new StatsSavedEvent(last.players(), last.millis()));
        }
    }

    void newLeader(Board board, PlayerRecord previous) {
        if (LeaderboardChangeEvent.getHandlerList().getRegisteredListeners().length == 0) {
            return;
        }
        PlayerRecord leader = board.leader();
        Bukkit.getPluginManager().callEvent(new LeaderboardChangeEvent(board.stat().id(), board.period(),
            previous == null ? null : previous.id, leader.id, leader.name(), board.values()[0]));
    }
}
