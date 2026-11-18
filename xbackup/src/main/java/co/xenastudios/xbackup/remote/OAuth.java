package co.xenastudios.xbackup.remote;

import co.xenastudios.xbackup.config.XBConfig;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.BooleanSupplier;

/**
 * Linking the cloud drives. Google Drive and OneDrive use the OAuth device flow: xBackup
 * shows a short code, the owner enters it on the provider's site from any device, and
 * xBackup gets a refresh token. Dropbox has no device flow, so it's the PKCE code flow with
 * no redirect: the owner opens a link, approves, and pastes the code Dropbox shows into
 * {@code /backup link}. Only the refresh token is kept, in {@code tokens.properties},
 * readable by its owner only, never in config.yml.
 *
 * <p>Each provider needs an app registered by whoever runs the server (or by Xena Studios
 * for a shared app); its client id (and for Google, secret) goes in the remote's settings.
 */
public final class OAuth {

    /** A provider's endpoints. Tests point them at a local server. */
    record Endpoints(String deviceCode, String token, String authorize, String scope) {}

    static final Map<String, Endpoints> ENDPOINTS = new java.util.concurrent.ConcurrentHashMap<>(Map.of(
        "drive", new Endpoints("https://oauth2.googleapis.com/device/code", "https://oauth2.googleapis.com/token", null,
            "https://www.googleapis.com/auth/drive.file"),
        "onedrive", new Endpoints("https://login.microsoftonline.com/common/oauth2/v2.0/devicecode",
            "https://login.microsoftonline.com/common/oauth2/v2.0/token", null, "Files.ReadWrite.AppFolder offline_access"),
        "dropbox", new Endpoints(null, "https://api.dropboxapi.com/oauth2/token", "https://www.dropbox.com/oauth2/authorize",
            null)));

    private static final Object LOCK = new Object();

    private OAuth() {}

    // ---------------------------------------------------------------- tokens

    private static Path file(Path home) {
        return home.resolve(Remotes.TOKENS);
    }

    private static Properties load(Path home) throws IOException {
        Properties properties = new Properties();
        Path file = file(home);
        if (Files.exists(file)) {
            properties.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
        }
        return properties;
    }

    private static void save(Path home, Properties properties) throws IOException {
        Files.createDirectories(home);
        StringWriter writer = new StringWriter();
        properties.store(writer, "Cloud drive tokens from /backup link. Keep this file private; delete a remote's lines"
            + " to unlink it.");
        Path temp = home.resolve(Remotes.TOKENS + ".part");
        Files.writeString(temp, writer.toString(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows: the folder's permissions apply.
        }
        Files.move(temp, file(home), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Whether the remote has been linked. */
    public static boolean linked(Path home, String remote) throws IOException {
        synchronized (LOCK) {
            return load(home).getProperty(remote + ".refresh-token") != null;
        }
    }

    /**
     * A current access token for the remote, refreshed with its refresh token when it's
     * about to run out.
     */
    static String accessToken(Path home, String remote, String type, XBConfig.Remote settings) throws IOException {
        synchronized (LOCK) {
            Properties tokens = load(home);
            String refresh = tokens.getProperty(remote + ".refresh-token");
            if (refresh == null) {
                throw new Http.Permanent("remote " + remote + " isn't linked yet. Run /backup link " + remote + ".");
            }
            String access = tokens.getProperty(remote + ".access-token");
            long expires = Long.parseLong(tokens.getProperty(remote + ".expires", "0"));
            if (access != null && System.currentTimeMillis() < expires - 60_000) {
                return access;
            }
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", refresh);
            form.put("client_id", clientId(remote, settings));
            if (type.equals("drive")) {
                form.put("client_secret", settings.clientSecret().strip());
            }
            if (type.equals("onedrive")) {
                form.put("scope", ENDPOINTS.get(type).scope());
            }
            Map<String, Object> reply = post(ENDPOINTS.get(type).token(), form);
            String error = Json.string(reply, "error");
            if (error != null) {
                if (error.equals("invalid_grant")) {
                    throw new Http.Permanent("remote " + remote + "'s link was revoked or expired. Run /backup link "
                        + remote + " again.");
                }
                throw new IOException("remote " + remote + ": refreshing its token failed (" + error + ")");
            }
            store(tokens, remote, reply);
            save(home, tokens);
            return tokens.getProperty(remote + ".access-token");
        }
    }

    /** Drops the cached access token, so the next request gets a fresh one. */
    static void expire(Path home, String remote) throws IOException {
        synchronized (LOCK) {
            Properties tokens = load(home);
            if (tokens.remove(remote + ".expires") != null) {
                save(home, tokens);
            }
        }
    }

    private static void store(Properties tokens, String remote, Map<String, Object> reply) throws IOException {
        String access = Json.string(reply, "access_token");
        if (access == null) {
            throw new IOException("the provider's reply had no access token");
        }
        tokens.setProperty(remote + ".access-token", access);
        tokens.setProperty(remote + ".expires",
            Long.toString(System.currentTimeMillis() + Json.number(reply, "expires_in", 3600) * 1000));
        String refresh = Json.string(reply, "refresh_token");
        if (refresh != null) {
            tokens.setProperty(remote + ".refresh-token", refresh);
        }
    }

    private static String clientId(String remote, XBConfig.Remote settings) {
        return Remotes.required(settings.clientId(), "client-id (from the app you registered; the docs show how)", remote);
    }

    // ---------------------------------------------------------------- device flow

    /** What the owner is shown: where to go and the code to enter. */
    public record DeviceCode(String userCode, String url, String deviceCode, int interval, long expiresAt) {}

    /** Starts a device-flow link for Google Drive or OneDrive. */
    public static DeviceCode start(String remote, String type, XBConfig.Remote settings) throws IOException {
        Endpoints endpoints = ENDPOINTS.get(type);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId(remote, settings));
        form.put("scope", endpoints.scope());
        Map<String, Object> reply = post(endpoints.deviceCode(), form);
        String error = Json.string(reply, "error");
        if (error != null) {
            throw new IOException("the provider refused to start linking (" + error + ": "
                + Json.string(reply, "error_description") + "). Check client-id.");
        }
        String url = Json.string(reply, "verification_url");
        if (url == null) {
            url = Json.string(reply, "verification_uri");
        }
        return new DeviceCode(Json.string(reply, "user_code"), url, Json.string(reply, "device_code"),
            (int) Json.number(reply, "interval", 5), System.currentTimeMillis() + Json.number(reply, "expires_in", 900) * 1000);
    }

    /** Waits for the owner to enter the code, then keeps the refresh token. Blocks: run it off the game threads. */
    public static void finish(Path home, String remote, String type, XBConfig.Remote settings, DeviceCode code,
                              BooleanSupplier cancelled) throws IOException {
        Endpoints endpoints = ENDPOINTS.get(type);
        long interval = Math.max(1, code.interval()) * 1000L;
        while (System.currentTimeMillis() < code.expiresAt()) {
            if (cancelled.getAsBoolean()) {
                throw new IOException("stopped");
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("stopped");
            }
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            form.put("client_id", clientId(remote, settings));
            form.put("device_code", code.deviceCode());
            if (type.equals("drive")) {
                form.put("client_secret", settings.clientSecret().strip());
            }
            Map<String, Object> reply = post(endpoints.token(), form);
            String error = Json.string(reply, "error");
            if (error == null) {
                synchronized (LOCK) {
                    Properties tokens = load(home);
                    store(tokens, remote, reply);
                    if (tokens.getProperty(remote + ".refresh-token") == null) {
                        throw new IOException("the provider gave no refresh token; check the app's settings");
                    }
                    save(home, tokens);
                }
                return;
            }
            switch (error) {
                case "authorization_pending" -> {
                    // Keep waiting.
                }
                case "slow_down" -> interval += 5000;
                case "access_denied", "authorization_declined" -> throw new IOException("linking was declined.");
                case "expired_token" -> throw new IOException("the code ran out before it was entered. Try again.");
                default -> throw new IOException("linking failed (" + error + ": " + Json.string(reply, "error_description") + ")");
            }
        }
        throw new IOException("the code ran out before it was entered. Try again.");
    }

    // ---------------------------------------------------------------- Dropbox

    /** The link the owner opens to approve Dropbox; remembers the PKCE verifier for {@link #dropboxCode}. */
    public static String dropboxUrl(Path home, String remote, XBConfig.Remote settings) throws IOException {
        byte[] random = new byte[48];
        new SecureRandom().nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String challenge;
        try {
            challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        synchronized (LOCK) {
            Properties tokens = load(home);
            tokens.setProperty(remote + ".verifier", verifier);
            save(home, tokens);
        }
        return ENDPOINTS.get("dropbox").authorize() + "?client_id=" + encode(clientId(remote, settings))
            + "&response_type=code&token_access_type=offline&code_challenge_method=S256&code_challenge=" + challenge;
    }

    /** Swaps the code Dropbox showed for a refresh token. */
    public static void dropboxCode(Path home, String remote, XBConfig.Remote settings, String code) throws IOException {
        synchronized (LOCK) {
            Properties tokens = load(home);
            String verifier = tokens.getProperty(remote + ".verifier");
            if (verifier == null) {
                throw new IOException("start with /backup link " + remote + " to get the link first.");
            }
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", code.strip());
            form.put("client_id", clientId(remote, settings));
            form.put("code_verifier", verifier);
            Map<String, Object> reply = post(ENDPOINTS.get("dropbox").token(), form);
            String error = Json.string(reply, "error");
            if (error != null) {
                throw new IOException("Dropbox refused the code (" + error + ": " + Json.string(reply, "error_description")
                    + "). Codes work once and soon run out; run /backup link " + remote + " again.");
            }
            store(tokens, remote, reply);
            tokens.remove(remote + ".verifier");
            save(home, tokens);
        }
    }

    // ---------------------------------------------------------------- HTTP

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> post(String url, Map<String, String> form) throws IOException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> field : form.entrySet()) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(encode(field.getKey())).append('=').append(encode(field.getValue()));
        }
        HttpResponse<byte[]> response = Http.retry(() -> {
            HttpResponse<byte[]> r = Http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
            if (r.statusCode() >= 500) {
                throw new Http.Transient("HTTP " + r.statusCode() + " from " + URI.create(url).getHost());
            }
            return r;
        });
        String text = Http.utf8(response.body());
        try {
            return Json.object(text);
        } catch (IOException e) {
            throw new IOException("HTTP " + response.statusCode() + " from " + URI.create(url).getHost()
                + " with a reply that isn't JSON");
        }
    }
}
