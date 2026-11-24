package co.xenastudios.core.jvm.platform

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.platform.Storage
import co.xenastudios.core.common.platform.StorageError
import co.xenastudios.core.common.platform.StoragePath
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A plugin's folder on disk, as every JVM platform's [Storage]: `plugins/<Plugin>/` on Bukkit,
 * `config/<plugin>/` on the mod loaders. Paths are [StoragePath]s, which can't leave the
 * folder, so nothing here needs to check that again.
 *
 * A write goes to a temporary file beside the real one, then is moved over it, so a crash
 * leaves the old file or the new one, never half of either. Every call waits for the disk:
 * call it from an async task.
 */
public class FolderStorage(
    /** The plugin's folder. It's made by the first write. */
    public val root: Path,
) : Storage {

    override fun read(path: StoragePath): Outcome<ByteArray?, StorageError> = io(path) {
        try {
            Files.readAllBytes(file(path))
        } catch (e: NoSuchFileException) {
            null
        }
    }

    override fun write(path: StoragePath, bytes: ByteArray): Outcome<Unit, StorageError> {
        if (path == StoragePath.ROOT) return Outcome.Err(StorageError(path.value, StorageError.Kind.BAD_PATH, "the plugin's folder isn't a file"))
        return io(path) {
            val file = file(path)
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling("${file.fileName}.tmp")
            try {
                Files.write(temp, bytes)
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
    }

    override fun delete(path: StoragePath): Outcome<Boolean, StorageError> = io(path) {
        val file = file(path)
        if (Files.isDirectory(file)) throw FileSystemException(file.toString(), null, "it's a folder")
        Files.deleteIfExists(file)
    }

    override fun list(folder: StoragePath): Outcome<List<String>, StorageError> = io(folder) {
        val dir = file(folder)
        if (!Files.isDirectory(dir)) return@io emptyList()
        Files.newDirectoryStream(dir).use { entries -> entries.map { it.fileName.toString() }.sorted() }
    }

    private fun file(path: StoragePath): Path = path.names.fold(root) { at, name -> at.resolve(name) }

    // Every disk error as a StorageError, with what the disk said rather than just the path.
    private inline fun <T> io(path: StoragePath, block: () -> T): Outcome<T, StorageError> = try {
        Outcome.Ok(block())
    } catch (e: AccessDeniedException) {
        Outcome.Err(StorageError(path.value, StorageError.Kind.DENIED, "permission denied"))
    } catch (e: SecurityException) {
        Outcome.Err(StorageError(path.value, StorageError.Kind.DENIED, e.message ?: "not allowed"))
    } catch (e: FileSystemException) {
        Outcome.Err(StorageError(path.value, StorageError.Kind.IO, e.reason ?: e.javaClass.simpleName))
    } catch (e: IOException) {
        Outcome.Err(StorageError(path.value, StorageError.Kind.IO, e.message ?: e.javaClass.simpleName))
    }
}
