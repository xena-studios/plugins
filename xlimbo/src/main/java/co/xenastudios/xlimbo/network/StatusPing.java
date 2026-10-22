package co.xenastudios.xlimbo.network;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Asks a Minecraft server for its status, as the multiplayer screen does. A server only
 * answers once it's running, not while it's still loading, so an answer means players
 * can join. Blocking: call it off the main thread.
 */
final class StatusPing {

    private static final int TIMEOUT_MILLIS = 3000;

    private StatusPing() {}

    /** "host:port", "[ipv6]:port", or a host alone for the default port. */
    static InetSocketAddress parse(String address) {
        String trimmed = address.trim();
        if (trimmed.startsWith("[")) {
            int close = trimmed.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("\"" + address + "\" is missing a ]");
            }
            String host = trimmed.substring(1, close);
            String rest = trimmed.substring(close + 1);
            try {
                return InetSocketAddress.createUnresolved(host, rest.isEmpty() ? 25565 : Integer.parseInt(rest.substring(1)));
            } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
                throw new IllegalArgumentException("\"" + address + "\" has a port that isn't a number");
            }
        }
        int colon = trimmed.lastIndexOf(':');
        if (colon > 0 && trimmed.indexOf(':') == colon) {
            try {
                return InetSocketAddress.createUnresolved(trimmed.substring(0, colon), Integer.parseInt(trimmed.substring(colon + 1)));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("\"" + address + "\" has a port that isn't a number");
            }
        }
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("server-check.address is blank");
        }
        return InetSocketAddress.createUnresolved(trimmed, 25565);
    }

    /** Whether the server answered with its status. */
    static boolean isUp(InetSocketAddress address) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address.getHostString(), address.getPort()), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            byte[] host = address.getHostString().getBytes(StandardCharsets.UTF_8);

            // Handshake: protocol version (any), address, port, next state 1 (status).
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream packet = new DataOutputStream(handshake);
            writeVarInt(packet, 0x00);
            writeVarInt(packet, -1);
            writeVarInt(packet, host.length);
            packet.write(host);
            packet.writeShort(address.getPort());
            writeVarInt(packet, 1);
            DataOutputStream stream = new DataOutputStream(out);
            writeVarInt(stream, handshake.size());
            handshake.writeTo(stream);
            // Status request: an empty packet 0x00.
            writeVarInt(stream, 1);
            writeVarInt(stream, 0x00);
            stream.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int length = readVarInt(in);
            int id = readVarInt(in);
            int json = readVarInt(in);
            return length > 0 && id == 0x00 && json > 0;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("closed");
            }
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("varint too long");
    }
}
