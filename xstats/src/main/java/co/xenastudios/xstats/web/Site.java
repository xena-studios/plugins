package co.xenastudios.xstats.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The website's files, read from the jar once and kept in memory. Only these exact paths
 * are ever served, looked up in a map: no path from a request ever reaches the disk, so
 * there's nothing to traverse.
 */
final class Site {

    /** Every file in the jar's web/ folder, and its type. The build copies them from xstats/web/. */
    static final Map<String, String> FILES = Map.of(
        "index.html", "text/html; charset=utf-8",
        "app.js", "application/javascript; charset=utf-8",
        "style.css", "text/css; charset=utf-8",
        "favicon.svg", "image/svg+xml");

    private final Map<String, Response> files;

    private Site(Map<String, Response> files) {
        this.files = files;
    }

    static Site load(ClassLoader loader) throws IOException {
        Map<String, Response> files = new HashMap<>();
        for (Map.Entry<String, String> file : FILES.entrySet()) {
            try (InputStream in = loader.getResourceAsStream("web/" + file.getKey())) {
                if (in == null) {
                    throw new IOException("the jar is missing web/" + file.getKey());
                }
                files.put("/" + file.getKey(), Response.of(200, file.getValue(), in.readAllBytes(), 300));
            }
        }
        files.put("/", files.get("/index.html"));
        // The built-in site reads the API from where it was loaded. A copy hosted elsewhere has its own config.json.
        files.put("/config.json", Response.json(200, "{\"api\":\"\"}", 300));
        return new Site(Map.copyOf(files));
    }

    Response get(String path) {
        return files.get(path);
    }

    /** The raw bytes of a file, for exporting the site. */
    static byte[] read(ClassLoader loader, String name) throws IOException {
        try (InputStream in = loader.getResourceAsStream("web/" + name)) {
            if (in == null) {
                throw new IOException("the jar is missing web/" + name);
            }
            return in.readAllBytes();
        }
    }

    static List<String> names() {
        return List.copyOf(FILES.keySet());
    }
}
