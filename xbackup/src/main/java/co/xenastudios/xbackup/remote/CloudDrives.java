package co.xenastudios.xbackup.remote;

import co.xenastudios.xbackup.config.XBConfig;
import co.xenastudios.xbackup.store.Backend;
import co.xenastudios.xbackup.store.Throttle;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Drive, OneDrive and Dropbox, over their REST APIs with OAuth tokens from
 * {@code /backup link}. Each keeps the repository in a folder only xBackup's app can see:
 * Drive's {@code drive.file} scope, OneDrive's app folder, Dropbox's app folder.
 */
final class CloudDrives {

    private CloudDrives() {}

    /** API base URLs. Tests point them at a local server. */
    static final Map<String, String> BASES = new java.util.concurrent.ConcurrentHashMap<>(Map.of(
        "drive", "https://www.googleapis.com",
        "onedrive", "https://graph.microsoft.com/v1.0",
        "dropbox-api", "https://api.dropboxapi.com",
        "dropbox-content", "https://content.dropboxapi.com"));

    static Backend open(String name, String type, XBConfig.Remote settings, String prefix, Remotes.Context context) {
        Remotes.required(settings.clientId(), "client-id (from the app you registered; the docs show how)", name);
        String folder = prefix.isEmpty() ? "xbackup" : prefix;
        return switch (type) {
            case "drive" -> new Drive(name, settings, folder, context);
            case "onedrive" -> new OneDrive(name, settings, folder, context);
            default -> new Dropbox(name, settings, folder, context);
        };
    }

    /** Throws if the drive says it holds a different number of bytes than were sent. */
    static void checkSize(String remote, String file, long stored, long sent) throws IOException {
        if (stored >= 0 && stored != sent) {
            throw new IOException("remote " + remote + " holds " + stored + " bytes of " + file + " after uploading "
                + sent + "; it will be sent again next time.");
        }
    }

    /** What the three share: a token that refreshes itself, and one retry after a 401. */
    private abstract static class Cloud implements Backend {
        final String name;
        final String type;
        final XBConfig.Remote settings;
        final String folder;
        final Remotes.Context context;
        final Throttle throttle;

        Cloud(String name, String type, XBConfig.Remote settings, String folder, Remotes.Context context) {
            this.name = name;
            this.type = type;
            this.settings = settings;
            this.folder = folder;
            this.context = context;
            this.throttle = context.upload();
        }

        HttpRequest.Builder request(String url) throws IOException {
            return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + OAuth.accessToken(context.home(), name, type, settings));
        }

        interface Build {
            HttpRequest build() throws IOException;
        }

        HttpResponse<byte[]> send(String what, Build build) throws IOException {
            return Http.retry(() -> {
                HttpResponse<byte[]> response = Http.send(build.build());
                if (response.statusCode() == 401) {
                    // The token was revoked early or clocks differ: one fresh token, then give up.
                    forget();
                    response = Http.send(build.build());
                }
                if (response.statusCode() >= 500 || response.statusCode() == 429) {
                    throw new Http.Transient(what + " on remote " + name + " failed: HTTP " + response.statusCode());
                }
                return response;
            });
        }

        void forget() throws IOException {
            OAuth.expire(context.home(), name);
        }

        IOException failed(String what, HttpResponse<byte[]> response) {
            String body = Http.utf8(response.body());
            if (body.length() > 300) {
                body = body.substring(0, 300);
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return new Http.Permanent(what + " on remote " + name + " was refused (HTTP " + response.statusCode()
                    + "). Run /backup link " + name + " again. " + body);
            }
            return Http.failure(what + " on remote " + name, response.statusCode(), body);
        }

        @Override
        public void put(String file, byte[] data) throws IOException {
            Path temp = Files.createTempFile("xbackup", ".part");
            try {
                Files.write(temp, data);
                put(file, temp);
            } finally {
                Files.deleteIfExists(temp);
            }
        }

        @Override
        public InputStream get(String file) throws IOException {
            return new java.io.ByteArrayInputStream(download(file, -1, 0));
        }

        @Override
        public byte[] read(String file, long offset, int length) throws IOException {
            return length == 0 ? new byte[0] : download(file, offset, length);
        }

        /** The file's bytes; the whole file when offset is -1. */
        abstract byte[] download(String file, long offset, int length) throws IOException;
    }

    // ---------------------------------------------------------------- Google Drive

    /**
     * Google Drive, v3. The app only sees files it made ({@code drive.file}), so everything goes
     * in one folder it makes, named after {@code path}, each file named by its repository
     * path. Drive finds files by id, so names are looked up once per backend and remembered.
     */
    static final class Drive extends Cloud {
        private String folderId;
        private Map<String, String> ids;
        private Map<String, Long> sizes;

        Drive(String name, XBConfig.Remote settings, String folder, Remotes.Context context) {
            super(name, "drive", settings, folder, context);
        }

        @Override
        public String describe() {
            return "Google Drive folder " + folder;
        }

        private String base() {
            return BASES.get("drive");
        }

        private static String q(String text) {
            return text.replace("\\", "\\\\").replace("'", "\\'");
        }

        private synchronized String folderId() throws IOException {
            if (folderId != null) {
                return folderId;
            }
            String query = "name = '" + q(folder) + "' and mimeType = 'application/vnd.google-apps.folder' and trashed = false";
            HttpResponse<byte[]> response = send("Finding the folder", () -> request(base() + "/drive/v3/files?q="
                + Http.encode(query, false) + "&fields=" + Http.encode("files(id)", false) + "&spaces=drive").GET().build());
            if (response.statusCode() != 200) {
                throw failed("Finding the folder", response);
            }
            List<?> files = (List<?>) Json.object(Http.utf8(response.body())).get("files");
            if (files != null && !files.isEmpty()) {
                folderId = Json.string(cast(files.get(0)), "id");
                return folderId;
            }
            String body = Json.write(Map.of("name", folder, "mimeType", "application/vnd.google-apps.folder"));
            HttpResponse<byte[]> created = send("Making the folder", () -> request(base() + "/drive/v3/files?fields=id")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
            if (created.statusCode() != 200) {
                throw failed("Making the folder", created);
            }
            folderId = Json.string(Json.object(Http.utf8(created.body())), "id");
            return folderId;
        }

        private synchronized void index() throws IOException {
            if (ids != null) {
                return;
            }
            Map<String, String> found = new HashMap<>();
            Map<String, Long> foundSizes = new HashMap<>();
            String parent = folderId();
            String token = null;
            do {
                String query = "'" + parent + "' in parents and trashed = false";
                String url = base() + "/drive/v3/files?q=" + Http.encode(query, false) + "&pageSize=1000&fields="
                    + Http.encode("nextPageToken,files(id,name,size)", false) + (token == null ? "" : "&pageToken="
                    + Http.encode(token, false));
                HttpResponse<byte[]> response = send("Listing", () -> request(url).GET().build());
                if (response.statusCode() != 200) {
                    throw failed("Listing", response);
                }
                Map<String, Object> reply = Json.object(Http.utf8(response.body()));
                Object files = reply.get("files");
                if (files instanceof List<?> list) {
                    for (Object item : list) {
                        Map<String, Object> file = cast(item);
                        found.put(Json.string(file, "name"), Json.string(file, "id"));
                        String size = Json.string(file, "size");
                        foundSizes.put(Json.string(file, "name"), size == null ? 0 : Long.parseLong(size));
                    }
                }
                token = Json.string(reply, "nextPageToken");
            } while (token != null);
            ids = found;
            sizes = foundSizes;
        }

        @Override
        public synchronized void put(String file, Path source) throws IOException {
            Backend.checkName(file);
            index();
            String metadata = Json.write(Map.of("name", file, "parents", List.of(folderId())));
            HttpResponse<byte[]> session = send("Starting the upload of " + file, () -> request(base()
                + "/upload/drive/v3/files?uploadType=resumable&fields=id,size")
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofString(metadata)).build());
            String location = session.headers().firstValue("Location").orElse(null);
            if (session.statusCode() != 200 || location == null) {
                throw failed("Starting the upload of " + file, session);
            }
            HttpResponse<byte[]> uploaded = send("Uploading " + file, () -> request(location)
                .PUT(Http.file(source, throttle)).build());
            if (uploaded.statusCode() != 200 && uploaded.statusCode() != 201) {
                throw failed("Uploading " + file, uploaded);
            }
            Map<String, Object> reply = Json.object(Http.utf8(uploaded.body()));
            String id = Json.string(reply, "id");
            long size = Long.parseLong(Json.string(reply, "size") == null ? "-1" : Json.string(reply, "size"));
            if (size >= 0 && size != Files.size(source)) {
                throw new IOException("remote " + name + " holds " + size + " bytes of " + file + " after uploading "
                    + Files.size(source) + "; it will be sent again next time.");
            }
            String old = ids.put(file, id);
            sizes.put(file, Files.size(source));
            if (old != null && !old.equals(id)) {
                deleteId(old);
            }
        }

        @Override
        synchronized byte[] download(String file, long offset, int length) throws IOException {
            index();
            String id = ids.get(Backend.checkName(file));
            if (id == null) {
                throw new NoSuchFileException(file);
            }
            HttpResponse<byte[]> response = send("Downloading " + file, () -> {
                HttpRequest.Builder builder = request(base() + "/drive/v3/files/" + id + "?alt=media").GET();
                if (offset >= 0) {
                    builder.header("Range", Http.range(offset, length));
                }
                return builder.build();
            });
            if (response.statusCode() == 404) {
                throw new NoSuchFileException(file);
            }
            if (response.statusCode() != 200 && response.statusCode() != 206) {
                throw failed("Downloading " + file, response);
            }
            return offset < 0 ? response.body() : Http.ranged(response, file, offset, length);
        }

        @Override
        public synchronized List<FileInfo> list(String prefix) throws IOException {
            index();
            List<FileInfo> files = new ArrayList<>();
            for (Map.Entry<String, String> entry : ids.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    files.add(new FileInfo(entry.getKey(), sizes.getOrDefault(entry.getKey(), 0L)));
                }
            }
            return files;
        }

        @Override
        public synchronized void delete(String file) throws IOException {
            index();
            String id = ids.remove(Backend.checkName(file));
            sizes.remove(file);
            if (id != null) {
                deleteId(id);
            }
        }

        private void deleteId(String id) throws IOException {
            HttpResponse<byte[]> response = send("Deleting", () -> request(base() + "/drive/v3/files/" + id).DELETE().build());
            if (response.statusCode() != 204 && response.statusCode() != 200 && response.statusCode() != 404) {
                throw failed("Deleting", response);
            }
        }
    }

    // ---------------------------------------------------------------- OneDrive

    /** OneDrive, through Microsoft Graph, in the app's own folder ({@code Apps/<app name>}). */
    static final class OneDrive extends Cloud {

        OneDrive(String name, XBConfig.Remote settings, String folder, Remotes.Context context) {
            super(name, "onedrive", settings, folder, context);
        }

        @Override
        public String describe() {
            return "OneDrive app folder " + folder;
        }

        private String item(String file) {
            return BASES.get("onedrive") + "/me/drive/special/approot:/" + Http.encode(folder + "/" + file, true) + ":";
        }

        @Override
        public void put(String file, Path source) throws IOException {
            Backend.checkName(file);
            long size = Files.size(source);
            String body = "{\"item\":{\"@microsoft.graph.conflictBehavior\":\"replace\"}}";
            HttpResponse<byte[]> session = send("Starting the upload of " + file, () -> request(item(file)
                + "/createUploadSession").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
            if (session.statusCode() != 200) {
                throw failed("Starting the upload of " + file, session);
            }
            String url = Json.string(Json.object(Http.utf8(session.body())), "uploadUrl");
            // One request for the whole file: packs are well under the 60 MiB a request may carry.
            HttpResponse<byte[]> uploaded = Http.retry(() -> Http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10)).header("Content-Range", "bytes 0-" + (size - 1) + "/" + size)
                .PUT(Http.file(source, throttle)).build()));
            if (uploaded.statusCode() != 200 && uploaded.statusCode() != 201) {
                throw failed("Uploading " + file, uploaded);
            }
            checkSize(name, file, Json.number(Json.object(Http.utf8(uploaded.body())), "size", -1), size);
        }

        @Override
        byte[] download(String file, long offset, int length) throws IOException {
            HttpResponse<byte[]> response = send("Downloading " + file, () -> {
                HttpRequest.Builder builder = request(item(Backend.checkName(file)) + "/content").GET();
                if (offset >= 0) {
                    builder.header("Range", Http.range(offset, length));
                }
                return builder.build();
            });
            if (response.statusCode() == 404) {
                throw new NoSuchFileException(file);
            }
            if (response.statusCode() != 200 && response.statusCode() != 206) {
                throw failed("Downloading " + file, response);
            }
            return offset < 0 ? response.body() : Http.ranged(response, file, offset, length);
        }

        @Override
        public List<FileInfo> list(String prefix) throws IOException {
            List<FileInfo> files = new ArrayList<>();
            int slash = prefix.lastIndexOf('/');
            Deque<String> folders = new java.util.ArrayDeque<>();
            folders.add(slash < 0 ? "" : prefix.substring(0, slash));
            while (!folders.isEmpty()) {
                String current = folders.poll();
                String path = current.isEmpty() ? folder : folder + "/" + current;
                String url = BASES.get("onedrive") + "/me/drive/special/approot:/" + Http.encode(path, true)
                    + ":/children?$select=name,size,folder&$top=1000";
                while (url != null) {
                    String page = url;
                    HttpResponse<byte[]> response = send("Listing", () -> request(page).GET().build());
                    if (response.statusCode() == 404) {
                        break;
                    }
                    if (response.statusCode() != 200) {
                        throw failed("Listing", response);
                    }
                    Map<String, Object> reply = Json.object(Http.utf8(response.body()));
                    if (reply.get("value") instanceof List<?> values) {
                        for (Object value : values) {
                            Map<String, Object> entry = cast(value);
                            String child = Json.string(entry, "name");
                            String relative = current.isEmpty() ? child : current + "/" + child;
                            if (entry.containsKey("folder")) {
                                folders.add(relative);
                            } else if (relative.startsWith(prefix)) {
                                files.add(new FileInfo(relative, Json.number(entry, "size", 0)));
                            }
                        }
                    }
                    url = Json.string(reply, "@odata.nextLink");
                }
            }
            return files;
        }

        @Override
        public void delete(String file) throws IOException {
            HttpResponse<byte[]> response = send("Deleting " + file, () -> request(item(Backend.checkName(file))).DELETE().build());
            if (response.statusCode() != 204 && response.statusCode() != 200 && response.statusCode() != 404) {
                throw failed("Deleting " + file, response);
            }
        }
    }

    // ---------------------------------------------------------------- Dropbox

    /** Dropbox, API v2, in the app's folder ({@code Apps/<app name>}). */
    static final class Dropbox extends Cloud {

        Dropbox(String name, XBConfig.Remote settings, String folder, Remotes.Context context) {
            super(name, "dropbox", settings, folder, context);
        }

        @Override
        public String describe() {
            return "Dropbox app folder " + folder;
        }

        private String path(String file) {
            return "/" + folder + "/" + file;
        }

        private static String arg(Map<String, ?> values) {
            // Dropbox-API-Arg must be ASCII; Json.write escapes everything else.
            return Json.write(values);
        }

        @Override
        public void put(String file, Path source) throws IOException {
            Backend.checkName(file);
            long size = Files.size(source);
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("path", path(file));
            args.put("mode", "overwrite");
            args.put("autorename", false);
            args.put("mute", true);
            HttpResponse<byte[]> response = send("Uploading " + file, () -> request(BASES.get("dropbox-content")
                + "/2/files/upload").header("Dropbox-API-Arg", arg(args)).header("Content-Type", "application/octet-stream")
                .POST(Http.file(source, throttle)).build());
            if (response.statusCode() != 200) {
                throw failed("Uploading " + file, response);
            }
            checkSize(name, file, Json.number(Json.object(Http.utf8(response.body())), "size", -1), size);
        }

        @Override
        byte[] download(String file, long offset, int length) throws IOException {
            HttpResponse<byte[]> response = send("Downloading " + file, () -> {
                HttpRequest.Builder builder = request(BASES.get("dropbox-content") + "/2/files/download")
                    .header("Dropbox-API-Arg", arg(Map.of("path", path(Backend.checkName(file)))))
                    .POST(HttpRequest.BodyPublishers.noBody());
                if (offset >= 0) {
                    builder.header("Range", Http.range(offset, length));
                }
                return builder.build();
            });
            if (response.statusCode() == 409 && Http.utf8(response.body()).contains("not_found")) {
                throw new NoSuchFileException(file);
            }
            if (response.statusCode() != 200 && response.statusCode() != 206) {
                throw failed("Downloading " + file, response);
            }
            return offset < 0 ? response.body() : Http.ranged(response, file, offset, length);
        }

        @Override
        public List<FileInfo> list(String prefix) throws IOException {
            List<FileInfo> files = new ArrayList<>();
            String root = "/" + folder;
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("path", root);
            args.put("recursive", true);
            args.put("limit", 2000);
            String url = BASES.get("dropbox-api") + "/2/files/list_folder";
            String body = Json.write(args);
            while (true) {
                String request = body;
                String endpoint = url;
                HttpResponse<byte[]> response = send("Listing", () -> request(endpoint)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(request)).build());
                if (response.statusCode() == 409 && Http.utf8(response.body()).contains("not_found")) {
                    return files;
                }
                if (response.statusCode() != 200) {
                    throw failed("Listing", response);
                }
                Map<String, Object> reply = Json.object(Http.utf8(response.body()));
                if (reply.get("entries") instanceof List<?> entries) {
                    for (Object value : entries) {
                        Map<String, Object> entry = cast(value);
                        if (!"file".equals(Json.string(entry, ".tag"))) {
                            continue;
                        }
                        String display = Json.string(entry, "path_display");
                        if (display == null || display.length() <= root.length() + 1) {
                            continue;
                        }
                        String relative = display.substring(root.length() + 1);
                        if (relative.startsWith(prefix)) {
                            files.add(new FileInfo(relative, Json.number(entry, "size", 0)));
                        }
                    }
                }
                if (!Boolean.TRUE.equals(reply.get("has_more"))) {
                    return files;
                }
                url = BASES.get("dropbox-api") + "/2/files/list_folder/continue";
                body = Json.write(Map.of("cursor", Json.string(reply, "cursor")));
            }
        }

        @Override
        public void delete(String file) throws IOException {
            String body = Json.write(Map.of("path", path(Backend.checkName(file))));
            HttpResponse<byte[]> response = send("Deleting " + file, () -> request(BASES.get("dropbox-api")
                + "/2/files/delete_v2").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
            if (response.statusCode() == 409 && Http.utf8(response.body()).contains("not_found")) {
                return;
            }
            if (response.statusCode() != 200) {
                throw failed("Deleting " + file, response);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Object value) throws IOException {
        if (!(value instanceof Map<?, ?>)) {
            throw new IOException("unexpected reply from the drive");
        }
        return (Map<String, Object>) value;
    }
}
