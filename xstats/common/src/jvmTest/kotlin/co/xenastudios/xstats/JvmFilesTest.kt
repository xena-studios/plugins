package co.xenastudios.xstats

import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.family.MemoryBoard
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.platform.FolderStorage
import co.xenastudios.core.testing.RecordingLog
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** What [JvmFiles] keeps once it has loaded: the translations in use, not the files they came from. */
class JvmFilesTest {
    private val folder = Files.createTempDirectory("xstats-files")

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    private fun files() = JvmFiles(folder.resolve("xStats"), Family(FolderStorage(folder.resolve("Xena")), MemoryBoard(), JvmYaml), JvmFilesTest::class.java.classLoader, RecordingLog())

    // okio's pool for this thread's bucket, as JvmFiles reads it.
    private fun pooled(): Int {
        val pool = Class.forName("okio.SegmentPool")
        return pool.getMethod("getByteCount").invoke(pool.getField("INSTANCE").get(null)) as Int
    }

    @Test
    fun everyShippedLanguageLoadsFromTheJarEachTime() {
        val files = files()
        repeat(2) {
            val loaded = files.load()
            for (tag in JvmFiles.LANGUAGES) assertNotNull(loaded.translations.language(tag), "$tag")
            assertNotNull(loaded.translations.language(LanguageTag.ENGLISH))
            for (tag in JvmFiles.LANGUAGES + LanguageTag.ENGLISH) assert(Files.exists(folder.resolve("xStats/lang/$tag.yml"))) { "lang/$tag.yml" }
        }
    }

    @Test
    fun loadingLeavesNoBuffersPooled() {
        val files = files()
        files.load()
        assertEquals(0, pooled(), "okio's pool after a load")
        files.config()
        assertEquals(0, pooled(), "okio's pool after reading config.yml")
    }
}
