package co.xenastudios.xbackup.remote

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.xbackup.store.SecretText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** secrets.yml: owner-only, read back exactly, refused rather than misread (1.x's SecretFileTest). */
class SecretFileTest {
    private val dir: Path = Files.createTempDirectory("xbackup-secrets")

    @AfterTest
    fun clean() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun whatItWritesReadsBackTheSameHereAndInAnyYamlReader() {
        val offsite = linkedMapOf("password" to "p\"a:s s#w\\o'rd é\t\u0001 <&>\u007f  end", "client-secret" to "")
        val values = linkedMapOf<String, Map<String, String>>("offsite" to offsite, "odd name" to mapOf("password" to "- [x] {y}: &z *w !v |u >t"))
        val file = dir.resolve(SecretText.SECRETS)
        SecretFile.write(file, "Header line\n\nmore", values)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals<Map<String, Map<String, String>>>(values, SecretFile.read(file))
        val yaml = (JvmYaml.read(String(Files.readAllBytes(file))) as Outcome.Ok).value as YamlNode.Mapping
        assertEquals(offsite["password"], ((yaml["offsite"] as YamlNode.Mapping)["password"] as YamlNode.Scalar).content)
        assertEquals("- [x] {y}: &z *w !v |u >t", ((yaml["odd name"] as YamlNode.Mapping)["password"] as YamlNode.Scalar).content)
    }

    @Test
    fun whatPeopleTypeIsRead() {
        val file = dir.resolve(SecretText.SECRETS)
        Files.write(
            file,
            """
            # Mine.
            offsite:
              password: hunter2   # the usual
              username: 'it''s me'
            gdrive:
                client-secret: "a\"bé"
            empty: {}
            blank:
              password:
            """.trimIndent().toByteArray(),
        )
        val read = SecretFile.read(file)
        assertEquals("hunter2", read["offsite"]?.get("password"))
        assertEquals("it's me", read["offsite"]?.get("username"))
        assertEquals("a\"bé", read["gdrive"]?.get("client-secret"))
        assertTrue(read["empty"]!!.isEmpty())
        assertEquals("", read["blank"]?.get("password"))
        assertTrue(SecretFile.read(dir.resolve("none.yml")).isEmpty())
    }

    @Test
    fun whatItCantReadIsRefusedNotMisread() {
        for (text in listOf("offsite:\n  password: |\n    hunter2\n", "offsite:\n  password: \"open\n", "  password: orphan\n", "offsite: inline\n", "offsite:\n  password: 'a' b\n")) {
            val file = dir.resolve("bad.yml")
            Files.write(file, text.toByteArray())
            val e = assertFailsWith<IOException>(text) { SecretFile.read(file) }
            assertTrue("line " in e.message!!, e.message)
            assertFalse("hunter2" in e.message!!, "never shows the value")
        }
    }

    @Test
    fun theCommandLineReadsPasswordsFromSecretsNotItsOwnCopy() {
        Files.write(dir.resolve(RestoreSettings.NAME), "repository=/srv/repo\nremote.offsite.type=sftp\nremote.offsite.host=example.com\nremote.offsite.username=backup\n".toByteArray())
        Files.write(dir.resolve(SecretText.SECRETS), "offsite:\n  password: \"hunter2\"\n".toByteArray())
        val settings = RestoreSettings.read(dir)!!
        assertEquals("hunter2", settings.remotes.getValue("offsite").password)
        assertEquals(null, settings.secretsProblem)
        Files.write(dir.resolve(SecretText.SECRETS), "offsite:\n  password: >\n    folded\n".toByteArray())
        val broken = RestoreSettings.read(dir)!!
        assertEquals("", broken.remotes.getValue("offsite").password)
        assertTrue(broken.secretsProblem!!.contains("line 2"), broken.secretsProblem)
    }
}
