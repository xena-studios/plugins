package co.xenastudios.core.jvm.proxy

import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.config.YamlReader
import co.xenastudios.core.common.proxy.FrameSigner
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The secret a network's proxy and servers share, which signs every message on a
 * [co.xenastudios.core.common.proxy.ProxyChannel] (DECISIONS.md, "The proxy layer"). Found, in
 * order:
 *
 * 1. `proxy.secret` in the family's folder (`plugins/Xena/`, `config/xena/` on Fabric): one line,
 *    the same on the proxy and every server behind it;
 * 2. Velocity's modern forwarding secret, which a Velocity network already shares between the
 *    proxy (`forwarding.secret`, or `VELOCITY_FORWARDING_SECRET`) and each Paper server
 *    (`proxies.velocity.secret` in `config/paper-global.yml`): such a network needs nothing set;
 * 3. on the proxy only, a new one, written to its `proxy.secret` for the owner to copy to each
 *    server: BungeeCord's forwarding has no secret, so a BungeeCord network always needs this.
 *
 * The secret itself never signs: a key for this use alone is derived from it
 * ([signer]), so a message's signature says nothing about the forwarding secret.
 */
public class ProxySecret private constructor(
    /** Where it came from, for the console and `/<plugin> debug`. */
    public val source: String,
    private val secret: ByteArray,
) {
    /**
     * The signer for frames: HMAC-SHA256 under a key derived from the secret. The key is derived
     * when the first frame is signed, since the first `Mac` in a JVM starts the JDK's crypto
     * providers (several milliseconds), which a plugin's start shouldn't wait for: the platforms
     * sign one empty frame on their async pool as the channel opens ([warm]).
     */
    public fun signer(): FrameSigner {
        val key by lazy { hmac(secret, LABEL) }
        // A Mac isn't safe to share between threads; one per thread, made once.
        val macs = ThreadLocal.withInitial {
            Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }
        }
        return FrameSigner { data -> macs.get().doFinal(data) }
    }

    override fun toString(): String = "ProxySecret($source)"

    public companion object {
        /**
         * Signs an empty frame with [signer], to have the key and the JDK's crypto providers
         * ready before the first message: for the platforms' async pools, as a channel opens.
         */
        @JvmStatic
        public fun warm(signer: FrameSigner) {
            signer.sign(ByteArray(0))
        }

        /** The file in the family's folder. */
        public const val FILE: String = "proxy.secret"

        private const val ALGORITHM = "HmacSHA256"
        private val LABEL = "xena proxy channel v1".toByteArray(StandardCharsets.UTF_8)

        // Shorter than this is too easy to guess; Velocity's own are 12 characters, so they pass.
        private const val SHORTEST = 12

        /** The secret in [text], or `null` if it's blank. Leading and trailing spaces are left out. */
        @JvmStatic
        public fun of(text: String, source: String): ProxySecret? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            return ProxySecret(source, trimmed.toByteArray(StandardCharsets.UTF_8))
        }

        /**
         * `proxy.secret` in [family], or `null` if there's none. Throws [IOException] when it's
         * there but can't be read, or is shorter than 12 characters.
         */
        @JvmStatic
        public fun read(family: Path): ProxySecret? {
            val file = family.resolve(FILE)
            if (!Files.isRegularFile(file)) return null
            val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim()
            if (text.length < SHORTEST) throw IOException("$file is shorter than $SHORTEST characters: put a long random line there, the same on the proxy and every server")
            return of(text, file.toString())
        }

        /**
         * Makes a new secret, 32 random bytes as base64, and writes it to `proxy.secret` in
         * [family], readable by the server's user alone where the file system allows: for a
         * proxy that found none.
         */
        @JvmStatic
        public fun create(family: Path): ProxySecret {
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            val text = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            Files.createDirectories(family)
            val file = family.resolve(FILE)
            val temporary = Files.createTempFile(family, FILE, ".tmp")
            try {
                try {
                    Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
                } catch (e: UnsupportedOperationException) {
                    // Windows: the folder's own permissions apply.
                }
                Files.write(temporary, (text + "\n").toByteArray(StandardCharsets.UTF_8))
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
            }
            return ProxySecret(file.toString(), text.toByteArray(StandardCharsets.UTF_8))
        }

        /**
         * Velocity's modern forwarding secret, as the proxy has it: `VELOCITY_FORWARDING_SECRET`,
         * or the file `forwarding-secret-file` in [velocityToml] names (`forwarding.secret` beside
         * it by default), when `player-info-forwarding-mode` is `modern`. `null` otherwise.
         */
        @JvmStatic
        public fun velocity(velocityToml: Path, environment: Map<String, String> = System.getenv()): ProxySecret? {
            if (!Files.isRegularFile(velocityToml)) return null
            val settings = topLevelToml(String(Files.readAllBytes(velocityToml), StandardCharsets.UTF_8))
            if (!settings["player-info-forwarding-mode"].equals("modern", ignoreCase = true)) return null
            environment["VELOCITY_FORWARDING_SECRET"]?.let { of(it, "VELOCITY_FORWARDING_SECRET")?.let { secret -> return secret } }
            val file = (velocityToml.parent ?: velocityToml.fileSystem.getPath(".")).resolve(settings["forwarding-secret-file"] ?: "forwarding.secret")
            if (!Files.isRegularFile(file)) return null
            return of(String(Files.readAllBytes(file), StandardCharsets.UTF_8), "Velocity's forwarding secret ($file)")
        }

        /**
         * Velocity's forwarding secret as a Paper server has it: `proxies.velocity.secret` in
         * [paperGlobal] (`config/paper-global.yml`) when `proxies.velocity.enabled` is true,
         * read with [yaml] (core-jvm's `JvmYaml`). `null` otherwise, or if the file can't be read.
         */
        @JvmStatic
        public fun paper(paperGlobal: Path, yaml: YamlReader): ProxySecret? {
            if (!Files.isRegularFile(paperGlobal)) return null
            val text = String(Files.readAllBytes(paperGlobal), StandardCharsets.UTF_8)
            val root = yaml.read(proxiesBlock(text) ?: text).getOrNull() as? YamlNode.Mapping ?: return null
            val velocity = (root["proxies"] as? YamlNode.Mapping)?.get("velocity") as? YamlNode.Mapping ?: return null
            if ((velocity["enabled"] as? YamlNode.Scalar)?.content != "true") return null
            val secret = (velocity["secret"] as? YamlNode.Scalar)?.content ?: return null
            return of(secret, "Velocity's forwarding secret ($paperGlobal)")
        }

        /**
         * The top-level `proxies:` block of a Paper config and nothing else, or `null` if it has
         * none of its own line. Paper writes the file in block style, so the block is its line
         * and the indented, blank or comment lines after it; reading those alone, not the whole
         * file's hundreds of lines, keeps this off a plugin's startup (it runs as the plugin
         * enables, and YAML that hasn't been compiled yet reads slowly).
         */
        internal fun proxiesBlock(text: String): String? {
            val lines = text.lines()
            val start = lines.indexOfFirst { it.startsWith("proxies:") }
            if (start < 0) return null
            var end = start + 1
            while (end < lines.size) {
                val line = lines[end]
                if (line.isNotEmpty() && !line[0].isWhitespace() && !line.startsWith("#")) break
                end++
            }
            return lines.subList(start, end).joinToString("\n")
        }

        // `key = "value"` lines before the first [table], which is all of velocity.toml this needs.
        internal fun topLevelToml(text: String): Map<String, String> {
            val settings = HashMap<String, String>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("[")) break
                if (line.isEmpty() || line.startsWith("#")) continue
                val equals = line.indexOf('=')
                if (equals <= 0) continue
                var value = line.substring(equals + 1).trim()
                val comment = value.indexOf(" #")
                if (comment >= 0 && !value.startsWith("\"")) value = value.substring(0, comment).trim()
                if (value.length >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length - 1)
                }
                settings[line.substring(0, equals).trim()] = value
            }
            return settings
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }.doFinal(data)
    }
}
