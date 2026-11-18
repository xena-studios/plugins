package co.xenastudios.xbackup.remote;

import co.xenastudios.xbackup.config.XBConfig;
import co.xenastudios.xbackup.store.Backend;
import co.xenastudios.xbackup.store.Throttle;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * S3 and everything that speaks it: AWS, Cloudflare R2, Backblaze B2, Wasabi, MinIO,
 * Hetzner, DigitalOcean Spaces. Requests are signed with AWS Signature Version 4, written
 * here on {@code java.net.http} rather than pulling in an SDK.
 *
 * <p>Each upload carries its SHA-256 (signed) and its MD5 ({@code Content-MD5}), so the
 * server refuses a body that arrives damaged. A PUT is all or nothing on S3.
 */
final class S3Backend implements Backend {

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final HexFormat HEX = HexFormat.of();

    private final String name;
    private final String endpoint;
    private final String region;
    private final String bucket;
    private final boolean pathStyle;
    private final String accessKey;
    private final String secretKey;
    private final String prefix;
    private final Throttle throttle;

    S3Backend(String name, XBConfig.Remote settings, String prefix, Throttle throttle) {
        this.name = name;
        String url = Remotes.required(settings.url(), "url (the endpoint, such as https://s3.us-east-1.amazonaws.com)", name);
        this.endpoint = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
            throw new IllegalArgumentException("url must start with https:// for remote " + name + ".");
        }
        this.region = settings.region().isBlank() ? "us-east-1" : settings.region().strip();
        this.bucket = Remotes.required(settings.bucket(), "bucket", name);
        this.pathStyle = settings.pathStyle();
        this.accessKey = Remotes.required(settings.username(), "username (the access key id)", name);
        this.secretKey = Remotes.required(settings.password(), "password (the secret key)", name);
        this.prefix = prefix;
        this.throttle = throttle;
    }

    @Override
    public String describe() {
        return "S3 bucket " + bucket + " at " + URI.create(endpoint).getHost();
    }

    private String key(String file) {
        return prefix.isEmpty() ? file : prefix + "/" + file;
    }

    private URI uri(String key, Map<String, String> query) {
        URI base = URI.create(endpoint);
        String host = base.getAuthority();
        String path = base.getPath() == null ? "" : base.getPath();
        String full;
        if (pathStyle) {
            full = base.getScheme() + "://" + host + path + "/" + Http.encode(bucket, false) + "/" + Http.encode(key, true);
        } else {
            full = base.getScheme() + "://" + bucket + "." + host + path + "/" + Http.encode(key, true);
        }
        String q = canonicalQuery(query);
        return URI.create(q.isEmpty() ? full : full + "?" + q);
    }

    private static String canonicalQuery(Map<String, String> query) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(query).entrySet()) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(Http.encode(entry.getKey(), false)).append('=').append(Http.encode(entry.getValue(), false));
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- signing

    /** Signs a request with SigV4; returns the headers to add. */
    Map<String, String> sign(String method, URI uri, Map<String, String> headers, String payloadHash, ZonedDateTime now) {
        String amzDate = AMZ_DATE.format(now);
        String date = amzDate.substring(0, 8);
        TreeMap<String, String> signed = new TreeMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            signed.put(header.getKey().toLowerCase(java.util.Locale.ROOT), header.getValue().strip());
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1 || port == 443 && uri.getScheme().equals("https")
            || port == 80 && uri.getScheme().equals("http");
        signed.put("host", uri.getHost() + (defaultPort ? "" : ":" + port));
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", amzDate);
        StringBuilder canonicalHeaders = new StringBuilder();
        for (Map.Entry<String, String> header : signed.entrySet()) {
            canonicalHeaders.append(header.getKey()).append(':').append(header.getValue()).append('\n');
        }
        String signedHeaders = String.join(";", signed.keySet());
        String canonical = method + "\n" + uri.getRawPath() + "\n" + (uri.getRawQuery() == null ? "" : uri.getRawQuery())
            + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = date + "/" + region + "/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        String signature = HEX.formatHex(hmac(key, toSign));
        Map<String, String> out = new TreeMap<>();
        out.put("x-amz-content-sha256", payloadHash);
        out.put("x-amz-date", amzDate);
        out.put("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders="
            + signedHeaders + ", Signature=" + signature);
        return out;
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpRequest.Builder request(String method, URI uri, Map<String, String> headers, String payloadHash,
                                        HttpRequest.BodyPublisher body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10))
            .method(method, body);
        headers.forEach(builder::header);
        sign(method, uri, headers, payloadHash, ZonedDateTime.now(ZoneOffset.UTC)).forEach(builder::header);
        return builder;
    }

    // ---------------------------------------------------------------- operations

    @Override
    public void put(String file, Path source) throws IOException {
        String key = key(Backend.checkName(file));
        MessageDigest sha = digest("SHA-256");
        MessageDigest md5 = digest("MD5");
        try (InputStream in = Files.newInputStream(source)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                sha.update(buffer, 0, n);
                md5.update(buffer, 0, n);
            }
        }
        String payload = HEX.formatHex(sha.digest());
        Map<String, String> headers = Map.of("Content-MD5", Base64.getEncoder().encodeToString(md5.digest()),
            "Content-Type", "application/octet-stream");
        Http.retry(() -> {
            HttpResponse<byte[]> response = Http.send(request("PUT", uri(key, Map.of()), headers, payload,
                Http.file(source, throttle)).build());
            check("Uploading " + file, response);
            return null;
        });
    }

    @Override
    public void put(String file, byte[] data) throws IOException {
        String key = key(Backend.checkName(file));
        Map<String, String> headers = Map.of("Content-MD5",
            Base64.getEncoder().encodeToString(digest("MD5").digest(data)), "Content-Type", "application/octet-stream");
        String payload = sha256Hex(data);
        Http.retry(() -> {
            throttle.acquire(data.length);
            HttpResponse<byte[]> response = Http.send(request("PUT", uri(key, Map.of()), headers, payload,
                HttpRequest.BodyPublishers.ofByteArray(data)).build());
            check("Uploading " + file, response);
            return null;
        });
    }

    @Override
    public InputStream get(String file) throws IOException {
        String key = key(Backend.checkName(file));
        return Http.retry(() -> {
            HttpResponse<InputStream> response = Http.stream(request("GET", uri(key, Map.of()), Map.of(), EMPTY_SHA256,
                HttpRequest.BodyPublishers.noBody()).build());
            if (response.statusCode() == 404) {
                response.body().close();
                throw new NoSuchFileException(file);
            }
            if (response.statusCode() / 100 != 2) {
                byte[] body = response.body().readAllBytes();
                throw Http.failure("Downloading " + file, response.statusCode(), errorOf(body));
            }
            return response.body();
        });
    }

    @Override
    public byte[] read(String file, long offset, int length) throws IOException {
        String key = key(Backend.checkName(file));
        if (length == 0) {
            return new byte[0];
        }
        Map<String, String> headers = Map.of("Range", Http.range(offset, length));
        return Http.retry(() -> {
            HttpResponse<byte[]> response = Http.send(request("GET", uri(key, Map.of()), headers, EMPTY_SHA256,
                HttpRequest.BodyPublishers.noBody()).build());
            if (response.statusCode() == 404) {
                throw new NoSuchFileException(file);
            }
            if (response.statusCode() == 416) {
                throw new EOFException(file + " is shorter than " + (offset + length) + " bytes");
            }
            check("Reading " + file, response);
            return Http.ranged(response, file, offset, length);
        });
    }

    @Override
    public List<FileInfo> list(String filePrefix) throws IOException {
        List<FileInfo> files = new ArrayList<>();
        String full = key(filePrefix);
        String cut = prefix.isEmpty() ? "" : prefix + "/";
        String token = null;
        do {
            Map<String, String> query = new TreeMap<>();
            query.put("list-type", "2");
            query.put("prefix", full);
            if (token != null) {
                query.put("continuation-token", token);
            }
            URI uri = pathStyle
                ? URI.create(endpointBase() + "/" + Http.encode(bucket, false) + "?" + canonicalQuery(query))
                : URI.create(scheme() + "://" + bucket + "." + authority() + basePath() + "/?" + canonicalQuery(query));
            HttpResponse<byte[]> response = Http.retry(() -> {
                HttpResponse<byte[]> r = Http.send(request("GET", uri, Map.of(), EMPTY_SHA256,
                    HttpRequest.BodyPublishers.noBody()).build());
                check("Listing " + describe(), r);
                return r;
            });
            Document xml = Http.xml(response.body());
            for (Element contents : Http.all(xml, "Contents")) {
                String key = Http.text(contents, "Key");
                String size = Http.text(contents, "Size");
                if (key != null && key.startsWith(cut) && !key.endsWith("/")) {
                    files.add(new FileInfo(key.substring(cut.length()), size == null ? 0 : Long.parseLong(size)));
                }
            }
            token = "true".equals(Http.text(xml, "IsTruncated")) ? Http.text(xml, "NextContinuationToken") : null;
        } while (token != null);
        return files;
    }

    private String endpointBase() {
        return endpoint;
    }

    private String scheme() {
        return URI.create(endpoint).getScheme();
    }

    private String authority() {
        return URI.create(endpoint).getAuthority();
    }

    private String basePath() {
        String path = URI.create(endpoint).getPath();
        return path == null ? "" : path;
    }

    @Override
    public void delete(String file) throws IOException {
        String key = key(Backend.checkName(file));
        Http.retry(() -> {
            HttpResponse<byte[]> response = Http.send(request("DELETE", uri(key, Map.of()), Map.of(), EMPTY_SHA256,
                HttpRequest.BodyPublishers.noBody()).build());
            if (response.statusCode() != 404) {
                check("Deleting " + file, response);
            }
            return null;
        });
    }

    private void check(String what, HttpResponse<byte[]> response) throws IOException {
        if (response.statusCode() / 100 == 2) {
            return;
        }
        String detail = errorOf(response.body());
        if (response.statusCode() == 403) {
            throw new Http.Permanent(what + " on remote " + name + " was refused (HTTP 403" + (detail.isEmpty() ? ""
                : ", " + detail) + "). Check the keys, the bucket, the region and the key's permissions.");
        }
        throw Http.failure(what + " on remote " + name, response.statusCode(), detail);
    }

    private static String errorOf(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            Document xml = Http.xml(body);
            String code = Http.text(xml, "Code");
            String message = Http.text(xml, "Message");
            return (code == null ? "" : code) + (message == null ? "" : ": " + message);
        } catch (IOException e) {
            String text = Http.utf8(body);
            return text.length() > 200 ? text.substring(0, 200) : text;
        }
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Makes the bucket, for tests against a fresh MinIO. */
    void createBucket() throws IOException {
        URI uri = pathStyle ? URI.create(endpoint + "/" + Http.encode(bucket, false))
            : URI.create(scheme() + "://" + bucket + "." + authority() + basePath() + "/");
        Http.retry(() -> {
            HttpResponse<byte[]> response = Http.send(request("PUT", uri, Map.of(), EMPTY_SHA256,
                HttpRequest.BodyPublishers.noBody()).build());
            if (response.statusCode() != 409) {
                check("Making the bucket", response);
            }
            return null;
        });
    }
}
