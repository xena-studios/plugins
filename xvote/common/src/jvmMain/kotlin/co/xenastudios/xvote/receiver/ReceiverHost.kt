package co.xenastudios.xvote.receiver

import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.platform.Log
import co.xenastudios.xvote.Json
import co.xenastudios.xvote.ReceiverSettings
import co.xenastudios.xvote.ReceiverStatus
import co.xenastudios.xvote.TestSend
import co.xenastudios.xvote.VoteReceiver
import co.xenastudios.xvote.vote.IncomingVote
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.util.Base64

/**
 * Keeps the receiver matching the config: started, stopped, or restarted when its address or limits
 * change, and given the current keys and tokens on every reload. Keys and tokens are files, so all of
 * it runs off the server's threads, one change at a time. On its first start (no keys or tokens of its
 * own yet), NuVotifier's are taken over, so the lists the owner already set up keep working.
 */
public class ReceiverHost(
    private val folder: Path,
    private val log: Log,
    /** Where the key is, as the owner finds it: `plugins/xVote/rsa/public.key`. */
    override val keyFile: String,
    /** The plugins folder NuVotifier would be in, or `null` where there's none (Fabric). */
    private val nuVotifierIn: Path? = null,
    /** Sets the receiver's settings in `config.yml` to NuVotifier's, as it's taken over. */
    private val files: co.xenastudios.xvote.Files? = null,
    /** The command its lines name: `xvote`, or the proxy's `xvoteproxy`. */
    private val command: String = "xvote",
    /** Where votes go before [plugin] is set, for tests. */
    private val sink: ((IncomingVote) -> Unit)? = null,
) : VoteReceiver, (IncomingVote) -> Unit {
    /** Where every vote that arrives goes. */
    @Volatile
    public var plugin: co.xenastudios.xvote.XVotePlugin? = null

    override fun invoke(vote: IncomingVote) {
        plugin?.accept(vote) ?: sink?.invoke(vote)
    }

    private val lock = Any()
    private var receiver: Receiver? = null
    private var failedSettings: ReceiverSettings? = null
    private var credentials: Receiver.Credentials? = null
    private var closed = false
    private var tookOver = false

    override fun apply(settings: ReceiverSettings?) {
        synchronized(lock) {
            // The plugin was disabled while this waited its turn: never bind after that.
            if (closed) return
            if (!tookOver) {
                tookOver = true
                takeOverNuVotifier()
            }
            loadCredentials()?.let { credentials = it }
            val current = credentials
            if (settings == null || current == null) return stop()
            val running = receiver
            if (running != null && running.settings == settings && running.listening) {
                running.credentials = current
                return
            }
            stop()
            val next = Receiver(settings, current, this, log)
            try {
                next.start()
                receiver = next
                failedSettings = null
                log.info("Listening for votes on ${settings.host.ifBlank { "0.0.0.0" }}:${next.port} (Votifier ${if (settings.v1) "v1 and v2" else "v2 only"}).")
            } catch (e: IOException) {
                next.close()
                // Logged once per setting, not on every reload that tries the same port.
                if (settings != failedSettings) {
                    failedSettings = settings
                    val why = if (e is BindException) "something else is using it (NuVotifier, or another server?)" else e.message
                    log.error("Couldn't listen for votes on port ${settings.port}: $why. Votes from lists can't arrive until receiver.port is free or changed; /$command reload tries again.")
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            stop()
        }
    }

    private fun stop() {
        receiver?.close()
        receiver = null
    }

    override val status: ReceiverStatus?
        get() = synchronized(lock) { receiver?.let { ReceiverStatus(it.port, it.listening, it.settings.v1, it.credentials.tokens.all.size) } }

    /** The running receiver, or `null`. */
    public val running: Receiver? get() = synchronized(lock) { receiver }

    override fun publicKey(): String? = synchronized(lock) { (credentials ?: loadCredentials())?.keys?.let(Keys::publicText) }

    override fun tokens(): Map<String, String>? = synchronized(lock) { (credentials ?: loadCredentials())?.tokens?.all }

    override fun newToken(): String = Protocol.newToken()

    override fun saveToken(service: String, token: String): String? = synchronized(lock) {
        val current = credentials ?: loadCredentials() ?: return@synchronized "the tokens couldn't be read"
        val next = current.tokens.with(service, token)
        try {
            next.save(folder)
        } catch (e: IOException) {
            return@synchronized e.message ?: e.toString()
        }
        val made = Receiver.Credentials(current.keys, next)
        credentials = made
        receiver?.credentials = made
        null
    }

    override fun sendTest(service: String, username: String, timestamp: String): TestSend? {
        val running = running ?: return TestSend(true, "the receiver isn't running")
        val tokens = running.credentials.tokens
        val token = tokens.forService(service) ?: tokens.forService(Tokens.DEFAULT) ?: ""
        val host = running.settings.host.let { if (it.isBlank() || it == "0.0.0.0") "127.0.0.1" else it }
        return try {
            VoteClient.sendV2(host, running.port, token, service, username, "127.0.0.1", timestamp, 5000)?.let { TestSend(false, it) }
        } catch (e: IOException) {
            TestSend(true, e.message ?: e.toString())
        }
    }

    private fun loadCredentials(): Receiver.Credentials? {
        val rsa = folder.resolve("rsa")
        val keys: KeyPair = try {
            val made = !Keys.exist(rsa)
            Keys.loadOrCreate(rsa).also {
                if (made) log.info("Made a new RSA key pair for Votifier v1 in rsa/. Lists that ask for a public key get it with /$command key.")
            }
        } catch (e: Exception) {
            log.error("Couldn't read the Votifier keys: ${e.message}")
            return null
        }
        return try {
            val (tokens, created) = Tokens.loadOrCreate(folder)
            if (created) log.info("Made a Votifier token in tokens.yml. Lists that ask for a token get it with /$command token.")
            Receiver.Credentials(keys, tokens)
        } catch (e: Exception) {
            log.error("Couldn't read ${Tokens.FILE}: ${e.message}")
            null
        }
    }

    // On the first start only (no keys or tokens of xVote's own yet), NuVotifier's are taken over.
    private fun takeOverNuVotifier() {
        val plugins = nuVotifierIn ?: return
        if (Files.exists(folder.resolve("rsa")) || Files.exists(folder.resolve(Tokens.FILE))) return
        val found = NuVotifier.find(plugins) ?: return
        try {
            val read = NuVotifier.read(found)
            NuVotifier.write(read, folder)
            val what = listOfNotNull(if (read.keys != null) "keys" else null, if (read.tokens != null) "tokens" else null).joinToString(" and ")
            log.info("Took over NuVotifier's $what from plugins/${found.fileName}, so your lists keep working.")
            read.usePort(files)?.let { log.warn("Couldn't set receiver.port to NuVotifier's ${read.port}: $it") }
        } catch (e: IOException) {
            log.warn("Couldn't take over NuVotifier's setup from plugins/${found.fileName}: ${e.message}")
        }
    }
}

/**
 * Sends a v2 vote the way a server list does, as NuVotifier's own forwarding client builds it.
 * `/vote test` uses it to send a vote to this server's receiver, so a test goes through the same
 * socket, protocol and token as a real one.
 */
public object VoteClient {
    /** Sends a v2 vote signed with [token], and reads the reply: `null` if it was accepted, otherwise the error. Throws [IOException] if it can't be reached or doesn't answer like Votifier. */
    public fun sendV2(host: String, port: Int, token: String, service: String, username: String, address: String, timestamp: String, timeoutMs: Int): String? {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            try {
                val challenge = challenge(readLine(socket.getInputStream(), 256))
                val payload = Json.write(linkedMapOf("serviceName" to service, "username" to username, "address" to address, "timestamp" to timestamp, "challenge" to challenge))
                val message = Json.write(linkedMapOf("payload" to payload, "signature" to Base64.getEncoder().encodeToString(Protocol.hmac(token, payload))))
                val bytes = message.toByteArray(Charsets.UTF_8)
                val frame = ByteArrayOutputStream(bytes.size + 4)
                DataOutputStream(frame).apply {
                    writeShort(Protocol.V2_MAGIC)
                    writeShort(bytes.size)
                    write(bytes)
                }
                socket.getOutputStream().write(frame.toByteArray())
                socket.getOutputStream().flush()
                val reply = Json.parse(readLine(socket.getInputStream(), 4096)) as? Map<*, *> ?: throw IOException("its answer isn't an object")
                if (reply["status"] == "ok") return null
                return reply["error"]?.toString() ?: "error"
            } catch (e: Json.Malformed) {
                throw IOException("The server didn't answer like Votifier: ${e.message}", e)
            }
        }
    }

    // "VOTIFIER 2 <challenge>": NuVotifier's client insists on three parts.
    private fun challenge(greeting: String): String {
        val parts = greeting.split(" ")
        if (parts.size != 3 || parts[0] != "VOTIFIER") throw IOException("The server's greeting wasn't Votifier's: $greeting")
        return parts[2]
    }

    private fun readLine(input: InputStream, max: Int): String {
        val line = ByteArrayOutputStream()
        var b = input.read()
        while (b != -1 && b != '\n'.code) {
            if (line.size() >= max) throw IOException("The server sent a line longer than $max bytes.")
            line.write(b)
            b = input.read()
        }
        if (b == -1 && line.size() == 0) throw IOException("The server closed the connection without answering.")
        return String(line.toByteArray(), Charsets.UTF_8).trim()
    }
}

/**
 * Takes over NuVotifier's setup: its RSA keys, its tokens, and the port it listened on, so every list
 * keeps working without the owner touching a thing. NuVotifier calls its folder `Votifier`; some
 * installs renamed it.
 */
public object NuVotifier {
    /** Folders NuVotifier may live in, in the plugins folder. */
    internal val FOLDERS = listOf("Votifier", "NuVotifier", "votifier", "nuvotifier")

    /** What was read from NuVotifier: [port] 0 and [host] empty if its config didn't say. */
    public class Found(public val folder: Path, public val keys: KeyPair?, public val tokens: Tokens?, public val host: String, public val port: Int, public val v1: Boolean) {
        /** Points xVote's receiver at NuVotifier's port, address and v1 setting in [files]' `config.yml`. */
        public fun usePort(files: co.xenastudios.xvote.Files?): String? {
            val address = host.takeIf { it.isNotBlank() && '%' !in it }
            return if (files != null && (port > 0 || address != null || !v1)) files.useReceiver(port, address, v1) else null
        }

        /** What was taken, in English: `its keys, 3 tokens, port 8192`. */
        public fun describe(): String = listOfNotNull(
            if (keys != null) "its keys" else "no keys",
            tokens?.let { "${it.all.size} tokens" } ?: "no tokens",
            if (port > 0) "port $port" else null,
        ).joinToString(", ")
    }

    /** NuVotifier's folder in [plugins], or `null` if there's none with keys or a config. */
    public fun find(plugins: Path): Path? = FOLDERS.map(plugins::resolve).firstOrNull { Keys.exist(it.resolve("rsa")) || Files.isRegularFile(it.resolve("config.yml")) }

    /** Reads NuVotifier's keys and config: throws [IOException] with why, for the owner. */
    public fun read(folder: Path): Found {
        val keys = if (Keys.exist(folder.resolve("rsa"))) Keys.load(folder.resolve("rsa")) else null
        var tokens: Map<String, String> = emptyMap()
        var host = ""
        var port = 0
        var v1 = true
        val config = folder.resolve("config.yml")
        if (Files.isRegularFile(config)) {
            val root = Tokens.readYaml(config) as? YamlNode.Mapping ?: throw IOException("NuVotifier's config.yml isn't a YAML section.")
            tokens = Tokens.section(root, "tokens")
            host = (root["host"] as? YamlNode.Scalar)?.content ?: ""
            port = (root["port"] as? YamlNode.Scalar)?.content?.trim()?.toIntOrNull() ?: 0
            v1 = (root["disable-v1-protocol"] as? YamlNode.Scalar)?.content?.trim()?.lowercase() != "true"
        }
        if (keys == null && tokens.isEmpty()) throw IOException("NuVotifier's folder ${folder.fileName} has no rsa keys and no tokens.")
        return Found(folder, keys, if (tokens.isEmpty()) null else Tokens.of(tokens), host, port, v1)
    }

    /** Writes what was found into xVote's folder: `rsa/` and `tokens.yml`. */
    public fun write(found: Found, xvote: Path) {
        found.keys?.let { Keys.save(xvote.resolve("rsa"), it) }
        found.tokens?.save(xvote)
    }
}
