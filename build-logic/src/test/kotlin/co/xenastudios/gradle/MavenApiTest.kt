package co.xenastudios.gradle

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class MavenApiTest {
    private val common = """
        package co.xenastudios.core.api

        import kotlin.jvm.JvmSynthetic

        /**
         * Work that finishes later.
         */
        public expect open class Pending<T>

        /** Already settled with [value]. */
        public expect fun <T> pendingOf(value: T): Pending<T>

        /** A [Pending] of what [transform] makes of the value. */
        @JvmSynthetic
        public fun <T, R> Pending<T>.map(transform: (T) -> R): Pending<R> {
            return TODO()
        }
    """.trimIndent()

    private val jvm = """
        package co.xenastudios.core.api

        import java.util.concurrent.CompletableFuture

        /** On the JVM, a [Pending] is a `CompletableFuture`. */
        public actual typealias Pending<T> = CompletableFuture<T>

        @JvmSynthetic
        public actual fun <T> pendingOf(value: T): Pending<T> = CompletableFuture.completedFuture(value)
    """.trimIndent()

    @Test
    fun `takes the JVM's view of a multiplatform file`() {
        val view = MavenApi.jvmView(common, jvm)!!
        assertTrue("public typealias Pending<T> = CompletableFuture<T>" in view)
        assertTrue("public fun <T> pendingOf(value: T)" in view)
        assertTrue("public fun <T, R> Pending<T>.map" in view, "the common file's other helpers stay")
        assertTrue("expect" !in view && "actual" !in view)
        assertEquals(1, Regex("^package ", RegexOption.MULTILINE).findAll(view).count())
        assertTrue("import java.util.concurrent.CompletableFuture" in view && "import kotlin.jvm.JvmSynthetic" in view)
        assertEquals(common.replace("expect ", ""), MavenApi.jvmView(common.replace("expect ", ""), null))
        assertThrows<GradleException> { MavenApi.jvmView(common, null) }
    }

    @Test
    fun `relocates the core as the jar does`() {
        assertEquals("package co.xenastudios.xbans.core.api\nimport co.xenastudios.xbans.core.api.event.EventBus",
            MavenApi.relocate("package co.xenastudios.core.api\nimport co.xenastudios.core.api.event.EventBus", "xbans"))
    }

    @Test
    fun `knows what a file declares`() {
        assertEquals(setOf("Pending"), MavenApi.declaredTypes(MavenApi.jvmView(common, jvm)!!))
        assertEquals(setOf("PlayerRef", "PlayerRefs"), MavenApi.declaredTypes("@file:JvmName(\"PlayerRefs\")\npublic class PlayerRef\npublic data class Other(val a: Int)".replace("public data class Other", "internal class Other")))
        assertEquals(setOf("PlayerRefs"), MavenApi.fileClasses("@file:JvmName(\"PlayerRefs\")\n"))
    }

    @Test
    fun `hides top-level functions a caller couldn't reach`() {
        val hidden = MavenApi.suppressTopLevel(MavenApi.jvmView(common, jvm)!!)
        assertEquals(2, Regex("@suppress").findAll(hidden).count(), hidden)
        assertTrue("/**\n * A [Pending] of what [transform] makes of the value.\n * @suppress\n */\n@JvmSynthetic\npublic fun <T, R>" in hidden, hidden)
        assertTrue("/** @suppress */\n@JvmSynthetic\npublic fun <T> pendingOf" in hidden, hidden)
        assertTrue("@suppress */\npublic typealias" !in hidden, "types stay")
    }

    private fun bundle(vararg skip: String, pom: String = POM): File {
        val repo = kotlin.io.path.createTempDirectory().toFile()
        val dir = repo.resolve("co/xenastudios/xbans/xbans-api/2.0.0").apply { mkdirs() }
        for (name in listOf("xbans-api-2.0.0.pom", "xbans-api-2.0.0.jar", "xbans-api-2.0.0-sources.jar", "xbans-api-2.0.0-javadoc.jar")) {
            for (file in listOf(name, "$name.md5", "$name.sha1")) {
                if (file !in skip) dir.resolve(file).writeText(if (file == "xbans-api-2.0.0.pom") pom else "x")
            }
        }
        return repo
    }

    @Test
    fun `checks a bundle against Central's requirements`() {
        assertEquals(emptyList<String>(), MavenApi.bundleProblems(bundle(), signed = false))
        assertTrue(MavenApi.bundleProblems(bundle(), signed = true).all { it.endsWith(".asc is missing") })
        assertEquals(listOf("co/xenastudios/xbans/xbans-api/2.0.0/xbans-api-2.0.0-javadoc.jar is missing"),
            MavenApi.bundleProblems(bundle("xbans-api-2.0.0-javadoc.jar"), signed = false))
        assertEquals(listOf("co/xenastudios/xbans/xbans-api/2.0.0/xbans-api-2.0.0.jar.sha1 is missing"),
            MavenApi.bundleProblems(bundle("xbans-api-2.0.0.jar.sha1"), signed = false))
        val bare = MavenApi.bundleProblems(bundle(pom = "<project><name>xBans API</name></project>"), signed = false)
        assertTrue(listOf("<description>", "<url>", "<license>", "<developer>", "<scm>").all { tag -> bare.any { tag in it } }, bare.toString())
    }

    companion object {
        val POM = """
            <project>
              <name>xBans API</name>
              <description>xBans' developer API.</description>
              <url>https://plugins.xenastudios.co/xbans/developer-api/</url>
              <licenses><license><name>GPL-3.0-only</name><url>https://www.gnu.org/licenses/gpl-3.0.txt</url></license></licenses>
              <developers><developer><id>xena-studios</id><name>Xena Studios</name></developer></developers>
              <scm><url>https://github.com/xena-studios/plugins</url></scm>
            </project>
        """.trimIndent()
    }
}
