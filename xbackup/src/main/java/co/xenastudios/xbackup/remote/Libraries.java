package co.xenastudios.xbackup.remote;

import co.xenastudios.xbackup.store.Backend;
import co.xenastudios.xbackup.store.Hash;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The SFTP and FTP libraries, fetched the first time a remote needs them, never shaded into
 * the jar. Each is one dependency-free jar from Maven Central (Google's mirror of it first,
 * then Maven Central itself), checked against the SHA-256 written here before it's used, and
 * kept in
 * {@code plugins/xBackup/libraries/}. A host that blocks the download gets one clear error
 * naming the remote; nothing else in xBackup is affected.
 *
 * <p>Fetched by xBackup itself rather than Paper's library loader so a remote added on
 * {@code /xbackup reload} works without a restart, and so the command-line restore, which
 * runs without a server, can use them too.
 *
 * <p>The code that uses a library ({@code remote.lib}) is loaded by its own class loader,
 * from xBackup's jar, with the library's classes from the library's jar, so the rest of
 * xBackup never touches them.
 */
public final class Libraries {

    /** A jar on Maven Central, pinned by version and hash. */
    public record Library(String group, String artifact, String version, String sha256, String packageName) {
        String path() {
            return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
        }

        String fileName() {
            return artifact + "-" + version + ".jar";
        }
    }

    /** JSch (mwiede's maintained fork): SSH and SFTP, pure Java, no dependencies. BSD. */
    public static final Library JSCH = new Library("com.github.mwiede", "jsch", "2.28.8",
        "2afca1357d816028f2ac33f22407d2488a68406682973949cebb1b8ea6688a8b", "com.jcraft.jsch.");

    /** Apache Commons Net: FTP and FTPS, no dependencies. Apache 2.0. */
    public static final Library COMMONS_NET = new Library("commons-net", "commons-net", "3.13.0",
        "9e88444afadc9467f0bf3d328f93b9538da35a9be05cdee430766e64fddbd6c5", "org.apache.commons.net.");

    private static final List<String> MIRRORS = List.of(
        "https://maven-central.storage-download.googleapis.com/maven2/",
        "https://repo1.maven.org/maven2/");
    private static final String OWN = "co.xenastudios.xbackup.remote.lib.";
    private static final Map<Library, ClassLoader> LOADERS = new ConcurrentHashMap<>();

    private Libraries() {}

    static Backend sftp(String name, RemoteConfig settings, String prefix, Remotes.Context context) throws IOException {
        return create(JSCH, "SftpBackend", name, settings, prefix, context);
    }

    static Backend ftp(String name, RemoteConfig settings, String prefix, Remotes.Context context) throws IOException {
        return create(COMMONS_NET, "FtpBackend", name, settings, prefix, context);
    }

    private static Backend create(Library library, String type, String name, RemoteConfig settings, String prefix,
                                  Remotes.Context context) throws IOException {
        ClassLoader loader = loader(library, name, context);
        try {
            Class<?> backend = Class.forName(OWN + type, true, loader);
            return (Backend) backend.getConstructor(String.class, RemoteConfig.class, String.class, Remotes.Context.class)
                .newInstance(name, settings, prefix, context);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IOException("remote " + name + " couldn't start: " + cause, cause);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IOException("remote " + name + " couldn't load " + library.artifact() + ": " + e, e);
        }
    }

    private static ClassLoader loader(Library library, String name, Remotes.Context context) throws IOException {
        ClassLoader existing = LOADERS.get(library);
        if (existing != null) {
            return existing;
        }
        // Already on the class path (tests): no download needed.
        try {
            Class.forName(library.packageName() + (library == JSCH ? "JSch" : "ftp.FTPClient"), false,
                Libraries.class.getClassLoader());
            LOADERS.put(library, Libraries.class.getClassLoader());
            return Libraries.class.getClassLoader();
        } catch (ClassNotFoundException e) {
            // Fetch it.
        }
        Path jar = fetch(library, context.home().resolve("libraries"), name);
        URL own;
        try {
            own = Libraries.class.getProtectionDomain().getCodeSource().getLocation();
        } catch (SecurityException | NullPointerException e) {
            throw new IOException("remote " + name + ": can't find xBackup's own jar to load " + library.artifact() + ".", e);
        }
        ClassLoader loader = isolated(own, jar.toUri().toURL(), library);
        LOADERS.put(library, loader);
        return loader;
    }

    /**
     * A class loader for the code that uses the library: xBackup's own {@code remote.lib}
     * classes only ever from xBackup's jar, the library's only from its jar, everything else
     * from xBackup's loader.
     */
    static ClassLoader isolated(URL own, URL library, Library which) {
        return new Isolated(own, library, Libraries.class.getClassLoader(), which.packageName());
    }

    /** Downloads the library once and checks its hash; a jar already there is checked too. */
    static Path fetch(Library library, Path folder, String remote) throws IOException {
        return fetch(library, folder, remote, MIRRORS);
    }

    static Path fetch(Library library, Path folder, String remote, List<String> mirrors) throws IOException {
        Path jar = folder.resolve(library.fileName());
        if (Files.exists(jar) && sha256(jar).equals(library.sha256())) {
            return jar;
        }
        Files.createDirectories(folder);
        IOException last = null;
        for (String mirror : mirrors) {
            Path part = folder.resolve(library.fileName() + ".part");
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(mirror + library.path()))
                    .timeout(Duration.ofMinutes(2)).GET().build();
                HttpResponse<Path> response = Http.await(request, HttpResponse.BodyHandlers.ofFile(part));
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " from " + URI.create(mirror).getHost());
                }
                String actual = sha256(part);
                if (!actual.equals(library.sha256())) {
                    throw new IOException("the download from " + URI.create(mirror).getHost()
                        + " didn't match its expected SHA-256, so it wasn't used");
                }
                Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return jar;
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("stopped");
            } finally {
                Files.deleteIfExists(part);
            }
        }
        throw new IOException("remote " + remote + " needs " + library.artifact() + " " + library.version()
            + ", which couldn't be downloaded (" + (last == null ? "no mirror" : last.getMessage()) + "). If this host"
            + " blocks downloads, put " + library.fileName() + " from Maven Central in " + folder + ".", last);
    }

    static String sha256(Path file) throws IOException {
        java.security.MessageDigest digest = Hash.digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Loads xBackup's code that uses the library from xBackup's own jar, and the library's
     * classes from the library's jar, never the other way round: a library jar can't stand in
     * for xBackup's classes. Everything else (the store, the config) comes from xBackup's
     * loader, so both sides agree on the {@link Backend} interface.
     */
    private static final class Isolated extends ClassLoader {
        private final URLClassLoader own;
        private final URLClassLoader library;
        private final String libraryPackage;

        Isolated(URL own, URL library, ClassLoader parent, String libraryPackage) {
            super(parent);
            // Each has no parent: they only find what's in their one jar.
            this.own = new URLClassLoader(new URL[] {own}, null);
            this.library = new URLClassLoader(new URL[] {library}, null);
            this.libraryPackage = libraryPackage;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            boolean ours = name.startsWith(OWN);
            if (!ours && !name.startsWith(libraryPackage)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    String resource = name.replace('.', '/') + ".class";
                    try (InputStream in = (ours ? own : library).getResourceAsStream(resource)) {
                        if (in == null) {
                            throw new ClassNotFoundException(name);
                        }
                        byte[] bytes = in.readAllBytes();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        @Override
        protected URL findResource(String name) {
            URL found = library.findResource(name);
            return found != null ? found : own.findResource(name);
        }
    }
}
