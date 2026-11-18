package co.xenastudios.xstats.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Players' heads fetched by xStats and kept, for owners who'd rather visitors' browsers
 * never contact a head service. Only heads of players xStats knows are fetched, at most
 * four at a time, each at most 64 KiB, and kept a day (up to 2,000 of them).
 */
final class Heads {

    static final int MAX_BYTES = 64 * 1024;
    static final long KEEP_MS = Duration.ofDays(1).toMillis();
    static final int SIZE = 64;

    private record Head(long at, Response response) {}

    private final String template;
    private final HttpClient client;
    private final Semaphore fetching = new Semaphore(4);
    private final Map<UUID, Head> kept = java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Head> eldest) {
            return size() > 2_000;
        }
    });

    Heads(String template) {
        this.template = template;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** The head, fetched if it isn't kept, or null if it couldn't be. Blocks: on a web thread only. */
    Response get(UUID player, String name) {
        Head head = kept.get(player);
        if (head != null && System.currentTimeMillis() - head.at() < KEEP_MS) {
            return head.response();
        }
        if (!fetching.tryAcquire()) {
            return head == null ? null : head.response();
        }
        try {
            String url = template.replace("{uuid}", player.toString()).replace("{name}", name == null ? player.toString() : name)
                .replace("{size}", Integer.toString(SIZE));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("User-Agent", "xStats").GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() != 200 || !(type.startsWith("image/png") || type.startsWith("image/jpeg")
                || type.startsWith("image/webp")) || response.body().length > MAX_BYTES) {
                return head == null ? null : head.response();
            }
            Response made = Response.of(200, type.split(";")[0], response.body(), 86_400);
            kept.put(player, new Head(System.currentTimeMillis(), made));
            return made;
        } catch (java.io.IOException | IllegalArgumentException e) {
            return head == null ? null : head.response();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            fetching.release();
        }
    }
}
