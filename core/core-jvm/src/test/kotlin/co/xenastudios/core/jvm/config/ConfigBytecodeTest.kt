package co.xenastudios.core.jvm.config

import co.xenastudios.core.jvm.JvmCore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The config code, and the YAML parser it brings with it, load on Java 8 (DECISIONS.md, M0).
 * kaml failed this (Java 11), which is why the parser is used directly.
 */
class ConfigBytecodeTest {

    @Test
    fun theConfigAndItsYamlLibrariesAreJava8Bytecode() {
        val classes = listOf(
            "co.xenastudios.core.jvm.config.ConfigFile",
            "co.xenastudios.core.jvm.config.JvmYaml",
            "co.xenastudios.core.common.config.Configs",
            "it.krzeminski.snakeyaml.engine.kmp.api.lowlevel.Compose",
            "it.krzeminski.snakeyaml.engine.kmp.scanner.ScannerImpl",
            "okio.Buffer",
            "net.thauvin.erik.urlencoder.UrlEncoderUtil",
        )
        for (name in classes) {
            val major = major(name)
            assertTrue(major <= JvmCore.CLASS_FILE_MAJOR, "$name is class file version $major")
        }
    }

    private fun major(name: String): Int {
        val path = name.replace('.', '/') + ".class"
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "no $path" }
        val bytes = stream.use { it.readBytes() }
        return ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
    }
}
