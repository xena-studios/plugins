package co.xenastudios.core.testing

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.platform.Storage
import co.xenastudios.core.common.platform.StorageError
import co.xenastudios.core.common.platform.StoragePath

/**
 * A plugin folder in memory. Set [failure] to make every call fail with that kind of error, to
 * test what a plugin does when the disk says no.
 */
public class MemoryStorage : Storage {
    private val files = LinkedHashMap<StoragePath, ByteArray>()

    /** When set, every call fails with this kind of [StorageError]. */
    public var failure: StorageError.Kind? = null

    /** Every file's path, sorted. */
    public val paths: List<String> get() = files.keys.map { it.value }.sorted()

    override fun read(path: StoragePath): Outcome<ByteArray?, StorageError> =
        failed(path) ?: Outcome.Ok(files[path]?.copyOf())

    override fun write(path: StoragePath, bytes: ByteArray): Outcome<Unit, StorageError> {
        failed(path)?.let { return it }
        if (path == StoragePath.ROOT || files.keys.any { it.names.size > path.names.size && it.names.subList(0, path.names.size) == path.names }) {
            return Outcome.Err(StorageError(path.value, StorageError.Kind.IO, "a folder is there"))
        }
        files[path] = bytes.copyOf()
        return Outcome.Ok(Unit)
    }

    override fun delete(path: StoragePath): Outcome<Boolean, StorageError> =
        failed(path) ?: Outcome.Ok(files.remove(path) != null)

    override fun list(folder: StoragePath): Outcome<List<String>, StorageError> {
        failed(folder)?.let { return it }
        val depth = folder.names.size
        return Outcome.Ok(
            files.keys
                .filter { it.names.size > depth && it.names.subList(0, depth) == folder.names }
                .map { it.names[depth] }
                .distinct()
                .sorted(),
        )
    }

    private fun failed(path: StoragePath): Outcome.Err<StorageError>? =
        failure?.let { Outcome.Err(StorageError(path.value, it, "failing on purpose")) }
}
