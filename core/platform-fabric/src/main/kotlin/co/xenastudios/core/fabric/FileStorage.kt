package co.xenastudios.core.fabric

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.platform.Storage
import co.xenastudios.core.common.platform.StorageError
import co.xenastudios.core.common.platform.StoragePath
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A plugin's folder on disk: `config/<plugin>/` on Fabric. [StoragePath] already keeps every
 * path inside it, so nothing here can reach another folder.
 *
 * A write goes to a temporary file beside the real one, which is then renamed over it, so a
 * crash mid-write leaves the old file whole (ROADMAP.md, Robust). Not Fabric's own: any JVM
 * platform could use it, and it moves to `core-jvm` when a second one does.
 */
public class FileStorage(
    /** The folder, made on the first write. */
    public val root: Path,
) : Storage {

    override fun read(path: StoragePath): Outcome<ByteArray?, StorageError> = io(path) {
        try {
            Files.readAllBytes(file(path))
        } catch (_: NoSuchFileException) {
            null
        }
    }

    override fun write(path: StoragePath, bytes: ByteArray): Outcome<Unit, StorageError> = io(path) {
        val file = file(path)
        Files.createDirectories(file.parent)
        val temporary = file.resolveSibling(".${file.fileName}.tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            // Gone after a move; after a failure, not left beside the file.
            Files.deleteIfExists(temporary)
        }
    }

    override fun delete(path: StoragePath): Outcome<Boolean, StorageError> = io(path) { Files.deleteIfExists(file(path)) }

    override fun list(folder: StoragePath): Outcome<List<String>, StorageError> = io(folder) {
        val dir = file(folder)
        if (!Files.isDirectory(dir)) {
            emptyList()
        } else {
            Files.list(dir).use { files -> files.map { it.fileName.toString() }.sorted().toList() }
        }
    }

    private fun file(path: StoragePath): Path = path.names.fold(root) { at, name -> at.resolve(name) }

    private inline fun <T> io(path: StoragePath, block: () -> T): Outcome<T, StorageError> = try {
        Outcome.Ok(block())
    } catch (e: AccessDeniedException) {
        Outcome.Err(StorageError(path.value, StorageError.Kind.DENIED, e.message ?: "access denied"))
    } catch (e: SecurityException) {
        Outcome.Err(StorageError(path.value, StorageError.Kind.DENIED, e.message ?: "access denied"))
    } catch (e: IOException) {
        Outcome.Err(StorageError(path.value, StorageError.Kind.IO, "${e::class.java.simpleName}: ${e.message}"))
    }
}
