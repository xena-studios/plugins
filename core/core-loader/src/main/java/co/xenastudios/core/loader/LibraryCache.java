package co.xenastudios.core.loader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * The folder every plugin of the family keeps its downloaded libraries in, laid out like a
 * Maven repository ({@code org/jetbrains/kotlin/kotlin-stdlib/2.4.21/kotlin-stdlib-2.4.21.jar}),
 * so each jar is downloaded once for all of them. A jar is used only when its SHA-256 is the
 * one pinned at build time, whether it was just downloaded or was already there.
 */
public final class LibraryCache {

    /** Google's mirror of Maven Central first (fast, rarely blocked), then Maven Central itself. */
    public static final List<String> DEFAULT_MIRRORS = Collections.unmodifiableList(Arrays.asList(
        "https://maven-central.storage-download.googleapis.com/maven2/",
        "https://repo1.maven.org/maven2/"));

    /** A jar bigger than this is no library of ours: the download stops. */
    private static final long MAX_BYTES = 128L * 1024 * 1024;

    private final Path folder;
    private final List<String> mirrors;
    private final String mirrorHelp;
    private int connectTimeoutMillis = 10_000;
    private int readTimeoutMillis = 60_000;
    private final ConcurrentMap<String, FutureTask<Fetched>> ahead = new ConcurrentHashMap<>();

    /**
     * @param mirrors Maven repositories to try in order, such as {@link #DEFAULT_MIRRORS}
     * @param mirrorHelp what the owner can do when none can be reached: how to set a mirror on
     *     this platform, ending with a full stop
     */
    public LibraryCache(Path folder, List<String> mirrors, String mirrorHelp) {
        this.folder = folder;
        List<String> normalized = new ArrayList<>();
        for (String mirror : mirrors) {
            String trimmed = mirror.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed.endsWith("/") ? trimmed : trimmed + '/');
            }
        }
        this.mirrors = Collections.unmodifiableList(normalized);
        this.mirrorHelp = mirrorHelp;
    }

    /** For tests: how long to wait for a mirror. */
    LibraryCache timeouts(int connectMillis, int readMillis) {
        this.connectTimeoutMillis = connectMillis;
        this.readTimeoutMillis = readMillis;
        return this;
    }

    public Path folder() {
        return folder;
    }

    public List<String> mirrors() {
        return mirrors;
    }

    /** Where the library's jar is kept. */
    public Path location(Library library) {
        return folder.resolve(library.path());
    }

    /** A jar ready to use, and how it got there. */
    public static final class Fetched {
        private final Library library;
        private final Path jar;
        private final String downloadedFrom;
        private final long bytes;
        private final long millis;
        private final boolean replacedBadCopy;

        Fetched(Library library, Path jar, String downloadedFrom, long bytes, long millis, boolean replacedBadCopy) {
            this.library = library;
            this.jar = jar;
            this.downloadedFrom = downloadedFrom;
            this.bytes = bytes;
            this.millis = millis;
            this.replacedBadCopy = replacedBadCopy;
        }

        public Library library() {
            return library;
        }

        public Path jar() {
            return jar;
        }

        /** The mirror's host, or null when the jar was already in the cache. */
        public String downloadedFrom() {
            return downloadedFrom;
        }

        public long bytes() {
            return bytes;
        }

        /** How long the download took; 0 when there wasn't one. */
        public long millis() {
            return millis;
        }

        /** The cache had a copy with the wrong hash (damaged, or put there by hand), which was replaced. */
        public boolean replacedBadCopy() {
            return replacedBadCopy;
        }
    }

    /**
     * Starts {@link #fetch fetching} each library now, so their hashes (and, the first time,
     * their downloads) happen while the server goes on with other things; {@link #fetch} then
     * waits for that library's result. For a platform that makes the plugin well before it may
     * add libraries to it (Velocity). The jars already in the cache are checked one after
     * another on one daemon thread called {@code name}, the biggest first (the stdlib, which is
     * waited for first): SHA-256 runs interpreted until the JIT has compiled it, so the first
     * jar's hash pays for that and the rest take a millisecond or two, where a thread each would
     * pay it once per jar, on a machine with two cores in the server's own startup's way. Each
     * jar that has to be downloaded gets a thread of its own, as the network is the wait there.
     */
    public void fetchAhead(List<Library> libraries, String name) {
        List<Library> bySize = new ArrayList<>(libraries);
        Collections.sort(bySize, new Comparator<Library>() {
            @Override
            public int compare(Library a, Library b) {
                return Long.compare(size(b), size(a));
            }
        });
        final List<FutureTask<Fetched>> cached = new ArrayList<>();
        for (final Library library : bySize) {
            FutureTask<Fetched> task = new FutureTask<>(new Callable<Fetched>() {
                @Override
                public Fetched call() throws LibraryException {
                    return fetchNow(library);
                }
            });
            if (ahead.putIfAbsent(library.coordinates(), task) != null) {
                continue;
            }
            if (Files.isRegularFile(location(library))) {
                cached.add(task);
            } else {
                start(task, name);
            }
        }
        if (!cached.isEmpty()) {
            start(new Runnable() {
                @Override
                public void run() {
                    // A bad copy is downloaded again here, after the others' checks: rare.
                    for (FutureTask<Fetched> task : cached) {
                        task.run();
                    }
                }
            }, name);
        }
    }

    private static void start(Runnable work, String name) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The library's jar, from the cache if it's there and right, downloaded otherwise; what
     * {@link #fetchAhead} started for it, once that's done, if it did.
     */
    public Fetched fetch(Library library) throws LibraryException {
        FutureTask<Fetched> task = ahead.remove(library.coordinates());
        if (task == null) {
            return fetchNow(library);
        }
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LibraryException("Interrupted while checking " + library);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LibraryException) {
                throw (LibraryException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new LibraryException("Couldn't check " + library + ": " + cause);
        }
    }

    private long size(Library library) {
        try {
            return Files.size(location(library));
        } catch (IOException e) {
            return 0;
        }
    }

    private Fetched fetchNow(Library library) throws LibraryException {
        Path jar = location(library);
        boolean bad = false;
        if (Files.isRegularFile(jar)) {
            try {
                if (Sha256.of(jar).equals(library.sha256())) {
                    return new Fetched(library, jar, null, Files.size(jar), 0, false);
                }
                bad = true;
            } catch (IOException e) {
                bad = true;
            }
        }
        long start = System.nanoTime();
        List<String> failures = new ArrayList<>();
        for (String mirror : mirrors) {
            String host = host(mirror);
            try {
                long bytes = download(new URL(mirror + library.path()), library, jar, host);
                return new Fetched(library, jar, host, bytes, (System.nanoTime() - start) / 1_000_000, bad);
            } catch (IOException e) {
                failures.add(host + ": " + reason(e));
            }
        }
        throw new LibraryException("Couldn't download " + library + " ("
            + (failures.isEmpty() ? "no mirror is set" : String.join("; ", failures)) + "). " + mirrorHelp
            + " Or put " + library.fileName() + " from Maven Central at " + jar + ".");
    }

    /** Downloads to a temporary file beside the jar, hashing as it goes; moved into place only if it matches. */
    private long download(URL url, Library library, Path jar, String host) throws IOException {
        Files.createDirectories(jar.getParent());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeoutMillis);
        connection.setRequestProperty("User-Agent", "xena-library-loader");
        Path part = Files.createTempFile(jar.getParent(), library.fileName(), ".part");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status);
            }
            MessageDigest digest = Sha256.digest();
            long total = 0;
            byte[] chunk = new byte[64 * 1024];
            try (InputStream body = connection.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
                for (int n = body.read(chunk); n >= 0; n = body.read(chunk)) {
                    total += n;
                    if (total > MAX_BYTES) {
                        throw new IOException("sent over " + MAX_BYTES / (1024 * 1024) + " MiB");
                    }
                    digest.update(chunk, 0, n);
                    out.write(chunk, 0, n);
                }
            }
            String actual = Sha256.hex(digest.digest());
            if (!actual.equals(library.sha256())) {
                throw new IOException("its jar's SHA-256 is " + actual + ", not the pinned " + library.sha256()
                    + ", so it wasn't used");
            }
            try {
                Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
            }
            return total;
        } finally {
            connection.disconnect();
            Files.deleteIfExists(part);
        }
    }

    private static String host(String mirror) {
        try {
            return new URL(mirror).getHost();
        } catch (IOException e) {
            return mirror;
        }
    }

    /** What went wrong, in words an owner can act on. */
    private static String reason(IOException e) {
        if (e instanceof UnknownHostException) {
            return "can't look up the host (is the server offline, or its DNS blocked?)";
        }
        if (e instanceof ConnectException) {
            return "can't connect (" + e.getMessage() + ")";
        }
        if (e instanceof SocketTimeoutException) {
            return "timed out";
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
