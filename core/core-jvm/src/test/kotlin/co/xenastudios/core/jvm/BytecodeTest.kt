package co.xenastudios.core.jvm

import co.xenastudios.core.api.CoreApi
import co.xenastudios.core.api.Location
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.text.Markup
import co.xenastudios.core.common.time.Durations
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The core runs on Spigot 1.8.8 on Java 8 (DECISIONS.md, M0), so every class it ships is a
 * Java 8 class file, from each module: a Java 17 class here would only show up on an old
 * server.
 */
class BytecodeTest {

    @Test
    fun everyModuleIsJava8Bytecode() {
        for (type in listOf(
            JvmCore::class, CoreApi::class, Location::class, EventBus::class,
            Outcome::class, Markup::class, Durations::class, SimpleEventBus::class, ModuleSet::class, Scheduler::class,
        )) {
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
