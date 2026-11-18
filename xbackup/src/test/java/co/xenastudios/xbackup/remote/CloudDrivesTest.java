package co.xenastudios.xbackup.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xbackup.Fixtures;
import co.xenastudios.xbackup.backup.Backup;
import co.xenastudios.xbackup.backup.Databases;
import co.xenastudios.xbackup.restore.Restore;
import co.xenastudios.xbackup.store.Backend;
import co.xenastudios.xbackup.store.LocalBackend;
import co.xenastudios.xbackup.store.Repository;
import co.xenastudios.xbackup.store.Session;
import co.xenastudios.xbackup.store.Snapshot;
import co.xenastudios.xbackup.store.Throttle;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Google Drive, OneDrive and Dropbox against a local stand-in for each API, written from
 * their documentation (no real accounts in tests): linking through the device flow or
 * Dropbox's code, token refresh, then the same put/get/list/delete and a copy and restore
 * as the other remotes. What it can't prove is that the real services answer exactly so.
 */
class CloudDrivesTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private String base;
    private final Map<String, byte[]> files = new TreeMap<>();
    private final AtomicInteger polls = new AtomicInteger();
    private final AtomicInteger refreshes = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        OAuth.ENDPOINTS.put("drive", new OAuth.Endpoints(base + "/device", base + "/token", null, "drive.file"));
        OAuth.ENDPOINTS.put("onedrive", new OAuth.Endpoints(base + "/device", base + "/token", null, "Files.ReadWrite.AppFolder offline_access"));
        OAuth.ENDPOINTS.put("dropbox", new OAuth.Endpoints(null, base + "/token", base + "/authorize", null));
        CloudDrives.BASES.put("drive", base);
        CloudDrives.BASES.put("onedrive", base + "/graph");
        CloudDrives.BASES.put("dropbox-api", base + "/dbapi");
        CloudDrives.BASES.put("dropbox-content", base + "/dbcontent");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    // ---------------------------------------------------------------- the stand-in

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            byte[] body = exchange.getRequestBody().readAllBytes();
            String method = exchange.getRequestMethod();
            if (!path.equals("/device") && !path.equals("/token") && !path.startsWith("/session/") && !path.startsWith("/graph/upload/")) {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth == null || !auth.startsWith("Bearer access-")) {
                    reply(exchange, 401, "{\"error\":\"unauthorized\"}");
                    return;
                }
            }
            if (path.equals("/device")) {
                reply(exchange, 200, "{\"device_code\":\"dc\",\"user_code\":\"ABCD-EFGH\",\"verification_url\":\"https://example/device\",\"interval\":1,\"expires_in\":60}");
            } else if (path.equals("/token")) {
                String form = new String(body, StandardCharsets.UTF_8);
                if (form.contains("grant_type=refresh_token")) {
                    refreshes.incrementAndGet();
                    reply(exchange, 200, "{\"access_token\":\"access-" + refreshes.get() + "\",\"expires_in\":3600}");
                } else if (form.contains("device_code") && polls.getAndIncrement() == 0) {
                    reply(exchange, 400, "{\"error\":\"authorization_pending\"}");
                } else if (form.contains("code_verifier") && !form.contains("code=right")) {
                    reply(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"bad code\"}");
                } else {
                    // Already expired, so the first request refreshes it.
                    reply(exchange, 200, "{\"access_token\":\"access-0\",\"refresh_token\":\"refresh\",\"expires_in\":0}");
                }
            } else if (path.startsWith("/drive/v3/files") || path.startsWith("/upload/drive/v3/files") || path.startsWith("/session/")) {
                drive(exchange, method, path, query, body);
            } else if (path.startsWith("/graph/")) {
                oneDrive(exchange, method, path, body);
            } else if (path.startsWith("/dbapi/") || path.startsWith("/dbcontent/")) {
                dropbox(exchange, path, body);
            } else {
                reply(exchange, 404, "{}");
            }
        }
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        reply(exchange, status, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }

    private static void content(HttpExchange exchange, byte[] data) throws IOException {
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range == null) {
            reply(exchange, 200, data);
            return;
        }
        Matcher m = Pattern.compile("bytes=(\\d+)-(\\d+)").matcher(range);
        if (!m.matches()) {
            reply(exchange, 416, new byte[0]);
            return;
        }
        int from = Integer.parseInt(m.group(1));
        int to = Math.min(Integer.parseInt(m.group(2)), data.length - 1);
        reply(exchange, 206, java.util.Arrays.copyOfRange(data, from, to + 1));
    }

    // Drive: ids are "f" + name; the folder is "folder".
    private final Map<String, String> sessions = new java.util.HashMap<>();
    private boolean folderMade;

    private void drive(HttpExchange exchange, String method, String path, String query, byte[] body) throws IOException {
        String q = query == null ? "" : URLDecoder.decode(query, StandardCharsets.UTF_8);
        if (path.equals("/drive/v3/files") && method.equals("GET")) {
            if (q.contains("mimeType = 'application/vnd.google-apps.folder'")) {
                reply(exchange, 200, folderMade ? "{\"files\":[{\"id\":\"folder\"}]}" : "{\"files\":[]}");
                return;
            }
            StringBuilder out = new StringBuilder("{\"files\":[");
            boolean first = true;
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                out.append(first ? "" : ",").append("{\"id\":").append(Json.quote("f" + file.getKey())).append(",\"name\":")
                    .append(Json.quote(file.getKey())).append(",\"size\":\"").append(file.getValue().length).append("\"}");
                first = false;
            }
            reply(exchange, 200, out.append("]}").toString());
        } else if (path.equals("/drive/v3/files") && method.equals("POST")) {
            folderMade = true;
            reply(exchange, 200, "{\"id\":\"folder\"}");
        } else if (path.equals("/upload/drive/v3/files") && method.equals("POST")) {
            String name = Json.string(Json.object(new String(body, StandardCharsets.UTF_8)), "name");
            String id = "s" + sessions.size();
            sessions.put(id, name);
            exchange.getResponseHeaders().add("Location", base + "/session/" + id);
            reply(exchange, 200, new byte[0]);
        } else if (path.startsWith("/session/")) {
            String name = sessions.get(path.substring(9));
            files.put(name, body);
            reply(exchange, 200, "{\"id\":" + Json.quote("f" + name) + ",\"size\":\"" + body.length + "\"}");
        } else if (path.startsWith("/drive/v3/files/")) {
            String name = URLDecoder.decode(path.substring(16), StandardCharsets.UTF_8).substring(1);
            if (method.equals("DELETE")) {
                reply(exchange, files.remove(name) == null ? 404 : 204, new byte[0]);
            } else if (files.containsKey(name)) {
                content(exchange, files.get(name));
            } else {
                reply(exchange, 404, "{}");
            }
        }
    }

    private void oneDrive(HttpExchange exchange, String method, String path, byte[] body) throws IOException {
        String prefix = "/graph/me/drive/special/approot:/";
        if (path.startsWith("/graph/upload/")) {
            files.put(URLDecoder.decode(path.substring(14), StandardCharsets.UTF_8), body);
            reply(exchange, 201, "{\"size\":" + body.length + "}");
            return;
        }
        String rest = URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
        int colon = rest.indexOf(':');
        String item = rest.substring(0, colon);
        String action = rest.substring(colon + 1);
        if (action.equals("/createUploadSession")) {
            reply(exchange, 200, "{\"uploadUrl\":" + Json.quote(base + "/graph/upload/" + Http.encode(item, true)) + "}");
        } else if (action.equals("/content")) {
            if (files.containsKey(item)) {
                content(exchange, files.get(item));
            } else {
                reply(exchange, 404, "{}");
            }
        } else if (action.equals("/children")) {
            java.util.Set<String> folders = new java.util.TreeSet<>();
            StringBuilder out = new StringBuilder("{\"value\":[");
            boolean first = true;
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                if (!file.getKey().startsWith(item + "/")) {
                    continue;
                }
                String below = file.getKey().substring(item.length() + 1);
                int slash = below.indexOf('/');
                if (slash >= 0) {
                    folders.add(below.substring(0, slash));
                    continue;
                }
                out.append(first ? "" : ",").append("{\"name\":").append(Json.quote(below)).append(",\"size\":")
                    .append(file.getValue().length).append("}");
                first = false;
            }
            for (String folder : folders) {
                out.append(first ? "" : ",").append("{\"name\":").append(Json.quote(folder)).append(",\"folder\":{}}");
                first = false;
            }
            reply(exchange, 200, out.append("]}").toString());
        } else if (action.isEmpty() && method.equals("DELETE")) {
            reply(exchange, files.remove(item) == null ? 404 : 204, new byte[0]);
        } else {
            reply(exchange, 400, "{}");
        }
    }

    private void dropbox(HttpExchange exchange, String path, byte[] body) throws IOException {
        String arg = exchange.getRequestHeaders().getFirst("Dropbox-API-Arg");
        switch (path) {
            case "/dbcontent/2/files/upload" -> {
                files.put(Json.string(Json.object(arg), "path"), body);
                reply(exchange, 200, "{\"size\":" + body.length + "}");
            }
            case "/dbcontent/2/files/download" -> {
                byte[] data = files.get(Json.string(Json.object(arg), "path"));
                if (data == null) {
                    reply(exchange, 409, "{\"error_summary\":\"path/not_found/\"}");
                } else {
                    content(exchange, data);
                }
            }
            case "/dbapi/2/files/list_folder" -> {
                String root = Json.string(Json.object(new String(body, StandardCharsets.UTF_8)), "path");
                StringBuilder out = new StringBuilder("{\"entries\":[");
                boolean first = true;
                for (Map.Entry<String, byte[]> file : files.entrySet()) {
                    if (file.getKey().startsWith(root + "/")) {
                        out.append(first ? "" : ",").append("{\".tag\":\"file\",\"path_display\":").append(Json.quote(file.getKey()))
                            .append(",\"size\":").append(file.getValue().length).append("}");
                        first = false;
                    }
                }
                reply(exchange, 200, out.append("],\"has_more\":false,\"cursor\":\"c\"}").toString());
            }
            case "/dbapi/2/files/delete_v2" -> {
                String target = Json.string(Json.object(new String(body, StandardCharsets.UTF_8)), "path");
                if (files.remove(target) == null) {
                    reply(exchange, 409, "{\"error_summary\":\"path_lookup/not_found/\"}");
                } else {
                    reply(exchange, 200, "{}");
                }
            }
            default -> reply(exchange, 404, "{}");
        }
    }

    // ---------------------------------------------------------------- the tests

    private RemoteConfig settings(String type) {
        return new RemoteConfig(type, true, "xbackup", "", "", "", false, "", "", "", 0, "", "", "explicit", "client",
            "secret");
    }

    private Backend linked(String type) throws Exception {
        Path home = dir.resolve("home");
        Files.createDirectories(home);
        RemoteConfig settings = settings(type);
        if (type.equals("dropbox")) {
            String url = OAuth.dropboxUrl(home, type, settings);
            assertTrue(url.startsWith(base + "/authorize?client_id=client&response_type=code"), url);
            assertThrows(IOException.class, () -> OAuth.dropboxCode(home, type, settings, "wrong"));
            OAuth.dropboxCode(home, type, settings, "right");
        } else {
            OAuth.DeviceCode code = OAuth.start(type, type, settings);
            assertEquals("ABCD-EFGH", code.userCode());
            OAuth.finish(home, type, type, settings, code, () -> false);
        }
        assertTrue(OAuth.linked(home, type));
        return Remotes.open(type, settings, new Remotes.Context(home, Throttle.NONE, Logger.getLogger("test"), t -> null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"drive", "onedrive", "dropbox"})
    void linkThenPutGetListReadDelete(String type) throws Exception {
        try (Backend backend = linked(type)) {
            byte[] data = new byte[200_000];
            new Random(5).nextBytes(data);
            backend.put("data/ab/one", data);
            backend.put("index/two", data);
            backend.put("index/two", data);
            assertEquals(Set.of(new Backend.FileInfo("data/ab/one", 200_000), new Backend.FileInfo("index/two", 200_000)),
                Set.copyOf(backend.list("")));
            assertEquals(1, backend.list("index/").size());
            try (InputStream in = backend.get("data/ab/one")) {
                assertArrayEquals(data, in.readAllBytes());
            }
            assertArrayEquals(java.util.Arrays.copyOfRange(data, 10, 110), backend.read("index/two", 10, 100));
            assertThrows(NoSuchFileException.class, () -> backend.read("data/ab/missing", 0, 1));
            backend.delete("data/ab/one");
            backend.delete("data/ab/one");
            assertEquals(1, backend.list("").size());
            // The first token came back already expired, so it was refreshed once, and kept.
            assertEquals(1, refreshes.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"drive", "onedrive", "dropbox"})
    void copyThenRestoreFromTheDrive(String type) throws Exception {
        Path server = dir.resolve("server");
        Fixtures.server(server, new Random(6));
        try (Repository repository = Repository.open(new LocalBackend(dir.resolve("repo")), dir.resolve("temp"), true);
             Backend remote = linked(type)) {
            Snapshot snapshot = Backup.run(repository, new Backup.Plan(server, List.of("world"), List.of("server.properties"),
                List.of("**/session.lock"), Set.of(), Map.of(), "drive", "test", dir.resolve("temp"), Throttle.NONE,
                Session.Options.DEFAULTS), Backup.Pause.NONE, Databases.sqlite(), () -> false, step -> {}).snapshot();
            Mirror.push(repository.backend(), remote, () -> false, step -> {});
            assertEquals(0, Mirror.push(repository.backend(), remote, () -> false, step -> {}).filesSent());
            try (Repository cached = Fetch.metadata(remote, dir.resolve("cache"), step -> {})) {
                List<Restore.Item> items = Restore.expand(cached, snapshot, List.of("world"), Set.of());
                Fetch.data(remote, cached, items, step -> {});
                Restore.write(cached, items, dir.resolve("restored"), Throttle.NONE, () -> false, step -> {});
            }
        }
        Map<String, byte[]> expected = Fixtures.contents(server.resolve("world"), path -> !path.endsWith("session.lock"));
        Map<String, byte[]> actual = Fixtures.contents(dir.resolve("restored/world"), path -> true);
        assertEquals(expected.keySet(), actual.keySet());
        for (String path : expected.keySet()) {
            assertArrayEquals(expected.get(path), actual.get(path), path);
        }
    }
}
