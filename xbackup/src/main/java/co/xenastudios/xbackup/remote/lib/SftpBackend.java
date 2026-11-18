package co.xenastudios.xbackup.remote.lib;

import co.xenastudios.xbackup.config.XBConfig;
import co.xenastudios.xbackup.remote.Remotes;
import co.xenastudios.xbackup.store.Backend;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * SFTP through JSch. One connection, opened on first use and kept for the backend's life.
 * Uploads go to a temporary name and are renamed into place, then their size is checked.
 *
 * <p>The server's key is checked on every connection: against {@code host-key} if it's set,
 * otherwise against the key seen the first time, remembered in {@code known-hosts.properties}.
 */
public final class SftpBackend implements Backend {

    private final String name;
    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final Path privateKey;
    private final String hostKey;
    private final String root;
    private final Remotes.Context context;
    private Session session;
    private ChannelSftp sftp;

    public SftpBackend(String name, XBConfig.Remote settings, String prefix, Remotes.Context context) {
        this.name = name;
        this.host = require(settings.host(), "host", name);
        this.port = settings.port() == 0 ? 22 : settings.port();
        this.user = require(settings.username(), "username", name);
        this.password = settings.password();
        this.privateKey = settings.privateKey().isBlank() ? null : context.home().resolve(settings.privateKey().strip());
        if (password.isBlank() && privateKey == null) {
            throw new IllegalArgumentException("set password or private-key for remote " + name + ".");
        }
        this.hostKey = settings.hostKey().strip();
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
        return "SFTP " + user + "@" + host + ":" + port + "/" + root;
    }

    private String path(String file) {
        return root.isEmpty() ? file : root + "/" + file;
    }

    private synchronized ChannelSftp channel() throws IOException {
        if (sftp != null && sftp.isConnected() && session.isConnected()) {
            return sftp;
        }
        closeQuietly();
        try {
            JSch jsch = new JSch();
            jsch.setHostKeyRepository(new Keys());
            if (privateKey != null) {
                if (!Files.exists(privateKey)) {
                    throw new IOException("remote " + name + ": the private key " + privateKey + " doesn't exist.");
                }
                jsch.addIdentity(privateKey.toString(), password.isBlank() ? null : password.getBytes(StandardCharsets.UTF_8));
            }
            session = jsch.getSession(user, host, port);
            if (privateKey == null) {
                session.setPassword(password.getBytes(StandardCharsets.UTF_8));
            }
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications", privateKey == null ? "password,keyboard-interactive" : "publickey");
            session.setUserInfo(new Quiet(password));
            session.setServerAliveInterval(30_000);
            session.connect(20_000);
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(20_000);
            return sftp;
        } catch (JSchException e) {
            closeQuietly();
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            if (message.contains("HostKey has been changed") || message.contains("reject HostKey")) {
                throw new IOException("remote " + name + ": the server's key isn't the one expected. If it really"
                    + " changed, update host-key (or remove its line from known-hosts.properties).", e);
            }
            if (message.contains("Auth fail") || message.contains("Auth cancel")) {
                throw new IOException("remote " + name + ": the server refused the login. Check username and password"
                    + " or private-key.", e);
            }
            throw new IOException("remote " + name + ": couldn't connect to " + host + ":" + port + " (" + message + ")", e);
        }
    }

    private void closeQuietly() {
        if (sftp != null) {
            sftp.disconnect();
        }
        if (session != null) {
            session.disconnect();
        }
        sftp = null;
        session = null;
    }

    // Runs an operation, reconnecting once if the connection dropped.
    private interface Operation<T> {
        T run(ChannelSftp channel) throws SftpException, IOException;
    }

    private synchronized <T> T run(Operation<T> operation) throws IOException {
        for (int attempt = 0; ; attempt++) {
            ChannelSftp channel = channel();
            try {
                return operation.run(channel);
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    throw new NoSuchFileException(e.getMessage());
                }
                if (attempt == 0 && (e.id == ChannelSftp.SSH_FX_CONNECTION_LOST || e.id == ChannelSftp.SSH_FX_NO_CONNECTION
                    || !channel.isConnected())) {
                    closeQuietly();
                    continue;
                }
                throw new IOException("remote " + name + ": " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void put(String file, Path source) throws IOException {
        long size = Files.size(source);
        try (InputStream in = context.upload().wrap(Files.newInputStream(source))) {
            upload(file, in, size);
        }
    }

    @Override
    public void put(String file, byte[] data) throws IOException {
        upload(file, new ByteArrayInputStream(data), data.length);
    }

    private void upload(String file, InputStream in, long size) throws IOException {
        String target = path(Backend.checkName(file));
        String temp = target + ".part-" + UUID.randomUUID().toString().substring(0, 8);
        run(channel -> {
            makeFolders(channel, target);
            channel.put(in, temp, ChannelSftp.OVERWRITE);
            try {
                channel.rename(temp, target);
            } catch (SftpException e) {
                // SFTP v3 can't rename over a file. Content-named files are the same bytes either way.
                channel.rm(target);
                channel.rename(temp, target);
            }
            SftpATTRS attributes = channel.stat(target);
            if (attributes.getSize() != size) {
                throw new IOException("remote " + name + " holds " + attributes.getSize() + " bytes of " + file
                    + " after uploading " + size + "; it will be sent again next time.");
            }
            return null;
        });
    }

    private void makeFolders(ChannelSftp channel, String target) throws SftpException {
        int slash = target.lastIndexOf('/');
        if (slash <= 0) {
            return;
        }
        String folder = target.substring(0, slash);
        try {
            channel.stat(folder);
            return;
        } catch (SftpException e) {
            if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                throw e;
            }
        }
        StringBuilder path = new StringBuilder();
        for (String part : folder.split("/")) {
            if (part.isEmpty()) {
                path.append('/');
                continue;
            }
            path.append(part);
            try {
                channel.stat(path.toString());
            } catch (SftpException e) {
                channel.mkdir(path.toString());
            }
            path.append('/');
        }
    }

    @Override
    public InputStream get(String file) throws IOException {
        byte[] data = run(channel -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            channel.get(path(Backend.checkName(file)), out);
            return out.toByteArray();
        });
        return new ByteArrayInputStream(data);
    }

    @Override
    public byte[] read(String file, long offset, int length) throws IOException {
        return run(channel -> {
            try (InputStream in = channel.get(path(Backend.checkName(file)), null, offset)) {
                byte[] bytes = in.readNBytes(length);
                if (bytes.length != length) {
                    throw new EOFException(file + " is shorter than " + (offset + length) + " bytes");
                }
                return bytes;
            }
        });
    }

    @Override
    public List<FileInfo> list(String prefix) throws IOException {
        return run(channel -> {
            List<FileInfo> files = new ArrayList<>();
            int slash = prefix.lastIndexOf('/');
            Deque<String> folders = new ArrayDeque<>();
            folders.add(slash < 0 ? "" : prefix.substring(0, slash));
            while (!folders.isEmpty()) {
                String folder = folders.poll();
                String remote = folder.isEmpty() ? (root.isEmpty() ? "." : root) : path(folder);
                List<ChannelSftp.LsEntry> entries = new ArrayList<>();
                try {
                    channel.ls(remote, entry -> {
                        entries.add(entry);
                        return ChannelSftp.LsEntrySelector.CONTINUE;
                    });
                } catch (SftpException e) {
                    if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                        continue;
                    }
                    throw e;
                }
                for (ChannelSftp.LsEntry entry : entries) {
                    String child = entry.getFilename();
                    if (child.equals(".") || child.equals("..")) {
                        continue;
                    }
                    String relative = folder.isEmpty() ? child : folder + "/" + child;
                    if (entry.getAttrs().isDir()) {
                        folders.add(relative);
                    } else if (relative.startsWith(prefix) && !child.contains(".part-")) {
                        files.add(new FileInfo(relative, entry.getAttrs().getSize()));
                    }
                }
            }
            return files;
        });
    }

    @Override
    public void delete(String file) throws IOException {
        try {
            run(channel -> {
                channel.rm(path(Backend.checkName(file)));
                return null;
            });
        } catch (NoSuchFileException e) {
            // Already gone.
        }
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }

    /** The server's key: the configured fingerprint, or the one remembered from the first connection. */
    private final class Keys implements HostKeyRepository {

        private Path file() {
            return context.home().resolve("known-hosts.properties");
        }

        @Override
        public int check(String hostName, byte[] key) {
            String fingerprint = fingerprint(key);
            if (!hostKey.isEmpty()) {
                String expected = hostKey.startsWith("SHA256:") ? hostKey : "SHA256:" + hostKey;
                return expected.replaceAll("=+$", "").equals(fingerprint) ? OK : CHANGED;
            }
            try {
                Properties known = new Properties();
                if (Files.exists(file())) {
                    known.load(new StringReader(Files.readString(file(), StandardCharsets.UTF_8)));
                }
                String id = host + ":" + port;
                String remembered = known.getProperty(id);
                if (remembered == null) {
                    known.setProperty(id, fingerprint);
                    StringWriter writer = new StringWriter();
                    known.store(writer, "SFTP servers' keys, remembered from the first connection. Remove a line if a"
                        + " server's key really changed.");
                    Files.writeString(file(), writer.toString(), StandardCharsets.UTF_8);
                    context.logger().info("Remote " + name + ": trusting " + id + "'s key " + fingerprint
                        + " from now on (first connection).");
                    return OK;
                }
                return remembered.equals(fingerprint) ? OK : CHANGED;
            } catch (IOException e) {
                return CHANGED;
            }
        }

        private String fingerprint(byte[] key) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
                return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void add(HostKey key, UserInfo info) {}

        @Override
        public void remove(String host, String type) {}

        @Override
        public void remove(String host, String type, byte[] key) {}

        @Override
        public String getKnownHostsRepositoryID() {
            return "xBackup";
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }
    }

    /** Answers JSch's prompts without asking anyone. */
    private record Quiet(String password) implements UserInfo, com.jcraft.jsch.UIKeyboardInteractive {
        @Override
        public String getPassphrase() {
            return password;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public boolean promptPassword(String message) {
            return !password.isBlank();
        }

        @Override
        public boolean promptPassphrase(String message) {
            return !password.isBlank();
        }

        @Override
        public boolean promptYesNo(String message) {
            return false;
        }

        @Override
        public void showMessage(String message) {}

        @Override
        public String[] promptKeyboardInteractive(String destination, String name, String instruction, String[] prompt,
                                                  boolean[] echo) {
            String[] answers = new String[prompt.length];
            java.util.Arrays.fill(answers, password);
            return answers;
        }
    }
}
