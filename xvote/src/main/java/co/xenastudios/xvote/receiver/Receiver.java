package co.xenastudios.xvote.receiver;

import co.xenastudios.xvote.vote.IncomingVote;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ServerSocketFactory;

/**
 * The Votifier receiver: listens on its own port for votes from server lists.
 *
 * <p>One platform thread accepts connections; each is handled on a virtual thread of its
 * own. What keeps it safe from the internet: a cap on connections at once, and on those
 * from one address; a limit on connections per address per minute; a short deadline for
 * the first bytes and a longer one for the whole conversation; and size limits on what's
 * read. IPv6 addresses count by their /64. Nothing here touches the game: a vote is
 * handed to {@code sink}.
 *
 * <p>A failed accept never stops it: it waits a moment, longer each time up to a second,
 * and tries again. Only {@link #close} ends it.
 *
 * <p>Errors are logged once and then kept quiet until the receiver is restarted by a
 * reload: a list's mistake (a wrong token) once per list with a token of its own, from a
 * budget of its own; a wrong public key, a bad signature on the default token and an unknown
 * service once each in all, since a scanner can send those; anything else once per kind,
 * without the address or names it carried.
 */
public final class Receiver implements AutoCloseable {

    /** Most lists' mistakes logged before those go quiet until the next reload. */
    static final int MAX_VOTE_ERRORS = 100;
    /** How long a connection has to send its first bytes, at most. Lists send at once. */
    static final long FIRST_BYTES_MS = 3_000;
    /** The longest pause between tries after a failed accept. */
    static final long MAX_BACKOFF_MS = 1_000;

    /**
     * What the receiver needs from the config.
     *
     * @param perIpConnections most connections open at once from one address; 0 is no limit
     * @param perIpPerMinute most connections from one address a minute; 0 is no limit
     */
    public record Settings(String host, int port, boolean v1, int maxConnections, int perIpConnections, int timeoutSeconds,
                           int perIpPerMinute) {}

    /** The keys and tokens it checks votes with, swapped whole on reload. */
    public record Credentials(KeyPair keys, Tokens tokens) {}

    private final Settings settings;
    private final Consumer<IncomingVote> sink;
    private final Logger logger;
    private final ServerSocketFactory sockets;
    private final Semaphore slots;
    private final RateLimiter limiter;
    private final Map<String, Integer> openFrom = new ConcurrentHashMap<>();
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final Set<String> noise = ConcurrentHashMap.newKeySet();
    private final Set<String> voteErrors = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean voteErrorsQuiet = new AtomicBoolean();
    private volatile Credentials credentials;
    private volatile ServerSocket server;
    private volatile boolean closed;
    private volatile boolean running;
    private Thread acceptor;

    public Receiver(Settings settings, Credentials credentials, Consumer<IncomingVote> sink, Logger logger) {
        this(settings, credentials, sink, logger, ServerSocketFactory.getDefault());
    }

    /** With the server socket made by {@code sockets}: for tests that make accepting fail. */
    Receiver(Settings settings, Credentials credentials, Consumer<IncomingVote> sink, Logger logger, ServerSocketFactory sockets) {
        this.settings = settings;
        this.credentials = credentials;
        this.sink = sink;
        this.logger = logger;
        this.sockets = sockets;
        this.slots = new Semaphore(settings.maxConnections());
        this.limiter = new RateLimiter(settings.perIpPerMinute());
    }

    public Settings settings() {
        return settings;
    }

    /** New keys or tokens, for votes from now on. */
    public void credentials(Credentials credentials) {
        this.credentials = credentials;
    }

    public Credentials credentials() {
        return credentials;
    }

    /**
     * Binds the port and starts accepting. Returns once it's listening.
     *
     * @throws IOException if the port can't be bound, such as when something else uses it
     */
    public void start() throws IOException {
        ServerSocket socket = sockets.createServerSocket();
        try {
            socket.setReuseAddress(true);
            InetSocketAddress address = settings.host().isBlank() || settings.host().equals("0.0.0.0")
                ? new InetSocketAddress(settings.port()) : new InetSocketAddress(settings.host(), settings.port());
            socket.bind(address, 50);
        } catch (IOException | IllegalArgumentException e) {
            socket.close();
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
        server = socket;
        running = true;
        acceptor = Thread.ofPlatform().daemon().name("xVote receiver").start(this::accept);
    }

    /** Whether it's taking connections: true from {@link #start} until {@link #close}, or until its thread dies. */
    public boolean listening() {
        return running && !closed;
    }

    /** The port it's listening on: the configured one, or the one picked for port 0. */
    public int port() {
        ServerSocket socket = server;
        return socket == null ? settings.port() : socket.getLocalPort();
    }

    /** Stops listening and drops every open connection. Waits up to a second for the threads. */
    @Override
    public void close() {
        closed = true;
        ServerSocket socket = server;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
        for (Socket connection : open) {
            quietClose(connection);
        }
        Thread thread = acceptor;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void accept() {
        ServerSocket socket = server;
        long backoff = 0;
        try {
            while (!closed) {
                Socket connection;
                try {
                    connection = socket.accept();
                    backoff = 0;
                } catch (IOException e) {
                    if (closed) {
                        return;
                    }
                    // Out of file handles, a network blip: wait and try again, never give up.
                    if (noise.add("accept")) {
                        logger.log(Level.WARNING, "The vote receiver couldn't accept a connection (" + e.getMessage()
                            + "); it keeps trying. Further errors like this are quiet until the next reload.", e);
                    }
                    backoff = backoff == 0 ? 100 : Math.min(MAX_BACKOFF_MS, backoff * 2);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException interrupted) {
                        if (closed) {
                            return;
                        }
                    }
                    continue;
                }
                take(connection);
            }
        } finally {
            running = false;
        }
    }

    // Checks a new connection against the limits and hands it to a thread of its own.
    private void take(Socket connection) {
        InetAddress from = connection.getInetAddress();
        String key = RateLimiter.key(from);
        if (!limiter.allow(key, System.currentTimeMillis())) {
            noise("rate", Level.WARNING, "An address connected more than " + settings.perIpPerMinute()
                + " times in a minute, so it's turned away for now. If every list reaches you through one address (a"
                + " tunnel such as playit.gg or TCPShield), raise receiver.per-ip-per-minute, or set it to 0.");
            quietClose(connection);
            return;
        }
        boolean counted = settings.perIpConnections() > 0;
        if (counted && openFrom.merge(key, 1, Integer::sum) > settings.perIpConnections()) {
            release(key, true);
            noise("busy", Level.WARNING, "An address had more than " + settings.perIpConnections()
                + " connections open at once, so more were turned away. If every list reaches you through one address (a"
                + " tunnel such as playit.gg or TCPShield), raise receiver.per-ip-connections, or set it to 0.");
            quietClose(connection);
            return;
        }
        if (!slots.tryAcquire()) {
            release(key, counted);
            noise("full", Level.WARNING, "More than " + settings.maxConnections()
                + " votes arrived at once, so some were turned away. Raise receiver.max-connections if your lists are that busy.");
            quietClose(connection);
            return;
        }
        open.add(connection);
        Runnable done = () -> {
            open.remove(connection);
            quietClose(connection);
            slots.release();
            release(key, counted);
        };
        try {
            Thread.ofVirtual().name("xVote vote").start(() -> {
                try {
                    handle(connection);
                } finally {
                    done.run();
                }
            });
        } catch (RuntimeException | OutOfMemoryError e) {
            done.run();
        }
    }

    // One fewer open from the address; at none, it's forgotten.
    private void release(String key, boolean counted) {
        if (counted) {
            openFrom.computeIfPresent(key, (unused, count) -> count <= 1 ? null : count - 1);
        }
    }

    /** One conversation: greet, read one vote, reply. */
    void handle(Socket connection) {
        String from = connection.getInetAddress().getHostAddress();
        long opened = System.currentTimeMillis();
        long deadline = opened + settings.timeoutSeconds() * 1000L;
        boolean v2 = false;
        boolean started = false;
        OutputStream out = null;
        try {
            connection.setTcpNoDelay(true);
            // The first bytes come at once from a list; a scanner that connects and waits is let go sooner.
            Deadline in = new Deadline(connection, Math.min(deadline, opened + FIRST_BYTES_MS));
            out = connection.getOutputStream();
            String challenge = Protocol.newToken();
            out.write(Protocol.greeting(challenge));
            out.flush();

            DataInputStream data = new DataInputStream(in);
            byte[] start = new byte[2];
            int got = in.readNBytes(start, 0, 2);
            if (got < 2) {
                // Some lists open a connection and send nothing; NuVotifier ignores that too.
                return;
            }
            started = true;
            in.until(deadline);
            long now = System.currentTimeMillis();
            Credentials current = credentials;
            IncomingVote vote;
            if (((start[0] & 0xFF) << 8 | (start[1] & 0xFF)) == Protocol.V2_MAGIC) {
                v2 = true;
                int length = data.readUnsignedShort();
                if (length == 0 || length > Protocol.V2_MAX) {
                    throw ProtocolException.noise("CorruptedFrameException", "A vote of " + length + " bytes is too "
                        + (length == 0 ? "short" : "long") + ".", "v2-length");
                }
                byte[] message = new byte[length];
                data.readFully(message);
                vote = Protocol.decodeV2(new String(message, StandardCharsets.UTF_8), challenge,
                    current.tokens()::forService, now);
            } else {
                if (!settings.v1()) {
                    throw ProtocolException.noise("QuietException", "This server only accepts well-formed Votifier v2 packets.",
                        "v1-off");
                }
                byte[] block = new byte[Protocol.V1_BLOCK];
                block[0] = start[0];
                block[1] = start[1];
                data.readFully(block, 2, Protocol.V1_BLOCK - 2);
                vote = Protocol.decodeV1(block, current.keys().getPrivate(), from, now);
            }
            sink.accept(vote);
            if (v2) {
                out.write(Protocol.ok());
                out.flush();
            }
        } catch (ProtocolException e) {
            if (e.voteError()) {
                voteError(e.logKey(), "Refused a vote: " + e.logLine());
            } else {
                noise(e.logKey(), Level.INFO, "Refused a connection that wasn't a well-formed vote: " + e.getMessage()
                    + " Scanners send these; if a list's votes go missing, check its settings. (Logged once until the next reload.)");
            }
            reply(out, v2, e.causeName(), e.getMessage());
        } catch (SocketTimeoutException | EOFException e) {
            if (started) {
                noise("slow", Level.INFO, "A connection sent too little, too slowly, and was closed. (Logged once until the next reload.)");
                reply(out, v2, "ReadTimeoutException", "Took too long to send the vote.");
            }
            // Before the first bytes: a connection that never spoke, as port scanners make. Not worth a line.
        } catch (SocketException e) {
            // Closed by the other side, or by close() during shutdown.
        } catch (IOException e) {
            if (!closed) {
                noise("io", Level.INFO, "A connection failed: " + e.getMessage() + " (Logged once until the next reload.)");
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Couldn't handle a vote", e);
            reply(out, v2, e.getClass().getSimpleName(), "The server couldn't handle the vote.");
        }
    }

    private void reply(OutputStream out, boolean v2, String cause, String message) {
        if (v2 && out != null) {
            try {
                out.write(Protocol.error(cause, message));
                out.flush();
            } catch (IOException ignored) {
                // They've gone.
            }
        }
    }

    // Once per kind. The kinds are a fixed few, so this can't grow.
    private void noise(String kind, Level level, String message) {
        if (noise.add(kind)) {
            logger.log(level, message);
        }
    }

    // Once per list, up to a budget only lists' mistakes use.
    private void voteError(String key, String message) {
        if (voteErrors.size() < MAX_VOTE_ERRORS) {
            if (voteErrors.add(key)) {
                logger.warning(message);
            }
        } else if (voteErrorsQuiet.compareAndSet(false, true)) {
            logger.warning("The vote receiver has logged " + MAX_VOTE_ERRORS + " lists' mistakes; more are quiet until the next reload.");
        }
    }

    private static void quietClose(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    /**
     * The socket's input with a deadline for the whole conversation, not just each read,
     * so a client can't hold a slot by sending a byte at a time.
     */
    private static final class Deadline extends InputStream {
        private final Socket socket;
        private final InputStream in;
        private long deadline;

        Deadline(Socket socket, long deadline) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.deadline = deadline;
        }

        /** A new deadline, for what's read from now on. */
        void until(long deadline) {
            this.deadline = deadline;
        }

        private void arm() throws IOException {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                throw new SocketTimeoutException("deadline");
            }
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, left));
        }

        @Override
        public int read() throws IOException {
            arm();
            return in.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            arm();
            return in.read(buffer, offset, length);
        }
    }
}
