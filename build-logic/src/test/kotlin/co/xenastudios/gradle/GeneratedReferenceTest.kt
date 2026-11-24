package co.xenastudios.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GeneratedReferenceTest {
    @TempDir
    lateinit var dir: File

    private val fresh by lazy { dir.resolve("fresh") }
    private val committed by lazy { dir.resolve("committed") }

    private fun write(root: File, path: String, text: String) {
        root.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }

    @Test
    fun `nothing committed yet is stale`() {
        write(fresh, "xhomes/manifest.json", "{}\n")
        write(fresh, "manifest.schema.json", "{}\n")
        assertEquals(listOf("xhomes/manifest.json: not committed", "manifest.schema.json: not committed"), GeneratedReference.stale(fresh, committed, "xhomes"))
    }

    @Test
    fun `a change names the first line that differs`() {
        write(fresh, "xhomes/manifest.json", "{\n  \"default\": 150\n}\n")
        write(committed, "xhomes/manifest.json", "{\n  \"default\": 100\n}\n")
        write(fresh, "manifest.schema.json", "{}\n")
        write(committed, "manifest.schema.json", "{}\n")
        assertEquals(
            listOf("xhomes/manifest.json: line 2\n    committed:   \"default\": 100\n    fresh:       \"default\": 150"),
            GeneratedReference.stale(fresh, committed, "xhomes"),
        )
    }

    @Test
    fun `updating copies what's new and deletes what's no longer written, and leaves other plugins alone`() {
        write(fresh, "xhomes/manifest.json", "new\n")
        write(fresh, "manifest.schema.json", "schema\n")
        write(committed, "xhomes/config.yml", "old\n")
        write(committed, "xother/config.yml", "theirs\n")
        assertEquals(listOf("xhomes/manifest.json: not committed", "xhomes/config.yml: no longer generated", "manifest.schema.json: not committed"), GeneratedReference.stale(fresh, committed, "xhomes"))

        GeneratedReference.update(fresh, committed, "xhomes")
        assertEquals(emptyList<String>(), GeneratedReference.stale(fresh, committed, "xhomes"))
        assertEquals("new\n", committed.resolve("xhomes/manifest.json").readText())
        assertFalse(committed.resolve("xhomes/config.yml").exists())
        assertEquals("theirs\n", committed.resolve("xother/config.yml").readText())
    }
}
