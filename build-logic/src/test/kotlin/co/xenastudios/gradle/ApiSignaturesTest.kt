package co.xenastudios.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File

class ApiSignaturesTest {

    private val signatures = ApiSignatures("xrewind")

    @TempDir
    lateinit var dir: File

    /** An API interface `co.xenastudios.xrewind.api.Thing` with one method, whose signature is [signature]. */
    private fun api(name: String, signature: String?, descriptor: String = "(Ljava/util/List;)V"): File {
        val classes = File(dir, name).apply { mkdirs() }
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            "co/xenastudios/xrewind/api/Thing", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "take", descriptor, signature, null).visitEnd()
        writer.visitEnd()
        File(classes, "co/xenastudios/xrewind/api").apply { mkdirs() }.resolve("Thing.class")
            .writeBytes(writer.toByteArray())
        return classes
    }

    @Test
    fun aChangeOnlyToGenericsFailsTheCheck() {
        val before = signatures.read(listOf(api("before", "(Ljava/util/List<Ljava/lang/String;>;)V")))
        val after = signatures.read(listOf(api("after", "(Ljava/util/List<Ljava/lang/Integer;>;)V")))
        val failure = signatures.compare(before.text, after.text, "test.api")
        assertNotNull(failure)
        assertTrue(failure!!.contains("Ljava/lang/Integer;"), failure)
        assertNull(signatures.compare(before.text, before.text, "test.api"))
    }

    @Test
    fun internalsInGenericSignaturesAreFlagged() {
        val result = signatures.read(listOf(api("leak",
            "(Ljava/util/List<Lco/xenastudios/xrewind/core/KeyedValues;>;)V")))
        assertEquals(1, result.problems.size, result.problems.toString())
        assertTrue(result.problems[0].contains("co.xenastudios.xrewind.core.KeyedValues"))
    }

    /** Writes one class and returns its classes directory. */
    private fun write(name: String, internalName: String, build: (ClassWriter) -> Unit): File {
        val classes = File(dir, name).apply { mkdirs() }
        val writer = ClassWriter(0)
        build(writer)
        writer.visitEnd()
        File(classes, internalName.substringBeforeLast('/')).apply { mkdirs() }
            .resolve(internalName.substringAfterLast('/') + ".class").writeBytes(writer.toByteArray())
        return classes
    }

    private fun sealed(name: String, permitted: List<String>) = write(name, "co/xenastudios/xrewind/api/Shape") {
        it.visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            "co/xenastudios/xrewind/api/Shape", null, "java/lang/Object", null)
        permitted.forEach { sub -> it.visitPermittedSubclass(sub) }
    }

    private fun annotation(name: String, default: Any) = write(name, "co/xenastudios/xrewind/api/Mark") {
        it.visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT or Opcodes.ACC_ANNOTATION,
            "co/xenastudios/xrewind/api/Mark", null, "java/lang/Object", arrayOf("java/lang/annotation/Annotation"))
        val method = it.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "value", "()I", null, null)
        method.visitAnnotationDefault().apply { visit(null, default); visitEnd() }
        method.visitEnd()
    }

    private fun constant(name: String, value: Int) = write(name, "co/xenastudios/xrewind/api/Limits") {
        it.visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "co/xenastudios/xrewind/api/Limits", null,
            "java/lang/Object", null)
        it.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "MAX", "I", null, value).visitEnd()
    }

    private fun fails(before: File, after: File): String? =
        signatures.compare(signatures.read(listOf(before)).text, signatures.read(listOf(after)).text, "test.api")

    @Test
    fun aChangeToPermittedSubclassesFailsTheCheck() {
        val failure = fails(sealed("sealed-before", listOf("co/xenastudios/xrewind/api/Circle")),
            sealed("sealed-after", listOf("co/xenastudios/xrewind/api/Circle", "co/xenastudios/xrewind/api/Square")))
        assertNotNull(failure)
        assertTrue(failure!!.contains("permits"), failure)
    }

    @Test
    fun aChangeToAnAnnotationDefaultFailsTheCheck() {
        val failure = fails(annotation("default-before", 1), annotation("default-after", 2))
        assertNotNull(failure)
        assertTrue(failure!!.contains("default 2"), failure)
    }

    @Test
    fun aChangeToAConstantFailsTheCheck() {
        val failure = fails(constant("constant-before", 10), constant("constant-after", 20))
        assertNotNull(failure)
        assertTrue(failure!!.contains("MAX = 20"), failure)
    }

    @Test
    fun anotherPluginsCodeIsFlagged() {
        // Another plugin's API, or a core class, in a signature: neither is part of this API.
        val result = signatures.read(listOf(api("other", "(Ljava/util/List<Lco/xenastudios/xutilities/api/Back;>;)V")))
        assertEquals(1, result.problems.size, result.problems.toString())
        assertTrue(result.problems[0].contains("co.xenastudios.xutilities.api.Back"))
    }
}
