package co.xenastudios.xvote.receiver;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Sends a v2 vote the way a server list does, as NuVotifier's own forwarding client
 * builds it. {@code /vote test} uses it to send a vote to this server's receiver, so a
 * test goes through the same socket, protocol and token as a real one.
 */
public final class VoteClient {

    private VoteClient() {}

    /** A vote to send. */
    public record Vote(String service, String username, String address, String timestamp) {}

    /**
     * Sends a v2 vote signed with the token, and reads the reply.
     *
     * @return null if the server accepted it, otherwise its error
     * @throws IOException if the server can't be reached or doesn't answer like Votifier
     */
    public static String sendV2(String host, int port, String token, Vote vote, int timeoutMs) throws IOException {
        try (Socket socket = connect(host, port, timeoutMs)) {
            String challenge = challenge(readLine(socket.getInputStream(), 256));
            JsonObject payload = new JsonObject();
            payload.addProperty("serviceName", vote.service());
            payload.addProperty("username", vote.username());
            payload.addProperty("address", vote.address());
            payload.addProperty("timestamp", vote.timestamp());
            payload.addProperty("challenge", challenge);
            String payloadText = payload.toString();
            JsonObject message = new JsonObject();
            message.addProperty("payload", payloadText);
            message.addProperty("signature", Base64.getEncoder().encodeToString(Protocol.hmac(token, payloadText)));
            byte[] bytes = message.toString().getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream(bytes.length + 4);
            DataOutputStream out = new DataOutputStream(frame);
            out.writeShort(Protocol.V2_MAGIC);
            out.writeShort(bytes.length);
            out.write(bytes);
            socket.getOutputStream().write(frame.toByteArray());
            socket.getOutputStream().flush();
            JsonObject reply = JsonParser.parseString(readLine(socket.getInputStream(), 4096)).getAsJsonObject();
            if ("ok".equals(reply.get("status").getAsString())) {
                return null;
            }
            return reply.has("error") ? reply.get("error").getAsString() : "error";
        } catch (RuntimeException e) {
            throw new IOException("The server didn't answer like Votifier: " + e.getMessage(), e);
        }
    }

    private static Socket connect(String host, int port, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        return socket;
    }

    // "VOTIFIER 2 <challenge>": NuVotifier's client insists on three parts.
    private static String challenge(String greeting) throws IOException {
        String[] parts = greeting.split(" ");
        if (parts.length != 3 || !parts[0].equals("VOTIFIER")) {
            throw new IOException("The server's greeting wasn't Votifier's: " + greeting);
        }
        return parts[2];
    }

    private static String readLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (line.size() >= max) {
                throw new IOException("The server sent a line longer than " + max + " bytes.");
            }
            line.write(b);
        }
        if (b == -1 && line.size() == 0) {
            throw new IOException("The server closed the connection without answering.");
        }
        return line.toString(StandardCharsets.UTF_8).strip();
    }
}
