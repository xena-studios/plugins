package co.xenastudios.xstats.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The web server on a real socket: what it serves, what it refuses, and its limits. */
class WebServerTest {

    static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @TempDir
    Path brand;

    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final List<WebServer> servers = new ArrayList<>();
    volatile CountDownLatch hold;
    /** Holds up the head service while set. */
    volatile CountDownLatch headHold;
    volatile boolean everyoneKnown;
    final List<com.sun.net.httpserver.HttpServer> headServices = new ArrayList<>();

    /** A source with one leaderboard page, one player, and a profile that can be held up. */
    final WebServer.Source source = new WebServer.Source() {
        @Override
        public Response page(String path) {
            return path.equals("/api/v1/server") ? Response.json(200, "{\"name\":\"Test\"," + "\"pad\":\"" + "x".repeat(2000) + "\"}", 60)
                : null;
        }

        @Override
        public Response search(String query) {
            return Response.json(200, new Json().object().field("q", query).end().toString(), Response.REVALIDATE);
        }

        @Override
        public Response profile(String who) {
            CountDownLatch latch = hold;
            if (latch != null) {
                try {
                    latch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return who.equals("Steve") || who.equals("+Bedrock") ? Response.json(200, "{\"name\":\"" + who + "\"}", 30)
                : Response.error(404, "No such player.");
        }

        @Override
        public String name(UUID player) {
            return player.equals(STEVE) || everyoneKnown ? "Steve" : null;
        }

        @Override
        public Path brandFolder() {
            return brand;
        }
    };

    WebServer start(int perMinute, int concurrent, List<String> cors, List<String> trusted) throws Exception {
        return start(perMinute, concurrent, cors, trusted, "X-Forwarded-For", "https://example.invalid/{uuid}", false);
    }

    WebServer start(int perMinute, int concurrent, List<String> cors, List<String> trusted, String header, String heads,
                    boolean proxyHeads) throws Exception {
        WebServer server = WebServer.start(new WebServer.Options("127.0.0.1", 0, true, cors, perMinute, concurrent, trusted,
            header, heads, proxyHeads), source, getClass().getClassLoader(), Logger.getLogger("test"));
        servers.add(server);
        return server;
    }

    @AfterEach
    void stop() {
        servers.forEach(WebServer::close);
        headServices.forEach(service -> service.stop(0));
    }

    HttpResponse<byte[]> get(WebServer server, String path, String... headers) throws Exception {
        return send(server, "GET", path, headers);
    }

    HttpResponse<byte[]> send(WebServer server, String method, String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    @Test
    void servesTheSiteAndTheApi() throws Exception {
        WebServer server = start(1000, 8, List.of("*"), List.of());
        HttpResponse<byte[]> index = get(server, "/");
        assertEquals(200, index.statusCode());
        assertTrue(text(index).contains("<title>"));
        assertTrue(index.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'self'"));
        assertEquals("nosniff", index.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals(200, get(server, "/app.js").statusCode());
        assertEquals("{\"api\":\"\"}", text(get(server, "/config.json")));
        HttpResponse<byte[]> api = get(server, "/api/v1/server");
        assertEquals(200, api.statusCode());
        assertTrue(api.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals("public, max-age=60", api.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("*", api.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        HttpResponse<byte[]> search = get(server, "/api/v1/search?q=Ste");
        assertEquals("{\"q\":\"Ste\"}", text(search));
        // Pages that can name a player who just went private: kept, but checked every time.
        assertEquals("public, no-cache", search.headers().firstValue("Cache-Control").orElse(""));
        assertEquals(304, get(server, "/api/v1/search?q=Ste", "If-None-Match", search.headers().firstValue("ETag").orElseThrow())
            .statusCode());
        assertEquals("{\"q\":\"a b\"}", text(get(server, "/api/v1/search?q=a%20b")));
        assertEquals(200, get(server, "/api/v1/player/Steve").statusCode());
        assertEquals(404, get(server, "/api/v1/player/Alex").statusCode());
        assertTrue(text(get(server, "/api/v1/")).contains("/api/v1/leaderboard/{stat}/{period}"));
    }

    @Test
    void aBedrockNameWithAPlusIsFoundAsTheSiteSendsIt() throws Exception {
        WebServer server = start(1000, 8, List.of("*"), List.of());
        assertEquals("{\"name\":\"+Bedrock\"}", text(get(server, "/api/v1/player/%2BBedrock")));
        assertEquals("{\"name\":\"+Bedrock\"}", text(get(server, "/api/v1/player/+Bedrock")));
    }

    @Test
    void sitesElsewhereCanRevalidateAndReadTheLimit() throws Exception {
        WebServer server = start(1000, 8, List.of("*"), List.of());
        HttpResponse<byte[]> api = get(server, "/api/v1/server");
        assertEquals("ETag, Retry-After", api.headers().firstValue("Access-Control-Expose-Headers").orElse(""));
        assertEquals("If-None-Match", api.headers().firstValue("Access-Control-Allow-Headers").orElse(""));
    }

    @Test
    void nothingOutsideTheSiteCanBeReached() throws Exception {
        Files.writeString(brand.getParent().resolve("secret.png"), "secret");
        Files.writeString(brand.resolve("logo.png"), "png");
        WebServer server = start(1000, 8, List.of("*"), List.of());
        for (String path : List.of("/../config.yml", "/%2e%2e/config.yml", "/web/app.js", "/index.html/..", "/app.js%00",
            "/brand/../secret.png", "/brand/%2e%2e/secret.png", "/brand/..%2fsecret.png", "/brand/logo.exe", "/api/v1/nothing",
            "/api/v1/head/" + STEVE, "/META-INF/MANIFEST.MF", "/co/xenastudios/xstats/XStats.class")) {
            HttpResponse<byte[]> response = get(server, path);
            assertEquals(404, response.statusCode(), path);
            assertFalse(text(response).contains("secret"), path);
        }
        assertEquals("png", text(get(server, "/brand/logo.png")));
    }

    @Test
    void itsReadOnly() throws Exception {
        WebServer server = start(1000, 8, List.of("*"), List.of());
        for (String method : List.of("POST", "PUT", "DELETE", "PATCH")) {
            HttpResponse<byte[]> response = send(server, method, "/api/v1/server");
            assertEquals(405, response.statusCode(), method);
            assertEquals("GET, HEAD, OPTIONS", response.headers().firstValue("Allow").orElse(""));
        }
        HttpResponse<byte[]> preflight = send(server, "OPTIONS", "/api/v1/server", "Origin", "https://example.com");
        assertEquals(204, preflight.statusCode());
        assertEquals("GET, HEAD, OPTIONS", preflight.headers().firstValue("Access-Control-Allow-Methods").orElse(""));
        HttpResponse<byte[]> head = send(server, "HEAD", "/api/v1/server");
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
    }

    @Test
    void corsOnlyForTheSitesListed() throws Exception {
        WebServer server = start(1000, 8, List.of("https://stats.example.com"), List.of());
        assertEquals("https://stats.example.com", get(server, "/api/v1/server", "Origin", "https://stats.example.com").headers()
            .firstValue("Access-Control-Allow-Origin").orElse(""));
        assertTrue(get(server, "/api/v1/server", "Origin", "https://evil.example").headers()
            .firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    @Test
    void sharedPagesAreGzippedAndCached() throws Exception {
        WebServer server = start(1000, 8, List.of("*"), List.of());
        HttpResponse<byte[]> plain = get(server, "/api/v1/server");
        HttpResponse<byte[]> zipped = get(server, "/api/v1/server", "Accept-Encoding", "gzip");
        assertEquals("gzip", zipped.headers().firstValue("Content-Encoding").orElse(""));
        assertTrue(zipped.body().length < plain.body().length);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(zipped.body()))) {
            assertArrayEquals(plain.body(), in.readAllBytes());
        }
        String etag = plain.headers().firstValue("ETag").orElseThrow();
        HttpResponse<byte[]> again = get(server, "/api/v1/server", "If-None-Match", etag);
        assertEquals(304, again.statusCode());
        assertEquals(0, again.body().length);
    }

    @Test
    void eachVisitorHasALimit() throws Exception {
        WebServer server = start(10, 8, List.of("*"), List.of());
        for (int i = 0; i < 10; i++) {
            assertEquals(200, get(server, "/api/v1/server").statusCode());
        }
        HttpResponse<byte[]> refused = get(server, "/api/v1/server");
        assertEquals(429, refused.statusCode());
        assertTrue(refused.headers().firstValue("Retry-After").isPresent());
    }

    @Test
    void behindATrustedProxyTheLimitIsPerVisitor() throws Exception {
        WebServer server = start(3, 8, List.of("*"), List.of("127.0.0.1"));
        for (int i = 0; i < 3; i++) {
            assertEquals(200, get(server, "/api/v1/server", "X-Forwarded-For", "203.0.113.5").statusCode());
        }
        assertEquals(429, get(server, "/api/v1/server", "X-Forwarded-For", "203.0.113.5").statusCode());
        // Someone else behind the same proxy, with a made-up first hop that doesn't fool it.
        assertEquals(200, get(server, "/api/v1/server", "X-Forwarded-For", "203.0.113.5, 198.51.100.7").statusCode());
    }

    @Test
    void aVisitorCantPickTheirAddressThroughTheProxy() throws Exception {
        // nginx or Caddy on this machine, passing on whatever headers the visitor sent.
        WebServer server = start(3, 8, List.of("*"), List.of("127.0.0.1"));
        for (int i = 0; i < 3; i++) {
            assertEquals(200, get(server, "/api/v1/server", "X-Forwarded-For", "203.0.113.5", "CF-Connecting-IP", "192.0.2." + i)
                .statusCode());
        }
        // A made-up CF-Connecting-IP isn't read unless the owner chose it, nor a made-up first hop.
        assertEquals(429, get(server, "/api/v1/server", "X-Forwarded-For", "203.0.113.5", "CF-Connecting-IP", "192.0.2.99")
            .statusCode());
        assertEquals(429, get(server, "/api/v1/server", "X-Forwarded-For", "198.51.100.1, 203.0.113.5").statusCode());
        assertEquals(429, get(server, "/api/v1/server", "X-Forwarded-For", "not-an-address, 203.0.113.5").statusCode());
    }

    @Test
    void cloudflaresHeaderWhenChosenAndRangesOfProxies() throws Exception {
        WebServer server = start(2, 8, List.of("*"), List.of("127.0.0.0/8"), "CF-Connecting-IP", "https://example.invalid/{uuid}",
            false);
        for (int i = 0; i < 2; i++) {
            assertEquals(200, get(server, "/api/v1/server", "CF-Connecting-IP", "192.0.2.1", "X-Forwarded-For", "10.0.0." + i)
                .statusCode());
        }
        assertEquals(429, get(server, "/api/v1/server", "CF-Connecting-IP", "192.0.2.1").statusCode());
        assertEquals(200, get(server, "/api/v1/server", "CF-Connecting-IP", "192.0.2.2").statusCode());
        // One IPv6 visitor is their /64: new addresses inside it don't get a new limit.
        assertEquals(200, get(server, "/api/v1/server", "CF-Connecting-IP", "2001:db8:1:2::1").statusCode());
        assertEquals(200, get(server, "/api/v1/server", "CF-Connecting-IP", "2001:db8:1:2::2").statusCode());
        assertEquals(429, get(server, "/api/v1/server", "CF-Connecting-IP", "2001:db8:1:2:ffff::3").statusCode());
    }

    @Test
    void portsAProxyAddsArentPartOfTheAddress() throws Exception {
        assertEquals("1.2.3.4", WebServer.withoutPort("1.2.3.4:5678"));
        assertEquals("2001:db8::1", WebServer.withoutPort("[2001:db8::1]:443"));
        assertEquals("2001:db8::1", WebServer.withoutPort("[2001:db8::1]"));
        assertEquals("2001:db8::1", WebServer.withoutPort("2001:db8::1"));
        assertEquals("1.2.3.4", WebServer.withoutPort("1.2.3.4"));
        WebServer server = start(2, 8, List.of("*"), List.of("127.0.0.1"));
        // One visitor, though the proxy passed on a different port each time.
        assertEquals(200, get(server, "/api/v1/server", "X-Forwarded-For", "203.0.113.7:1000").statusCode());
        assertEquals(200, get(server, "/api/v1/server", "X-Forwarded-For", "203.0.113.7:1001").statusCode());
        assertEquals(429, get(server, "/api/v1/server", "X-Forwarded-For", "203.0.113.7:1002").statusCode());
        assertEquals(200, get(server, "/api/v1/server", "X-Forwarded-For", "[2001:db8::1]:443").statusCode());
        assertEquals(200, get(server, "/api/v1/server", "X-Forwarded-For", "[2001:db8::1]:444").statusCode());
        assertEquals(429, get(server, "/api/v1/server", "X-Forwarded-For", "2001:db8::1").statusCode());
    }

    @Test
    void proxiesAreAddressesAndRangesNeverNames() {
        Proxies proxies = Proxies.of(List.of("10.1.0.0/16", "::1", "2400:cb00::/32", "localhost", "10.0.0.0/33", "1.2.3.4/x"),
            "X-Real-IP", Logger.getLogger("test"));
        assertTrue(proxies.trusted("10.1.200.3"));
        assertFalse(proxies.trusted("10.2.0.1"));
        assertTrue(proxies.trusted("::1"));
        assertTrue(proxies.trusted("2400:cb00:1234::5"));
        assertFalse(proxies.trusted("127.0.0.1"), "localhost is a name: not read");
        assertFalse(proxies.trusted("example.com"));
        assertEquals("x-real-ip", proxies.header());
        assertEquals("x-forwarded-for", Proxies.of(List.of(), "bad header!", Logger.getLogger("test")).header());
    }

    @Test
    void aFloodOfMadeUpAddressesResetsNobody() {
        long[] now = {0};
        RateLimit limit = new RateLimit(3, 100, () -> now[0]);
        for (int i = 0; i < 3; i++) {
            assertTrue(limit.allow("visitor"));
        }
        assertFalse(limit.allow("visitor"));
        for (int i = 0; i < 10_000; i++) {
            limit.allow("made-up-" + i);
        }
        assertTrue(limit.tracked() <= 101, "bounded: " + limit.tracked());
        assertFalse(limit.allow("visitor"), "still limited: the map wasn't cleared");
        now[0] += 60_000;
        assertTrue(limit.allow("visitor"), "a new minute");
        assertTrue(limit.tracked() <= 2, "earlier minutes forgotten");
    }

    @Test
    void aFloodOfNewAddressesLocksOutNoRealVisitor() {
        long[] now = {0};
        RateLimit limit = new RateLimit(120, 10_000, () -> now[0]);
        now[0] += 60_000;
        for (int i = 0; i < 10_000; i++) {
            limit.allow("2001:db8:0:" + Integer.toHexString(i) + "::1");
        }
        for (int visitor = 0; visitor < 50; visitor++) {
            assertTrue(limit.allow("203.0.113." + visitor), "real visitor " + visitor + " let through");
        }
        assertTrue(limit.tracked() <= 10_000, "still bounded: " + limit.tracked());
        now[0] += 60_000;
        for (int visitor = 0; visitor < 50; visitor++) {
            assertTrue(limit.allow("203.0.113." + visitor), "and in the next minute");
        }
    }

    @Test
    void aRefusedRequestStillCarriesCors() throws Exception {
        WebServer server = start(10, 8, List.of("https://stats.example.com"), List.of());
        HttpResponse<byte[]> refused = null;
        for (int i = 0; i < 11; i++) {
            refused = get(server, "/api/v1/server", "Origin", "https://stats.example.com");
        }
        assertEquals(429, refused.statusCode());
        assertEquals("https://stats.example.com", refused.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
    }

    @Test
    void gzipHasItsOwnEtagAndCanBeRefused() throws Exception {
        WebServer server = start(1000, 8, List.of("*"), List.of());
        String plain = get(server, "/api/v1/server").headers().firstValue("ETag").orElseThrow();
        HttpResponse<byte[]> zipped = get(server, "/api/v1/server", "Accept-Encoding", "gzip");
        String zippedTag = zipped.headers().firstValue("ETag").orElseThrow();
        assertFalse(plain.equals(zippedTag));
        assertEquals(200, get(server, "/api/v1/server", "Accept-Encoding", "gzip", "If-None-Match", plain).statusCode(),
            "the plain copy's tag doesn't match the gzipped one");
        HttpResponse<byte[]> notModified = get(server, "/api/v1/server", "Accept-Encoding", "gzip", "If-None-Match", zippedTag);
        assertEquals(304, notModified.statusCode());
        assertTrue(notModified.headers().allValues("Vary").stream().anyMatch(v -> v.contains("Accept-Encoding")));
        assertTrue(get(server, "/api/v1/server", "Accept-Encoding", "gzip;q=0, deflate").headers()
            .firstValue("Content-Encoding").isEmpty());
        assertTrue(get(server, "/api/v1/server", "Accept-Encoding", "br, gzip;q=0.5").headers()
            .firstValue("Content-Encoding").isPresent());
        assertTrue(get(server, "/api/v1/server", "Accept-Encoding", "*").headers().firstValue("Content-Encoding").isPresent());
    }

    /** A head service on this machine: a small PNG after a moment, a redirect, or something too big. */
    String headService() throws Exception {
        com.sun.net.httpserver.HttpServer service = com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 0), 64);
        service.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        service.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            try (exchange) {
                if (path.startsWith("/same/")) {
                    exchange.getResponseHeaders().set("Location", "/avatar/" + path.substring("/same/".length()));
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
                if (path.startsWith("/away/")) {
                    exchange.getResponseHeaders().set("Location", "http://localhost:" + exchange.getLocalAddress().getPort()
                        + "/avatar/" + path.substring("/away/".length()));
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
                byte[] body = new byte[path.startsWith("/big/") ? Heads.MAX_BYTES + 1 : 100];
                CountDownLatch latch = headHold;
                if (latch != null) {
                    latch.await(10, TimeUnit.SECONDS);
                }
                Thread.sleep(300);
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        service.start();
        headServices.add(service);
        return "http://127.0.0.1:" + service.getAddress().getPort();
    }

    @Test
    void manyHeadsAtOnceAllArrive() throws Exception {
        everyoneKnown = true;
        // A page limit of 10: a leaderboard's heads have a limit of their own.
        WebServer server = start(10, 32, List.of("*"), List.of(), "X-Forwarded-For", headService() + "/avatar/{uuid}/{size}", true);
        List<Thread> threads = new ArrayList<>();
        java.util.concurrent.ConcurrentLinkedQueue<Integer> statuses = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (int i = 0; i < 20; i++) {
            UUID player = new UUID(1, i);
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    statuses.add(get(server, "/api/v1/head/" + player).statusCode());
                } catch (Exception e) {
                    statuses.add(-1);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join(20_000);
        }
        assertEquals(20, statuses.size());
        assertTrue(statuses.stream().allMatch(status -> status == 200), statuses.toString());
    }

    @Test
    void headsWaitingForAFetchDontHoldUpPages() throws Exception {
        everyoneKnown = true;
        // One turn for pages; six heads whose fetches are held up, four at a time.
        WebServer server = start(1000, 1, List.of("*"), List.of(), "X-Forwarded-For", headService() + "/avatar/{uuid}/{size}", true);
        headHold = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID player = new UUID(2, i);
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    get(server, "/api/v1/head/" + player);
                } catch (Exception ignored) {
                    // Only here to keep the head service busy.
                }
            }));
        }
        Thread.sleep(300);
        long started = System.nanoTime();
        assertEquals(200, get(server, "/api/v1/server").statusCode());
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(1000), "didn't wait behind the heads");
        headHold.countDown();
        for (Thread thread : threads) {
            thread.join(20_000);
        }
    }

    @Test
    void headsFollowRedirectsOnlyOnTheSameHostAndStaySmall() throws Exception {
        everyoneKnown = true;
        String service = headService();
        WebServer same = start(1000, 8, List.of("*"), List.of(), "X-Forwarded-For", service + "/same/{uuid}", true);
        assertEquals(200, get(same, "/api/v1/head/" + STEVE).statusCode());
        WebServer away = start(1000, 8, List.of("*"), List.of(), "X-Forwarded-For", service + "/away/{uuid}", true);
        assertEquals(404, get(away, "/api/v1/head/" + STEVE).statusCode(), "localhost isn't 127.0.0.1's name here");
        WebServer big = start(1000, 8, List.of("*"), List.of(), "X-Forwarded-For", service + "/big/{uuid}", true);
        assertEquals(404, get(big, "/api/v1/head/" + STEVE).statusCode());
    }

    @Test
    void anUntrustedClientCantPickItsAddress() throws Exception {
        WebServer server = start(3, 8, List.of("*"), List.of());
        for (int i = 0; i < 3; i++) {
            assertEquals(200, get(server, "/api/v1/server", "X-Forwarded-For", "203.0.113." + i).statusCode());
        }
        assertEquals(429, get(server, "/api/v1/server", "X-Forwarded-For", "203.0.113.99").statusCode());
    }

    @Test
    void tooManyAtOnceAreTurnedAway() throws Exception {
        WebServer server = start(1000, 1, List.of("*"), List.of());
        hold = new CountDownLatch(1);
        Thread slow = Thread.ofVirtual().start(() -> {
            try {
                get(server, "/api/v1/player/Steve");
            } catch (Exception ignored) {
                // Only here to hold the one turn.
            }
        });
        Thread.sleep(300);
        long started = System.nanoTime();
        assertEquals(503, get(server, "/api/v1/server").statusCode());
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(1500), "waited for a turn first");
        hold.countDown();
        slow.join(5000);
        hold = null;
        assertEquals(200, get(server, "/api/v1/server").statusCode());
    }

    @Test
    void aFloodIsCutOffAndTheServerCarriesOn() throws Exception {
        WebServer server = start(1000, 1, List.of("*"), List.of());
        hold = new CountDownLatch(1);
        // One holds the only turn; eight more may wait; the rest are closed straight away.
        List<Thread> flood = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger cut = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 20; i++) {
            flood.add(Thread.ofVirtual().start(() -> {
                try {
                    get(server, "/api/v1/player/Steve");
                } catch (Exception e) {
                    cut.incrementAndGet();
                }
            }));
        }
        Thread.sleep(500);
        hold.countDown();
        for (Thread thread : flood) {
            thread.join(10_000);
        }
        hold = null;
        assertTrue(cut.get() > 0, "some connections were cut");
        assertEquals(200, get(server, "/api/v1/server").statusCode(), "and it still answers");
    }

    @Test
    void closingFreesThePort() throws Exception {
        WebServer server = start(1000, 8, List.of("*"), List.of());
        int port = server.port();
        server.close();
        servers.clear();
        try (ServerSocket again = new ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, again.getLocalPort());
        }
    }

    @Test
    void jsonStringsCantBreakOut() {
        String json = new Json().object().field("name", "\"</script><img src=x onerror=alert(1)>\\\n\u2028").end().toString();
        assertEquals("{\"name\":\"\\\"\\u003c/script\\u003e\\u003cimg src=x onerror=alert(1)\\u003e\\\\\\n\\u2028\"}", json);
    }
}
