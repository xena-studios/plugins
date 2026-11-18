package co.xenastudios.xbackup.remote;

import co.xenastudios.xbackup.config.XBConfig;
import co.xenastudios.xbackup.store.Backend;
import co.xenastudios.xbackup.store.Throttle;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * WebDAV: Nextcloud, ownCloud, Synology, Koofr, Apache and nginx. Uploads go to a temporary
 * name and are then MOVEd into place, so a file is never visible half sent; the size the
 * server reports afterwards is checked against the file's.
 */
final class WebDavBackend implements Backend {

    private static final String PROPFIND = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>""";

    private final String name;
    private final URI base;
    private final String authorization;
    private final Throttle throttle;
    private final Set<String> madeFolders = new HashSet<>();

    WebDavBackend(String name, XBConfig.Remote settings, Throttle throttle) {
        this.name = name;
        String url = Remotes.required(settings.url(), "url (the folder's WebDAV address)", name);
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new IllegalArgumentException("url must start with https:// for remote " + name + ".");
        }
        String folder = Remotes.cleanPrefix(settings.path());
        String joined = (url.endsWith("/") ? url : url + "/") + (folder.isEmpty() ? "" : Http.encode(folder, true) + "/");
        this.base = URI.create(joined);
        String user = settings.username().strip();
        this.authorization = user.isEmpty() ? null : "Basic " + Base64.getEncoder()
            .encodeToString((user + ":" + settings.password()).getBytes(StandardCharsets.UTF_8));
        this.throttle = throttle;
    }

    @Override
    public String describe() {
        return "WebDAV folder " + base;
    }

    private URI uri(String file) {
        return base.resolve(Http.encode(file, true));
    }

    private HttpRequest.Builder request(URI uri, String method, HttpRequest.BodyPublisher body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).method(method, body);
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return builder;
    }

    @Override
    public void put(String file, Path source) throws IOException {
        long size = Files.size(source);
        upload(file, size, () -> Http.file(source, throttle));
    }

    @Override
    public void put(String file, byte[] data) throws IOException {
        upload(file, data.length, () -> HttpRequest.BodyPublishers.ofByteArray(data));
    }

    private interface Body {
        HttpRequest.BodyPublisher get() throws IOException;
    }

    private void upload(String file, long size, Body body) throws IOException {
        Backend.checkName(file);
        makeFolders(file);
        String temp = file + ".part-" + UUID.randomUUID().toString().substring(0, 8);
        Http.retry(() -> {
            HttpResponse<byte[]> response = Http.send(request(uri(temp), "PUT", body.get())
                .header("Content-Type", "application/octet-stream").build());
            check("Uploading " + file, response);
            return null;
        });
        Http.retry(() -> {
            HttpResponse<byte[]> response = Http.send(request(uri(temp), "MOVE", HttpRequest.BodyPublishers.noBody())
                .header("Destination", uri(file).toString()).header("Overwrite", "T").build());
            check("Moving " + file + " into place", response);
            return null;
        });
        long stored = size(file);
        if (stored != size) {
            throw new IOException("Remote " + name + " holds " + stored + " bytes of " + file + " after uploading " + size
                + "; it will be sent again next time.");
        }
    }

    private void makeFolders(String file) throws IOException {
        int slash = file.lastIndexOf('/');
        if (slash < 0) {
            return;
        }
        String folder = file.substring(0, slash);
        if (madeFolders.contains(folder)) {
            return;
        }
        StringBuilder path = new StringBuilder();
        // The base folder itself, then each part.
        mkcol(base);
        for (String part : folder.split("/")) {
            path.append(part).append('/');
            String current = path.toString();
            if (!madeFolders.contains(current)) {
                mkcol(base.resolve(Http.encode(current, true)));
                madeFolders.add(current);
            }
        }
        madeFolders.add(folder);
    }

    private void mkcol(URI uri) throws IOException {
        Http.retry(() -> {
            HttpResponse<byte[]> response = Http.send(request(uri, "MKCOL", HttpRequest.BodyPublishers.noBody()).build());
            // 405: already there. 301: some servers redirect a folder without its slash.
            if (response.statusCode() != 405 && response.statusCode() != 301 && response.statusCode() / 100 != 2) {
                check("Making the folder " + uri.getPath(), response);
            }
            return null;
        });
    }

    private long size(String file) throws IOException {
        HttpResponse<byte[]> response = Http.retry(() -> Http.send(request(uri(file), "PROPFIND",
            HttpRequest.BodyPublishers.ofString(PROPFIND)).header("Depth", "0")
            .header("Content-Type", "application/xml; charset=utf-8").build()));
        if (response.statusCode() == 404) {
            throw new NoSuchFileException(file);
        }
        check("Checking " + file, response);
        String length = Http.text(Http.xml(response.body()), "getcontentlength");
        return length == null ? -1 : Long.parseLong(length.strip());
    }

    @Override
    public InputStream get(String file) throws IOException {
        return Http.retry(() -> {
            HttpResponse<InputStream> response = Http.stream(request(uri(Backend.checkName(file)), "GET",
                HttpRequest.BodyPublishers.noBody()).build());
            if (response.statusCode() == 404) {
                response.body().close();
                throw new NoSuchFileException(file);
            }
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                throw Http.failure("Downloading " + file + " from remote " + name, response.statusCode(), null);
            }
            return response.body();
        });
    }

    @Override
    public byte[] read(String file, long offset, int length) throws IOException {
        if (length == 0) {
            return new byte[0];
        }
        return Http.retry(() -> {
            HttpResponse<byte[]> response = Http.send(request(uri(Backend.checkName(file)), "GET",
                HttpRequest.BodyPublishers.noBody()).header("Range", "bytes=" + offset + "-" + (offset + length - 1)).build());
            if (response.statusCode() == 404) {
                throw new NoSuchFileException(file);
            }
            if (response.statusCode() == 416) {
                throw new EOFException(file + " is shorter than " + (offset + length) + " bytes");
            }
            check("Reading " + file, response);
            byte[] body = response.body();
            if (response.statusCode() == 200) {
                // No range support: slice the whole file.
                if (body.length < offset + length) {
                    throw new EOFException(file + " is shorter than " + (offset + length) + " bytes");
                }
                return java.util.Arrays.copyOfRange(body, (int) offset, (int) offset + length);
            }
            if (body.length != length) {
                throw new EOFException(file + " is shorter than " + (offset + length) + " bytes");
            }
            return body;
        });
    }

    @Override
    public List<FileInfo> list(String prefix) throws IOException {
        List<FileInfo> files = new ArrayList<>();
        int slash = prefix.lastIndexOf('/');
        String start = slash < 0 ? "" : prefix.substring(0, slash + 1);
        Deque<String> folders = new ArrayDeque<>();
        folders.add(start);
        String basePath = URLDecoder.decode(base.getRawPath(), StandardCharsets.UTF_8);
        while (!folders.isEmpty()) {
            String folder = folders.poll();
            URI uri = folder.isEmpty() ? base : base.resolve(Http.encode(folder, true));
            HttpResponse<byte[]> response = Http.retry(() -> Http.send(request(uri, "PROPFIND",
                HttpRequest.BodyPublishers.ofString(PROPFIND)).header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8").build()));
            if (response.statusCode() == 404) {
                continue;
            }
            check("Listing " + describe(), response);
            Document xml = Http.xml(response.body());
            for (Element entry : Http.all(xml, "response")) {
                String href = Http.text(entry, "href");
                if (href == null) {
                    continue;
                }
                String path = URLDecoder.decode(URI.create(href.strip()).getRawPath(), StandardCharsets.UTF_8);
                if (!path.startsWith(basePath)) {
                    continue;
                }
                String relative = path.substring(basePath.length());
                boolean collection = !Http.all(entry, "collection").isEmpty();
                if (collection) {
                    String child = relative.endsWith("/") ? relative : relative + "/";
                    if (!child.equals(folder) && child.length() > folder.length() && child.startsWith(folder)) {
                        folders.add(child);
                    }
                } else if (relative.startsWith(prefix) && !relative.contains(".part-")) {
                    String length = Http.text(entry, "getcontentlength");
                    files.add(new FileInfo(relative, length == null ? 0 : Long.parseLong(length.strip())));
                }
            }
        }
        return files;
    }

    @Override
    public void delete(String file) throws IOException {
        Http.retry(() -> {
            HttpResponse<byte[]> response = Http.send(request(uri(Backend.checkName(file)), "DELETE",
                HttpRequest.BodyPublishers.noBody()).build());
            if (response.statusCode() != 404) {
                check("Deleting " + file, response);
            }
            return null;
        });
    }

    private void check(String what, HttpResponse<byte[]> response) throws IOException {
        int status = response.statusCode();
        if (status / 100 == 2) {
            return;
        }
        if (status == 401 || status == 403) {
            throw new Http.Permanent(what + " on remote " + name + " was refused (HTTP " + status
                + "). Check the user name, password (Nextcloud: an app password) and the folder's permissions.");
        }
        throw Http.failure(what + " on remote " + name, status, null);
    }
}
