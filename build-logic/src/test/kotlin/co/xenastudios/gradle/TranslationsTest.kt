package co.xenastudios.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TranslationsTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun onlyTheYamlFilesInFoldersThatExistAreChecked() {
        val common = dir.resolve("common").apply { mkdirs() }
        common.resolve("de.yml").writeText("a: b")
        common.resolve("README.md").writeText("")
        val jvm = dir.resolve("jvm").apply { mkdirs() }
        jvm.resolve("fr.yml").writeText("a: b")
        assertEquals(listOf("de.yml", "fr.yml"), Translations.files(listOf(common, jvm, dir.resolve("none"))).map { it.name })
    }

    @Test
    fun theArgumentsNameTheSourceEachFolderAndWhetherMissingIsAllowed() {
        val a = File("a")
        assertEquals(listOf("--source", "x.Ref", "--lang", "a"), Translations.arguments("x.Ref", listOf(a), allowMissing = false))
        assertEquals(listOf("--source", "x.Ref", "--allow-missing"), Translations.arguments("x.Ref", emptyList(), allowMissing = true))
    }
}
