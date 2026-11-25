package co.xenastudios.core.jvm.config

import co.xenastudios.core.testing.RecordingLog
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarriedMessagesTest {
    @Test
    fun editedMessagesStartEnglishsFileAndNeverOverwriteIt() {
        val dir = Files.createTempDirectory("carried")
        try {
            val log = RecordingLog()
            assertNull(CarriedMessages.write(dir, mapOf("heal.self" to "<gold>\"Patched\" up!\\"), "xHomes 1.x", "config.yml.bak-v1", log))
            val text = String(Files.readAllBytes(dir.resolve("lang/en.yml")))
            assertEquals("# Your messages from xHomes 1.x's config.yml.\nheal.self: \"<gold>\\\"Patched\\\" up!\\\\\"\n", text)
            assertTrue(log.lines.any { "Copied 1 message you edited" in it.message })
            val again = CarriedMessages.write(dir, mapOf("a" to "b"), "xHomes 1.x", "config.yml.bak-v1", log)
            assertTrue(again!!.contains("already there"))
            assertEquals(text, String(Files.readAllBytes(dir.resolve("lang/en.yml"))))
            assertNull(CarriedMessages.write(dir, emptyMap(), "x", "y", log), "nothing edited, nothing written")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
