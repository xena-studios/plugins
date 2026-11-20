package co.xenastudios.xvote.receiver;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xvote.api.VoteSource;
import co.xenastudios.xvote.vote.IncomingVote;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Votifier's protocols against what NuVotifier and the lists send. The key, the v1 block
 * and the HMAC were made with openssl, not this code, so a mistake here can't hide behind
 * the same mistake on both sides; the rest builds votes exactly as NuVotifier's own client
 * and tests do ({@code VotifierProtocol2Encoder}, {@code VoteUtil}).
 */
class ProtocolTest {

    static final Path FIXTURES = Path.of("src/test/resources/nuvotifier/Votifier");
    static final String TOKEN = "r1kpn4qcl9m3c0k4tbm2ffbu3e";
    static final Function<String, String> TOKENS = Map.of("default", TOKEN, "MinecraftServers.org", "8m9f5ljm8okk2gcq2ncv8g1ab5")::get;

    @Test
    void greetingIsNuVotifiers() {
        assertArrayEquals("VOTIFIER 2 abc\n".getBytes(StandardCharsets.UTF_8), Protocol.greeting("abc"));
        // 130 random bits in base 32: up to 26 characters of 0-9 and a-v.
        String token = Protocol.newToken();
        assertTrue(token.matches("[0-9a-v]{1,26}"), token);
        assertArrayEquals("{\"status\":\"ok\"}\r\n".getBytes(StandardCharsets.UTF_8), Protocol.ok());
        JsonObject error = JsonParser.parseString(new String(Protocol.error("CorruptedFrameException", "Challenge is not valid"),
            StandardCharsets.UTF_8).strip()).getAsJsonObject();
        assertEquals("error", error.get("status").getAsString());
        assertEquals("CorruptedFrameException", error.get("cause").getAsString());
        assertEquals("Challenge is not valid", error.get("error").getAsString());
    }

    @Test
    void v1BlockFromOpenssl() throws Exception {
        KeyPair keys = Keys.load(FIXTURES.resolve("rsa"));
        byte[] block;
        try (InputStream in = getClass().getResourceAsStream("/nuvotifier/v1-vote.bin")) {
            block = in.readAllBytes();
        }
        IncomingVote vote = Protocol.decodeV1(block, keys.getPrivate(), "test", 42);
        assertEquals("PlanetMinecraft.com", vote.site());
        assertEquals("Steve", vote.username());
        assertEquals("203.0.113.7", vote.address());
        assertEquals("1731801600", vote.timestamp());
        assertEquals(VoteSource.VOTIFIER_V1, vote.source());
        assertEquals(42, vote.receivedAt());
    }

    /** NuVotifier's VoteUtil.encodePOJOv1: a naive join with a trailing newline, Cipher "RSA". */
    static byte[] nuVotifierV1(KeyPair keys, String text) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.ENCRYPT_MODE, keys.getPublic());
        return cipher.doFinal(text.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void v1AsNuVotifierSendsIt() throws Exception {
        KeyPair keys = Keys.load(FIXTURES.resolve("rsa"));
        IncomingVote vote = Protocol.decodeV1(nuVotifierV1(keys, "VOTE\nTest\ntest\ntest\ntest\n"), keys.getPrivate(), "x", 0);
        assertEquals("Test", vote.site());
        assertEquals("test", vote.username());
        // Some lists leave off the last newline.
        assertEquals("1", Protocol.decodeV1(nuVotifierV1(keys, "VOTE\na\nb\nc\n1"), keys.getPrivate(), "x", 0).timestamp());
    }

    @Test
    void v1Refusals() throws Exception {
        KeyPair keys = Keys.load(FIXTURES.resolve("rsa"));
        // NuVotifier's own failure cases: a missing field, a bad opcode, the wrong key.
        assertThrows(ProtocolException.class, () -> Protocol.decodeV1(nuVotifierV1(keys, "VOTE\nTest\ntest\ntest"), keys.getPrivate(), "x", 0));
        assertThrows(ProtocolException.class, () -> Protocol.decodeV1(nuVotifierV1(keys, "TEST\nTest\ntest\ntest\ntest\n"), keys.getPrivate(), "x", 0));
        KeyPair other = Keys.generate();
        assertThrows(ProtocolException.class, () -> Protocol.decodeV1(nuVotifierV1(other, "VOTE\na\nb\nc\nd\n"), keys.getPrivate(), "x", 0));
        assertThrows(ProtocolException.class, () -> Protocol.decodeV1(new byte[256], keys.getPrivate(), "x", 0));
        assertThrows(ProtocolException.class, () -> Protocol.decodeV1(new byte[255], keys.getPrivate(), "x", 0));
    }

    @Test
    void hmacMatchesOpenssl() {
        String payload = "{\"serviceName\":\"MinecraftServers.org\",\"username\":\"Alex\",\"address\":\"198.51.100.4\","
            + "\"timestamp\":\"1731801600000\",\"challenge\":\"abc123\"}";
        // printf '%s' "$payload" | openssl dgst -sha256 -hmac "$TOKEN" -binary | base64
        assertEquals("1CtkZuzR7a54A6WN7ZumPZfppdSkHjYktYFQ63Uen5o=", Base64.getEncoder().encodeToString(Protocol.hmac(TOKEN, payload)));
    }

    /** A v2 message as NuVotifier's VotifierProtocol2Encoder builds it. */
    static String nuVotifierV2(String service, String username, String timestamp, String challenge, String token) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("serviceName", service);
        payload.addProperty("username", username);
        payload.addProperty("address", "198.51.100.4");
        payload.addProperty("timestamp", timestamp);
        payload.addProperty("challenge", challenge);
        String text = payload.toString();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        JsonObject message = new JsonObject();
        message.addProperty("payload", text);
        message.addProperty("signature", Base64.getEncoder().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8))));
        return message.toString();
    }

    @Test
    void v2AsNuVotifierSendsIt() throws Exception {
        IncomingVote vote = Protocol.decodeV2(nuVotifierV2("Some List", "Alex", "1731801600000", "abc123", TOKEN), "abc123", TOKENS, 7);
        assertEquals("Some List", vote.site());
        assertEquals("Alex", vote.username());
        assertEquals("1731801600000", vote.timestamp());
        assertEquals(VoteSource.VOTIFIER_V2, vote.source());
        // A list with its own token uses it, not the default.
        Protocol.decodeV2(nuVotifierV2("MinecraftServers.org", "Alex", "1", "c", "8m9f5ljm8okk2gcq2ncv8g1ab5"), "c", TOKENS, 0);
        assertThrows(ProtocolException.class,
            () -> Protocol.decodeV2(nuVotifierV2("MinecraftServers.org", "Alex", "1", "c", TOKEN), "c", TOKENS, 0));
    }

    @Test
    void v2TimestampAsNumber() throws Exception {
        String message = "{\"payload\":\"{\\\"serviceName\\\":\\\"L\\\",\\\"username\\\":\\\"Alex\\\",\\\"address\\\":\\\"a\\\","
            + "\\\"timestamp\\\":1731801600000,\\\"challenge\\\":\\\"c\\\"}\",\"signature\":\"%s\"}";
        String payload = "{\"serviceName\":\"L\",\"username\":\"Alex\",\"address\":\"a\",\"timestamp\":1731801600000,\"challenge\":\"c\"}";
        String signed = message.formatted(Base64.getEncoder().encodeToString(Protocol.hmac(TOKEN, payload)));
        assertEquals("1731801600000", Protocol.decodeV2(signed, "c", TOKENS, 0).timestamp());
    }

    @Test
    void v2Refusals() throws Exception {
        // NuVotifier's own failure cases.
        assertCause("CorruptedFrameException", nuVotifierV2("L", "Alex", "0", "not the challenge", TOKEN), "abc");
        assertCause("CorruptedFrameException", nuVotifierV2("L", "Alex", "0", "abc", "BadKey"), "abc");
        assertCause("CorruptedFrameException", nuVotifierV2("L", "ThisNameIsTooLong", "0", "abc", TOKEN), "abc");
        ProtocolException unknown = assertThrows(ProtocolException.class,
            () -> Protocol.decodeV2(nuVotifierV2("L", "Alex", "0", "abc", TOKEN), "abc", Map.<String, String>of()::get, 0));
        assertEquals("Unknown service 'L'", unknown.getMessage());
        // No signature, not JSON, a payload that isn't an object.
        JsonObject unsigned = JsonParser.parseString(nuVotifierV2("L", "Alex", "0", "abc", TOKEN)).getAsJsonObject();
        unsigned.remove("signature");
        assertThrows(ProtocolException.class, () -> Protocol.decodeV2(unsigned.toString(), "abc", TOKENS, 0));
        assertThrows(ProtocolException.class, () -> Protocol.decodeV2("not json", "abc", TOKENS, 0));
        assertThrows(ProtocolException.class, () -> Protocol.decodeV2("{\"payload\":\"[1]\",\"signature\":\"x\"}", "abc", TOKENS, 0));
        assertThrows(ProtocolException.class, () -> Protocol.decodeV2("{\"payload\":\"{}\",\"signature\":\"!!\"}", "abc", TOKENS, 0));
    }

    @Test
    void aSendersOwnChoicesCantUseUpTheWarnings() throws Exception {
        // Made-up service names share one warning; a service with its own token is named.
        ProtocolException made = assertThrows(ProtocolException.class,
            () -> Protocol.decodeV2(nuVotifierV2("Made Up 1", "Alex", "0", "abc", "BadKey"), "abc", TOKENS, 0));
        ProtocolException other = assertThrows(ProtocolException.class,
            () -> Protocol.decodeV2(nuVotifierV2("Made Up 2", "Alex", "0", "abc", "BadKey"), "abc", TOKENS, 0));
        assertEquals(made.logKey(), other.logKey());
        ProtocolException own = assertThrows(ProtocolException.class,
            () -> Protocol.decodeV2(nuVotifierV2("MinecraftServers.org", "Alex", "0", "abc", "BadKey"), "abc", TOKENS, 0));
        assertEquals("v2-signature:MinecraftServers.org", own.logKey());
        // v1 can't tell a list from a scanner: one warning for every sender.
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        ProtocolException one = assertThrows(ProtocolException.class, () -> Protocol.decodeV1(new byte[256], keys.getPrivate(), "198.51.100.1", 0));
        ProtocolException two = assertThrows(ProtocolException.class, () -> Protocol.decodeV1(new byte[256], keys.getPrivate(), "198.51.100.2", 0));
        assertEquals(one.logKey(), two.logKey());
    }

    private static void assertCause(String cause, String message, String challenge) {
        ProtocolException e = assertThrows(ProtocolException.class, () -> Protocol.decodeV2(message, challenge, TOKENS, 0));
        assertEquals(cause, e.causeName());
    }

    /** Random bytes and mangled votes never get past the checks, and never throw anything else. */
    @Test
    void fuzz() throws Exception {
        java.util.Random random = new java.util.Random(21);
        KeyPair keys = Keys.load(FIXTURES.resolve("rsa"));
        String good = nuVotifierV2("L", "Alex", "0", "abc", TOKEN);
        for (int i = 0; i < 2_000; i++) {
            byte[] noise = new byte[random.nextInt(300)];
            random.nextBytes(noise);
            byte[] block = noise.length == 256 ? noise : java.util.Arrays.copyOf(noise, 256);
            assertThrows(ProtocolException.class, () -> Protocol.decodeV1(block, keys.getPrivate(), "x", 0));
            StringBuilder mangled = new StringBuilder(good);
            for (int j = 0; j < 1 + random.nextInt(4); j++) {
                int at = random.nextInt(mangled.length());
                mangled.setCharAt(at, (char) (32 + random.nextInt(95)));
            }
            try {
                Protocol.decodeV2(mangled.toString(), "abc", TOKENS, 0);
                // A change that leaves the vote intact (such as in whitespace) is fine; one that
                // alters the payload can't keep the signature.
            } catch (ProtocolException expected) {
                // Refused, as it should be.
            }
            String text = new String(noise, StandardCharsets.ISO_8859_1);
            assertThrows(ProtocolException.class, () -> Protocol.decodeV2(text, "abc", TOKENS, 0));
        }
    }
}
