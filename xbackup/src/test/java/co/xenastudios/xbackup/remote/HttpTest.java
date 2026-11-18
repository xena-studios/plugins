package co.xenastudios.xbackup.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xbackup.store.Throttle;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

/** The HTTP pieces the remotes share: timeouts, ranged replies, paths. */
class HttpTest {

    @Test
    void aReplyThatStopsArrivingIsGivenUp() throws Exception {
        // Headers, half a body, then nothing: the connection stays open, as a dead link would.
        try (ServerSocket server = new ServerSocket(0)) {
            Thread thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.getInputStream().read(new byte[4096]);
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\nhalf".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread.sleep(30_000);
                } catch (IOException | InterruptedException e) {
                    // Done.
                }
            });
            thread.setDaemon(true);
            thread.start();
            Duration before = Http.idle;
            Http.idle = Duration.ofSeconds(1);
            try {
                long start = System.nanoTime();
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/x"))
                    .timeout(Duration.ofSeconds(20)).GET().build();
                assertThrows(HttpTimeoutException.class, () -> Http.send(request));
                assertTrue(System.nanoTime() - start < 10_000_000_000L, "gave up in time");
                assertThrows(HttpTimeoutException.class, () -> Http.download(request));
            } finally {
                Http.idle = before;
                thread.interrupt();
            }
        }
    }

    @Test
    void aRangeComesFromWhereItWasAskedFor() throws IOException {
        byte[] file = new byte[100];
        for (int i = 0; i < file.length; i++) {
            file[i] = (byte) i;
        }
        // A server that ignores Range sends the whole file with a 200, even when it happens to
        // be as long as the range asked for.
        assertArrayEquals(java.util.Arrays.copyOfRange(file, 10, 20), Http.ranged(reply(200, file, null), "f", 10, 10));
        byte[] start = java.util.Arrays.copyOfRange(file, 0, 10);
        assertThrows(Http.ShortFile.class, () -> Http.ranged(reply(200, start, null), "f", 10, 10));
        byte[] part = java.util.Arrays.copyOfRange(file, 10, 20);
        assertArrayEquals(part, Http.ranged(reply(206, part, "bytes 10-19/100"), "f", 10, 10));
        assertThrows(Http.Transient.class, () -> Http.ranged(reply(206, part, "bytes 0-9/100"), "f", 10, 10));
        assertThrows(Http.ShortFile.class, () -> Http.ranged(reply(206, new byte[4], "bytes 10-13/14"), "f", 10, 10));
    }

    @Test
    void aShortFileIsNotTriedAgain() {
        int[] calls = {0};
        assertThrows(Http.ShortFile.class, () -> Http.retry(() -> {
            calls[0]++;
            throw new Http.ShortFile("f", 10);
        }));
        assertEquals(1, calls[0]);
    }

    @Test
    void pathsDecodeWithoutTurningPlusIntoSpace() {
        assertEquals("/dav/a+b c/ü", Http.decodePath("/dav/a+b%20c/%C3%BC"));
        assertEquals("/50%", Http.decodePath("/50%"));
    }

    @Test
    void uploadTimeoutsGrowWithTheSize() {
        assertEquals(Duration.ofSeconds(120), Http.uploadTimeout(0, Throttle.NONE));
        assertTrue(Http.uploadTimeout(16L << 20, Throttle.NONE).toSeconds() > 1500, "a pack at 16 KiB/s, with room");
        assertTrue(Http.uploadTimeout(16L << 20, new Throttle(4096)).toSeconds() > 6000, "slower still under a limit");
    }

    @Test
    void webDavServedBelowTheRootListsOnlyItsFolder() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        String base = "/remote.php/dav/files/u/xbackup/";
        Map<String, String> listings = Map.of(
            base, entries(base, true) + entries(base + "config", false) + entries(base + "data/", true)
                + entries(base + "a+b", false),
            base + "data/", entries(base + "data/", true) + entries(base + "data/ab/", true),
            base + "data/ab/", entries(base + "data/ab/", true) + entries(base + "data/ab/abcd", false));
        server.createContext("/", exchange -> {
            asked.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
            String listing = listings.get(exchange.getRequestURI().getRawPath());
            byte[] body = listing == null ? new byte[0] : ("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">"
                + listing + "</d:multistatus>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(listing == null ? 404 : 207, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            RemoteConfig settings = new RemoteConfig("webdav", true, "xbackup", "http://127.0.0.1:"
                + server.getAddress().getPort() + "/remote.php/dav/files/u/", "", "", false, "", "", "", 0, "", "", "", "", "");
            WebDavBackend backend = new WebDavBackend("nextcloud", settings, Throttle.NONE);
            List<String> names = backend.list("").stream().map(co.xenastudios.xbackup.store.Backend.FileInfo::name)
                .sorted().toList();
            assertEquals(List.of("a+b", "config", "data/ab/abcd"), names);
            for (String request : asked) {
                assertTrue(request.contains(base), "listed outside the folder: " + request);
            }
            assertFalse(asked.contains("PROPFIND /"));
        } finally {
            server.stop(0);
        }
    }

    private static String entries(String href, boolean folder) {
        String encoded = href.replace("+", "%2B");
        return "<d:response><d:href>" + encoded + "</d:href><d:propstat><d:prop>"
            + (folder ? "<d:resourcetype><d:collection/></d:resourcetype>" : "<d:resourcetype/><d:getcontentlength>7"
                + "</d:getcontentlength>") + "</d:prop></d:propstat></d:response>";
    }

    private static HttpResponse<byte[]> reply(int status, byte[] body, String contentRange) {
        HttpHeaders headers = HttpHeaders.of(contentRange == null ? Map.of() : Map.of("Content-Range", List.of(contentRange)),
            (a, b) -> true);
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpRequest request() {
                return null;
            }

            @Override
            public Optional<HttpResponse<byte[]>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return headers;
            }

            @Override
            public byte[] body() {
                return body;
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create("http://x/");
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

}
