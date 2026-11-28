package co.xenastudios.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApiFloorTest {

    @TempDir
    lateinit var dir: File

    private fun jar(name: String, vararg classes: Pair<String, ByteArray>): File = File(dir, name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, bytes) in classes) {
                zip.putNextEntry(ZipEntry("$path.class"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun type(name: String, superName: String = "java/lang/Object", interfaces: Array<String>? = null, interfaceType: Boolean = false, body: ClassWriter.() -> Unit = {}): Pair<String, ByteArray> {
        val w = ClassWriter(0)
        val access = Opcodes.ACC_PUBLIC or (if (interfaceType) Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT else 0)
        w.visit(Opcodes.V1_8, access, name, null, superName, interfaces)
        w.body()
        w.visitEnd()
        return name to w.toByteArray()
    }

    private fun ClassWriter.abstractMethod(name: String, descriptor: String) =
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, name, descriptor, null, null).visitEnd()

    // A method of the plugin's whose code makes each call: owner, name, descriptor, interface.
    private fun ClassWriter.calls(vararg calls: List<Any>) {
        val m = visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null)
        m.visitCode()
        for ((owner, name, descriptor, itf) in calls) {
            m.visitInsn(Opcodes.ACONST_NULL)
            m.visitMethodInsn(if (itf as Boolean) Opcodes.INVOKEINTERFACE else Opcodes.INVOKEVIRTUAL, owner as String, name as String, descriptor as String, itf)
            m.visitInsn(Opcodes.POP)
        }
        m.visitInsn(Opcodes.RETURN)
        m.visitMaxs(2, 0)
        m.visitEnd()
    }

    // 1.13's API, small: EntityType is an enum without getKey(), Keyed has it, Entity has getType().
    private fun floor() = jar(
        "floor.jar",
        type("org/bukkit/Keyed", interfaceType = true) { abstractMethod("getKey", "()Lorg/bukkit/NamespacedKey;") },
        type("org/bukkit/NamespacedKey"),
        type("org/bukkit/entity/EntityType", superName = "java/lang/Enum") { abstractMethod("getName", "()Ljava/lang/String;") },
        type("org/bukkit/entity/Entity", interfaceType = true) { abstractMethod("getType", "()Lorg/bukkit/entity/EntityType;") },
        type("org/bukkit/entity/Player", interfaceType = true, interfaces = arrayOf("org/bukkit/entity/Entity")),
    )

    @Test
    fun aCallR8MadeToAMethodTheFloorLacksIsFound() {
        // What R8 made of `(type as? Keyed)?.key` shrinking against the newest API (#238).
        val plugin = jar(
            "plugin.jar",
            type("xrewind/a") {
                calls(
                    listOf("org/bukkit/entity/EntityType", "getKey", "()Lorg/bukkit/NamespacedKey;", false),
                    listOf("org/bukkit/entity/EntityType", "getName", "()Ljava/lang/String;", false),
                    // Inherited: Player gets getType() from Entity, name() from the JDK's Enum.
                    listOf("org/bukkit/entity/Player", "getType", "()Lorg/bukkit/entity/EntityType;", true),
                    listOf("org/bukkit/entity/EntityType", "name", "()Ljava/lang/String;", false),
                    // A class the floor hasn't, used guarded: one line for the class.
                    listOf("org/bukkit/event/world/EntitiesLoadEvent", "getChunk", "()Lorg/bukkit/Chunk;", false),
                    // Not the server's: not looked at.
                    listOf("kotlin/jvm/internal/Intrinsics", "nothing", "()V", false),
                )
            },
        )
        val mapping = File(dir, "plugin.map").apply { writeText("co.xenastudios.xrewind.Keys -> xrewind.a:\n    void run() -> run\n") }
        assertEquals(
            mapOf(
                "org/bukkit/entity/EntityType.getKey()Lorg/bukkit/NamespacedKey;" to setOf("co.xenastudios.xrewind.Keys"),
                "org/bukkit/event/world/EntitiesLoadEvent" to setOf("co.xenastudios.xrewind.Keys"),
            ),
            ApiFloor.missing(plugin, listOf(floor()), mapping),
        )
    }

    @Test
    fun theSameCallThroughKeyedIsAtTheFloor() {
        val plugin = jar("plugin.jar", type("xrewind/a") { calls(listOf("org/bukkit/Keyed", "getKey", "()Lorg/bukkit/NamespacedKey;", true)) })
        assertEquals(emptyMap<String, Set<String>>(), ApiFloor.missing(plugin, listOf(floor()), null))
    }

    @Test
    fun aMemberThePluginInheritsFromTheServerIsLookedUpThere() {
        // The plugin's own Player, calling getType() it has from the floor's Entity, and getPing() it hasn't.
        val plugin = jar(
            "plugin.jar",
            type("xrewind/b", interfaceType = true, interfaces = arrayOf("org/bukkit/entity/Player")),
            type("xrewind/a") {
                calls(
                    listOf("org/bukkit/entity/Player", "getPing", "()I", true),
                    listOf("xrewind/b", "getType", "()Lorg/bukkit/entity/EntityType;", true),
                )
            },
        )
        assertEquals(setOf("org/bukkit/entity/Player.getPing()I"), ApiFloor.missing(plugin, listOf(floor()), null).keys)
    }
}
