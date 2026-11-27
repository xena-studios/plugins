package co.xenastudios.xvote.receiver

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.jvm.config.JvmYaml
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The RSA key pair for Votifier v1, kept as NuVotifier keeps it: `rsa/public.key` (X.509) and
 * `rsa/private.key` (PKCS#8), each one line of Base64. So NuVotifier's folder can be copied in as it
 * is, and lists keep the public key they already have.
 */
public object Keys {
    internal const val PUBLIC = "public.key"
    internal const val PRIVATE = "private.key"
    internal const val BITS = 2048

    /** Whether [folder] has both key files. */
    public fun exist(folder: Path): Boolean = Files.isRegularFile(folder.resolve(PUBLIC)) && Files.isRegularFile(folder.resolve(PRIVATE))

    /** Reads the key pair from [folder], or makes and saves one if it has none. */
    public fun loadOrCreate(folder: Path): KeyPair = if (exist(folder)) load(folder) else generate().also { save(folder, it) }

    /** Reads the key pair from [folder]: throws [IOException] with why, for the owner. */
    public fun load(folder: Path): KeyPair {
        try {
            val factory = KeyFactory.getInstance("RSA")
            val pair = KeyPair(
                factory.generatePublic(X509EncodedKeySpec(read(folder.resolve(PUBLIC)))),
                factory.generatePrivate(PKCS8EncodedKeySpec(read(folder.resolve(PRIVATE)))),
            )
            if ((pair.public as RSAPublicKey).modulus.bitLength() > BITS) {
                throw IOException("The key in $folder is longer than $BITS bits; Votifier v1 needs a $BITS-bit key. Delete the folder to make a new one, and give lists the new public key.")
            }
            return pair
        } catch (e: GeneralSecurityException) {
            throw IOException("The files in $folder aren't an RSA key pair: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("The files in $folder aren't an RSA key pair: ${e.message}", e)
        } catch (e: ClassCastException) {
            throw IOException("The files in $folder aren't an RSA key pair: ${e.message}", e)
        }
    }

    /** A new 2048-bit pair, as NuVotifier makes them. */
    public fun generate(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSAKeyGenParameterSpec(BITS, RSAKeyGenParameterSpec.F4))
        return generator.generateKeyPair()
    }

    /** Writes the pair, each file one line of Base64. The private key is readable by the server's user only, where the file system allows. */
    public fun save(folder: Path, pair: KeyPair) {
        Files.createDirectories(folder)
        Files.write(folder.resolve(PUBLIC), encode(pair.public.encoded).toByteArray(Charsets.US_ASCII))
        Secrets.write(folder.resolve(PRIVATE), encode(pair.private.encoded))
    }

    /** The public key as lists ask for it: Base64 of its X.509 form, on one line. */
    public fun publicText(pair: KeyPair): String = encode(pair.public.encoded)

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    // As NuVotifier reads them: ASCII, trimmed, Base64. Some editors add a line break.
    private fun read(file: Path): ByteArray {
        val text = String(Files.readAllBytes(file), Charsets.US_ASCII).filterNot { it.isWhitespace() }
        return Base64.getDecoder().decode(text)
    }
}

/**
 * Files only the server's own user may read: the private key, the tokens, and their backups. Made
 * with those permissions from the start, so there's no moment another user on the machine could read
 * them. Where the file system has no such permissions (Windows, some mounts), the folder's own apply.
 */
public object Secrets {
    private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

    /** Writes [text] to a new file only the owner can read, replacing any file there. */
    public fun write(file: Path, text: String) {
        Files.deleteIfExists(file)
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        } catch (e: UnsupportedOperationException) {
            Files.createFile(file)
        } catch (e: FileAlreadyExistsException) {
            // Made between the delete and here; its permissions are set below.
        }
        ownerOnly(file)
        Files.write(file, text.toByteArray(Charsets.UTF_8))
    }

    /** Makes an existing file readable by its owner only, where the file system allows. */
    public fun ownerOnly(file: Path) {
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY)
        } catch (e: UnsupportedOperationException) {
            // Windows, or a mount that doesn't keep permissions.
        } catch (e: IOException) {
            // Likewise.
        }
    }
}

/**
 * Votifier v2 tokens, one per server list by its service name, with `default` for every list without
 * its own. Kept in `tokens.yml` with the same `tokens:` section NuVotifier's config has, so it can be
 * pasted across either way, and out of `config.yml`, which owners often share when asking for help.
 * Service names often have dots (`MinecraftServers.org`): the file is read as YAML, never split on
 * them, and written with every name quoted.
 */
public class Tokens private constructor(tokens: Map<String, String>) {
    /** Every token, by service name. */
    public val all: Map<String, String> = LinkedHashMap(tokens)
    private val byLowerName: Map<String, String> = LinkedHashMap<String, String>().also { lower ->
        for ((name, token) in tokens) if (!lower.containsKey(name.lowercase())) lower[name.lowercase()] = token
    }

    /** A list's token: its own by exact name (as NuVotifier looks it up), then ignoring case, or `null` if it has none. `default` is the caller's fallback. */
    public fun forService(service: String): String? = all[service] ?: byLowerName[service.lowercase()]

    /** These tokens plus one for [service], or with its token replaced. */
    public fun with(service: String, token: String): Tokens = Tokens(LinkedHashMap(all).also { it[service] = token })

    /** Writes `tokens.yml` in [folder], readable by the server's user only, through a temporary file moved into place. */
    public fun save(folder: Path) {
        Files.createDirectories(folder)
        val text = StringBuilder()
        for (line in HEADER) text.append("# ").append(line).append('\n')
        text.append("tokens:\n")
        for ((name, token) in all) text.append("  ").append(quote(name)).append(": ").append(quote(token)).append('\n')
        val file = folder.resolve(FILE)
        val temporary = folder.resolve("$FILE.tmp")
        // Owner-only, like rsa/private.key: anyone with a token can send votes as that list.
        Secrets.write(temporary, text.toString())
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    public companion object {
        public const val FILE: String = "tokens.yml"
        public const val DEFAULT: String = "default"

        private val HEADER = listOf(
            "xVote's Votifier tokens, one per server list, by the name the list sends (its",
            "service name). \"default\" is used by every list without its own. Give a list its",
            "token when you add your server; /xvote token shows them.",
            "Keep these secret: anyone with a token can send votes as that list.",
            "This is NuVotifier's tokens section, so tokens can be copied across either way.",
        )

        public fun of(tokens: Map<String, String>): Tokens = Tokens(tokens)

        /** Reads `tokens.yml`, or writes one with a new `default` token if it's missing or has none: the tokens, and whether one was made. */
        public fun loadOrCreate(folder: Path): Pair<Tokens, Boolean> {
            val file = folder.resolve(FILE)
            val read = if (Files.exists(file)) section(readYaml(file), "tokens") else emptyMap()
            if (read.isNotEmpty()) return Tokens(read) to false
            val made = Tokens(mapOf(DEFAULT to Protocol.newToken()))
            made.save(folder)
            return made to true
        }

        /** A YAML file, or [IOException] with why it can't be read. */
        internal fun readYaml(file: Path): YamlNode {
            val text = String(Files.readAllBytes(file), Charsets.UTF_8)
            return when (val read = JvmYaml.read(text)) {
                is Outcome.Ok -> read.value
                is Outcome.Err -> throw IOException("${file.fileName} isn't valid YAML, so it was left as it is: ${read.error}")
            }
        }

        /** The names and values of [root]'s [name] section: what NuVotifier's `tokens:` holds. */
        internal fun section(root: YamlNode, name: String): Map<String, String> {
            val section = (root as? YamlNode.Mapping)?.get(name) as? YamlNode.Mapping ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (entry in section.entries) {
                val value = (entry.value as? YamlNode.Scalar)?.content ?: continue
                if (value.isNotBlank()) out[entry.key] = value
            }
            return out
        }

        private fun quote(text: String): String = buildString {
            append('"')
            for (c in text) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    else -> if (c.code < 0x20) append(' ') else append(c)
                }
            }
            append('"')
        }
    }
}
