package co.xenastudios.xlimbo.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StatusPingTest {

    @Test
    void addressesTakeAnOptionalPort() {
        assertEquals(25566, StatusPing.parse("127.0.0.1:25566").getPort());
        assertEquals(25565, StatusPing.parse("play.example.com").getPort());
        assertEquals("play.example.com", StatusPing.parse(" play.example.com ").getHostString());
        assertEquals("::1", StatusPing.parse("[::1]:25566").getHostString());
        assertEquals(25566, StatusPing.parse("[::1]:25566").getPort());
        assertEquals(25565, StatusPing.parse("[2001:db8::1]").getPort());
        assertThrows(IllegalArgumentException.class, () -> StatusPing.parse("host:abc"));
        assertThrows(IllegalArgumentException.class, () -> StatusPing.parse(""));
    }

    @Test
    void aServerThatAnswersItsStatusIsUp() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread fake = Thread.ofVirtual().start(() -> answerOnce(server));
            assertTrue(StatusPing.isUp(InetSocketAddress.createUnresolved("127.0.0.1", server.getLocalPort())));
            fake.join();
        }
    }

    @Test
    void aServerThatIsDownOrSilentIsNot() throws Exception {
        int port;
        try (ServerSocket closed = new ServerSocket(0)) {
            port = closed.getLocalPort();
        }
        assertFalse(StatusPing.isUp(InetSocketAddress.createUnresolved("127.0.0.1", port)), "nothing listening");
        try (ServerSocket silent = new ServerSocket(0)) {
            // Accepts the connection (as a server still loading would) but never answers.
            Thread.ofVirtual().start(() -> {
                try (Socket socket = silent.accept()) {
                    Thread.sleep(5000);
                } catch (Exception ignored) {
                    // Closed by the test.
                }
            });
            assertFalse(StatusPing.isUp(InetSocketAddress.createUnresolved("127.0.0.1", silent.getLocalPort())), "never answers");
        }
    }

    // Reads a handshake and status request, then answers with a status JSON, as a server does.
    private static void answerOnce(ServerSocket server) {
        try (Socket socket = server.accept()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            in.readNBytes(readVarInt(in));
            in.readNBytes(readVarInt(in));
            byte[] json = "{\"version\":{\"name\":\"1.21.11\",\"protocol\":774}}".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream packet = new DataOutputStream(body);
            writeVarInt(packet, 0x00);
            writeVarInt(packet, json.length);
            packet.write(json);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            writeVarInt(out, body.size());
            body.writeTo(out);
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; ; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }
}
