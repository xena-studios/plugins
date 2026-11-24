package co.xenastudios.core.bukkit

import co.xenastudios.core.bukkit.modern.FoliaScheduler
import co.xenastudios.core.jvm.JvmCore
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Both source sets are Java 8 class files: main runs on Spigot 1.8.8 on Java 8, and the code
 * compiled against Paper's API runs on Paper 1.16.5, which runs on Java 8 too.
 */
class BytecodeTest {

    @Test
    fun mainAndModernAreJava8Bytecode() {
        for (type in listOf(BukkitPlatform::class, BukkitCommands::class, FoliaScheduler::class)) {
            assertEquals(JvmCore.CLASS_FILE_MAJOR, major(type), "class file version of ${type.qualifiedName}")
        }
    }

    private fun major(type: KClass<*>): Int {
        val path = "/" + type.java.name.replace('.', '/') + ".class"
        val bytes = checkNotNull(type.java.getResourceAsStream(path)) { "no $path" }.use { it.readBytes() }
        // The magic number, the minor version, then the major version, big-endian.
        return ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
    }
}
