package co.xenastudios.xbackup.remote;

import co.xenastudios.xbackup.api.StorageBackend;
import co.xenastudios.xbackup.config.XBConfig;
import co.xenastudios.xbackup.store.Backend;
import co.xenastudios.xbackup.store.LocalBackend;
import co.xenastudios.xbackup.store.Throttle;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Opens a remote from its config: the backends xBackup ships, and any another plugin
 * registered through the API.
 */
public final class Remotes {

    /** OAuth tokens for the cloud drives, owner-only, beside config.yml. */
    public static final String TOKENS = "tokens.properties";

    private Remotes() {}

    /**
     * What a remote needs from its surroundings.
     *
     * @param home xBackup's folder, for keys, tokens and downloaded libraries
     * @param upload the shared upload limit
     * @param registered backends other plugins registered, by type
     */
    public record Context(Path home, Throttle upload, Logger logger, Function<String, Registered> registered) {}

    /**
     * A backend another plugin registered.
     *
     * @param enabled whether that plugin is still enabled
     */
    public record Registered(java.util.function.BooleanSupplier enabled, StorageBackend backend) {}

    /** The types xBackup ships, for messages and completions. */
    public static final List<String> TYPES = List.of("local", "s3", "webdav", "sftp", "ftp", "drive", "onedrive", "dropbox");

    /**
     * Opens the remote. Network backends connect lazily, on first use.
     *
     * @throws IOException with a message naming the remote, if it can't be set up
     */
    public static Backend open(String name, XBConfig.Remote settings, Context context) throws IOException {
        String type = settings.type().strip().toLowerCase(Locale.ROOT);
        String prefix = cleanPrefix(settings.path());
        try {
            return switch (type) {
                case "local", "folder", "disk" -> {
                    Path folder = Path.of(settings.path().isBlank() ? "backups" : settings.path().strip());
                    if (!folder.isAbsolute()) {
                        folder = Path.of("").toAbsolutePath().resolve(folder);
                    }
                    yield new LocalBackend(folder);
                }
                case "s3" -> new S3Backend(name, settings, prefix, context.upload());
                case "webdav", "dav" -> new WebDavBackend(name, settings, context.upload());
                case "sftp" -> Libraries.sftp(name, settings, prefix, context);
                case "ftp", "ftps" -> Libraries.ftp(name, settings, prefix, context);
                case "drive", "onedrive", "dropbox" -> CloudDrives.open(name, type, settings, prefix, context);
                default -> {
                    Registered registered = context.registered().apply(type);
                    if (registered == null || !registered.enabled().getAsBoolean()) {
                        throw new IOException("remote " + name + " has type \"" + settings.type() + "\", which isn't one"
                            + " xBackup knows (" + String.join(", ", TYPES) + ") or one another plugin added.");
                    }
                    yield adapt(registered.backend());
                }
            };
        } catch (IllegalArgumentException e) {
            throw new IOException("remote " + name + ": " + e.getMessage(), e);
        }
    }

    /** "/backups/server1/" becomes "backups/server1". */
    static String cleanPrefix(String path) {
        String clean = path.strip().replace('\\', '/');
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    static String required(String value, String what, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("set " + what + " for remote " + name + ".");
        }
        return value.strip();
    }

    /** A plugin's {@link StorageBackend} as the store's {@link Backend}. */
    static Backend adapt(StorageBackend storage) {
        return new Backend() {
            @Override
            public String describe() {
                return storage.describe();
            }

            @Override
            public void put(String name, Path file) throws IOException {
                storage.put(Backend.checkName(name), file);
            }

            @Override
            public void put(String name, byte[] data) throws IOException {
                Path temp = Files.createTempFile("xbackup", ".part");
                try {
                    Files.write(temp, data);
                    storage.put(Backend.checkName(name), temp);
                } finally {
                    Files.deleteIfExists(temp);
                }
            }

            @Override
            public InputStream get(String name) throws IOException {
                return storage.get(Backend.checkName(name));
            }

            @Override
            public byte[] read(String name, long offset, int length) throws IOException {
                try (InputStream in = storage.get(Backend.checkName(name))) {
                    in.skipNBytes(offset);
                    byte[] bytes = in.readNBytes(length);
                    if (bytes.length != length) {
                        throw new java.io.EOFException(name + " is shorter than expected");
                    }
                    return bytes;
                }
            }

            @Override
            public List<FileInfo> list(String prefix) throws IOException {
                List<FileInfo> files = new ArrayList<>();
                for (StorageBackend.StoredFile file : storage.list(prefix)) {
                    files.add(new FileInfo(file.name(), file.size()));
                }
                return files;
            }

            @Override
            public void delete(String name) throws IOException {
                storage.delete(Backend.checkName(name));
            }
        };
    }
}
