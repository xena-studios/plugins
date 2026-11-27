package co.xenastudios.xvote.receiver

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Taking a connection, and one conversation with a list: greet, read one vote, reply. Apart from
 * [Receiver], so it loads with the first connection, not as the receiver starts listening.
 */
internal object Conversation {
    // Checks a new connection against the limits and hands it to a thread of its own.
    fun take(receiver: Receiver, connection: Socket) {
        val settings = receiver.settings
        val key = RateLimiter.key(connection.inetAddress)
        val limiter = receiver.limiter ?: RateLimiter(settings.perIpPerMinute).also { receiver.limiter = it }
        if (!limiter.allow(key, System.currentTimeMillis())) {
            receiver.noise("rate", false, "An address connected more than ${settings.perIpPerMinute} times in a minute, so it's turned away for now. If every list reaches you through one address (a" +
                " tunnel such as playit.gg or TCPShield), raise receiver.per-ip-per-minute, or set it to 0.")
            return Receiver.quietClose(connection)
        }
        val counted = settings.perIpConnections > 0
        if (counted && receiver.openFrom.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet() > settings.perIpConnections) {
            receiver.release(key, true)
            receiver.noise("busy", false, "An address had more than ${settings.perIpConnections} connections open at once, so more were turned away. If every list reaches you through one address (a" +
                " tunnel such as playit.gg or TCPShield), raise receiver.per-ip-connections, or set it to 0.")
            return Receiver.quietClose(connection)
        }
        if (receiver.inFlight.incrementAndGet() > settings.maxConnections) {
            receiver.inFlight.decrementAndGet()
            receiver.release(key, counted)
            receiver.noise("full", false, "More than ${settings.maxConnections} votes arrived at once, so some were turned away. Raise receiver.max-connections if your lists are that busy.")
            return Receiver.quietClose(connection)
        }
        receiver.open += connection
        val done = Runnable {
            receiver.open -= connection
            Receiver.quietClose(connection)
            receiver.inFlight.decrementAndGet()
            receiver.release(key, counted)
        }
        try {
            receiver.workers().execute {
                try {
                    handle(receiver, connection)
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

    /** One conversation: greet, read one vote, reply. */
    fun handle(receiver: Receiver, connection: Socket) {
        val settings = receiver.settings
        val opened = System.currentTimeMillis()
        val deadline = opened + settings.timeoutSeconds * 1000L
        var v2 = false
        var started = false
        var out: OutputStream? = null
        try {
            connection.tcpNoDelay = true
            // The first bytes come at once from a list; a scanner that connects and waits is let go sooner.
            val input = Deadline(connection, minOf(deadline, opened + Receiver.FIRST_BYTES_MS))
            out = connection.getOutputStream()
            val challenge = Protocol.newToken()
            out.write(Protocol.greeting(challenge))
            out.flush()
            val data = DataInputStream(input)
            val start = ByteArray(2)
            // Some lists open a connection and send nothing; NuVotifier ignores that too.
            if (Receiver.readFully(input, start, 0, 2) < 2) return
            started = true
            input.until(deadline)
            val now = System.currentTimeMillis()
            val current = receiver.credentials
            val vote = if (((start[0].toInt() and 0xFF) shl 8 or (start[1].toInt() and 0xFF)) == Protocol.V2_MAGIC) {
                v2 = true
                val length = data.readUnsignedShort()
                if (length == 0 || length > Protocol.V2_MAX) {
                    throw ProtocolException.noise("CorruptedFrameException", "A vote of $length bytes is too ${if (length == 0) "short" else "long"}.", "v2-length")
                }
                val message = ByteArray(length)
                data.readFully(message)
                Protocol.decodeV2(String(message, Charsets.UTF_8), challenge, current.tokens::forService, now)
            } else {
                if (!settings.v1) throw ProtocolException.noise("QuietException", "This server only accepts well-formed Votifier v2 packets.", "v1-off")
                val keys = current.keys ?: throw ProtocolException.noise("QuietException", "There's no key for Votifier v1 yet.", "v1-nokey")
                val block = ByteArray(Protocol.V1_BLOCK)
                block[0] = start[0]
                block[1] = start[1]
                data.readFully(block, 2, Protocol.V1_BLOCK - 2)
                Protocol.decodeV1(block, keys.private, now)
            }
            receiver.sink(vote)
            if (v2) {
                out.write(Protocol.ok())
                out.flush()
            }
        } catch (e: ProtocolException) {
            if (e.voteError) {
                receiver.voteError(e.logKey, "Refused a vote: ${e.logLine}")
            } else {
                receiver.noise(e.logKey, true, "Refused a connection that wasn't a well-formed vote: ${e.message} Scanners send these; if a list's votes go missing, check its settings. (Logged once until the next reload.)")
            }
            reply(out, v2, e.causeName, e.message ?: "")
        } catch (e: SocketTimeoutException) {
            if (started) {
                receiver.noise("slow", true, "A connection sent too little, too slowly, and was closed. (Logged once until the next reload.)")
                reply(out, v2, "ReadTimeoutException", "Took too long to send the vote.")
            }
            // Before the first bytes: a connection that never spoke, as port scanners make. Not worth a line.
        } catch (e: EOFException) {
            if (started) {
                receiver.noise("slow", true, "A connection sent too little, too slowly, and was closed. (Logged once until the next reload.)")
                reply(out, v2, "ReadTimeoutException", "Took too long to send the vote.")
            }
        } catch (e: SocketException) {
            // Closed by the other side, or by close() as the plugin stops.
        } catch (e: IOException) {
            if (!receiver.closed) receiver.noise("io", true, "A connection failed: ${e.message} (Logged once until the next reload.)")
        } catch (e: RuntimeException) {
            receiver.log.warn("Couldn't handle a vote", e)
            reply(out, v2, e.javaClass.simpleName, "The server couldn't handle the vote.")
        }
    }

    private fun reply(out: OutputStream?, v2: Boolean, cause: String, message: String) {
        if (!v2 || out == null) return
        try {
            out.write(Protocol.error(cause, message))
            out.flush()
        } catch (e: IOException) {
            // They've gone.
        }
    }

    /**
     * The socket's input with a deadline for the whole conversation, not just each read, so a client
     * can't hold a slot by sending a byte at a time.
     */
    private class Deadline(private val socket: Socket, @Volatile private var deadline: Long) : InputStream() {
        private val input = socket.getInputStream()

        /** A new deadline, for what's read from now on. */
        fun until(deadline: Long) {
            this.deadline = deadline
        }

        private fun arm() {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw SocketTimeoutException("deadline")
            socket.soTimeout = minOf(Int.MAX_VALUE.toLong(), left).toInt()
        }

        override fun read(): Int {
            arm()
            return input.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            arm()
            return input.read(buffer, offset, length)
        }
    }

}
