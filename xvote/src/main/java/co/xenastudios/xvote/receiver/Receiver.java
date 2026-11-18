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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Votifier receiver: listens on its own port for votes from server lists.
 *
 * <p>One platform thread accepts connections; each is handled on a virtual thread of its
 * own, with a hard cap on how many at once (more are closed straight away), a deadline
 * for the whole conversation, size limits on what's read, and a limit on connections per
 * address per minute. Nothing here touches the game: a vote is handed to {@code sink}.
 *
 * <p>Errors are logged once per kind and source (one list's wrong token is one line, not
 * one per vote), until the receiver is restarted by a reload.
 */
public final class Receiver implements AutoCloseable {

    /** Most distinct errors logged before the receiver goes quiet until the next reload. */
    static final int MAX_LOGGED = 200;

    /** What the receiver needs from the config. */
    public record Settings(String host, int port, boolean v1, int maxConnections, int timeoutSeconds, int perIpPerMinute) {}

    /** The keys and tokens it checks votes with, swapped whole on reload. */
    public record Credentials(KeyPair keys, Tokens tokens) {}

    private final Settings settings;
    private final Consumer<IncomingVote> sink;
    private final Logger logger;
    private final Semaphore slots;
    private final RateLimiter limiter;
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final Set<String> logged = ConcurrentHashMap.newKeySet();
    private volatile Credentials credentials;
    private volatile ServerSocket server;
    private volatile boolean closed;
    private Thread acceptor;

    public Receiver(Settings settings, Credentials credentials, Consumer<IncomingVote> sink, Logger logger) {
        this.settings = settings;
        this.credentials = credentials;
        this.sink = sink;
        this.logger = logger;
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
        ServerSocket socket = new ServerSocket();
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
        acceptor = Thread.ofPlatform().daemon().name("xVote receiver").start(this::accept);
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
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void accept() {
        ServerSocket socket = server;
        while (!closed) {
            Socket connection;
            try {
                connection = socket.accept();
            } catch (IOException e) {
                if (!closed) {
                    logger.log(Level.WARNING, "The vote receiver stopped accepting connections", e);
                }
                return;
            }
            InetAddress from = connection.getInetAddress();
            if (!limiter.allow(from, System.currentTimeMillis())) {
                once("rate:" + from.getHostAddress(), Level.WARNING, from.getHostAddress()
                    + " is connecting more than " + settings.perIpPerMinute() + " times a minute; turning it away for now.");
                quietClose(connection);
                continue;
            }
            if (!slots.tryAcquire()) {
                once("full", Level.WARNING, "More than " + settings.maxConnections()
                    + " votes arrived at once, so some were turned away. Raise receiver.max-connections if a list is that busy.");
                quietClose(connection);
                continue;
            }
            open.add(connection);
            try {
                Thread.ofVirtual().name("xVote vote").start(() -> {
                    try {
                        handle(connection);
                    } finally {
                        open.remove(connection);
                        quietClose(connection);
                        slots.release();
                    }
                });
            } catch (RuntimeException e) {
                open.remove(connection);
                quietClose(connection);
                slots.release();
            }
        }
    }

    /** One conversation: greet, read one vote, reply. */
    void handle(Socket connection) {
        String from = connection.getInetAddress().getHostAddress();
        long deadline = System.currentTimeMillis() + settings.timeoutSeconds() * 1000L;
        boolean v2 = false;
        OutputStream out = null;
        try {
            connection.setTcpNoDelay(true);
            Deadline in = new Deadline(connection, deadline);
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
            long now = System.currentTimeMillis();
            Credentials current = credentials;
            IncomingVote vote;
            if (((start[0] & 0xFF) << 8 | (start[1] & 0xFF)) == Protocol.V2_MAGIC) {
                v2 = true;
                int length = data.readUnsignedShort();
                if (length == 0 || length > Protocol.V2_MAX) {
                    throw new ProtocolException("CorruptedFrameException", "A vote of " + length + " bytes is too "
                        + (length == 0 ? "short" : "long") + ".", "v2-length:" + from);
                }
                byte[] message = new byte[length];
                data.readFully(message);
                vote = Protocol.decodeV2(new String(message, StandardCharsets.UTF_8), challenge,
                    current.tokens()::forService, now);
            } else {
                if (!settings.v1()) {
                    throw new ProtocolException("QuietException", "This server only accepts well-formed Votifier v2 packets.",
                        "v1-off:" + from);
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
            once(e.logKey(), Level.WARNING, "Refused a vote from " + from + ": " + e.getMessage()
                + " (further errors like this are quiet until the next reload)");
            reply(out, v2, e.causeName(), e.getMessage());
        } catch (SocketTimeoutException | EOFException e) {
            once("slow:" + from, Level.INFO, "A connection from " + from + " sent too little, too slowly, and was closed.");
            reply(out, v2, "ReadTimeoutException", "Took too long to send the vote.");
        } catch (SocketException e) {
            // Closed by the other side, or by close() during shutdown.
        } catch (IOException e) {
            if (!closed) {
                once("io:" + from, Level.INFO, "A connection from " + from + " failed: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Couldn't handle a vote from " + from, e);
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

    private void once(String key, Level level, String message) {
        if (logged.size() < MAX_LOGGED) {
            if (logged.add(key)) {
                logger.log(level, message);
            }
        } else if (logged.add("quiet")) {
            logger.warning("The vote receiver has logged " + MAX_LOGGED + " different problems; it's quiet until the next reload.");
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
        private final long deadline;

        Deadline(Socket socket, long deadline) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
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
