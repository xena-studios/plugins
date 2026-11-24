package co.xenastudios.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LibraryPinsTest {

    private val old = "a".repeat(64)
    private val new = "b".repeat(64)
    private val pinsText = """
        # The SHA-256 of every library downloaded at runtime.

        org.jetbrains.kotlin:kotlin-stdlib:2.4.21 $old
    """.trimIndent() + "\n"

    @Test
    fun `a jar matching its pin passes`() {
        val pins = LibraryPins.parse(pinsText)
        assertEquals(emptyList<String>(), LibraryPins.problems(listOf(LibraryPins.Jar("org.jetbrains.kotlin:kotlin-stdlib:2.4.21", old)), pins))
    }

    @Test
    fun `a version bump fails until its hash is pinned`() {
        val pins = LibraryPins.parse(pinsText)
        assertEquals(
            listOf("org.jetbrains.kotlin:kotlin-stdlib:2.4.30 has no SHA-256 pinned (it resolves to $new)"),
            LibraryPins.problems(listOf(LibraryPins.Jar("org.jetbrains.kotlin:kotlin-stdlib:2.4.30", new)), pins),
        )
        assertEquals(
            listOf("org.jetbrains.kotlin:kotlin-stdlib:2.4.21 resolves to $new, but its pin is $old"),
            LibraryPins.problems(listOf(LibraryPins.Jar("org.jetbrains.kotlin:kotlin-stdlib:2.4.21", new)), pins),
        )
    }

    @Test
    fun `updating keeps the header and sorts the pins`() {
        val updated = LibraryPins.updated(pinsText, listOf(LibraryPins.Jar("com.example:a:1.0", new)))
        assertEquals(
            "# The SHA-256 of every library downloaded at runtime.\n\n" +
                "com.example:a:1.0 $new\norg.jetbrains.kotlin:kotlin-stdlib:2.4.21 $old\n",
            updated,
        )
    }

    @Test
    fun `a malformed pin is refused`() {
        assertThrows<IllegalArgumentException> { LibraryPins.parse("kotlin-stdlib $old") }
        assertThrows<IllegalArgumentException> { LibraryPins.parse("a:b:c not-a-hash") }
    }

    @Test
    fun `the probe is the jar's shortest top-level class`(@TempDir dir: File) {
        val jar = dir.resolve("lib.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            for (name in listOf("META-INF/versions/9/module-info.class", "kotlin/collections/ArraysKt.class", "kotlin/Unit.class",
                "kotlin/Lazy.class", "kotlin/A\$B.class", "kotlin/package-info.class")) {
                zip.putNextEntry(ZipEntry(name))
                zip.closeEntry()
            }
        }
        assertEquals("kotlin.Lazy", LibraryPins.probe(jar))
    }

    @Test
    fun `the manifest is what the loader reads`() {
        val text = LibraryPins.manifest(listOf(LibraryPins.Jar("org.jetbrains.kotlin:kotlin-stdlib:2.4.21", old) to "kotlin.Lazy"))
        assertTrue(text.lines().contains("xena-libraries 1"), text)
        assertTrue(text.lines().contains("org.jetbrains.kotlin:kotlin-stdlib:2.4.21 $old kotlin.Lazy"), text)
    }
}
