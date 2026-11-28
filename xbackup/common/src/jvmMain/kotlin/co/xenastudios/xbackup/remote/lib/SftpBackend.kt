package co.xenastudios.xbackup.remote.lib

import co.xenastudios.xbackup.remote.RemoteConfig
import co.xenastudios.xbackup.remote.Remotes
import co.xenastudios.xbackup.store.Backend
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Base64
import java.util.Properties
import java.util.UUID

/**
 * SFTP through JSch. One connection, opened on first use and kept for the backend's life. Uploads
 * go to a temporary name and are renamed into place, then their size is checked.
 *
 * The server's key is checked on every connection: against `host-key` if it's set, otherwise
 * against the key seen the first time, remembered in `known-hosts.properties`.
 *
 * Loaded by its own class loader, with JSch's classes (Libraries.Isolated): nothing outside
 * `remote.lib` names a JSch type.
 */
public class SftpBackend(private val name: String, settings: RemoteConfig, private val root: String, private val context: Remotes.Context) : Backend {
    private val host: String = needed(settings.host, "host", name)
    private val port: Int = if (settings.port == 0) 22 else settings.port
    private val user: String = needed(settings.username, "username", name)
    private val secret: String = settings.password
    private val privateKey: Path? = if (settings.privateKey.isBlank()) null else context.home.resolve(settings.privateKey.trim())
    private val pinnedKey: String = settings.hostKey.trim()
    private var session: Session? = null
    private var sftp: ChannelSftp? = null

    init {
        require(!(secret.isBlank() && privateKey == null)) { "set a password in secrets.yml, or private-key, for remote $name." }
    }

    override fun describe(): String = "SFTP $user@$host:$port/$root"

    private fun path(file: String): String = if (root.isEmpty()) file else "$root/$file"

    @Synchronized
    private fun channel(): ChannelSftp {
        val open = sftp
        if (open != null && open.isConnected && session?.isConnected == true) return open
        closeQuietly()
        try {
            val jsch = JSch()
            jsch.hostKeyRepository = Keys()
            if (privateKey != null) {
                if (!Files.exists(privateKey)) throw java.io.IOException("remote $name: the private key $privateKey doesn't exist.")
                jsch.addIdentity(privateKey.toString(), if (secret.isBlank()) null else secret.toByteArray(Charsets.UTF_8))
            }
            val opened = jsch.getSession(user, host, port)
            session = opened
            if (privateKey == null) opened.setPassword(secret.toByteArray(Charsets.UTF_8))
            opened.setConfig("StrictHostKeyChecking", "yes")
            opened.setConfig("PreferredAuthentications", if (privateKey == null) "password,keyboard-interactive" else "publickey")
            opened.userInfo = Quiet(secret)
            opened.setServerAliveInterval(30_000)
            opened.connect(20_000)
            val channel = opened.openChannel("sftp") as ChannelSftp
            channel.connect(20_000)
            sftp = channel
            return channel
        } catch (e: JSchException) {
            closeQuietly()
            val message = e.message ?: e.toString()
            if ("HostKey has been changed" in message || "reject HostKey" in message) {
                throw java.io.IOException("remote $name: the server's key isn't the one expected. If it really changed, update host-key (or remove its line from known-hosts.properties).", e)
            }
            if ("Auth fail" in message || "Auth cancel" in message) {
                throw java.io.IOException("remote $name: the server refused the login. Check username, and password in secrets.yml, or private-key.", e)
            }
            throw java.io.IOException("remote $name: couldn't connect to $host:$port ($message)", e)
        }
    }

    private fun closeQuietly() {
        sftp?.disconnect()
        session?.disconnect()
        sftp = null
        session = null
    }

    // Runs an operation, reconnecting once if the connection dropped.
    @Synchronized
    private fun <T> run(operation: (ChannelSftp) -> T): T {
        var attempt = 0
        while (true) {
            val channel = channel()
            try {
                return operation(channel)
            } catch (e: SftpException) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) throw NoSuchFileException(e.message)
                if (attempt == 0 && (e.id == ChannelSftp.SSH_FX_CONNECTION_LOST || e.id == ChannelSftp.SSH_FX_NO_CONNECTION || !channel.isConnected)) {
                    closeQuietly()
                    attempt++
                    continue
                }
                throw java.io.IOException("remote $name: ${e.message}", e)
            }
        }
    }

    override fun put(name: String, file: Path) {
        upload(name, Files.size(file)) { context.upload.wrap(Files.newInputStream(file)) }
    }

    override fun put(name: String, data: ByteArray) {
        upload(name, data.size.toLong()) { ByteArrayInputStream(data) }
    }

    // Each attempt reads the file from its start: a retry after a dropped connection never sends
    // the rest of a stream the first attempt part read. The size is checked under the temporary
    // name, so a cut-off copy never takes the real one.
    private fun upload(file: String, size: Long, source: () -> InputStream) {
        val target = path(Backend.checkName(file))
        val temp = target + ".part-" + UUID.randomUUID().toString().substring(0, 8)
        try {
            run { channel ->
                makeFolders(channel, target)
                source().use { channel.put(it, temp, ChannelSftp.OVERWRITE) }
                val stored = channel.stat(temp).size
                if (stored != size) throw java.io.IOException("remote $name holds $stored bytes of $file after uploading $size; it will be sent again next time.")
                try {
                    channel.rename(temp, target)
                } catch (e: SftpException) {
                    // SFTP v3 can't rename over a file, and says so only as a general failure: the
                    // old file is removed only if it's really there. Repository files are named by
                    // their content, so it's the same bytes either way.
                    val existing = try {
                        channel.lstat(target)
                    } catch (missing: SftpException) {
                        throw e
                    }
                    if (existing.isDir) throw e
                    channel.rm(target)
                    channel.rename(temp, target)
                }
            }
        } catch (e: java.io.IOException) {
            try {
                run { it.rm(temp) }
            } catch (ignored: java.io.IOException) {
                // Listings skip .part- files.
            }
            throw e
        }
    }

    private fun makeFolders(channel: ChannelSftp, target: String) {
        val slash = target.lastIndexOf('/')
        if (slash <= 0) return
        val folder = target.substring(0, slash)
        try {
            channel.stat(folder)
            return
        } catch (e: SftpException) {
            if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e
        }
        val path = StringBuilder()
        for (part in folder.split('/')) {
            if (part.isEmpty()) {
                path.append('/')
                continue
            }
            path.append(part)
            try {
                channel.stat(path.toString())
            } catch (e: SftpException) {
                channel.mkdir(path.toString())
            }
            path.append('/')
        }
    }

    override fun get(name: String): InputStream = ByteArrayInputStream(
        run { channel ->
            val out = ByteArrayOutputStream()
            channel.get(path(Backend.checkName(name)), out)
            out.toByteArray()
        },
    )

    override fun read(name: String, offset: Long, length: Int): ByteArray = run { channel ->
        channel.get(path(Backend.checkName(name)), null, offset).use { input ->
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(bytes, read, length - read)
                if (n < 0) throw EOFException("$name is shorter than ${offset + length} bytes")
                read += n
            }
            bytes
        }
    }

    override fun list(prefix: String): List<Backend.FileInfo> = run { channel ->
        val files = ArrayList<Backend.FileInfo>()
        val slash = prefix.lastIndexOf('/')
        val folders = ArrayDeque<String>()
        folders += if (slash < 0) "" else prefix.substring(0, slash)
        while (folders.isNotEmpty()) {
            val folder = folders.poll()
            val remote = if (folder.isEmpty()) root.ifEmpty { "." } else path(folder)
            val entries = ArrayList<ChannelSftp.LsEntry>()
            try {
                channel.ls(remote, Collect(entries))
            } catch (e: SftpException) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) continue
                throw e
            }
            for (entry in entries) {
                val child = entry.filename
                if (child == "." || child == "..") continue
                val relative = if (folder.isEmpty()) child else "$folder/$child"
                if (entry.attrs.isDir) folders += relative else if (relative.startsWith(prefix) && ".part-" !in child) files += Backend.FileInfo(relative, entry.attrs.size)
            }
        }
        files
    }

    private class Collect(private val entries: MutableList<ChannelSftp.LsEntry>) : ChannelSftp.LsEntrySelector {
        override fun select(entry: ChannelSftp.LsEntry): Int {
            entries += entry
            return ChannelSftp.LsEntrySelector.CONTINUE
        }
    }

    override fun delete(name: String) {
        try {
            run { it.rm(path(Backend.checkName(name))) }
        } catch (e: NoSuchFileException) {
            // Already gone.
        }
    }

    @Synchronized
    override fun close() {
        closeQuietly()
    }

    /** The server's key: the configured fingerprint, or the one remembered from the first connection. */
    private inner class Keys : HostKeyRepository {
        private fun file(): Path = context.home.resolve("known-hosts.properties")

        override fun check(hostName: String, key: ByteArray): Int {
            val fingerprint = fingerprint(key)
            if (pinnedKey.isNotEmpty()) {
                val expected = if (pinnedKey.startsWith("SHA256:")) pinnedKey else "SHA256:$pinnedKey"
                return if (expected.replace(Regex("=+$"), "") == fingerprint) HostKeyRepository.OK else HostKeyRepository.CHANGED
            }
            return try {
                val known = Properties()
                if (Files.exists(file())) StringReader(String(Files.readAllBytes(file()), Charsets.UTF_8)).use { known.load(it) }
                val id = "$host:$port"
                val remembered = known.getProperty(id)
                if (remembered == null) {
                    known.setProperty(id, fingerprint)
                    val writer = StringWriter()
                    known.store(writer, "SFTP servers' keys, remembered from the first connection. Remove a line if a server's key really changed.")
                    Files.write(file(), writer.toString().toByteArray(Charsets.UTF_8))
                    context.info("Remote $name: trusting $id's key $fingerprint from now on (first connection).")
                    HostKeyRepository.OK
                } else if (remembered == fingerprint) {
                    HostKeyRepository.OK
                } else {
                    HostKeyRepository.CHANGED
                }
            } catch (e: java.io.IOException) {
                HostKeyRepository.CHANGED
            }
        }

        private fun fingerprint(key: ByteArray): String = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

        override fun add(hostkey: HostKey?, ui: UserInfo?) {}

        override fun remove(host: String?, type: String?) {}

        override fun remove(host: String?, type: String?, key: ByteArray?) {}

        override fun getKnownHostsRepositoryID(): String = "xBackup"

        override fun getHostKey(): Array<HostKey> = emptyArray()

        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /** Answers JSch's prompts without asking anyone. */
    private class Quiet(private val secret: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String = secret

        override fun getPassword(): String = secret

        override fun promptPassword(message: String?): Boolean = secret.isNotBlank()

        override fun promptPassphrase(message: String?): Boolean = secret.isNotBlank()

        override fun promptYesNo(message: String?): Boolean = false

        override fun showMessage(message: String?) {}

        override fun promptKeyboardInteractive(destination: String?, name: String?, instruction: String?, prompt: Array<out String>, echo: BooleanArray?): Array<String> =
            Array(prompt.size) { secret }
    }

    private companion object {
        fun needed(value: String?, what: String, name: String): String {
            require(!value.isNullOrBlank()) { "set $what for remote $name." }
            return value.trim()
        }
    }
}
