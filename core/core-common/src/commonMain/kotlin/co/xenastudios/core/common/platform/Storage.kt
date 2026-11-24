package co.xenastudios.core.common.platform

import co.xenastudios.core.common.Outcome

/**
 * The plugin's own folder, as the platform gives it: `plugins/<Plugin>/` on Bukkit,
 * `config/<plugin>/` on the mod loaders, the plugin's `data` folder on Pumpkin, which can't
 * use the stdlib's files or a shared database (M0). Shared code keeps its files only through
 * this.
 *
 * Paths are relative to the folder, with `/` between names (`lang/en.yml`); one that tries
 * to leave the folder is refused (see [StoragePath]).
 *
 * Every call reads or writes the disk and waits for it, so it belongs off the server's
 * threads, in an async task. Errors come back as [StorageError]s; nothing throws.
 */
public interface Storage {
    /** The file's bytes, or `null` if there's no such file. */
    public fun read(path: StoragePath): Outcome<ByteArray?, StorageError>

    /**
     * Writes the file, making its folders: all of it or, if that fails, none of it. JVM
     * platforms write a temporary file beside it and rename it over the old one.
     */
    public fun write(path: StoragePath, bytes: ByteArray): Outcome<Unit, StorageError>

    /** Deletes the file. Returns whether there was one. */
    public fun delete(path: StoragePath): Outcome<Boolean, StorageError>

    /** The names of the files and folders directly in [folder], sorted; empty if it doesn't exist. */
    public fun list(folder: StoragePath): Outcome<List<String>, StorageError>
}

/** Reads the file as UTF-8 text, or `null` if there's no such file. */
public fun Storage.readText(path: StoragePath): Outcome<String?, StorageError> = when (val read = read(path)) {
    is Outcome.Ok -> Outcome.Ok(read.value?.decodeToString())
    is Outcome.Err -> read
}

/** Writes [text] to the file as UTF-8, all or nothing. */
public fun Storage.writeText(path: StoragePath, text: String): Outcome<Unit, StorageError> =
    write(path, text.encodeToByteArray())

/**
 * A path inside a plugin's folder: names joined by `/`, none of them empty, `.` or `..`, and
 * no `\` or `:`, so it can't leave the folder or mean different things on different systems.
 * [ROOT] is the folder itself.
 */
public class StoragePath private constructor(
    /** The names from the folder down, such as `["lang", "en.yml"]`. */
    public val names: List<String>,
) {
    /** The path as written, `lang/en.yml`; empty for [ROOT]. */
    public val value: String get() = names.joinToString("/")

    /** The path of [name] inside this one. */
    public fun resolve(name: String): StoragePath = parse(if (names.isEmpty()) name else "$value/$name").getOrNull()
        ?: throw IllegalArgumentException("\"$name\" isn't a name inside a plugin's folder")

    override fun equals(other: Any?): Boolean = other is StoragePath && other.names == names

    override fun hashCode(): Int = names.hashCode()

    override fun toString(): String = value

    public companion object {
        /** The plugin's folder itself. */
        public val ROOT: StoragePath = StoragePath(emptyList())

        /** [path] as a [StoragePath], or why it can't be one. A leading, trailing or doubled `/` is refused too. */
        public fun parse(path: String): Outcome<StoragePath, StorageError> {
            if (path.isEmpty()) return Outcome.Ok(ROOT)
            if (path.startsWith("/")) {
                return Outcome.Err(StorageError(path, StorageError.Kind.BAD_PATH, "a path starts inside the plugin's folder, not with /"))
            }
            val names = path.split('/')
            for (name in names) {
                if (name.isEmpty() || name == "." || name == ".." || '\\' in name || ':' in name || name.any { it < ' ' }) {
                    return Outcome.Err(StorageError(path, StorageError.Kind.BAD_PATH, "\"$name\" isn't allowed in a path"))
                }
            }
            return Outcome.Ok(StoragePath(names))
        }

        /** [path] as a [StoragePath], for paths written in code. Throws if it can't be one. */
        public fun of(path: String): StoragePath = when (val parsed = parse(path)) {
            is Outcome.Ok -> parsed.value
            is Outcome.Err -> throw IllegalArgumentException(parsed.error.detail)
        }
    }
}

/** Why [Storage] couldn't do something with [path]: the [kind] of failure and the platform's [detail]. */
public data class StorageError(
    public val path: String,
    public val kind: Kind,
    public val detail: String,
) {
    /** The kinds of failure, for a message in the reader's language. */
    public enum class Kind {
        /** The path isn't one inside the plugin's folder. */
        BAD_PATH,

        /** The platform wouldn't let the plugin do it, such as a file it may not write. */
        DENIED,

        /** The disk said no: full, gone, or a folder where a file should be. */
        IO,
    }
}
