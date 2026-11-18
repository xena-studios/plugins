package co.xenastudios.xstats.web;

import co.xenastudios.xstats.XStats;
import co.xenastudios.xstats.config.XSConfig;
import co.xenastudios.xstats.stat.StatDef;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /xstats web test}: starts a second copy of the web server on a free port on this
 * machine only, asks it for the site and every kind of API page, checks what comes back,
 * and stops it. It shows the site works on this Java before a port is opened to anyone.
 */
public final class SelfTest {

    private record Check(String method, String path, int status, String contains) {}

    private SelfTest() {}

    /** Runs the checks. Blocks for a moment: off the server's threads. */
    public static List<String> run(XStats plugin) {
        XSConfig.Web settings = plugin.config().web();
        WebServer.Options options = new WebServer.Options("127.0.0.1", 0, true, settings.corsOrigins(), 10_000,
            settings.maxConcurrent(), List.of(), "x-forwarded-for", settings.avatars().url(), false);
        List<String> failures = new ArrayList<>();
        WebServer server;
        try {
            server = WebServer.start(options, plugin.pages(), plugin.getClass().getClassLoader(), plugin.getLogger());
        } catch (IOException | RuntimeException | NoClassDefFoundError e) {
            return List.of("couldn't start a web server: " + e);
        }
        try {
            String stat = plugin.catalog().snapshot().stats().stream().filter(StatDef::website).map(StatDef::id).findFirst().orElse("none");
            List<Check> checks = List.of(
                new Check("GET", "/", 200, "<title>"),
                new Check("GET", "/app.js", 200, "textContent"),
                new Check("GET", "/style.css", 200, "--accent"),
                new Check("GET", "/api/v1/server", 200, "\"stats\""),
                new Check("GET", "/api/v1/leaderboards/all", 200, "\"boards\""),
                new Check("GET", "/api/v1/leaderboard/" + stat + "/all", stat.equals("none") ? 404 : 200, "\"entries\""),
                new Check("GET", "/api/v1/search?q=a", 200, "\"players\""),
                new Check("GET", "/api/v1/player/NobodyByThisName", 404, "error"),
                new Check("GET", "/../config.yml", 404, ""),
                new Check("GET", "/%2e%2e/config.yml", 404, ""),
                new Check("POST", "/api/v1/server", 405, ""));
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            for (Check check : checks) {
                String result = check(client, server.port(), check);
                if (result != null) {
                    failures.add(result);
                }
            }
        } finally {
            server.close();
        }
        return failures;
    }

    private static String check(HttpClient client, int port, Check check) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + check.path()))
                .timeout(Duration.ofSeconds(10))
                .method(check.method(), HttpRequest.BodyPublishers.noBody())
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != check.status()) {
                return check.method() + " " + check.path() + " answered " + response.statusCode() + ", not " + check.status();
            }
            if (!check.contains().isEmpty() && !response.body().contains(check.contains())) {
                return check.method() + " " + check.path() + " didn't contain " + check.contains();
            }
            return null;
        } catch (IOException | IllegalArgumentException e) {
            return check.method() + " " + check.path() + ": " + e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    /** The number of checks, for the reply. */
    public static int count() {
        return 11;
    }
}
