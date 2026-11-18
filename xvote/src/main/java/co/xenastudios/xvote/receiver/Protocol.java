package co.xenastudios.xvote.receiver;

import co.xenastudios.xvote.api.VoteSource;
import co.xenastudios.xvote.vote.IncomingVote;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Function;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Votifier's two protocols, byte for byte as NuVotifier speaks them, so every server
 * list that works with NuVotifier works with xVote.
 *
 * <p>The server greets each connection with {@code VOTIFIER 2 <challenge>\n}. The list
 * then sends either:
 * <ul>
 *   <li><b>v1:</b> one 256-byte block, RSA-encrypted (PKCS#1 v1.5) with the server's
 *   2048-bit public key, holding {@code VOTE\n<service>\n<username>\n<address>\n<timestamp>\n}
 *   in ASCII. No reply; the connection closes.</li>
 *   <li><b>v2:</b> the two bytes {@code 0x733A}, a two-byte length, then that many bytes
 *   of JSON: {@code {"payload": "<json>", "signature": "<base64>"}}. The payload holds
 *   {@code serviceName}, {@code username}, {@code address}, {@code timestamp} and the
 *   greeting's {@code challenge}; the signature is its HMAC-SHA256 with the list's token.
 *   The reply is {@code {"status":"ok"}} or an error, then {@code \r\n}.</li>
 * </ul>
 */
public final class Protocol {

    /** The first two bytes of a v2 vote. */
    public static final int V2_MAGIC = 0x733A;
    /** A v1 vote's size: one block of a 2048-bit key. */
    public static final int V1_BLOCK = 256;
    /**
     * The largest v2 message read. NuVotifier refuses more than 1,024 bytes, so lists stay
     * well under; a little room costs nothing.
     */
    public static final int V2_MAX = 2048;
    /** Longest name a v2 vote may carry, as NuVotifier checks. */
    static final int MAX_USERNAME = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Protocol() {}

    /** A new challenge or token, as NuVotifier makes them: 130 random bits in base 32. */
    public static String newToken() {
        return new BigInteger(130, RANDOM).toString(32);
    }

    /** What the server says first. */
    public static byte[] greeting(String challenge) {
        return ("VOTIFIER 2 " + challenge + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** The reply to a v2 vote that was accepted. */
    public static byte[] ok() {
        return "{\"status\":\"ok\"}\r\n".getBytes(StandardCharsets.UTF_8);
    }

    /** The reply to a v2 vote that wasn't. */
    public static byte[] error(String cause, String message) {
        JsonObject reply = new JsonObject();
        reply.addProperty("status", "error");
        reply.addProperty("cause", cause);
        reply.addProperty("error", message);
        return (reply + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Reads a v1 block. */
    static IncomingVote decodeV1(byte[] block, PrivateKey key, String from, long now) throws ProtocolException {
        if (block.length != V1_BLOCK) {
            throw ProtocolException.noise("QuietException", "A v1 vote is " + V1_BLOCK + " bytes; got " + block.length + ".", "v1-size");
        }
        byte[] plain;
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, key);
            plain = cipher.doFinal(block);
        } catch (GeneralSecurityException e) {
            String why = "Couldn't decrypt a v1 vote from " + from + ". Check the list has this server's public key (/xvote key).";
            throw ProtocolException.vote("QuietException", why, "v1-decrypt:" + from, why);
        }
        String[] fields = new String(plain, StandardCharsets.US_ASCII).split("\n");
        if (fields.length < 5) {
            throw ProtocolException.noise("QuietException", "A v1 vote had " + fields.length
                + " fields instead of 5; the list sent it wrong.", "v1-fields");
        }
        if (!fields[0].equals("VOTE")) {
            throw ProtocolException.noise("QuietException", "A v1 vote didn't start with VOTE; the list sent it wrong.", "v1-opcode");
        }
        return new IncomingVote(fields[1], fields[2], fields[3], fields[4], VoteSource.VOTIFIER_V1, now);
    }

    /**
     * Reads and checks a v2 message.
     *
     * @param tokenFor a service name's token, or null if it has none (then {@code default}'s is tried)
     */
    static IncomingVote decodeV2(String message, String challenge, Function<String, String> tokenFor, long now) throws ProtocolException {
        try {
            JsonObject outer = JsonParser.parseString(message).getAsJsonObject();
            String payload = string(outer, "payload");
            JsonObject vote = JsonParser.parseString(payload).getAsJsonObject();
            if (!challenge.equals(string(vote, "challenge"))) {
                throw ProtocolException.noise("CorruptedFrameException", "Challenge is not valid", "v2-challenge");
            }
            String service = string(vote, "serviceName");
            // As it may be logged: no line breaks or other control characters a sender could fake log lines with.
            String shown = IncomingVote.clean(service);
            String token = tokenFor.apply(service);
            if (token == null) {
                token = tokenFor.apply("default");
            }
            if (token == null) {
                throw ProtocolException.vote("RuntimeException", "Unknown service '" + shown + "'", "v2-service:" + shown,
                    "A v2 vote came from \"" + shown + "\", which has no token and there's no default one. Add one with /xvote token.");
            }
            byte[] signature;
            try {
                signature = Base64.getDecoder().decode(string(outer, "signature"));
            } catch (IllegalArgumentException e) {
                throw badSignature(shown);
            }
            if (!MessageDigest.isEqual(hmac(token, payload), signature)) {
                throw badSignature(shown);
            }
            if (vote.has("uuid")) {
                UUID.fromString(vote.get("uuid").getAsString());
            }
            String username = string(vote, "username");
            if (username.length() > MAX_USERNAME) {
                throw ProtocolException.noise("CorruptedFrameException", "Username too long", "v2-username");
            }
            return new IncomingVote(service, username, string(vote, "address"), timestamp(vote.get("timestamp")),
                VoteSource.VOTIFIER_V2, now);
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException | IllegalArgumentException
                 | NullPointerException e) {
            // Missing fields, a field of the wrong kind, a malformed uuid or not JSON at all.
            throw ProtocolException.noise(e.getClass().getSimpleName(), "Not a well-formed vote: " + IncomingVote.clean(e.getMessage()),
                "v2-malformed");
        }
    }

    private static ProtocolException badSignature(String service) {
        return ProtocolException.vote("CorruptedFrameException", "Signature is not valid (invalid token?)", "v2-signature:" + service,
            "A v2 vote from \"" + service + "\" was signed with a different token. Give the list its token again (/xvote token).");
    }

    /** The HMAC-SHA256 of the payload's UTF-8 bytes, keyed with the token's UTF-8 bytes. */
    static byte[] hmac(String token, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This Java has no HmacSHA256", e);
        }
    }

    private static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            throw new IllegalStateException("no " + name);
        }
        return element.getAsString();
    }

    // Lists send a number or a string; NuVotifier keeps either as text.
    private static String timestamp(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            throw new IllegalStateException("no timestamp");
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            try {
                return Long.toString(element.getAsLong());
            } catch (NumberFormatException e) {
                return element.getAsString();
            }
        }
        return element.getAsString();
    }
}
