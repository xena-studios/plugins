package co.xenastudios.xvote.receiver

import co.xenastudios.core.common.platform.Log
import co.xenastudios.xvote.ReceiverSettings
import co.xenastudios.xvote.vote.IncomingVote
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ServerSocketFactory

/**
 * The Votifier receiver: listens on its own port for votes from server lists, as 1.x's. One thread
 * accepts connections; each is handled on a thread of a pool as large as `max-connections` (threads
 * are made as they're needed and end a minute after their last vote: Java 8 has no virtual threads).
 * What keeps it safe from the internet: a cap on connections at once, and on those from one address;
 * a limit on connections per address per minute, checked before anything is read or decrypted; a
 * short deadline for the first bytes and a longer one for the whole conversation, so a byte at a time
 * can't hold a slot; and size limits on what's read. IPv6 addresses count by their /64. Nothing here
 * touches the game: a vote is handed to [sink].
 *
 * A failed accept never stops it: it waits a moment, longer each time up to a second, and tries again.
 * Only [close] ends it. Errors are logged once and then kept quiet until a reload restarts it: a list's
 * mistake once per list with a token of its own, from a budget of its own; anything else once per
 * kind, without the address or names it carried.
 */
public class Receiver(
    public val settings: ReceiverSettings,
    credentials: Credentials,
    internal val sink: (IncomingVote) -> Unit,
    internal val log: Log,
    private val sockets: ServerSocketFactory = ServerSocketFactory.getDefault(),
) : AutoCloseable {
    /** The keys and tokens votes are checked with, swapped whole on reload. */
    public class Credentials(public val keys: KeyPair?, public val tokens: Tokens)

    @Volatile
    public var credentials: Credentials = credentials

    private val open = ConcurrentHashMap.newKeySet<Socket>()
    private val openFrom = ConcurrentHashMap<String, AtomicInteger>()
    private val inFlight = AtomicInteger()
    private val noise = ConcurrentHashMap.newKeySet<String>()
    private val voteErrors = ConcurrentHashMap.newKeySet<String>()
    private val voteErrorsQuiet = AtomicBoolean()
    private val limiter = RateLimiter(settings.perIpPerMinute)
    // As many threads as connections allowed at once, each ending a minute after its last vote; the
    // count of votes in flight keeps the queue from ever holding one.
    private val workers = ThreadPoolExecutor(settings.maxConnections, settings.maxConnections, 60, TimeUnit.SECONDS, LinkedBlockingQueue()) { runnable ->
        Thread(runnable, "xVote vote").also { it.isDaemon = true }
    }.also { it.allowCoreThreadTimeOut(true) }

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    internal var closed = false
        private set

    @Volatile
    private var running = false
    private var acceptor: Thread? = null

    /** Binds the port and starts accepting. Returns once it's listening; throws if the port can't be bound. */
    public fun start() {
        val socket = sockets.createServerSocket()
        try {
            socket.reuseAddress = true
            val host = settings.host
            val address = if (host.isBlank() || host == "0.0.0.0") InetSocketAddress(settings.port) else InetSocketAddress(host, settings.port)
            socket.bind(address, 50)
        } catch (e: Exception) {
            socket.close()
            throw if (e is IOException) e else IOException(e.message, e)
        }
        server = socket
        running = true
        acceptor = Thread({ accept(socket) }, "xVote receiver").also {
            it.isDaemon = true
            it.start()
        }
    }

    /** Whether it's taking connections: from [start] until [close], or until its thread dies. */
    public val listening: Boolean get() = running && !closed

    /** The port it's listening on: the configured one, or the one picked for port 0. */
    public val port: Int get() = server?.localPort ?: settings.port

    /** Stops listening and drops every open connection. Waits up to a second for the threads. */
    override fun close() {
        closed = true
        try {
            server?.close()
        } catch (e: IOException) {
            // Closing anyway.
        }
        for (connection in open) quietClose(connection)
        workers.shutdownNow()
        acceptor?.let {
            it.interrupt()
            try {
                it.join(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun accept(socket: ServerSocket) {
        var backoff = 0L
        try {
            while (!closed) {
                val connection = try {
                    socket.accept().also { backoff = 0 }
                } catch (e: IOException) {
                    if (closed) return
                    // Out of file handles, a network blip: wait and try again, never give up.
                    if (noise.add("accept")) {
                        log.warn("The vote receiver couldn't accept a connection (${e.message}); it keeps trying. Further errors like this are quiet until the next reload.", e)
                    }
                    backoff = if (backoff == 0L) 100 else minOf(MAX_BACKOFF_MS, backoff * 2)
                    try {
                        Thread.sleep(backoff)
                    } catch (interrupted: InterruptedException) {
                        if (closed) return
                    }
                    continue
                }
                take(connection)
            }
        } finally {
            running = false
        }
    }

    // Checks a new connection against the limits and hands it to a thread of its own.
    private fun take(connection: Socket) {
        val key = RateLimiter.key(connection.inetAddress)
        if (!limiter.allow(key, System.currentTimeMillis())) {
            noise("rate", false, "An address connected more than ${settings.perIpPerMinute} times in a minute, so it's turned away for now. If every list reaches you through one address (a" +
                " tunnel such as playit.gg or TCPShield), raise receiver.per-ip-per-minute, or set it to 0.")
            return quietClose(connection)
        }
        val counted = settings.perIpConnections > 0
        if (counted && openFrom.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet() > settings.perIpConnections) {
            release(key, true)
            noise("busy", false, "An address had more than ${settings.perIpConnections} connections open at once, so more were turned away. If every list reaches you through one address (a" +
                " tunnel such as playit.gg or TCPShield), raise receiver.per-ip-connections, or set it to 0.")
            return quietClose(connection)
        }
        if (inFlight.incrementAndGet() > settings.maxConnections) {
            inFlight.decrementAndGet()
            release(key, counted)
            noise("full", false, "More than ${settings.maxConnections} votes arrived at once, so some were turned away. Raise receiver.max-connections if your lists are that busy.")
            return quietClose(connection)
        }
        open += connection
        val done = Runnable {
            open -= connection
            quietClose(connection)
            inFlight.decrementAndGet()
            release(key, counted)
        }
        try {
            workers.execute {
                try {
                    Conversation.handle(this, connection)
                } finally {
                    done.run()
                }
            }
        } catch (e: Exception) {
            done.run()
        } catch (e: OutOfMemoryError) {
            done.run()
        }
    }

    // One fewer open from the address; at none, it's forgotten.
    private fun release(key: String, counted: Boolean) {
        if (!counted) return
        val count = openFrom[key] ?: return
        if (count.decrementAndGet() <= 0) openFrom.remove(key, count)
    }

    // Once per kind. The kinds are a fixed few, so this can't grow.
    internal fun noise(kind: String, info: Boolean, message: String) {
        if (noise.add(kind)) if (info) log.info(message) else log.warn(message)
    }

    // Once per list, up to a budget only lists' mistakes use.
    internal fun voteError(key: String, message: String) {
        if (voteErrors.size < MAX_VOTE_ERRORS) {
            if (voteErrors.add(key)) log.warn(message)
        } else if (voteErrorsQuiet.compareAndSet(false, true)) {
            log.warn("The vote receiver has logged $MAX_VOTE_ERRORS lists' mistakes; more are quiet until the next reload.")
        }
    }

    internal companion object {
        /** Most lists' mistakes logged before those go quiet until the next reload. */
        const val MAX_VOTE_ERRORS = 100

        /** How long a connection has to send its first bytes, at most. Lists send at once. */
        const val FIRST_BYTES_MS = 3_000L

        /** The longest pause between tries after a failed accept. */
        const val MAX_BACKOFF_MS = 1_000L

        fun quietClose(socket: Socket) {
            try {
                socket.close()
            } catch (e: IOException) {
                // Already closed.
            }
        }

        /** Reads up to [length] bytes, stopping only at the end: Java 9's `readNBytes`. */
        fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
            var read = 0
            while (read < length) {
                val n = input.read(buffer, offset + read, length - read)
                if (n < 0) break
                read += n
            }
            return read
        }
    }
}

/**
 * Connections allowed per address per minute. A list sends one connection per vote, so even a busy
 * one stays far below the limit; a flood from one address is turned away before anything is read or
 * decrypted. 0 turns the limit off. IPv6 addresses are counted by their /64, the block one machine
 * usually gets, so a host can't pick a new address for every connection.
 */
internal class RateLimiter(private val perMinute: Int) {
    private class Window {
        var start = 0L
        var count = 0
    }

    private val windows = ConcurrentHashMap<String, Window>()
    private val lastSweep = AtomicLong()

    /** Counts a connection, and says whether it's within the limit. */
    fun allow(key: String, now: Long): Boolean {
        if (perMinute <= 0) return true
        if (windows.size > SWEEP_AT) {
            val last = lastSweep.get()
            if (now - last >= SWEEP_EVERY_MS && lastSweep.compareAndSet(last, now)) {
                val iterator = windows.values.iterator()
                while (iterator.hasNext()) {
                    val window = iterator.next()
                    if (synchronized(window) { now - window.start >= WINDOW_MS }) iterator.remove()
                }
            }
        }
        val window = windows.computeIfAbsent(key) { Window() }
        synchronized(window) {
            if (now - window.start >= WINDOW_MS) {
                window.start = now
                window.count = 0
            }
            return ++window.count <= perMinute
        }
    }

    /** Addresses being counted, for tests. */
    val size: Int get() = windows.size

    companion object {
        const val WINDOW_MS = 60_000L

        /** Addresses kept before old ones are swept out. */
        const val SWEEP_AT = 10_000

        /** Sweeps at most this often, so a flood from many addresses doesn't sweep on every connection. */
        const val SWEEP_EVERY_MS = 1_000L

        /** What an address is counted as: itself for IPv4, its /64 for IPv6. */
        fun key(address: InetAddress): String {
            val bytes = address.address
            if (address is Inet6Address && bytes.size == 16) {
                val out = StringBuilder(20)
                for (i in 0 until 8) out.append(((bytes[i].toInt() and 0xFF) + 0x100).toString(16).substring(1))
                return out.append("/64").toString()
            }
            return address.hostAddress
        }
    }
}
