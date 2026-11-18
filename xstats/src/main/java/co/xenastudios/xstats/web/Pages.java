package co.xenastudios.xstats.web;

import co.xenastudios.xstats.XStats;
import co.xenastudios.xstats.api.LeaderboardEntry;
import co.xenastudios.xstats.api.Period;
import co.xenastudios.xstats.api.ProfileLine;
import co.xenastudios.xstats.config.XSConfig;
import co.xenastudios.xstats.data.Board;
import co.xenastudios.xstats.data.Engine;
import co.xenastudios.xstats.data.PlayerRecord;
import co.xenastudios.xstats.profile.Sections;
import co.xenastudios.xstats.stat.Format;
import co.xenastudios.xstats.stat.StatDef;
import co.xenastudios.xstats.storage.Store;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The web API's answers. Everything every visitor asks for (the server's page, each
 * leaderboard) is made once after each save and kept, so serving it is a map lookup;
 * a profile is made from memory when asked, with its 30-day history read from the
 * database through a small cache.
 */
public final class Pages implements WebServer.Source {

    /** The API's version: the {@code v1} in its paths. A change that breaks a field means v2, with v1 kept. */
    public static final int VERSION = 1;
    public static final String BASE = "/api/v1/";
    static final int HISTORY_DAYS = 30;
    private static final int SUMMARY = 5;
    private static final Pattern LOGO_FILE = Pattern.compile("[A-Za-z0-9_.-]+\\.(png|jpe?g|svg|webp)");
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>\\\\]+");

    private final XStats plugin;
    private volatile Map<String, Response> rendered = Map.of();
    private final Map<String, Board> renderedFrom = new HashMap<>();
    private final Map<String, Response> boardPages = new HashMap<>();
    private final Map<Integer, Cached> history = java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Cached> eldest) {
            return size() > 256;
        }
    });

    private record Cached(long at, List<Store.Day> days, List<Store.ServerValue> servers) {}

    public Pages(XStats plugin) {
        this.plugin = plugin;
    }

    /** A page made at the last save, by its path, or null. Any thread. */
    @Override
    public Response page(String path) {
        return rendered.get(path);
    }

    @Override
    public String name(UUID player) {
        PlayerRecord record = plugin.engine().player(player);
        return record == null || record.hidden() ? null : record.name();
    }

    private static boolean shown(StatDef stat) {
        return stat.website();
    }

    /** Makes every page visitors share again. On xStats' thread, after a save. */
    public void render() {
        Engine engine = plugin.engine();
        if (engine == null || !engine.ready()) {
            return;
        }
        XSConfig config = plugin.config();
        List<StatDef> stats = plugin.catalog().snapshot().stats().stream().filter(Pages::shown).toList();
        Map<String, Response> pages = new HashMap<>();
        pages.put(BASE + "server", Response.json(server(config, engine, stats), 60));
        int size = config.leaderboards().size();
        for (Period period : Period.values()) {
            Json summary = new Json().object().field("period", period.id()).field("saved", engine.lastSave().at()).key("boards").array();
            for (StatDef stat : stats) {
                if (period != Period.ALL && !stat.periods()) {
                    continue;
                }
                Board board = engine.leaderboards().get(stat.id(), period);
                if (board == null) {
                    continue;
                }
                String path = BASE + "leaderboard/" + stat.id() + "/" + period.id();
                // A board that didn't change since the last save keeps its page.
                Response page = renderedFrom.get(path) == board ? boardPages.get(path) : null;
                if (page == null) {
                    Json json = new Json().object().field("stat", stat.id()).field("period", period.id())
                        .field("saved", engine.lastSave().at()).key("entries");
                    entries(json, stat, board.top(size, true));
                    page = Response.json(json.end(), 60);
                    renderedFrom.put(path, board);
                    boardPages.put(path, page);
                }
                pages.put(path, page);
                summary.object().field("stat", stat.id()).key("entries");
                entries(summary, stat, board.top(SUMMARY, true));
                summary.end();
            }
            pages.put(BASE + "leaderboards/" + period.id(), Response.json(summary.endArray().end(), 60));
        }
        rendered = Map.copyOf(pages);
    }

    private static void entries(Json json, StatDef stat, List<LeaderboardEntry> entries) {
        json.array();
        for (LeaderboardEntry entry : entries) {
            json.object().field("rank", entry.rank()).field("uuid", entry.player().toString()).field("name", entry.name())
                .field("value", entry.value()).field("display", Format.value(stat, entry.value())).end();
        }
        json.endArray();
    }

    private Json server(XSConfig config, Engine engine, List<StatDef> stats) {
        XSConfig.Branding brand = config.web().branding();
        Json json = new Json().object()
            .field("api", VERSION)
            .field("version", plugin.getPluginMeta().getVersion())
            .field("name", brand.name().isBlank() ? "Server stats" : brand.name())
            .field("tagline", brand.tagline())
            .field("logo", logo(brand.logo()))
            .key("colours").object()
                .field("accent", brand.accent().hex()).field("background", brand.background().hex())
                .field("surface", brand.surface().hex()).field("text", brand.text().hex())
            .end()
            .field("avatar", avatar(config.web().avatars()))
            .field("players", engine.playerCount())
            .field("saved", engine.lastSave().at())
            .field("network", config.network().enabled());
        json.key("periods").array();
        for (Period period : Period.values()) {
            json.object().field("id", period.id()).field("name", periodName(config, period)).end();
        }
        json.endArray().key("stats").array();
        for (StatDef stat : stats) {
            long total = engine.leaderboards().total(stat.index());
            json.object().field("id", stat.id()).field("name", stat.name()).field("unit", stat.unit().name().toLowerCase(Locale.ROOT))
                .field("decimals", stat.decimals()).field("periods", stat.periods())
                .field("total", total).field("totalDisplay", Format.value(stat, total)).end();
        }
        return json.endArray().end();
    }

    public static String periodName(XSConfig config, Period period) {
        XSConfig.PeriodNames names = config.periods().names();
        return switch (period) {
            case DAILY -> names.daily();
            case WEEKLY -> names.weekly();
            case MONTHLY -> names.monthly();
            case ALL -> names.all();
        };
    }

    /** The heads' address: the owner's template, or xStats' own copy when heads are proxied. */
    static String avatar(XSConfig.Avatars avatars) {
        if (avatars.proxy()) {
            return BASE + "head/{uuid}";
        }
        return URL.matcher(avatars.url()).matches() ? avatars.url() : "https://mc-heads.net/avatar/{uuid}/{size}";
    }

    /** The logo: a link as written, a file in plugins/xStats/web/ as {@code /brand/<file>}, or null. */
    String logo(String logo) {
        String value = logo.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (URL.matcher(value).matches()) {
            return value;
        }
        if (LOGO_FILE.matcher(value).matches() && Files.isRegularFile(brandFolder().resolve(value))) {
            return "/brand/" + value;
        }
        return null;
    }

    @Override
    public Path brandFolder() {
        return plugin.getDataFolder().toPath().resolve("web");
    }

    /** Players whose names start with what was typed, for the site's search. Any thread. */
    @Override
    public Response search(String query) {
        if (query == null || !query.matches("\\w{1,16}")) {
            return Response.json(200, "{\"players\":[]}", 30);
        }
        Json json = new Json().object().key("players").array();
        for (PlayerRecord player : plugin.engine().search(query, 10, PlayerRecord::hidden)) {
            json.object().field("uuid", player.id.toString()).field("name", player.name()).end();
        }
        return Response.json(json.endArray().end(), 30);
    }

    /** A player's page, by name or uuid. Off the server's threads: other plugins' sections and the history may take a moment. */
    @Override
    public Response profile(String who) {
        Engine engine = plugin.engine();
        PlayerRecord record = null;
        if (who.matches("[0-9a-fA-F-]{36}")) {
            try {
                record = engine.player(UUID.fromString(who));
            } catch (IllegalArgumentException e) {
                record = null;
            }
        } else if (who.matches("\\w{1,16}")) {
            record = engine.byName(who);
        }
        if (record == null || record.hidden() || record.name() == null) {
            return Response.error(404, "No such player, or they keep their stats private.");
        }
        XSConfig config = plugin.config();
        List<StatDef> stats = plugin.catalog().snapshot().stats().stream().filter(Pages::shown).toList();
        Json json = new Json().object()
            .field("uuid", record.id.toString())
            .field("name", record.name())
            .field("firstJoin", record.firstJoin())
            .field("lastSeen", record.lastSeen())
            .field("online", record.online());
        json.key("stats").array();
        for (StatDef stat : stats) {
            json.object().field("id", stat.id());
            value(json, engine, record, stat, Period.ALL);
            if (stat.periods()) {
                json.key("periods").object();
                for (Period period : List.of(Period.DAILY, Period.WEEKLY, Period.MONTHLY)) {
                    json.key(period.id()).object();
                    value(json, engine, record, stat, period);
                    json.end();
                }
                json.end();
            }
            json.end();
        }
        json.endArray();
        json.key("sections").array();
        for (Sections.Filled section : plugin.sections().fill(record.id, false)) {
            json.object().field("id", section.key()).field("title", section.title()).key("lines").array();
            for (ProfileLine line : section.lines()) {
                json.object().field("label", line.label()).field("value", line.value()).end();
            }
            json.endArray().end();
        }
        json.endArray();
        Cached cached = history(record, config.network().enabled());
        int today = engine.today();
        json.key("history").object().field("from", today - HISTORY_DAYS + 1).key("stats").object();
        Map<String, long[]> byStat = new TreeMap<>();
        for (Store.Day day : cached.days()) {
            int offset = day.day() - (today - HISTORY_DAYS + 1);
            if (offset >= 0 && offset < HISTORY_DAYS && stats.stream().anyMatch(stat -> stat.id().equals(day.stat()))) {
                byStat.computeIfAbsent(day.stat(), key -> new long[HISTORY_DAYS])[offset] += day.delta();
            }
        }
        for (Map.Entry<String, long[]> entry : byStat.entrySet()) {
            json.key(entry.getKey()).array();
            for (long value : entry.getValue()) {
                json.value(value);
            }
            json.endArray();
        }
        json.end().end();
        if (config.network().enabled()) {
            Map<String, Map<String, Long>> servers = new TreeMap<>();
            for (Store.ServerValue value : cached.servers()) {
                if (stats.stream().anyMatch(stat -> stat.id().equals(value.stat()))) {
                    servers.computeIfAbsent(value.server(), key -> new TreeMap<>()).put(value.stat(), value.value());
                }
            }
            json.key("servers").array();
            servers.forEach((server, values) -> {
                json.object().field("server", server).key("stats").object();
                values.forEach(json::field);
                json.end().end();
            });
            json.endArray();
        }
        return Response.json(json.end(), 30);
    }

    private static void value(Json json, Engine engine, PlayerRecord record, StatDef stat, Period period) {
        long value = engine.value(record, stat, period);
        json.field("value", value).field("display", Format.value(stat, value)).field("rank", engine.rank(record, stat, period));
    }

    /** The player's last 30 days and, on a network, each server's numbers: kept a minute. */
    private Cached history(PlayerRecord record, boolean network) {
        int id = record.dbId();
        Store store = plugin.engine().store();
        if (id == 0 || store == null) {
            return new Cached(0, List.of(), List.of());
        }
        Cached cached = history.get(id);
        if (cached != null && System.currentTimeMillis() - cached.at() < 60_000) {
            return cached;
        }
        try {
            int today = plugin.engine().today();
            cached = new Cached(System.currentTimeMillis(), store.history(id, today - HISTORY_DAYS + 1, network),
                network ? store.perServer(id) : List.of());
        } catch (SQLException e) {
            plugin.getLogger().fine("Couldn't read a profile's history: " + e.getMessage());
            cached = new Cached(System.currentTimeMillis(), List.of(), List.of());
        }
        history.put(id, cached);
        return cached;
    }
}
