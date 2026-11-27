package co.xenastudios.xvote.receiver

import co.xenastudios.xvote.ListLog
import co.xenastudios.xvote.Json
import co.xenastudios.xvote.ReceiverSettings
import co.xenastudios.xvote.api.VoteSource
import co.xenastudios.xvote.vote.IncomingVote
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyPair
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.net.ServerSocketFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fixtures: a throwaway key pair and tokens made with openssl, laid out as NuVotifier's folder. */
internal object Fixtures {
    val FOLDER: Path = Paths.get(Fixtures::class.java.getResource("/nuvotifier/Votifier/config.yml")!!.toURI()).parent
    const val TOKEN = "r1kpn4qcl9m3c0k4tbm2ffbu3e"
    val TOKENS: (String) -> String? = mapOf("default" to TOKEN, "MinecraftServers.org" to "8m9f5ljm8okk2gcq2ncv8g1ab5")::get

    fun keys(): KeyPair = Keys.load(FOLDER.resolve("rsa"))

    /** NuVotifier's VoteUtil.encodePOJOv1: a naive join with a trailing newline, Cipher "RSA". */
    fun nuVotifierV1(keys: KeyPair, text: String): ByteArray {
        val cipher = Cipher.getInstance("RSA")
        cipher.init(Cipher.ENCRYPT_MODE, keys.public)
        return cipher.doFinal(text.toByteArray(Charsets.US_ASCII))
    }

    /** A v2 message as NuVotifier's VotifierProtocol2Encoder builds it. */
    fun nuVotifierV2(service: String, username: String, timestamp: String, challenge: String, token: String): String {
        val payload = Json.write(linkedMapOf("serviceName" to service, "username" to username, "address" to "198.51.100.4", "timestamp" to timestamp, "challenge" to challenge))
        return Json.write(linkedMapOf("payload" to payload, "signature" to Base64.getEncoder().encodeToString(Protocol.hmac(token, payload))))
    }
}

/**
 * Votifier's protocols against what NuVotifier and the lists send, as 1.x's tests. The key, the v1
 * block and the HMAC were made with openssl, not this code, so a mistake here can't hide behind the
 * same mistake on both sides; the rest builds votes exactly as NuVotifier's own client does.
 */
class ProtocolTest {
    @Test
    fun greetingIsNuVotifiers() {
        assertContentEquals("VOTIFIER 2 abc\n".toByteArray(), Protocol.greeting("abc"))
        // 130 random bits in base 32: up to 26 characters of 0-9 and a-v.
        val token = Protocol.newToken()
        assertTrue(Regex("[0-9a-v]{1,26}").matches(token), token)
        assertContentEquals("{\"status\":\"ok\"}\r\n".toByteArray(), Protocol.ok())
        val error = Json.parse(String(Protocol.error("CorruptedFrameException", "Challenge is not valid")).trim()) as Map<*, *>
        assertEquals("error", error["status"])
        assertEquals("CorruptedFrameException", error["cause"])
        assertEquals("Challenge is not valid", error["error"])
    }

    @Test
    fun v1BlockFromOpenssl() {
        val block = javaClass.getResourceAsStream("/nuvotifier/v1-vote.bin")!!.use { it.readBytes() }
        val vote = Protocol.decodeV1(block, Fixtures.keys().private, 42)
        assertEquals("PlanetMinecraft.com", vote.site)
        assertEquals("Steve", vote.username)
        assertEquals("203.0.113.7", vote.address)
        assertEquals("1731801600", vote.timestamp)
        assertEquals(VoteSource.VOTIFIER_V1, vote.source)
        assertEquals(42, vote.receivedAt)
    }

    @Test
    fun v1AsNuVotifierSendsIt() {
        val keys = Fixtures.keys()
        val vote = Protocol.decodeV1(Fixtures.nuVotifierV1(keys, "VOTE\nTest\ntest\ntest\ntest\n"), keys.private, 0)
        assertEquals("Test", vote.site)
        assertEquals("test", vote.username)
        // Some lists leave off the last newline.
        assertEquals("1", Protocol.decodeV1(Fixtures.nuVotifierV1(keys, "VOTE\na\nb\nc\n1"), keys.private, 0).timestamp)
    }

    @Test
    fun v1Refusals() {
        val keys = Fixtures.keys()
        // NuVotifier's own failure cases: a missing field, a bad opcode, the wrong key.
        assertFailsWith<ProtocolException> { Protocol.decodeV1(Fixtures.nuVotifierV1(keys, "VOTE\nTest\ntest\ntest"), keys.private, 0) }
        assertFailsWith<ProtocolException> { Protocol.decodeV1(Fixtures.nuVotifierV1(keys, "TEST\nTest\ntest\ntest\ntest\n"), keys.private, 0) }
        val other = Keys.generate()
        assertFailsWith<ProtocolException> { Protocol.decodeV1(Fixtures.nuVotifierV1(other, "VOTE\na\nb\nc\nd\n"), keys.private, 0) }
        assertFailsWith<ProtocolException> { Protocol.decodeV1(ByteArray(256), keys.private, 0) }
        assertFailsWith<ProtocolException> { Protocol.decodeV1(ByteArray(255), keys.private, 0) }
    }

    @Test
    fun hmacMatchesOpenssl() {
        val payload = "{\"serviceName\":\"MinecraftServers.org\",\"username\":\"Alex\",\"address\":\"198.51.100.4\",\"timestamp\":\"1731801600000\",\"challenge\":\"abc123\"}"
        // printf '%s' "$payload" | openssl dgst -sha256 -hmac "$TOKEN" -binary | base64
        assertEquals("1CtkZuzR7a54A6WN7ZumPZfppdSkHjYktYFQ63Uen5o=", Base64.getEncoder().encodeToString(Protocol.hmac(Fixtures.TOKEN, payload)))
    }

    @Test
    fun v2AsNuVotifierSendsIt() {
        val vote = Protocol.decodeV2(Fixtures.nuVotifierV2("Some List", "Alex", "1731801600000", "abc123", Fixtures.TOKEN), "abc123", Fixtures.TOKENS, 7)
        assertEquals("Some List", vote.site)
        assertEquals("Alex", vote.username)
        assertEquals("1731801600000", vote.timestamp)
        assertEquals(VoteSource.VOTIFIER_V2, vote.source)
        // A list with its own token uses it, not the default.
        Protocol.decodeV2(Fixtures.nuVotifierV2("MinecraftServers.org", "Alex", "1", "c", "8m9f5ljm8okk2gcq2ncv8g1ab5"), "c", Fixtures.TOKENS, 0)
        assertFailsWith<ProtocolException> { Protocol.decodeV2(Fixtures.nuVotifierV2("MinecraftServers.org", "Alex", "1", "c", Fixtures.TOKEN), "c", Fixtures.TOKENS, 0) }
    }

    @Test
    fun v2TimestampAsNumber() {
        val payload = "{\"serviceName\":\"L\",\"username\":\"Alex\",\"address\":\"a\",\"timestamp\":1731801600000,\"challenge\":\"c\"}"
        val signed = Json.write(linkedMapOf("payload" to payload, "signature" to Base64.getEncoder().encodeToString(Protocol.hmac(Fixtures.TOKEN, payload))))
        assertEquals("1731801600000", Protocol.decodeV2(signed, "c", Fixtures.TOKENS, 0).timestamp)
    }

    @Test
    fun v2Refusals() {
        fun cause(message: String, challenge: String) = assertFailsWith<ProtocolException> { Protocol.decodeV2(message, challenge, Fixtures.TOKENS, 0) }.causeName
        // NuVotifier's own failure cases.
        assertEquals("CorruptedFrameException", cause(Fixtures.nuVotifierV2("L", "Alex", "0", "not the challenge", Fixtures.TOKEN), "abc"))
        assertEquals("CorruptedFrameException", cause(Fixtures.nuVotifierV2("L", "Alex", "0", "abc", "BadKey"), "abc"))
        assertEquals("CorruptedFrameException", cause(Fixtures.nuVotifierV2("L", "ThisNameIsTooLong", "0", "abc", Fixtures.TOKEN), "abc"))
        val unknown = assertFailsWith<ProtocolException> { Protocol.decodeV2(Fixtures.nuVotifierV2("L", "Alex", "0", "abc", Fixtures.TOKEN), "abc", { null }, 0) }
        assertEquals("Unknown service 'L'", unknown.message)
        // No signature, not JSON, a payload that isn't an object.
        val unsigned = (Json.parse(Fixtures.nuVotifierV2("L", "Alex", "0", "abc", Fixtures.TOKEN)) as Map<*, *>).filterKeys { it != "signature" }
        assertFailsWith<ProtocolException> { Protocol.decodeV2(Json.write(unsigned), "abc", Fixtures.TOKENS, 0) }
        assertFailsWith<ProtocolException> { Protocol.decodeV2("not json", "abc", Fixtures.TOKENS, 0) }
        assertFailsWith<ProtocolException> { Protocol.decodeV2("{\"payload\":\"[1]\",\"signature\":\"x\"}", "abc", Fixtures.TOKENS, 0) }
        assertFailsWith<ProtocolException> { Protocol.decodeV2("{\"payload\":\"{}\",\"signature\":\"!!\"}", "abc", Fixtures.TOKENS, 0) }
    }

    @Test
    fun aSendersOwnChoicesCantUseUpTheWarnings() {
        val made = assertFailsWith<ProtocolException> { Protocol.decodeV2(Fixtures.nuVotifierV2("Made Up 1", "Alex", "0", "abc", "BadKey"), "abc", Fixtures.TOKENS, 0) }
        val other = assertFailsWith<ProtocolException> { Protocol.decodeV2(Fixtures.nuVotifierV2("Made Up 2", "Alex", "0", "abc", "BadKey"), "abc", Fixtures.TOKENS, 0) }
        assertEquals(made.logKey, other.logKey)
        val own = assertFailsWith<ProtocolException> { Protocol.decodeV2(Fixtures.nuVotifierV2("MinecraftServers.org", "Alex", "0", "abc", "BadKey"), "abc", Fixtures.TOKENS, 0) }
        assertEquals("v2-signature:MinecraftServers.org", own.logKey)
        val keys = Keys.generate()
        val one = assertFailsWith<ProtocolException> { Protocol.decodeV1(ByteArray(256), keys.private, 0) }
        val two = assertFailsWith<ProtocolException> { Protocol.decodeV1(ByteArray(256), keys.private, 0) }
        assertEquals(one.logKey, two.logKey)
    }

    /** Random bytes and mangled votes never get past the checks, and never throw anything else. */
    @Test
    fun fuzz() {
        val random = java.util.Random(21)
        val keys = Fixtures.keys()
        val good = Fixtures.nuVotifierV2("L", "Alex", "0", "abc", Fixtures.TOKEN)
        repeat(2_000) {
            val noise = ByteArray(random.nextInt(300)).also(random::nextBytes)
            val block = if (noise.size == 256) noise else noise.copyOf(256)
            assertFailsWith<ProtocolException> { Protocol.decodeV1(block, keys.private, 0) }
            val mangled = StringBuilder(good)
            repeat(1 + random.nextInt(4)) { mangled.setCharAt(random.nextInt(mangled.length), (32 + random.nextInt(95)).toChar()) }
            try {
                Protocol.decodeV2(mangled.toString(), "abc", Fixtures.TOKENS, 0)
            } catch (expected: ProtocolException) {
                // Refused, as it should be.
            }
            assertFailsWith<ProtocolException> { Protocol.decodeV2(String(noise, Charsets.ISO_8859_1), "abc", Fixtures.TOKENS, 0) }
        }
    }
}

/** The receiver on a real socket, as 1.x's tests: both protocols, every limit, and a failing accept. */
class ReceiverTest {
    private val votes = LinkedBlockingQueue<IncomingVote>()
    private val log = ListLog()
    private var receiver: Receiver? = null

    private fun start(v1: Boolean = true, max: Int = 8, perIp: Int = 0, timeout: Int = 5, perMinute: Int = 100, sockets: ServerSocketFactory = ServerSocketFactory.getDefault()): Receiver {
        val made = Receiver(ReceiverSettings("127.0.0.1", 0, v1, max, perIp, timeout, perMinute), Receiver.Credentials(Fixtures.keys(), Tokens.of(mapOf("default" to Fixtures.TOKEN))), { votes += it }, log, sockets)
        made.start()
        receiver = made
        return made
    }

    @AfterTest
    fun stop() {
        receiver?.close()
    }

    private fun send(port: Int, token: String = Fixtures.TOKEN, name: String = "Steve", timestamp: String = "1") =
        VoteClient.sendV2("127.0.0.1", port, token, "List", name, "1.2.3.4", timestamp, 2000)

    private fun readLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            out.write(b)
        }
        return String(out.toByteArray())
    }

    private fun sendV1(port: Int, text: String) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2000
            readLine(socket.getInputStream())
            socket.getOutputStream().write(Fixtures.nuVotifierV1(Fixtures.keys(), text))
            socket.getOutputStream().flush()
            try {
                socket.getInputStream().read()
            } catch (reset: IOException) {
                // The receiver hung up before reading it all (v1 turned off).
            }
        }
    }

    @Test
    fun v2ThroughTheSocket() {
        val r = start()
        assertNull(send(r.port))
        val vote = votes.poll(2, TimeUnit.SECONDS)!!
        assertEquals("Steve", vote.username)
        assertEquals("List", vote.site)
        assertEquals(VoteSource.VOTIFIER_V2, vote.source)
    }

    @Test
    fun v2WithTheWrongTokenGetsNuVotifiersError() {
        val r = start()
        assertEquals("Signature is not valid (invalid token?)", send(r.port, "wrong", timestamp = "1"))
        send(r.port, "wrong", timestamp = "2")
        send(r.port, "wrong", timestamp = "3")
        assertEquals(1, log.lines.count { "different token" in it }, log.lines.toList().toString())
        assertTrue(votes.isEmpty())
    }

    @Test
    fun v1ThroughTheSocket() {
        val r = start()
        sendV1(r.port, "VOTE\nList\nAlex\n5.6.7.8\n12\n")
        val vote = votes.poll(2, TimeUnit.SECONDS)!!
        assertEquals("Alex", vote.username)
        assertEquals(VoteSource.VOTIFIER_V1, vote.source)
    }

    @Test
    fun v1CanBeTurnedOff() {
        val r = start(v1 = false)
        sendV1(r.port, "VOTE\nList\nAlex\n\n1\n")
        assertNull(votes.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun oversizedV2IsRefused() {
        val r = start()
        Socket("127.0.0.1", r.port).use { socket ->
            socket.soTimeout = 2000
            readLine(socket.getInputStream())
            val out = DataOutputStream(socket.getOutputStream())
            out.writeShort(Protocol.V2_MAGIC)
            out.writeShort(Protocol.V2_MAX + 1)
            out.flush()
            val reply = readLine(socket.getInputStream())
            assertTrue("\"status\":\"error\"" in reply, reply)
        }
        assertTrue(votes.isEmpty())
    }

    @Test
    fun aSilentConnectionIsClosedAtTheDeadline() {
        val r = start(timeout = 1)
        Socket("127.0.0.1", r.port).use { socket ->
            socket.soTimeout = 5000
            readLine(socket.getInputStream())
            val started = System.nanoTime()
            assertEquals(-1, socket.getInputStream().read(), "closed")
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(4))
        }
    }

    @Test
    fun aTrickleCantHoldASlot() {
        val r = start(timeout = 1)
        Socket("127.0.0.1", r.port).use { socket ->
            socket.soTimeout = 5000
            readLine(socket.getInputStream())
            val started = System.nanoTime()
            // A byte every 300 ms would take over a minute for a v1 block; the deadline is the whole conversation.
            try {
                repeat(20) {
                    socket.getOutputStream().write(1)
                    socket.getOutputStream().flush()
                    Thread.sleep(300)
                }
            } catch (closed: IOException) {
                // The receiver hung up.
            }
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(7))
        }
    }

    @Test
    fun connectionsPastTheCapAreTurnedAway() {
        val r = start(max = 2)
        val held = ArrayList<Socket>()
        try {
            repeat(2) { held += Socket("127.0.0.1", r.port).also { readLine(it.getInputStream()) } }
            Socket("127.0.0.1", r.port).use { third ->
                third.soTimeout = 2000
                assertEquals(-1, third.getInputStream().read(), "turned away without a greeting")
            }
        } finally {
            held.forEach(Socket::close)
        }
        Thread.sleep(300)
        assertNull(send(r.port))
    }

    @Test
    fun anAddressOverTheRateLimitIsTurnedAway() {
        val r = start(perMinute = 3)
        repeat(3) { assertNull(send(r.port, timestamp = "t$it")) }
        assertFailsWith<IOException> { send(r.port, timestamp = "x") }
        assertEquals(3, votes.size)
    }

    @Test
    fun closingFreesThePortAndDropsConnections() {
        val r = start(timeout = 30)
        val port = r.port
        val idle = Socket("127.0.0.1", port)
        idle.soTimeout = 3000
        readLine(idle.getInputStream())
        r.close()
        assertEquals(-1, idle.getInputStream().read(), "an open connection is dropped")
        idle.close()
        ServerSocket(port).use { assertEquals(port, it.localPort) }
        receiver = null
    }

    @Test
    fun anEmptyConnectionIsIgnoredQuietly() {
        val r = start()
        Socket("127.0.0.1", r.port).use { readLine(it.getInputStream()) }
        Thread.sleep(200)
        assertTrue(log.lines.isEmpty(), log.lines.toList().toString())
    }

    @Test
    fun aFailedAcceptDoesntStopTheReceiver() {
        // The first two accepts fail as running out of file handles would; the third and later work.
        val accepts = AtomicInteger()
        val failing = object : ServerSocketFactory() {
            override fun createServerSocket(): ServerSocket = object : ServerSocket() {
                override fun accept(): Socket {
                    if (accepts.incrementAndGet() <= 2) throw IOException("Too many open files")
                    return super.accept()
                }
            }

            override fun createServerSocket(port: Int): ServerSocket = throw UnsupportedOperationException()
            override fun createServerSocket(port: Int, backlog: Int): ServerSocket = throw UnsupportedOperationException()
            override fun createServerSocket(port: Int, backlog: Int, address: InetAddress?): ServerSocket = throw UnsupportedOperationException()
        }
        val r = start(sockets = failing)
        assertNull(VoteClient.sendV2("127.0.0.1", r.port, Fixtures.TOKEN, "List", "Steve", "", "1", 3000))
        assertEquals("Steve", votes.poll(2, TimeUnit.SECONDS)!!.username)
        assertTrue(r.listening, "still listening")
        assertTrue(accepts.get() >= 3)
        assertEquals(1, log.lines.count { "couldn't accept" in it }, log.lines.toList().toString())
        r.close()
        assertFalse(r.listening, "closing is what stops it")
    }

    @Test
    fun oneAddressCantHoldEverySlot() {
        val r = start(max = 50, perIp = 2)
        val held = ArrayList<Socket>()
        try {
            repeat(2) { held += Socket("127.0.0.1", r.port).also { readLine(it.getInputStream()) } }
            Socket("127.0.0.1", r.port).use { third ->
                third.soTimeout = 2000
                assertEquals(-1, third.getInputStream().read(), "turned away: two are already open from here")
            }
        } finally {
            held.forEach(Socket::close)
        }
        Thread.sleep(300)
        assertNull(send(r.port))
        assertTrue(log.lines.any { "per-ip-connections" in it }, log.lines.toList().toString())
        assertTrue(log.lines.none { "127.0.0.1" in it }, "noise doesn't name the address: ${log.lines}")
    }

    @Test
    fun ipv6CountsByItsSlash64() {
        val a = RateLimiter.key(InetAddress.getByName("2001:db8:1:2:3:4:5:6"))
        val b = RateLimiter.key(InetAddress.getByName("2001:db8:1:2:ffff:ffff:ffff:ffff"))
        assertEquals(a, b)
        assertEquals("20010db800010002/64", a)
        assertEquals("203.0.113.9", RateLimiter.key(InetAddress.getByName("203.0.113.9")))
    }
}

/** NuVotifier's folder taken over, and the host that keeps the receiver in step with the config. */
class ReceiverHostTest {
    private val folder = Files.createTempDirectory("xvote-host")
    private val log = ListLog()

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    @Test
    fun nuVotifiersKeysTokensAndPortAreTakenOverOnTheFirstStart() {
        val plugins = folder.resolve("plugins")
        val nuvotifier = plugins.resolve("Votifier")
        Files.createDirectories(nuvotifier.resolve("rsa"))
        for (name in listOf("config.yml", "rsa/public.key", "rsa/private.key")) Files.copy(Fixtures.FOLDER.resolve(name), nuvotifier.resolve(name))
        val xvote = plugins.resolve("xVote")
        val ports = ArrayList<Int>()
        val votes = LinkedBlockingQueue<IncomingVote>()
        val files = object : co.xenastudios.xvote.Files {
            override fun load(): co.xenastudios.xvote.Loaded = throw UnsupportedOperationException()
            override fun useReceiver(port: Int, host: String?, v1: Boolean): String? = null.also { ports += port }
        }
        val host = ReceiverHost(xvote, log, "plugins/xVote/rsa/public.key", plugins, files, sink = { votes += it })
        try {
            host.apply(ReceiverSettings("127.0.0.1", 0, true, 8, 4, 5, 60))
            assertEquals(listOf(8193), ports, "NuVotifier's port, into config.yml")
            assertEquals(Keys.publicText(Fixtures.keys()), host.publicKey())
            assertEquals("8m9f5ljm8okk2gcq2ncv8g1ab5", host.tokens()!!["MinecraftServers.org"], "a dotted service name stays one key")
            assertTrue(log.lines.any { "Took over NuVotifier's keys and tokens" in it }, log.lines.toList().toString())
            // A test vote through its own port.
            assertNull(host.sendTest("MinecraftServers.org", "Steve", "1"))
            assertEquals("Steve", votes.poll(2, TimeUnit.SECONDS)!!.username)
            // A new token, saved and used at once.
            assertNull(host.saveToken("New List", "secret-ish"))
            assertEquals("secret-ish", Tokens.loadOrCreate(xvote).first.forService("new list"))
            assertNotNull(host.status)
        } finally {
            host.close()
        }
        assertNull(host.status)
    }

    @Test
    fun aFreshFolderGetsAKeyAndADefaultToken() {
        val host = ReceiverHost(folder, log, "rsa/public.key")
        try {
            host.apply(ReceiverSettings("127.0.0.1", 0, true, 8, 4, 5, 60))
            assertTrue(Keys.exist(folder.resolve("rsa")))
            assertEquals(setOf("default"), host.tokens()!!.keys)
            assertTrue(host.status!!.listening)
            host.apply(null)
            assertNull(host.status)
        } finally {
            host.close()
        }
    }
}
