package co.xenastudios.xlimbo

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The status ping against real sockets on this machine: a server that answers, one that's down, one that never answers. */
class StatusPingTest {
    @Test
    fun addressesTakeAnOptionalPort() {
        assertEquals(25566, (StatusAddress.parse("127.0.0.1:25566") as StatusAddress.Ok).port)
        assertEquals(25565, (StatusAddress.parse("play.example.com") as StatusAddress.Ok).port)
        assertEquals("play.example.com", (StatusAddress.parse(" play.example.com ") as StatusAddress.Ok).host)
        val v6 = StatusAddress.parse("[::1]:25566") as StatusAddress.Ok
        assertEquals("::1" to 25566, v6.host to v6.port)
        assertEquals(25565, (StatusAddress.parse("[2001:db8::1]") as StatusAddress.Ok).port)
        assertIs<StatusAddress.Bad>(StatusAddress.parse("host:abc"))
        assertIs<StatusAddress.Bad>(StatusAddress.parse(""))
        assertIs<StatusAddress.Bad>(StatusAddress.parse("host:70000"))
    }

    @Test
    fun aServerThatAnswersItsStatusIsUp() {
        ServerSocket(0).use { server ->
            val fake = thread { answerOnce(server) }
            assertTrue(JvmStatusPing.isUp("127.0.0.1", server.localPort))
            fake.join(5000)
        }
    }

    @Test
    fun aServerThatIsDownOrSilentIsNot() {
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(JvmStatusPing.isUp("127.0.0.1", port), "nothing listening")
        ServerSocket(0).use { silent ->
            // Accepts the connection (as a server still loading would) but never answers.
            thread(isDaemon = true) {
                try {
                    silent.accept().use { Thread.sleep(5000) }
                } catch (e: Exception) {
                    // Closed by the test.
                }
            }
            assertFalse(JvmStatusPing.isUp("127.0.0.1", silent.localPort), "never answers")
        }
    }

    // Reads a handshake and status request, then answers with a status JSON, as a server does.
    private fun answerOnce(server: ServerSocket) {
        server.accept().use { socket ->
            val input = DataInputStream(socket.getInputStream())
            input.readFully(ByteArray(readVarInt(input)))
            input.readFully(ByteArray(readVarInt(input)))
            val json = "{\"version\":{\"name\":\"1.21.11\",\"protocol\":774}}".toByteArray(StandardCharsets.UTF_8)
            val body = ByteArrayOutputStream()
            val packet = DataOutputStream(body)
            writeVarInt(packet, 0x00)
            writeVarInt(packet, json.size)
            packet.write(json)
            val out = DataOutputStream(socket.getOutputStream())
            writeVarInt(out, body.size())
            body.writeTo(out)
            out.flush()
        }
    }

    private fun readVarInt(input: DataInputStream): Int {
        var value = 0
        var shift = 0
        while (true) {
            val b = input.readUnsignedByte()
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return value
            shift += 7
        }
    }

    private fun writeVarInt(out: DataOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.writeByte(v)
    }
}
