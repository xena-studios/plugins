package co.xenastudios.xbackup.remote.lib

import co.xenastudios.xbackup.remote.RemoteConfig
import co.xenastudios.xbackup.remote.Remotes
import co.xenastudios.xbackup.store.Backend
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.ArrayDeque
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * FTP and FTPS through Apache Commons Net: passive mode, binary, one connection kept open.
 * Uploads go to a temporary name and are renamed into place, then their size is checked.
 * `security: explicit` (the default) upgrades with AUTH TLS and protects the data connection too;
 * `implicit` is TLS from the start, usually on port 990.
 *
 * Loaded by its own class loader, with Commons Net's classes (Libraries.Isolated).
 */
public class FtpBackend(private val name: String, settings: RemoteConfig, private val root: String, private val context: Remotes.Context) : Backend {
    private val host: String = needed(settings.host, "host", name)
    private val security: String = settings.security.trim().lowercase().ifEmpty { "explicit" }
    private val port: Int
    private val user: String = needed(settings.username, "username", name)
    private val secret: String = settings.password
    private val pin: ByteArray?
    private var ftp: FTPClient? = null

    init {
        require(security in listOf("none", "explicit", "implicit")) { "security must be none, explicit or implicit for remote $name." }
        port = if (settings.port != 0) settings.port else if (security == "implicit") 990 else 21
        pin = if (settings.hostKey.isBlank()) null else fingerprint(settings.hostKey, name)
        require(!(pin != null && security == "none")) { "host-key is a TLS certificate's fingerprint, but remote $name has security: none." }
    }

    override fun describe(): String = (if (security == "none") "FTP " else "FTPS ") + "$user@$host:$port/$root"

    private fun path(file: String): String = if (root.isEmpty()) file else "$root/$file"

    @Synchronized
    private fun client(): FTPClient {
        val open = ftp
        if (open != null && open.isConnected) {
            try {
                if (open.sendNoOp()) return open
            } catch (e: java.io.IOException) {
                // Reconnect below.
            }
        }
        closeQuietly()
        val client: FTPClient = if (security == "none") {
            FTPClient()
        } else {
            val secure = FTPSClient("TLS", security == "implicit")
            secure.trustManager = sameOnEveryConnection(trust(pin, name), name)
            // The host name is checked against the certificate, unless it's pinned by fingerprint.
            secure.isEndpointCheckingEnabled = pin == null
            secure
        }
        client.connectTimeout = 20_000
        client.defaultTimeout = 60_000
        // A transfer that stops moving fails instead of holding the only worker for good.
        client.setDataTimeout(Duration.ofSeconds(60))
        client.setControlKeepAliveTimeout(Duration.ofSeconds(30))
        // Data connections go to the server the control connection reached, never to an address
        // a passive reply names.
        client.isRemoteVerificationEnabled = true
        client.setPassiveNatWorkaroundStrategy(SameHost(client))
        client.setControlKeepAliveReplyTimeout(Duration.ofSeconds(30))
        try {
            client.connect(host, port)
            client.soTimeout = 60_000
            if (!FTPReply.isPositiveCompletion(client.replyCode)) throw java.io.IOException("the server said ${client.replyString.trim()}")
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            if (!client.login(user, secret)) throw java.io.IOException("the server refused the login. Check username, and password in secrets.yml")
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
        } catch (e: java.io.IOException) {
            try {
                client.disconnect()
            } catch (ignored: java.io.IOException) {
                // Already failing.
            }
            throw java.io.IOException("remote $name: couldn't connect to $host:$port (${e.message})", e)
        }
        ftp = client
        return client
    }

    // The control connection's own address, whatever a passive reply names.
    private class SameHost(private val client: FTPClient) : FTPClient.HostnameResolver {
        override fun resolve(hostname: String?): String = client.remoteAddress.hostAddress
    }

    private fun closeQuietly() {
        ftp?.let { open ->
            try {
                open.logout()
            } catch (ignored: java.io.IOException) {
                // Closing anyway.
            }
            try {
                open.disconnect()
            } catch (ignored: java.io.IOException) {
                // Closing anyway.
            }
        }
        ftp = null
    }

    private fun failed(what: String, client: FTPClient) = java.io.IOException("remote $name: $what failed: ${client.replyString?.trim()}")

    @Synchronized
    override fun put(name: String, file: Path) {
        upload(name, Files.size(file)) { context.upload.wrap(Files.newInputStream(file)) }
    }

    @Synchronized
    override fun put(name: String, data: ByteArray) {
        upload(name, data.size.toLong()) { ByteArrayInputStream(data) }
    }

    private fun upload(file: String, size: Long, source: () -> InputStream) {
        val target = path(Backend.checkName(file))
        val temp = target + ".part-" + UUID.randomUUID().toString().substring(0, 8)
        val client = client()
        try {
            makeFolders(client, target)
            source().use { input ->
                val out = client.storeFileStream(temp) ?: throw failed("uploading $file", client)
                // The data timeout only bounds reads: an upload the server stops taking would block
                // for good, so a watch closes it after a minute without progress.
                val stall = Stall(out)
                try {
                    val buffer = ByteArray(64 shl 10)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        stall.moved()
                    }
                    out.close()
                } catch (e: java.io.IOException) {
                    if (stall.fired) {
                        closeQuietly()
                        throw java.io.IOException("remote $name: the upload of $file stopped moving for ${Stall.LIMIT_SECONDS} seconds; it will be sent again next time.", e)
                    }
                    throw e
                } finally {
                    stall.stop()
                }
                if (!client.completePendingCommand()) throw failed("uploading $file", client)
            }
            // Checked under the temporary name, so a cut-off copy never takes the real name.
            val stored = storedSize(client, temp)
            if (stored != size) throw java.io.IOException("remote $name holds $stored bytes of $file after uploading $size; it will be sent again next time.")
            if (!client.rename(temp, target)) {
                // A server that can't rename over a file: the old one goes only if it's there.
                // Repository files are named by their content, so it's the same bytes.
                if (!exists(client, target)) throw failed("moving $file into place", client)
                client.deleteFile(target)
                if (!client.rename(temp, target)) throw failed("moving $file into place", client)
            }
        } catch (e: java.io.IOException) {
            try {
                ftp?.takeIf { it.isConnected }?.deleteFile(temp)
            } catch (ignored: java.io.IOException) {
                // Listings skip .part- files.
            }
            throw e
        }
    }

    // A file's size: SIZE if the server has it, else MLST, else a listing of just that file. Never
    // assumed: an upload that can't be checked isn't put in place.
    private fun storedSize(client: FTPClient, path: String): Long {
        val size = size(client, path)
        if (size >= 0) return size
        try {
            val file = client.mlistFile(path)
            if (file != null && file.size >= 0) return file.size
        } catch (e: java.io.IOException) {
            // Try a listing.
        }
        val files = client.listFiles(path)
        if (files != null && files.size == 1 && files[0] != null && files[0].size >= 0) return files[0].size
        throw java.io.IOException("remote $name: the server won't say how big $path is (no SIZE, MLST or LIST), so the upload can't be checked and isn't used.")
    }

    private fun exists(client: FTPClient, path: String): Boolean {
        if (size(client, path) >= 0) return true
        try {
            if (client.mlistFile(path) != null) return true
        } catch (e: java.io.IOException) {
            // Try a listing.
        }
        val files = client.listFiles(path)
        return files != null && files.size == 1 && files[0] != null && files[0].isFile
    }

    private fun size(client: FTPClient, path: String): Long = client.getSize(path)?.trim()?.toLongOrNull() ?: -1

    private fun makeFolders(client: FTPClient, target: String) {
        val slash = target.lastIndexOf('/')
        if (slash <= 0) return
        val path = StringBuilder()
        for (part in target.substring(0, slash).split('/')) {
            if (part.isEmpty()) {
                path.append('/')
                continue
            }
            path.append(part)
            // Fails harmlessly if it's already there.
            client.makeDirectory(path.toString())
            path.append('/')
        }
    }

    @Synchronized
    override fun get(name: String): InputStream {
        val client = client()
        val out = ByteArrayOutputStream()
        client.setRestartOffset(0)
        if (!client.retrieveFile(path(Backend.checkName(name)), out)) {
            if (client.replyCode == 550) throw NoSuchFileException(name)
            throw failed("downloading $name", client)
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    @Synchronized
    override fun read(name: String, offset: Long, length: Int): ByteArray {
        val client = client()
        client.setRestartOffset(offset)
        val bytes = ByteArray(length)
        var read = 0
        try {
            val input = client.retrieveFileStream(path(Backend.checkName(name)))
                ?: if (client.replyCode == 550) throw NoSuchFileException(name) else throw failed("reading $name", client)
            input.use {
                while (read < length) {
                    val n = it.read(bytes, read, length - read)
                    if (n < 0) break
                    read += n
                }
            }
        } finally {
            client.setRestartOffset(0)
        }
        // Closing early aborts the transfer; the server's reply to that is expected.
        client.completePendingCommand()
        if (read != length) throw EOFException("$name is shorter than ${offset + length} bytes")
        return bytes
    }

    @Synchronized
    override fun list(prefix: String): List<Backend.FileInfo> {
        val client = client()
        val files = ArrayList<Backend.FileInfo>()
        val slash = prefix.lastIndexOf('/')
        val folders = ArrayDeque<String>()
        folders += if (slash < 0) "" else prefix.substring(0, slash)
        while (folders.isNotEmpty()) {
            val folder = folders.poll()
            val remote = if (folder.isEmpty()) root else path(folder)
            val entries = (if (remote.isEmpty()) client.listFiles() else client.listFiles(remote)) ?: continue
            for (entry in entries) {
                if (entry == null || entry.name == "." || entry.name == "..") continue
                // Some servers list full paths.
                val child = entry.name.substringAfterLast('/')
                val relative = if (folder.isEmpty()) child else "$folder/$child"
                if (entry.isDirectory) folders += relative else if (relative.startsWith(prefix) && ".part-" !in child) files += Backend.FileInfo(relative, entry.size)
            }
        }
        return files
    }

    @Synchronized
    override fun delete(name: String) {
        val client = client()
        if (!client.deleteFile(path(Backend.checkName(name))) && client.replyCode != 550) throw failed("deleting $name", client)
    }

    @Synchronized
    override fun close() {
        closeQuietly()
    }

    /** Closes an upload's stream once it has made no progress for [LIMIT_SECONDS]. */
    private class Stall(private val stream: Closeable) : Runnable {
        private val thread = Thread(this, "xBackup FTP upload watch").apply {
            isDaemon = true
            start()
        }

        @Volatile
        private var last = System.nanoTime()

        @Volatile
        private var done = false

        @Volatile
        var fired = false
            private set

        fun moved() {
            last = System.nanoTime()
        }

        fun stop() {
            done = true
            thread.interrupt()
        }

        override fun run() {
            while (!done) {
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    return
                }
                if (!done && System.nanoTime() - last > LIMIT_SECONDS * 1_000_000_000L) {
                    fired = true
                    try {
                        stream.close()
                    } catch (ignored: java.io.IOException) {
                        // Closing is the point.
                    }
                    return
                }
            }
        }

        companion object {
            const val LIMIT_SECONDS = 60L
        }
    }

    public companion object {
        private fun needed(value: String?, what: String, name: String): String {
            require(!value.isNullOrBlank()) { "set $what for remote $name." }
            return value.trim()
        }

        /**
         * A certificate fingerprint as owners copy it: `SHA256:` and then base64 (as OpenSSH prints
         * keys), or hex with or without colons (as `openssl x509 -fingerprint -sha256` prints
         * certificates).
         */
        @JvmStatic
        public fun fingerprint(text: String, name: String): ByteArray {
            var value = text.trim()
            if (value.regionMatches(0, "SHA256:", 0, 7, ignoreCase = true)) value = value.substring(7)
            val hex = value.replace(":", "")
            if (hex.length == 64 && hex.all { Character.digit(it, 16) >= 0 }) {
                return ByteArray(32) { ((Character.digit(hex[it * 2], 16) shl 4) or Character.digit(hex[it * 2 + 1], 16)).toByte() }
            }
            try {
                val padded = if (value.endsWith("=")) value else value + "=".repeat((4 - value.length % 4) % 4)
                val bytes = Base64.getDecoder().decode(padded)
                if (bytes.size == 32) return bytes
            } catch (e: IllegalArgumentException) {
                // Not base64 either.
            }
            throw IllegalArgumentException("host-key for remote $name isn't a SHA-256 fingerprint (SHA256:... or 64 hex digits).")
        }

        @JvmStatic
        public fun fingerprint(certificate: X509Certificate): String = try {
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))
        } catch (e: java.security.GeneralSecurityException) {
            "unknown"
        }

        /**
         * The certificates this remote trusts. With a pin (host-key), only the certificate with that
         * fingerprint, which suits a self-signed one. Without, whatever the JVM trusts, for the host
         * name connected to (checked by `setEndpointCheckingEnabled`). Commons Net's default would
         * accept any certificate that's in date.
         */
        @JvmStatic
        public fun trust(pin: ByteArray?, name: String): X509TrustManager {
            if (pin != null) {
                return object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        throw CertificateException("not a server")
                    }

                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        if (chain == null || chain.isEmpty()) throw CertificateException("the server sent no certificate")
                        val hash = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                        if (!MessageDigest.isEqual(hash, pin)) throw CertificateException("the server's certificate is ${fingerprint(chain[0])}, not the host-key set for remote $name")
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
            }
            val system = try {
                val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                factory.init(null as KeyStore?)
                factory.trustManagers.filterIsInstance<X509TrustManager>().lastOrNull()
            } catch (e: java.security.GeneralSecurityException) {
                throw java.io.IOException("Java's trusted certificates can't be loaded: ${e.message}", e)
            } ?: throw java.io.IOException("Java has no trusted certificates to check the server's against.")
            return object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = system.checkClientTrusted(chain, authType)

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    try {
                        system.checkServerTrusted(chain, authType)
                    } catch (e: CertificateException) {
                        val seen = if (chain == null || chain.isEmpty()) "" else " Its fingerprint is ${fingerprint(chain[0])}; if it's your server's own (self-signed) certificate, set host-key to that."
                        throw CertificateException("Java doesn't trust the server's certificate (${e.message}).$seen", e)
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
            }
        }

        /**
         * The certificate checks, plus: every connection of one session (control, then each data
         * connection) shows the same certificate as the first, the one whose host name was checked.
         * A data connection can't be handed to another server.
         */
        @JvmStatic
        public fun sameOnEveryConnection(checks: X509TrustManager, name: String): X509TrustManager {
            val first = AtomicReference<X509Certificate>()
            return object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = checks.checkClientTrusted(chain, authType)

                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                    checks.checkServerTrusted(chain, authType)
                    if (!first.compareAndSet(null, chain[0]) && first.get() != chain[0]) {
                        throw CertificateException("remote $name's data connection showed ${fingerprint(chain[0])}, not the certificate of its control connection")
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = checks.acceptedIssuers
            }
        }
    }
}
