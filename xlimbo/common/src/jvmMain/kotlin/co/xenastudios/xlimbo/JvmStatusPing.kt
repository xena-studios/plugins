package co.xenastudios.xlimbo

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * The Server List Ping, as the multiplayer screen sends it: a handshake asking for status, then
 * the status request. A server only answers once it's ticking, not while it's loading, so an
 * answer means players can join. About 60 lines, no library. Blocking: xLimbo runs it on an
 * async task, with a 3-second timeout on connecting and on reading.
 */
public object JvmStatusPing : StatusPinger {
    private const val TIMEOUT_MILLIS = 3000

    override fun isUp(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MILLIS)
            socket.soTimeout = TIMEOUT_MILLIS
            val name = host.toByteArray(StandardCharsets.UTF_8)
            // Handshake: protocol version (any), address, port, next state 1 (status).
            val handshake = ByteArrayOutputStream()
            DataOutputStream(handshake).use { packet ->
                writeVarInt(packet, 0x00)
                writeVarInt(packet, -1)
                writeVarInt(packet, name.size)
                packet.write(name)
                packet.writeShort(port)
                writeVarInt(packet, 1)
            }
            val out = DataOutputStream(socket.getOutputStream())
            writeVarInt(out, handshake.size())
            handshake.writeTo(out)
            // Status request: an empty packet 0x00.
            writeVarInt(out, 1)
            writeVarInt(out, 0x00)
            out.flush()
            val input = DataInputStream(socket.getInputStream())
            val length = readVarInt(input)
            val id = readVarInt(input)
            val json = readVarInt(input)
            length > 0 && id == 0x00 && json > 0
        }
    } catch (e: IOException) {
        false
    } catch (e: RuntimeException) {
        false
    }

    private fun writeVarInt(out: DataOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.writeByte(v)
    }

    private fun readVarInt(input: InputStream): Int {
        var value = 0
        var shift = 0
        while (shift < 35) {
            val b = input.read()
            if (b < 0) throw IOException("closed")
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return value
            shift += 7
        }
        throw IOException("varint too long")
    }
}
