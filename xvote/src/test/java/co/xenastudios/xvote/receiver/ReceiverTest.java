package co.xenastudios.xvote.receiver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xvote.api.VoteSource;
import co.xenastudios.xvote.vote.IncomingVote;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The receiver on a real socket: both protocols, and the limits that keep it safe. */
class ReceiverTest {

    static final String TOKEN = ProtocolTest.TOKEN;

    final LinkedBlockingQueue<IncomingVote> votes = new LinkedBlockingQueue<>();
    final List<String> logged = new ArrayList<>();
    final Logger logger = Logger.getAnonymousLogger();
    Receiver receiver;
    KeyPair keys;

    Receiver start(boolean v1, int maxConnections, int timeoutSeconds, int perMinute) throws Exception {
        keys = Keys.load(ProtocolTest.FIXTURES.resolve("rsa"));
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                synchronized (logged) {
                    logged.add(record.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        receiver = new Receiver(new Receiver.Settings("127.0.0.1", 0, v1, maxConnections, timeoutSeconds, perMinute),
            new Receiver.Credentials(keys, Tokens.of(Map.of("default", TOKEN))), votes::add, logger);
        receiver.start();
        return receiver;
    }

    @AfterEach
    void stop() {
        if (receiver != null) {
            receiver.close();
        }
    }

    @Test
    void v2ThroughTheSocket() throws Exception {
        start(true, 8, 5, 100);
        assertNull(VoteClient.sendV2("127.0.0.1", receiver.port(), TOKEN, new VoteClient.Vote("List", "Steve", "1.2.3.4", "99"), 2000));
        IncomingVote vote = votes.poll(2, TimeUnit.SECONDS);
        assertEquals("Steve", vote.username());
        assertEquals("List", vote.site());
        assertEquals(VoteSource.VOTIFIER_V2, vote.source());
    }

    @Test
    void v2WithTheWrongTokenGetsNuVotifiersError() throws Exception {
        start(true, 8, 5, 100);
        String error = VoteClient.sendV2("127.0.0.1", receiver.port(), "wrong", new VoteClient.Vote("List", "Steve", "", "1"), 2000);
        assertEquals("Signature is not valid (invalid token?)", error);
        // Logged once, however many times the list tries.
        VoteClient.sendV2("127.0.0.1", receiver.port(), "wrong", new VoteClient.Vote("List", "Steve", "", "2"), 2000);
        VoteClient.sendV2("127.0.0.1", receiver.port(), "wrong", new VoteClient.Vote("List", "Steve", "", "3"), 2000);
        synchronized (logged) {
            assertEquals(1, logged.stream().filter(line -> line.contains("Signature")).count(), logged.toString());
        }
        assertTrue(votes.isEmpty());
    }

    @Test
    void v1ThroughTheSocket() throws Exception {
        start(true, 8, 5, 100);
        sendV1("VOTE\nList\nAlex\n5.6.7.8\n12\n");
        IncomingVote vote = votes.poll(2, TimeUnit.SECONDS);
        assertEquals("Alex", vote.username());
        assertEquals(VoteSource.VOTIFIER_V1, vote.source());
    }

    @Test
    void v1CanBeTurnedOff() throws Exception {
        start(false, 8, 5, 100);
        sendV1("VOTE\nList\nAlex\n\n1\n");
        assertNull(votes.poll(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void oversizedV2IsRefused() throws Exception {
        start(true, 8, 5, 100);
        try (Socket socket = new Socket("127.0.0.1", receiver.port())) {
            socket.setSoTimeout(2000);
            readLine(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeShort(Protocol.V2_MAGIC);
            out.writeShort(Protocol.V2_MAX + 1);
            out.flush();
            String reply = readLine(socket.getInputStream());
            assertTrue(reply.contains("\"status\":\"error\""), reply);
        }
        assertTrue(votes.isEmpty());
    }

    @Test
    void aSilentConnectionIsClosedAtTheDeadline() throws Exception {
        start(true, 8, 1, 100);
        try (Socket socket = new Socket("127.0.0.1", receiver.port())) {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream();
            readLine(in);
            long started = System.nanoTime();
            assertEquals(-1, in.read(), "closed");
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(4));
        }
    }

    @Test
    void aTrickleCantHoldASlot() throws Exception {
        start(true, 8, 1, 100);
        try (Socket socket = new Socket("127.0.0.1", receiver.port())) {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream();
            readLine(in);
            long started = System.nanoTime();
            // A byte every 300 ms would take over a minute for a v1 block; the deadline is the whole conversation.
            try {
                for (int i = 0; i < 20; i++) {
                    socket.getOutputStream().write(1);
                    socket.getOutputStream().flush();
                    Thread.sleep(300);
                }
            } catch (IOException closed) {
                // The receiver hung up.
            }
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(6));
        }
    }

    @Test
    void connectionsPastTheCapAreTurnedAway() throws Exception {
        start(true, 2, 5, 100);
        List<Socket> held = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                Socket socket = new Socket("127.0.0.1", receiver.port());
                readLine(socket.getInputStream());
                held.add(socket);
            }
            try (Socket third = new Socket("127.0.0.1", receiver.port())) {
                third.setSoTimeout(2000);
                assertEquals(-1, third.getInputStream().read(), "turned away without a greeting");
            }
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }
        // Once they're gone, votes get through again.
        Thread.sleep(200);
        assertNull(VoteClient.sendV2("127.0.0.1", receiver.port(), TOKEN, new VoteClient.Vote("L", "Steve", "", "1"), 2000));
    }

    @Test
    void anAddressOverTheRateLimitIsTurnedAway() throws Exception {
        start(true, 8, 5, 3);
        for (int i = 0; i < 3; i++) {
            assertNull(VoteClient.sendV2("127.0.0.1", receiver.port(), TOKEN, new VoteClient.Vote("L", "Steve", "", "t" + i), 2000));
        }
        assertThrows(IOException.class,
            () -> VoteClient.sendV2("127.0.0.1", receiver.port(), TOKEN, new VoteClient.Vote("L", "Steve", "", "x"), 2000));
        assertEquals(3, votes.size());
    }

    @Test
    void closingFreesThePortAndDropsConnections() throws Exception {
        start(true, 8, 30, 100);
        int port = receiver.port();
        Socket idle = new Socket("127.0.0.1", port);
        idle.setSoTimeout(3000);
        readLine(idle.getInputStream());
        receiver.close();
        assertEquals(-1, idle.getInputStream().read(), "an open connection is dropped");
        idle.close();
        try (ServerSocket again = new ServerSocket(port)) {
            assertEquals(port, again.getLocalPort());
        }
        receiver = null;
    }

    @Test
    void anEmptyConnectionIsIgnoredQuietly() throws Exception {
        start(true, 8, 5, 100);
        try (Socket socket = new Socket("127.0.0.1", receiver.port())) {
            readLine(socket.getInputStream());
        }
        Thread.sleep(200);
        synchronized (logged) {
            assertTrue(logged.isEmpty(), logged.toString());
        }
    }

    /** A v1 vote as a list sends it: the greeting read, then one block encrypted as NuVotifier does. */
    void sendV1(String text) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", receiver.port())) {
            socket.setSoTimeout(2000);
            readLine(socket.getInputStream());
            socket.getOutputStream().write(ProtocolTest.nuVotifierV1(keys, text));
            socket.getOutputStream().flush();
        }
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            line.append((char) b);
        }
        return line.toString();
    }

    @Test
    void greetingCarriesAChallenge() throws Exception {
        start(true, 8, 5, 100);
        try (Socket socket = new Socket("127.0.0.1", receiver.port())) {
            String greeting = readLine(socket.getInputStream());
            String[] parts = greeting.split(" ");
            assertEquals(3, parts.length);
            assertEquals("VOTIFIER", parts[0]);
            assertEquals("2", parts[1]);
            assertTrue(parts[2].getBytes(StandardCharsets.US_ASCII).length > 10);
        }
    }
}
