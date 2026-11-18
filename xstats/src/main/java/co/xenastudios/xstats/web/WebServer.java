package co.xenastudios.xstats.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The website and its JSON API, on the JDK's own HTTP server: no library in the jar.
 * Read-only: GET and HEAD only, nothing to log in to, nothing that changes anything.
 *
 * <ul>
 *   <li>Each request runs on a virtual thread, at most {@code maxConcurrent} at once; a
 *       request that can't get a turn within two seconds gets 503, and past eight times
 *       that many waiting, new connections are closed.</li>
 *   <li>Each visitor (by address, or by the address a trusted proxy passes on in the one
 *       header the owner chose; an IPv6 visitor by their /64) may make {@code perMinute}
 *       requests a minute; more get 429. Heads have a limit of their own, five times that,
 *       since a leaderboard shows many.</li>
 *   <li>Pages every visitor shares are made after each save and sent from memory, gzipped
 *       and with an ETag. Site files come from the jar, by exact name.</li>
 * </ul>
 */
public final class WebServer implements AutoCloseable {

    /** What the server serves. {@link Pages} in the plugin; something simpler in tests. */
    public interface Source {
        /** A page made at the last save, or null. */
        Response page(String path);

        Response search(String query);

        Response profile(String who);

        /** A known player's name, or null if xStats doesn't know them (their head isn't fetched). */
        String name(UUID player);

        /** Where the owner's logo files are. */
        Path brandFolder();
    }

    public record Options(String bind, int port, boolean site, List<String> corsOrigins, int perMinute, int maxConcurrent,
                          List<String> trustedProxies, String clientIpHeader, String headTemplate, boolean proxyHeads) {}

    /** Heads may be asked for this many times more often than pages: a leaderboard shows up to a hundred. */
    static final int HEADS_PER_PAGE = 5;

    private static final Pattern BRAND_FILE = Pattern.compile("[A-Za-z0-9_.-]+\\.(png|jpe?g|svg|webp)");
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final String SITE_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' https: data:;"
        + " connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'";
    private static final Response INDEX = Response.json(new Json().object()
        .field("api", Pages.VERSION)
        .key("endpoints").array()
            .value("/api/v1/server").value("/api/v1/leaderboards/{period}").value("/api/v1/leaderboard/{stat}/{period}")
            .value("/api/v1/search?q={name}").value("/api/v1/player/{name or uuid}").value("/api/v1/head/{uuid}")
        .endArray()
        .field("docs", "https://docs.xenastudios.co/xstats/web-api/")
        .end(), 300);

    private final HttpServer server;
    private final ExecutorService threads;
    private final Source source;
    private final Options options;
    private final Site site;
    private final Heads heads;
    private final RateLimit limit;
    private final RateLimit headLimit;
    private final Semaphore turns;
    private final Proxies proxies;
    private final Logger logger;
    private final AtomicInteger waiting = new AtomicInteger();

    private WebServer(HttpServer server, ExecutorService threads, Source source, Options options, Site site, Logger logger) {
        this.server = server;
        this.threads = threads;
        this.source = source;
        this.options = options;
        this.site = site;
        this.logger = logger;
        this.heads = options.proxyHeads() ? new Heads(options.headTemplate()) : null;
        this.limit = new RateLimit(options.perMinute(), System::currentTimeMillis);
        this.headLimit = new RateLimit(options.perMinute() * HEADS_PER_PAGE, System::currentTimeMillis);
        this.turns = new Semaphore(options.maxConcurrent());
        this.proxies = Proxies.of(options.trustedProxies(), options.clientIpHeader(), logger);
    }

    /**
     * Starts listening. Fails if the port is taken or the address isn't this machine's.
     *
     * @param port 0 for any free port, as the self-test uses
     */
    public static WebServer start(Options options, Source source, ClassLoader loader, Logger logger) throws IOException {
        // The JDK's server waits forever for a slow request unless told otherwise. These are
        // the JVM's own settings, read once when its server first loads, so they also apply
        // to any other plugin's, and are ignored if another plugin's server loaded first: set
        // only if nobody has. They don't stop a slow trickle of bytes; a reverse proxy does.
        defaultProperty("sun.net.httpserver.maxReqTime", "15");
        defaultProperty("sun.net.httpserver.maxRspTime", "60");
        Site site = options.site() ? Site.load(loader) : null;
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(options.bind()), options.port()), 64);
        ExecutorService threads = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("xStats web ", 0).factory());
        WebServer web = new WebServer(server, threads, source, options, site, logger);
        int most = options.maxConcurrent() * 8;
        server.setExecutor(task -> {
            if (web.waiting.incrementAndGet() > most) {
                web.waiting.decrementAndGet();
                throw new RejectedExecutionException("too many requests waiting");
            }
            threads.execute(() -> {
                try {
                    task.run();
                } finally {
                    web.waiting.decrementAndGet();
                }
            });
        });
        server.createContext("/", web::handle);
        server.start();
        return web;
    }

    private static void defaultProperty(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Stops listening and lets requests in progress finish for up to a second. */
    @Override
    public void close() {
        server.stop(1);
        if (heads != null) {
            heads.close();
        }
        threads.shutdownNow();
        try {
            threads.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            boolean turn = false;
            try {
                turn = turns.tryAcquire(2, TimeUnit.SECONDS);
                if (!turn) {
                    send(exchange, Response.error(503, "Busy. Try again in a moment."), false);
                    return;
                }
                serve(exchange);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // The visitor went away mid-reply.
            } catch (RuntimeException e) {
                logger.log(java.util.logging.Level.WARNING, "The web server hit an error on " + exchange.getRequestURI().getRawPath(), e);
                try {
                    send(exchange, Response.error(500, "Something went wrong."), false);
                } catch (IOException | RuntimeException ignored) {
                    // Already replying, or gone.
                }
            } finally {
                if (turn) {
                    turns.release();
                }
            }
        }
    }

    private void serve(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = exchange.getRequestURI().getRawPath();
        boolean api = path.startsWith("/api/") || path.equals("/api");
        if (api) {
            // Before the limit, so a site elsewhere can read a 429 too.
            cors(exchange);
        }
        RateLimit which = path.startsWith(Pages.BASE + "head/") ? headLimit : limit;
        if (!which.allow(client(exchange))) {
            exchange.getResponseHeaders().set("Retry-After", Long.toString(which.retryAfter()));
            send(exchange, Response.error(429, "Too many requests. Wait a minute."), false);
            return;
        }
        if (method.equals("OPTIONS")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD, OPTIONS");
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        boolean head = method.equals("HEAD");
        if (!head && !method.equals("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD, OPTIONS");
            send(exchange, Response.error(405, "Only GET and HEAD: the API is read-only."), false);
            return;
        }
        Response response = api ? api(path, exchange.getRequestURI().getRawQuery())
            : path.startsWith("/brand/") ? brand(path.substring("/brand/".length()))
            : site == null ? null : site.get(path);
        if (response == null) {
            response = Response.error(404, "Not found.");
        }
        if (response.type().startsWith("text/html")) {
            exchange.getResponseHeaders().set("Content-Security-Policy", SITE_POLICY);
        }
        send(exchange, response, head);
    }

    private Response api(String path, String query) {
        if (path.equals("/api") || path.equals("/api/") || path.equals(Pages.BASE)) {
            return INDEX;
        }
        if (!path.startsWith(Pages.BASE)) {
            return null;
        }
        String rest = path.substring(Pages.BASE.length());
        if (rest.equals("search")) {
            return source.search(parameter(query, "q"));
        }
        if (rest.startsWith("player/")) {
            String who = rest.substring("player/".length());
            return who.isEmpty() || who.length() > 36 ? null : source.profile(who);
        }
        if (rest.startsWith("head/")) {
            return head(rest.substring("head/".length()));
        }
        return source.page(path);
    }

    private Response head(String id) {
        if (heads == null || !UUID_TEXT.matcher(id).matches()) {
            return null;
        }
        UUID player = UUID.fromString(id);
        String name = source.name(player);
        return name == null ? null : heads.get(player, name);
    }

    /** The owner's logo from plugins/xStats/web/, by exact file name, read once. */
    private Response brand(String name) {
        if (!BRAND_FILE.matcher(name).matches()) {
            return null;
        }
        // Read each time, so a new logo shows without a restart; browsers keep it an hour.
        Path folder = source.brandFolder();
        Path path = folder.resolve(name).normalize();
        try {
            if (!path.startsWith(folder) || !Files.isRegularFile(path) || Files.size(path) > 1024 * 1024) {
                return null;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            String type = lower.endsWith(".svg") ? "image/svg+xml" : lower.endsWith(".png") ? "image/png"
                : lower.endsWith(".webp") ? "image/webp" : "image/jpeg";
            return Response.of(200, type, Files.readAllBytes(path), 3600);
        } catch (IOException e) {
            return null;
        }
    }

    static String parameter(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                try {
                    return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Who's asking, for the rate limit: the connection's address, or for a trusted proxy, the
     * visitor's address it passes on in the one header the owner chose. Only that header is
     * read: any other could have come from the visitor, through the proxy, unchanged.
     */
    String client(HttpExchange exchange) {
        byte[] remote = exchange.getRemoteAddress().getAddress().getAddress();
        if (!proxies.trusted(remote)) {
            return key(remote);
        }
        List<String> values = exchange.getRequestHeaders().get(proxies.header());
        if (values == null || values.isEmpty()) {
            return key(remote);
        }
        if (!proxies.header().equals(Proxies.FORWARDED_FOR)) {
            // A header the proxy sets alone, such as CF-Connecting-IP: one address, its last if repeated.
            byte[] visitor = Proxies.literal(values.getLast().strip());
            return key(visitor != null ? visitor : remote);
        }
        // The nearest address that isn't one of our proxies: anything before it, the visitor
        // could have made up. Repeated headers count as one list, in order.
        String[] hops = String.join(",", values).split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String text = hops[i].strip();
            if (text.isEmpty()) {
                continue;
            }
            byte[] hop = Proxies.literal(text);
            if (hop == null) {
                // Not an address: nothing further left can be believed.
                break;
            }
            if (!proxies.trusted(hop)) {
                return key(hop);
            }
        }
        return key(remote);
    }

    /** The rate limit's key for an address: itself, or for IPv6, its /64, which one visitor usually has whole. */
    static String key(byte[] address) {
        HexFormat hex = HexFormat.of();
        return address.length == 16 ? hex.formatHex(address, 0, 8) + "::/64" : hex.formatHex(address);
    }

    /** Whether Accept-Encoding allows gzip: listed (or *) without q=0. */
    static boolean acceptsGzip(List<String> headers) {
        if (headers == null) {
            return false;
        }
        Boolean any = null;
        for (String header : headers) {
            for (String part : header.split(",")) {
                String[] pieces = part.split(";");
                String coding = pieces[0].strip().toLowerCase(Locale.ROOT);
                boolean allowed = true;
                for (int i = 1; i < pieces.length; i++) {
                    String parameter = pieces[i].strip().toLowerCase(Locale.ROOT);
                    if (parameter.startsWith("q=")) {
                        try {
                            allowed = Double.parseDouble(parameter.substring(2)) > 0;
                        } catch (NumberFormatException e) {
                            allowed = false;
                        }
                    }
                }
                if (coding.equals("gzip")) {
                    return allowed;
                }
                if (coding.equals("*")) {
                    any = allowed;
                }
            }
        }
        return Boolean.TRUE.equals(any);
    }

    private void cors(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        List<String> origins = options.corsOrigins();
        if (origins.contains("*")) {
            headers.set("Access-Control-Allow-Origin", "*");
        } else {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && origins.contains(origin)) {
                headers.set("Access-Control-Allow-Origin", origin);
            }
            headers.add("Vary", "Origin");
        }
        headers.set("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        headers.set("Access-Control-Max-Age", "86400");
    }

    private static void send(HttpExchange exchange, Response response, boolean head) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", response.type());
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", response.maxAge() > 0 ? "public, max-age=" + response.maxAge() : "no-store");
        if (response.type().startsWith("image/svg")) {
            // An SVG opened on its own can run scripts: not from here.
            headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
        }
        boolean zip = false;
        if (response.gzip() != null) {
            // Also on a 304, so a cache keeps the plain and gzipped copies apart.
            headers.add("Vary", "Accept-Encoding");
            zip = acceptsGzip(exchange.getRequestHeaders().get("Accept-Encoding"));
        }
        if (response.status() == 200) {
            // The gzipped copy is different bytes, so it has an ETag of its own.
            String etag = zip ? response.gzipEtag() : response.etag();
            headers.set("ETag", etag);
            String match = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (match != null && match.contains(etag)) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }
        }
        byte[] body = response.body();
        if (zip) {
            headers.set("Content-Encoding", "gzip");
            body = response.gzip();
        }
        if (head || body.length == 0) {
            if (head) {
                headers.set("Content-Length", Integer.toString(body.length));
            }
            exchange.sendResponseHeaders(response.status(), -1);
            return;
        }
        exchange.sendResponseHeaders(response.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
