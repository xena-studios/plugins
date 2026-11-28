package co.xenastudios.core.jvm.platform

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.platform.StorageError
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderStorageTest {

    private val dir: Path = Files.createTempDirectory("folder-storage-test")
    private val storage = FolderStorage(dir.resolve("XSample"))

    @AfterTest
    fun cleanUp() {
        Files.walk(dir).forEach { it.toFile().setWritable(true) }
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun <T> ok(outcome: Outcome<T, StorageError>): T = assertIs<Outcome.Ok<T>>(outcome).value

    @Test
    fun writesMakeTheirFoldersAndReadBack() {
        ok(storage.writeText(StoragePath.of("lang/de.yml"), "hallo: Hallo"))
        assertEquals("hallo: Hallo", ok(storage.readText(StoragePath.of("lang/de.yml"))))
        assertEquals("hallo: Hallo", String(Files.readAllBytes(dir.resolve("XSample/lang/de.yml")), Charsets.UTF_8))
        assertEquals(listOf("de.yml"), ok(storage.list(StoragePath.of("lang"))))
        assertEquals(listOf("lang"), ok(storage.list(StoragePath.ROOT)))
    }

    @Test
    fun aMissingFileOrFolderIsNothingNotAnError() {
        assertNull(ok(storage.read(StoragePath.of("none.yml"))))
        assertEquals(emptyList<String>(), ok(storage.list(StoragePath.of("none"))))
        assertFalse(ok(storage.delete(StoragePath.of("none.yml"))))
    }

    @Test
    fun aWriteReplacesTheWholeFileAndLeavesNoTemporaryFile() {
        val path = StoragePath.of("data.bin")
        ok(storage.write(path, ByteArray(1000) { 1 }))
        ok(storage.write(path, byteArrayOf(2, 3)))
        assertContentEquals(byteArrayOf(2, 3), ok(storage.read(path)))
        assertEquals(listOf("data.bin"), ok(storage.list(StoragePath.ROOT)))
        assertTrue(ok(storage.delete(path)))
        assertNull(ok(storage.read(path)))
    }

    @Test
    fun aFolderWhereAFileShouldBeIsAnIoError() {
        ok(storage.writeText(StoragePath.of("lang/en.yml"), "x"))
        assertEquals(StorageError.Kind.IO, assertIs<Outcome.Err<StorageError>>(storage.writeText(StoragePath.of("lang"), "x")).error.kind)
        assertEquals(StorageError.Kind.IO, assertIs<Outcome.Err<StorageError>>(storage.delete(StoragePath.of("lang"))).error.kind)
        assertEquals(StorageError.Kind.BAD_PATH, assertIs<Outcome.Err<StorageError>>(storage.write(StoragePath.ROOT, byteArrayOf())).error.kind)
    }

    @Test
    fun aFolderItMayNotWriteIsDenied() {
        ok(storage.writeText(StoragePath.of("locked/a.txt"), "x"))
        val locked = dir.resolve("XSample/locked").toFile()
        // Root ignores permissions, as in some CI containers; nothing to test there.
        if (!locked.setWritable(false) || locked.canWrite()) return
        val error = assertIs<Outcome.Err<StorageError>>(storage.writeText(StoragePath.of("locked/b.txt"), "y")).error
        assertEquals(StorageError.Kind.DENIED, error.kind)
        assertEquals("locked/b.txt", error.path)
    }

    @Test
    fun twoPluginsSavingTheSameFileAtOnceBothSucceed() {
        // Two plugins' cores share plugins/Xena/: each has its own FolderStorage on the same folder.
        val other = FolderStorage(dir.resolve("XSample"))
        val start = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val writers = listOf(storage, other).mapIndexed { n, store ->
            Thread {
                start.await()
                repeat(300) { i ->
                    val written = store.writeText(StoragePath.of("config.yml"), "writer: $n\nround: $i\n")
                    if (written is Outcome.Err) failures += written.error.detail
                }
            }.also { it.start() }
        }
        start.countDown()
        writers.forEach { it.join(30_000) }
        assertTrue(failures.isEmpty(), "writes failed: ${failures.take(3)}")
        assertTrue(ok(storage.readText(StoragePath.of("config.yml")))!!.startsWith("writer: "))
        assertEquals(listOf("config.yml"), ok(storage.list(StoragePath.ROOT)))
    }
}
