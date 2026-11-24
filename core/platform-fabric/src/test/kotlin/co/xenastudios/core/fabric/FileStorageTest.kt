package co.xenastudios.core.fabric

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.platform.StorageError
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileStorageTest {
    private val root = Files.createTempDirectory("storage").resolve("xsample")
    private val storage = FileStorage(root)

    @Test
    fun `files are written, read, listed and deleted inside the folder`() {
        val path = StoragePath.of("lang/en.yml")
        assertEquals(Outcome.Ok(null), storage.readText(path))
        assertEquals(Outcome.Ok(Unit), storage.writeText(path, "hello"))
        assertEquals(Outcome.Ok("hello"), storage.readText(path))
        assertEquals(Outcome.Ok(listOf("lang")), storage.list(StoragePath.ROOT))
        assertEquals(Outcome.Ok(listOf("en.yml")), storage.list(StoragePath.of("lang")))
        assertEquals(Outcome.Ok(true), storage.delete(path))
        assertEquals(Outcome.Ok(false), storage.delete(path))
        assertEquals(Outcome.Ok(emptyList()), storage.list(StoragePath.of("missing")))
    }

    @Test
    fun `a write replaces the file whole and leaves no temporary file`() {
        val path = StoragePath.of("config.yml")
        storage.writeText(path, "old")
        storage.writeText(path, "new")
        assertEquals(Outcome.Ok("new"), storage.readText(path))
        assertEquals(listOf("config.yml"), root.listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun `a disk that says no is an error, not an exception`() {
        // A folder where the file should be.
        root.resolve("taken").createDirectories()
        val result = storage.writeText(StoragePath.of("taken"), "x")
        assertIs<Outcome.Err<StorageError>>(result)
        assertEquals(StorageError.Kind.IO, result.error.kind)
        assertTrue(root.resolve("taken").exists())
    }
}
