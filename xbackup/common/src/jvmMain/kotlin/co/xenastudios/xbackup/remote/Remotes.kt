package co.xenastudios.xbackup.remote

import co.xenastudios.xbackup.api.StorageBackend
import co.xenastudios.xbackup.store.Backend
import co.xenastudios.xbackup.store.Hashes
import co.xenastudios.xbackup.store.LocalBackend
import co.xenastudios.xbackup.store.SecretText
import co.xenastudios.xbackup.store.Throttle
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Opens a remote from its config: the backends xBackup ships, and any another plugin registered through the API. */
public object Remotes {
    /** OAuth tokens for the cloud drives, owner-only, beside config.yml. */
    public const val TOKENS: String = "tokens.yml"

    /** Where 1.x's first builds kept the tokens; moved to [TOKENS] on first use. */
    public const val LEGACY_TOKENS: String = "tokens.properties"

    /**
     * What a remote needs from its surroundings.
     *
     * @param home xBackup's folder, for keys, tokens and downloaded libraries
     * @param upload the shared upload limit
     * @param registered backends other plugins registered, by type
     */
    public class Context(
        public val home: Path,
        public val upload: Throttle,
        public val info: (String) -> Unit,
        public val registered: (String) -> StorageBackend? = { null },
    )

    /** The types xBackup ships, for messages and completions. */
    @JvmField
    public val TYPES: List<String> = listOf("local", "s3", "webdav", "sftp", "ftp", "drive", "onedrive", "dropbox")

    /**
     * Opens the remote. Network backends connect lazily, on first use.
     *
     * @throws java.io.IOException with a message naming the remote, if it can't be set up
     */
    @JvmStatic
    public fun open(name: String, settings: RemoteConfig, context: Context): Backend {
        val type = settings.type.trim().lowercase()
        val prefix = cleanPrefix(settings.path)
        try {
            return when (type) {
                "local", "folder", "disk" -> {
                    var folder = Paths.get(settings.path.trim().ifEmpty { "backups" })
                    if (!folder.isAbsolute) folder = Paths.get("").toAbsolutePath().resolve(folder)
                    LocalBackend(folder)
                }
                "s3" -> S3Backend(name, settings, prefix, context.upload)
                "webdav", "dav" -> WebDavBackend(name, settings, context.upload)
                "sftp" -> Libraries.sftp(name, settings, prefix, context)
                "ftp", "ftps" -> Libraries.ftp(name, settings, prefix, context)
                "drive", "onedrive", "dropbox" -> CloudDrives.open(name, type, settings, prefix, context)
                else -> {
                    val registered = context.registered(type)
                        ?: throw java.io.IOException("remote $name has type \"${settings.type}\", which isn't one xBackup knows (${TYPES.joinToString(", ")}) or one another plugin added.")
                    adapt(registered)
                }
            }
        } catch (e: IllegalArgumentException) {
            throw java.io.IOException("remote $name: ${e.message}", e)
        }
    }

    /** `/backups/server1/` becomes `backups/server1`. */
    @JvmStatic
    public fun cleanPrefix(path: String): String = path.trim().replace('\\', '/').trim('/')

    @JvmStatic
    public fun required(value: String?, what: String, name: String): String {
        require(!value.isNullOrBlank()) { "set $what for remote $name." }
        return value.trim()
    }

    /** A plugin's [StorageBackend] as the store's [Backend]. */
    internal fun adapt(storage: StorageBackend): Backend = object : Backend {
        override fun describe(): String = storage.describe()

        override fun put(name: String, file: Path) = storage.put(Backend.checkName(name), file.toAbsolutePath().toString())

        override fun put(name: String, data: ByteArray) {
            val temp = Files.createTempFile("xbackup", ".part")
            try {
                Files.write(temp, data)
                storage.put(Backend.checkName(name), temp.toAbsolutePath().toString())
            } finally {
                Files.deleteIfExists(temp)
            }
        }

        override fun get(name: String): InputStream = ByteArrayInputStream(storage.get(Backend.checkName(name)) ?: throw NoSuchFileException(name))

        override fun read(name: String, offset: Long, length: Int): ByteArray {
            val bytes = storage.get(Backend.checkName(name)) ?: throw NoSuchFileException(name)
            if (offset + length > bytes.size) throw EOFException("$name is shorter than expected")
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }

        override fun list(prefix: String): List<Backend.FileInfo> = storage.list(prefix).map { Backend.FileInfo(it.name, it.size) }

        override fun delete(name: String) = storage.delete(Backend.checkName(name))
    }
}

/**
 * The SFTP and FTP libraries, fetched the first time a remote needs them, never shaded into the
 * jar. Each is one dependency-free jar from Maven Central (Google's mirror of it first, then
 * Maven Central itself), checked against the SHA-256 written here before it's used, and kept in
 * xBackup's `libraries/` folder. A host that blocks the download gets one clear error naming the
 * remote; nothing else in xBackup is affected.
 *
 * Fetched by xBackup itself, not the server's library loader, so a remote added on `/xbackup
 * reload` works without a restart, and the command-line restore, which runs without a server,
 * can use them too (decisions/2026-11-18-xbackup.md).
 *
 * The code that uses a library (`remote.lib`) is loaded by its own class loader, from xBackup's
 * jar, with the library's classes from the library's jar, so the rest of xBackup never touches them.
 */
public object Libraries {
    /** A jar on Maven Central, pinned by version and hash. */
    public class Library(public val group: String, public val artifact: String, public val version: String, public val sha256: String, public val packageName: String) {
        internal val path: String get() = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar"
        internal val fileName: String get() = "$artifact-$version.jar"
    }

    /** JSch (mwiede's maintained fork): SSH and SFTP, pure Java, no dependencies. BSD. */
    @JvmField
    public val JSCH: Library = Library("com.github.mwiede", "jsch", "2.28.8", "2afca1357d816028f2ac33f22407d2488a68406682973949cebb1b8ea6688a8b", "com.jcraft.jsch.")

    /** Apache Commons Net: FTP and FTPS, no dependencies. Apache 2.0. */
    @JvmField
    public val COMMONS_NET: Library = Library("commons-net", "commons-net", "3.13.0", "9e88444afadc9467f0bf3d328f93b9538da35a9be05cdee430766e64fddbd6c5", "org.apache.commons.net.")

    private val MIRRORS = listOf("https://maven-central.storage-download.googleapis.com/maven2/", "https://repo1.maven.org/maven2/")
    private const val OWN = "co.xenastudios.xbackup.remote.lib."
    private val LOADERS = ConcurrentHashMap<Library, ClassLoader>()

    internal fun sftp(name: String, settings: RemoteConfig, prefix: String, context: Remotes.Context): Backend = create(JSCH, "SftpBackend", name, settings, prefix, context)

    internal fun ftp(name: String, settings: RemoteConfig, prefix: String, context: Remotes.Context): Backend = create(COMMONS_NET, "FtpBackend", name, settings, prefix, context)

    private fun create(library: Library, type: String, name: String, settings: RemoteConfig, prefix: String, context: Remotes.Context): Backend {
        val loader = loader(library, name, context)
        try {
            val backend = Class.forName(OWN + type, true, loader)
            return backend.getConstructor(String::class.java, RemoteConfig::class.java, String::class.java, Remotes.Context::class.java)
                .newInstance(name, settings, prefix, context) as Backend
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            if (cause is IllegalArgumentException) throw cause
            throw java.io.IOException("remote $name couldn't start: $cause", cause)
        } catch (e: ReflectiveOperationException) {
            throw java.io.IOException("remote $name couldn't load ${library.artifact}: $e", e)
        } catch (e: LinkageError) {
            throw java.io.IOException("remote $name couldn't load ${library.artifact}: $e", e)
        }
    }

    private fun loader(library: Library, name: String, context: Remotes.Context): ClassLoader {
        LOADERS[library]?.let { return it }
        // Already on the class path (tests): no download needed.
        try {
            Class.forName(library.packageName + if (library === JSCH) "JSch" else "ftp.FTPClient", false, Libraries::class.java.classLoader)
            LOADERS[library] = Libraries::class.java.classLoader
            return Libraries::class.java.classLoader
        } catch (e: ClassNotFoundException) {
            // Fetch it.
        }
        val jar = fetch(library, context.home.resolve("libraries"), name)
        val own = try {
            Libraries::class.java.protectionDomain.codeSource.location
        } catch (e: Exception) {
            throw java.io.IOException("remote $name: can't find xBackup's own jar to load ${library.artifact}.", e)
        }
        val loader = isolated(own, jar.toUri().toURL(), library)
        LOADERS[library] = loader
        return loader
    }

    /** Downloads the library once and checks its hash; a jar already there is checked too. */
    @JvmStatic
    @JvmOverloads
    public fun fetch(library: Library, folder: Path, remote: String, mirrors: List<String> = MIRRORS): Path {
        val jar = folder.resolve(library.fileName)
        if (Files.exists(jar) && sha256(jar) == library.sha256) return jar
        Files.createDirectories(folder)
        var last: java.io.IOException? = null
        for (mirror in mirrors) {
            val part = folder.resolve(library.fileName + ".part")
            try {
                val download = Http.download(URI.create(mirror + library.path))
                download.body.use { body ->
                    if (download.status != 200) throw java.io.IOException("HTTP ${download.status} from ${URI.create(mirror).host}")
                    Files.copy(body, part, StandardCopyOption.REPLACE_EXISTING)
                }
                if (sha256(part) != library.sha256) {
                    throw java.io.IOException("the download from ${URI.create(mirror).host} didn't match its expected SHA-256, so it wasn't used")
                }
                Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return jar
            } catch (e: java.io.IOException) {
                last = e
            } finally {
                Files.deleteIfExists(part)
            }
        }
        throw java.io.IOException(
            "remote $remote needs ${library.artifact} ${library.version}, which couldn't be downloaded (${last?.message ?: "no mirror"}). If this host blocks downloads, put ${library.fileName} from Maven Central in $folder.",
            last,
        )
    }

    internal fun sha256(file: Path): String {
        val digest = Hashes.digest()
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    /**
     * Loads xBackup's code that uses the library from xBackup's own jar, and the library's classes
     * from the library's jar, never the other way round: a library jar can't stand in for
     * xBackup's classes. Everything else (the store, the config) comes from xBackup's loader, so
     * both sides agree on the [Backend] interface.
     */
    /** xBackup's remote.lib classes from [own], [of]'s from [library], everything else from xBackup's loader. */
    internal fun isolated(own: URL, library: URL, of: Library): ClassLoader = Isolated(own, library, Libraries::class.java.classLoader, of.packageName)

    private class Isolated(own: URL, library: URL, parent: ClassLoader, private val libraryPackage: String) : ClassLoader(parent) {
        // Each has no parent: they only find what's in their one jar.
        private val own = URLClassLoader(arrayOf(own), null)
        private val library = URLClassLoader(arrayOf(library), null)

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            val ours = name.startsWith(OWN)
            if (!ours && !name.startsWith(libraryPackage)) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name)
                if (loaded == null) {
                    val resource = name.replace('.', '/') + ".class"
                    val bytes = try {
                        (if (ours) own else library).getResourceAsStream(resource)?.use { it.readBytes() } ?: throw ClassNotFoundException(name)
                    } catch (e: java.io.IOException) {
                        throw ClassNotFoundException(name, e)
                    }
                    loaded = defineClass(name, bytes, 0, bytes.size)
                }
                if (resolve) resolveClass(loaded)
                return loaded!!
            }
        }

        override fun findResource(name: String): URL? = library.findResource(name) ?: own.findResource(name)
    }
}

/** Files that hold secrets: readable by their owner only from the moment they exist. */
public object OwnerOnlyFile {
    /**
     * Writes [text] to [file], swapped in whole. The new file is created owner-only (where the
     * system has permissions), never made first and narrowed after, so there's no moment another
     * user could open it.
     */
    @JvmStatic
    public fun write(file: Path, text: String) {
        val folder = file.toAbsolutePath().parent
        Files.createDirectories(folder)
        val temp = folder.resolve(file.fileName.toString() + "." + UUID.randomUUID().toString().substring(0, 8) + ".part")
        try {
            try {
                Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } catch (e: UnsupportedOperationException) {
                // Windows: made, then narrowed to its owner before anything is written to it.
                Files.createFile(temp)
                ownerOnlyAcl(temp)
            }
            FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                // On disk before the rename, so a power cut never leaves an empty file in its place.
                channel.force(true)
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** Narrows an existing file to its owner, where the system has permissions. */
    @JvmStatic
    public fun restrict(file: Path) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        } catch (e: UnsupportedOperationException) {
            try {
                ownerOnlyAcl(file)
            } catch (ignored: Exception) {
                // Not ours to change.
            }
        } catch (e: java.io.IOException) {
            // Not ours to change.
        }
    }

    // Windows: one entry, the owner's, with nothing inherited from the folder.
    private fun ownerOnlyAcl(file: Path) {
        val view = Files.getFileAttributeView(file, AclFileAttributeView::class.java) ?: return
        val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.owner).setPermissions(EnumSet.allOf(AclEntryPermission::class.java)).build()
        view.acl = listOf(entry)
    }
}

/** [SecretText] on disk: `secrets.yml` and `tokens.yml`, read where they are and written owner-only. */
public object SecretFile {
    /** The file's values by remote, then by key; empty if there's no file. */
    @JvmStatic
    public fun read(file: Path): MutableMap<String, MutableMap<String, String>> {
        val text = try {
            String(Files.readAllBytes(file), Charsets.UTF_8)
        } catch (e: NoSuchFileException) {
            return LinkedHashMap()
        }
        return SecretText.read(text, file.fileName.toString())
    }

    /** Writes the file owner-only, every value double-quoted on one line, under [header]. */
    @JvmStatic
    public fun write(file: Path, header: String, values: Map<String, Map<String, String>>) {
        OwnerOnlyFile.write(file, SecretText.write(header, values))
    }
}

/**
 * The settings the command-line restore reads, since it can't read config.yml without a server:
 * the plugin writes them to [NAME] whenever its config loads. The remotes' passwords are read from
 * `secrets.yml` where they are. 1.x's file, the same keys.
 *
 * @param repository the repository folder, or `null` if not recorded
 * @param secretsProblem why `secrets.yml` couldn't be read, or `null`
 */
public class RestoreSettings(public val repository: Path?, public val remotes: Map<String, RemoteConfig>, public val secretsProblem: String?) {
    public companion object {
        public const val NAME: String = "restore-settings.properties"

        /** Reads the file in [home]; `null` if it isn't there. */
        @JvmStatic
        public fun read(home: Path): RestoreSettings? {
            val file = home.resolve(NAME)
            if (!Files.exists(file)) return null
            val properties = Properties()
            StringReader(String(Files.readAllBytes(file), Charsets.UTF_8)).use { properties.load(it) }
            var secretsProblem: String? = null
            val secrets = try {
                SecretFile.read(home.resolve(SecretText.SECRETS))
            } catch (e: java.io.IOException) {
                secretsProblem = e.message
                LinkedHashMap()
            }
            val remotes = LinkedHashMap<String, RemoteConfig>()
            for (key in properties.stringPropertyNames()) {
                if (!key.startsWith("remote.") || !key.endsWith(".type")) continue
                val name = key.substring(7, key.length - 5)
                val p = "remote.$name."
                val secret = secrets[name].orEmpty()
                remotes[name] = RemoteConfig(
                    properties.getProperty(p + "type", ""), properties.getProperty(p + "enabled", "true").toBoolean(), properties.getProperty(p + "path", ""),
                    properties.getProperty(p + "url", ""), properties.getProperty(p + "region", ""), properties.getProperty(p + "bucket", ""),
                    properties.getProperty(p + "path-style").toBoolean(), properties.getProperty(p + "username", ""), secret["password"] ?: "",
                    properties.getProperty(p + "host", ""), properties.getProperty(p + "port", "0").toIntOrNull() ?: 0,
                    properties.getProperty(p + "private-key", ""), properties.getProperty(p + "host-key", ""),
                    properties.getProperty(p + "security", "explicit"), properties.getProperty(p + "client-id", ""), secret["client-secret"] ?: "",
                )
            }
            return RestoreSettings(properties.getProperty("repository")?.let { Paths.get(it) }, remotes, secretsProblem)
        }

        /** Writes what the command-line restore needs from config.yml: where the repository is and how to reach each remote. No passwords. */
        @JvmStatic
        public fun write(home: Path, repository: Path, remotes: Map<String, RemoteConfig>) {
            val properties = Properties()
            properties.setProperty("repository", repository.toString())
            for ((name, remote) in remotes) {
                val key = "remote.$name."
                properties.setProperty(key + "type", remote.type)
                properties.setProperty(key + "enabled", remote.enabled.toString())
                properties.setProperty(key + "path", remote.path)
                properties.setProperty(key + "url", remote.url)
                properties.setProperty(key + "region", remote.region)
                properties.setProperty(key + "bucket", remote.bucket)
                properties.setProperty(key + "path-style", remote.pathStyle.toString())
                properties.setProperty(key + "username", remote.username)
                properties.setProperty(key + "host", remote.host)
                properties.setProperty(key + "port", remote.port.toString())
                properties.setProperty(key + "private-key", remote.privateKey)
                properties.setProperty(key + "host-key", remote.hostKey)
                properties.setProperty(key + "security", remote.security)
                properties.setProperty(key + "client-id", remote.clientId)
            }
            val text = java.io.StringWriter()
            properties.store(text, "For the command-line restore (java -jar xBackup.jar restore). Written from config.yml; edits here are overwritten. Passwords stay in secrets.yml.")
            OwnerOnlyFile.write(home.resolve(NAME), text.toString())
        }
    }
}
