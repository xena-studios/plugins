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
        FTPClient client = security.equals("none") ? new FTPClient() : new FTPSClient("TLS", security.equals("implicit"));
        client.setConnectTimeout(20_000);
        client.setDefaultTimeout(60_000);
        client.setControlKeepAliveTimeout(Duration.ofSeconds(30));
        try {
            client.connect(host, port);
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
        try (InputStream in = context.upload().wrap(Files.newInputStream(source))) {
            upload(file, in, size);
        }
    }

    @Override
    public synchronized void put(String file, byte[] data) throws IOException {
        upload(file, new ByteArrayInputStream(data), data.length);
    }

    private void upload(String file, InputStream in, long size) throws IOException {
        String target = path(Backend.checkName(file));
        String temp = target + ".part-" + UUID.randomUUID().toString().substring(0, 8);
        FTPClient client = client();
        makeFolders(client, target);
        if (!client.storeFile(temp, in)) {
            throw failed("uploading " + file, client);
        }
        if (!client.rename(temp, target)) {
            client.deleteFile(target);
            if (!client.rename(temp, target)) {
                throw failed("moving " + file + " into place", client);
            }
        }
        long stored = size(client, target);
        if (stored >= 0 && stored != size) {
            throw new IOException("remote " + name + " holds " + stored + " bytes of " + file + " after uploading " + size
                + "; it will be sent again next time.");
        }
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
