package co.xenastudios.core.jvm.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.ConfigContext
import co.xenastudios.core.common.config.ConfigEditor
import co.xenastudios.core.common.config.ConfigError
import co.xenastudios.core.common.config.ConfigNotice
import co.xenastudios.core.common.config.ConfigProblem
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.Configs
import co.xenastudios.core.common.config.YamlReader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A config file on disk, read and written against its [schema] by [Configs]. This adds what
 * only a file has:
 * - the old file is copied aside (`config.yml.bak-v1`) before it's carried forward, and
 *   if that copy can't be made, it's carried forward in memory only and left as it is, so a
 *   backup can't pile up on every start;
 * - a file that can't be written (a read-only mount, say) is still used, with a warning;
 * - every write goes to a temporary file that's then moved over the old one, so a crash
 *   never leaves half a config; a filesystem that can't move atomically gets a plain replace.
 *
 * Call it from one thread at a time.
 */
public class ConfigFile(
    public val schema: ConfigSchema,
    public val path: Path,
    private val reader: YamlReader = JvmYaml,
) {
    /** What loading the file found: the [config] to use, what was wrong ([problems]), and what changed ([notices]). */
    public class Loaded internal constructor(
        public val config: Config,
        public val problems: List<ConfigProblem>,
        public val notices: List<ConfigNotice>,
        /** The config version it was carried forward from, or 0 if it wasn't. */
        public val carriedFrom: Int,
    )

    private val name: String get() = path.fileName.toString()

    /**
     * Reads the file, writing the defaults first if there isn't one, carrying an old one
     * forward (after copying it aside) and adding new settings. An error means the file
     * couldn't be read or isn't valid YAML; it's left untouched, and the caller keeps the
     * last good config.
     */
    public fun load(context: ConfigContext = ConfigContext.DEFAULT): Outcome<Loaded, ConfigError> {
        val text = when (val read = readText()) {
            is Outcome.Ok -> read.value
            is Outcome.Err -> return read
        }
        val load = when (val loaded = Configs.load(schema, text, reader, context)) {
            is Outcome.Ok -> loaded.value
            is Outcome.Err -> return Outcome.Err(named(loaded.error))
        }
        val write = load.write ?: return Outcome.Ok(Loaded(load.config, load.problems, load.notices, load.carriedFrom))

        val problems = load.problems.toMutableList()
        val notices = load.notices.toMutableList()
        var backup: Path? = null
        if (load.backup) {
            try {
                backup = backup(load.carriedFrom)
                notices += ConfigNotice(ConfigNotice.Kind.BACKED_UP, "The old $name is kept as ${backup.fileName}.")
            } catch (e: Exception) {
                problems += ConfigProblem(
                    ConfigProblem.Kind.BACKUP_FAILED, "", 0, null,
                    "Couldn't copy $name aside (${e.reason()}), so it's carried forward for this run only and left unchanged on disk.",
                )
                return Outcome.Ok(Loaded(load.config, problems, emptyList(), load.carriedFrom))
            }
        }
        try {
            writeAtomically(path, write)
        } catch (e: Exception) {
            // A file that can't be written back still says what the owner wants: run with it.
            backup?.let { runCatching { Files.deleteIfExists(it) } }
            problems += ConfigProblem(
                ConfigProblem.Kind.SAVE_FAILED, "", 0, null,
                "Couldn't save the updated $name (${e.reason()}), so it's unchanged on disk; these settings are in use anyway.",
            )
            return Outcome.Ok(Loaded(load.config, problems, emptyList(), load.carriedFrom))
        }
        return Outcome.Ok(Loaded(load.config, problems, notices, load.carriedFrom))
    }

    /**
     * Reads the settings the file holds, carried forward in memory if it's old, without
     * writing, moving or backing up anything: a peek for code that runs before the plugin's
     * own [load], which then does those once. No notices, since nothing changes.
     */
    public fun read(context: ConfigContext = ConfigContext.DEFAULT): Outcome<Loaded, ConfigError> {
        val text = when (val read = readText()) {
            is Outcome.Ok -> read.value
            is Outcome.Err -> return read
        }
        return when (val loaded = Configs.load(schema, text, reader, context)) {
            is Outcome.Ok -> Outcome.Ok(Loaded(loaded.value.config, loaded.value.problems, emptyList(), loaded.value.carriedFrom))
            is Outcome.Err -> Outcome.Err(named(loaded.error))
        }
    }

    /**
     * Changes settings in the file, as an in-game edit does (`/filter add` changing
     * `filter.words`): the owner's other values are kept, what [change] reads is the file as
     * it is now, and the file is swapped in whole. Returns whether it was written: a change
     * that changes nothing writes nothing. A file that isn't valid YAML is never touched.
     * Reload afterwards to apply it.
     */
    public fun edit(context: ConfigContext = ConfigContext.DEFAULT, change: ConfigEditor.() -> Unit): Outcome<Boolean, ConfigError> {
        val text = when (val read = readText()) {
            is Outcome.Ok -> read.value
            is Outcome.Err -> return read
        }
        val edited = when (val result = Configs.edit(schema, text, reader, context, change)) {
            is Outcome.Ok -> result.value ?: return Outcome.Ok(false)
            is Outcome.Err -> return Outcome.Err(named(result.error))
        }
        return try {
            writeAtomically(path, edited)
            Outcome.Ok(true)
        } catch (e: Exception) {
            Outcome.Err(ConfigError("Couldn't write $name (${e.reason()}).", 0))
        }
    }

    private fun readText(): Outcome<String?, ConfigError> = try {
        Outcome.Ok(String(Files.readAllBytes(path), StandardCharsets.UTF_8))
    } catch (e: NoSuchFileException) {
        Outcome.Ok(null)
    } catch (e: Exception) {
        Outcome.Err(ConfigError("Couldn't read $name (${e.reason()}).", 0))
    }

    private fun named(error: ConfigError) = error.copy(message = error.message.replaceFirst("The config", name))

    // config.yml -> config.yml.bak-v1, or config.yml.bak-v1-2 if that exists.
    private fun backup(version: Int): Path {
        var backup = path.resolveSibling("$name.bak-v$version")
        var n = 2
        while (Files.exists(backup)) backup = path.resolveSibling("$name.bak-v$version-${n++}")
        Files.copy(path, backup)
        return backup
    }

    internal companion object {
        /** Tests only: the temporary file a write uses, to make the write fail on purpose. */
        @JvmStatic internal var blockedTemp: Path? = null

        /** Writes beside [file] and moves it into place, so a crash never leaves half a file. */
        fun writeAtomically(file: Path, text: String) {
            file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            // Each write gets its own temporary file: plugins sharing plugins/Xena/ can save the same
            // file at once, and a shared temporary name lets one move the other's away mid-write.
            val temp = blockedTemp ?: Files.createTempFile(file.toAbsolutePath().parent, ".${file.fileName}.", ".tmp")
            try {
                Files.write(temp, text.toByteArray(StandardCharsets.UTF_8))
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    // Some mounts (network shares, some container volumes) can't swap atomically;
                    // a plain replace is the best they offer.
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }

        // Files' exceptions carry just the path as their message; say what happened instead.
        private fun Exception.reason(): String = when (this) {
            is java.nio.file.AccessDeniedException -> "permission denied"
            is java.nio.file.ReadOnlyFileSystemException -> "read-only file system"
            is java.nio.file.FileSystemException -> reason ?: javaClass.simpleName
            else -> message ?: javaClass.simpleName
        }
    }
}
