package co.xenastudios.xbackup.remote.lib;

import co.xenastudios.xbackup.remote.RemoteConfig;
import co.xenastudios.xbackup.remote.Remotes;
import co.xenastudios.xbackup.store.Backend;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

/**
 * FTP and FTPS through Apache Commons Net: passive mode, binary, one connection kept open.
 * Uploads go to a temporary name and are renamed into place, then their size is checked.
 * {@code security: explicit} (the default) upgrades with AUTH TLS and protects the data
 * connection too; {@code implicit} is TLS from the start, usually on port 990.
 */
public final class FtpBackend implements Backend {

    private final String name;
    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String security;
    private final String root;
    private final Remotes.Context context;
    private final byte[] pin;
    private FTPClient ftp;

    public FtpBackend(String name, RemoteConfig settings, String prefix, Remotes.Context context) {
        this.name = name;
        this.host = require(settings.host(), "host", name);
        this.security = settings.security().isBlank() ? "explicit" : settings.security().strip().toLowerCase(Locale.ROOT);
        if (!List.of("none", "explicit", "implicit").contains(security)) {
            throw new IllegalArgumentException("security must be none, explicit or implicit for remote " + name + ".");
        }
        this.port = settings.port() != 0 ? settings.port() : security.equals("implicit") ? 990 : 21;
        this.user = require(settings.username(), "username", name);
        this.password = settings.password();
        this.root = prefix;
        this.context = context;
        this.pin = settings.hostKey().isBlank() ? null : fingerprint(settings.hostKey(), name);
        if (pin != null && security.equals("none")) {
            throw new IllegalArgumentException("host-key is a TLS certificate's fingerprint, but remote " + name
                + " has security: none.");
        }
    }

    /**
     * A certificate fingerprint as owners copy it: {@code SHA256:} and then base64 (as OpenSSH
     * prints keys), or hex with or without colons (as {@code openssl x509 -fingerprint
     * -sha256} prints certificates).
     */
    static byte[] fingerprint(String text, String name) {
        String value = text.strip();
        if (value.regionMatches(true, 0, "SHA256:", 0, 7)) {
            value = value.substring(7);
        }
        String hex = value.replace(":", "");
        if (hex.length() == 64 && hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            return java.util.HexFormat.of().parseHex(hex.toLowerCase(Locale.ROOT));
        }
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(value.endsWith("=") ? value : value + "=".repeat((4 - value.length() % 4) % 4));
            if (bytes.length == 32) {
                return bytes;
            }
        } catch (IllegalArgumentException e) {
            // Not base64 either.
        }
        throw new IllegalArgumentException("host-key for remote " + name + " isn't a SHA-256 fingerprint (SHA256:... or"
            + " 64 hex digits).");
    }

    static String fingerprint(java.security.cert.X509Certificate certificate) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(hash);
        } catch (java.security.GeneralSecurityException e) {
            return "unknown";
        }
    }

    /**
     * The certificates this remote trusts. With a pin (host-key), only the certificate with
     * that fingerprint, which suits a self-signed one. Without, whatever the JVM trusts, for
     * the host name connected to (checked by {@code setEndpointCheckingEnabled}). Commons Net's
     * default would accept any certificate that's in date.
     */
    static javax.net.ssl.X509TrustManager trust(byte[] pin, String name) throws IOException {
        if (pin != null) {
            return new javax.net.ssl.X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
                    throw new java.security.cert.CertificateException("not a server");
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
                    if (chain == null || chain.length == 0) {
                        throw new java.security.cert.CertificateException("the server sent no certificate");
                    }
                    String seen = fingerprint(chain[0]);
                    try {
                        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                        if (!java.security.MessageDigest.isEqual(hash, pin)) {
                            throw new java.security.cert.CertificateException("the server's certificate is " + seen
                                + ", not the host-key set for remote " + name);
                        }
                    } catch (java.security.NoSuchAlgorithmException e) {
                        throw new java.security.cert.CertificateException(e);
                    }
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            };
        }
        javax.net.ssl.X509TrustManager system;
        try {
            javax.net.ssl.TrustManagerFactory factory = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            factory.init((java.security.KeyStore) null);
            system = null;
            for (javax.net.ssl.TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof javax.net.ssl.X509TrustManager x509) {
                    system = x509;
                }
            }
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("Java's trusted certificates can't be loaded: " + e.getMessage(), e);
        }
        if (system == null) {
            throw new IOException("Java has no trusted certificates to check the server's against.");
        }
        javax.net.ssl.X509TrustManager checked = system;
        return new javax.net.ssl.X509TrustManager() {
            @Override
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
                checked.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
                try {
                    checked.checkServerTrusted(chain, authType);
                } catch (java.security.cert.CertificateException e) {
                    String seen = chain == null || chain.length == 0 ? "" : " Its fingerprint is " + fingerprint(chain[0])
                        + "; if it's your server's own (self-signed) certificate, set host-key to that.";
                    throw new java.security.cert.CertificateException("Java doesn't trust the server's certificate ("
                        + e.getMessage() + ")." + seen, e);
                }
            }

            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return checked.getAcceptedIssuers();
            }
        };
    }

    private static String require(String value, String what, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("set " + what + " for remote " + name + ".");
        }
        return value.strip();
    }

    @Override
    public String describe() {
        return (security.equals("none") ? "FTP " : "FTPS ") + user + "@" + host + ":" + port + "/" + root;
    }

    private String path(String file) {
        return root.isEmpty() ? file : root + "/" + file;
    }

    private synchronized FTPClient client() throws IOException {
        if (ftp != null && ftp.isConnected()) {
            try {
                if (ftp.sendNoOp()) {
                    return ftp;
                }
            } catch (IOException e) {
                // Reconnect below.
            }
        }
        closeQuietly();
        FTPClient client;
        if (security.equals("none")) {
            client = new FTPClient();
        } else {
            FTPSClient secure = new FTPSClient("TLS", security.equals("implicit"));
            secure.setTrustManager(trust(pin, name));
            // The host name is checked against the certificate, unless it's pinned by fingerprint.
            secure.setEndpointCheckingEnabled(pin == null);
            client = secure;
        }
        client.setConnectTimeout(20_000);
        client.setDefaultTimeout(60_000);
        // A transfer that stops moving fails instead of holding the only worker for good.
        client.setDataTimeout(Duration.ofSeconds(60));
        client.setControlKeepAliveTimeout(Duration.ofSeconds(30));
        client.setControlKeepAliveReplyTimeout(Duration.ofSeconds(30));
        try {
            client.connect(host, port);
            client.setSoTimeout(60_000);
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                throw new IOException("the server said " + client.getReplyString().strip());
            }
            if (client instanceof FTPSClient secure) {
                secure.execPBSZ(0);
                secure.execPROT("P");
            }
            if (!client.login(user, password)) {
                throw new IOException("the server refused the login. Check username, and password in secrets.yml");
            }
            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);
        } catch (IOException e) {
            try {
                client.disconnect();
            } catch (IOException ignored) {
                // Already failing.
            }
            throw new IOException("remote " + name + ": couldn't connect to " + host + ":" + port + " (" + e.getMessage() + ")", e);
        }
        ftp = client;
        return client;
    }

    private void closeQuietly() {
        if (ftp != null) {
            try {
                ftp.logout();
            } catch (IOException ignored) {
                // Closing anyway.
            }
            try {
                ftp.disconnect();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
        ftp = null;
    }

    private IOException failed(String what, FTPClient client) {
        return new IOException("remote " + name + ": " + what + " failed: " + client.getReplyString().strip());
    }

    @Override
    public synchronized void put(String file, Path source) throws IOException {
        long size = Files.size(source);
        upload(file, () -> context.upload().wrap(Files.newInputStream(source)), size);
    }

    @Override
    public synchronized void put(String file, byte[] data) throws IOException {
        upload(file, () -> new ByteArrayInputStream(data), data.length);
    }

    private interface Source {
        InputStream open() throws IOException;
    }

    private void upload(String file, Source source, long size) throws IOException {
        String target = path(Backend.checkName(file));
        String temp = target + ".part-" + UUID.randomUUID().toString().substring(0, 8);
        FTPClient client = client();
        try {
            makeFolders(client, target);
            try (InputStream in = source.open()) {
                if (!client.storeFile(temp, in)) {
                    throw failed("uploading " + file, client);
                }
            }
            // Checked under the temporary name, so a cut-off copy never takes the real name.
            long stored = storedSize(client, temp);
            if (stored != size) {
                throw new IOException("remote " + name + " holds " + stored + " bytes of " + file + " after uploading "
                    + size + "; it will be sent again next time.");
            }
            if (!client.rename(temp, target)) {
                client.deleteFile(target);
                if (!client.rename(temp, target)) {
                    throw failed("moving " + file + " into place", client);
                }
            }
        } catch (IOException e) {
            try {
                if (ftp != null && ftp.isConnected()) {
                    ftp.deleteFile(temp);
                }
            } catch (IOException ignored) {
                // Listings skip .part- files.
            }
            throw e;
        }
    }

    // A file's size: SIZE if the server has it, else MLST, else a listing of just that file.
    // Never assumed: an upload that can't be checked isn't put in place.
    private long storedSize(FTPClient client, String path) throws IOException {
        long size = size(client, path);
        if (size >= 0) {
            return size;
        }
        try {
            FTPFile file = client.mlistFile(path);
            if (file != null && file.getSize() >= 0) {
                return file.getSize();
            }
        } catch (IOException e) {
            // Try a listing.
        }
        FTPFile[] files = client.listFiles(path);
        if (files != null && files.length == 1 && files[0] != null && files[0].getSize() >= 0) {
            return files[0].getSize();
        }
        throw new IOException("remote " + name + ": the server won't say how big " + path + " is (no SIZE, MLST or LIST),"
            + " so the upload can't be checked and isn't used.");
    }

    private long size(FTPClient client, String path) throws IOException {
        String reply = client.getSize(path);
        if (reply == null) {
            return -1;
        }
        try {
            return Long.parseLong(reply.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void makeFolders(FTPClient client, String target) throws IOException {
        int slash = target.lastIndexOf('/');
        if (slash <= 0) {
            return;
        }
        StringBuilder path = new StringBuilder();
        for (String part : target.substring(0, slash).split("/")) {
            if (part.isEmpty()) {
                path.append('/');
                continue;
            }
            path.append(part);
            // Fails harmlessly if it's already there.
            client.makeDirectory(path.toString());
            path.append('/');
        }
    }

    @Override
    public synchronized InputStream get(String file) throws IOException {
        FTPClient client = client();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        client.setRestartOffset(0);
        if (!client.retrieveFile(path(Backend.checkName(file)), out)) {
            if (client.getReplyCode() == 550) {
                throw new NoSuchFileException(file);
            }
            throw failed("downloading " + file, client);
        }
        return new ByteArrayInputStream(out.toByteArray());
    }

    @Override
    public synchronized byte[] read(String file, long offset, int length) throws IOException {
        FTPClient client = client();
        client.setRestartOffset(offset);
        byte[] bytes;
        try (InputStream in = client.retrieveFileStream(path(Backend.checkName(file)))) {
            if (in == null) {
                if (client.getReplyCode() == 550) {
                    throw new NoSuchFileException(file);
                }
                throw failed("reading " + file, client);
            }
            bytes = in.readNBytes(length);
        } finally {
            client.setRestartOffset(0);
        }
        // Closing early aborts the transfer; the server's reply to that is expected.
        client.completePendingCommand();
        if (bytes.length != length) {
            throw new EOFException(file + " is shorter than " + (offset + length) + " bytes");
        }
        return bytes;
    }

    @Override
    public synchronized List<FileInfo> list(String prefix) throws IOException {
        FTPClient client = client();
        List<FileInfo> files = new ArrayList<>();
        int slash = prefix.lastIndexOf('/');
        Deque<String> folders = new ArrayDeque<>();
        folders.add(slash < 0 ? "" : prefix.substring(0, slash));
        while (!folders.isEmpty()) {
            String folder = folders.poll();
            String remote = folder.isEmpty() ? root : path(folder);
            FTPFile[] entries = remote.isEmpty() ? client.listFiles() : client.listFiles(remote);
            if (entries == null) {
                continue;
            }
            for (FTPFile entry : entries) {
                if (entry == null || entry.getName().equals(".") || entry.getName().equals("..")) {
                    continue;
                }
                String child = entry.getName();
                // Some servers list full paths.
                int cut = child.lastIndexOf('/');
                if (cut >= 0) {
                    child = child.substring(cut + 1);
                }
                String relative = folder.isEmpty() ? child : folder + "/" + child;
                if (entry.isDirectory()) {
                    folders.add(relative);
                } else if (relative.startsWith(prefix) && !child.contains(".part-")) {
                    files.add(new FileInfo(relative, entry.getSize()));
                }
            }
        }
        return files;
    }

    @Override
    public synchronized void delete(String file) throws IOException {
        FTPClient client = client();
        if (!client.deleteFile(path(Backend.checkName(file))) && client.getReplyCode() != 550) {
            throw failed("deleting " + file, client);
        }
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }
}
