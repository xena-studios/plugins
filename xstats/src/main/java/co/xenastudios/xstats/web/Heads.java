package co.xenastudios.xstats.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Players' heads fetched by xStats and kept, for owners who'd rather visitors' browsers
 * never contact a head service. Only heads of players xStats knows are fetched, at most
 * four at a time (up to {@link #MAX_WAITING} others wait up to five seconds for a turn, the
 * rest get none), each at most 64 KiB, and kept a day, up to 8 MiB of them. Redirects are
 * followed only on the same host. A head request doesn't take one of the web server's
 * turns, so heads waiting here never hold up pages.
 */
final class Heads implements AutoCloseable {

    static final int MAX_BYTES = 64 * 1024;
    static final long MAX_KEPT_BYTES = 8L * 1024 * 1024;
    static final long KEEP_MS = Duration.ofDays(1).toMillis();
    static final int SIZE = 64;
    static final int FETCHING = 4;
    static final long WAIT_SECONDS = 5;
    /** Requests that may wait for a fetch at once: a few leaderboards' worth. */
    static final int MAX_WAITING = 64;
    private static final int MAX_REDIRECTS = 3;

    private record Head(long at, Response response) {}

    private final String template;
    private final HttpClient client;
    private final Semaphore fetching = new Semaphore(FETCHING);
    private final AtomicInteger waiting = new AtomicInteger();
    // Most recently used last; guarded by itself.
    private final LinkedHashMap<UUID, Head> kept = new LinkedHashMap<>(64, 0.75f, true);
    private long keptBytes;

    Heads(String template) {
        this.template = template;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private Head kept(UUID player) {
        synchronized (kept) {
            return kept.get(player);
        }
    }

    private void keep(UUID player, Response response) {
        synchronized (kept) {
            Head old = kept.put(player, new Head(System.currentTimeMillis(), response));
            keptBytes += response.body().length - (old == null ? 0 : old.response().body().length);
            Iterator<Head> eldest = kept.values().iterator();
            while (keptBytes > MAX_KEPT_BYTES && eldest.hasNext()) {
                keptBytes -= eldest.next().response().body().length;
                eldest.remove();
            }
        }
    }

    /** The head, fetched if it isn't kept, or null if it couldn't be. Blocks: on a web thread only. */
    Response get(UUID player, String name) {
        Head head = kept(player);
        if (head != null && System.currentTimeMillis() - head.at() < KEEP_MS) {
            return head.response();
        }
        Response old = head == null ? null : head.response();
        if (waiting.incrementAndGet() > MAX_WAITING) {
            waiting.decrementAndGet();
            return old;
        }
        try {
            // A first visit to a leaderboard asks for many heads at once: wait for a turn.
            if (!fetching.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) {
                return old;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return old;
        } finally {
            waiting.decrementAndGet();
        }
        try {
            // Another request may have fetched it while this one waited.
            Head fresh = kept(player);
            if (fresh != null && System.currentTimeMillis() - fresh.at() < KEEP_MS) {
                return fresh.response();
            }
            String url = template.replace("{uuid}", player.toString()).replace("{name}", name == null ? player.toString() : name)
                .replace("{size}", Integer.toString(SIZE));
            Response made = fetch(URI.create(url));
            if (made == null) {
                return old;
            }
            keep(player, made);
            return made;
        } catch (IOException | IllegalArgumentException e) {
            return old;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return old;
        } finally {
            fetching.release();
        }
    }

    /** An image from the head service, or null. Follows a redirect only to the same host. */
    private Response fetch(URI uri) throws IOException, InterruptedException {
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest request = HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(10))
                .header("User-Agent", "xStats").GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    URI next = location == null ? null : current.resolve(location);
                    if (next == null || !Objects.equals(next.getHost(), uri.getHost())
                        || !("https".equals(next.getScheme()) || "http".equals(next.getScheme()))) {
                        return null;
                    }
                    current = next;
                    continue;
                }
                String type = response.headers().firstValue("Content-Type").orElse("");
                if (status != 200 || !(type.startsWith("image/png") || type.startsWith("image/jpeg") || type.startsWith("image/webp"))) {
                    return null;
                }
                // Read at most one byte past the limit: a bigger one is refused without being held whole.
                byte[] bytes = body.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    return null;
                }
                return Response.of(200, type.split(";")[0].strip(), bytes, 86_400);
            }
        }
        return null;
    }

    @Override
    public void close() {
        client.shutdownNow();
    }
}
