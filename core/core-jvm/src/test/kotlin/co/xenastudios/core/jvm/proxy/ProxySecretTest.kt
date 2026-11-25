package co.xenastudios.core.jvm.proxy

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.proxy.ProxyFrame
import co.xenastudios.core.jvm.config.JvmYaml
import java.io.IOException
import java.nio.file.Files
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProxySecretTest {
    private val folder = createTempDirectory("proxy-secret")

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    @Test
    fun signsWithHmacUnderAKeyDerivedFromTheSecret() {
        val signer = ProxySecret.of("correct horse battery staple", "test")!!.signer()
        val key = hmac("correct horse battery staple".toByteArray(), "xena proxy channel v1".toByteArray())
        assertContentEquals(hmac(key, byteArrayOf(1, 2, 3)), signer.sign(byteArrayOf(1, 2, 3)))
        val frame = ProxyFrame(true, "chat", Instant.fromEpochMilliseconds(1), 7, byteArrayOf(5))
        val bytes = ProxyFrame.encode("xena:zchat", frame, signer)
        assertTrue(ProxyFrame.decode("xena:zchat", bytes, signer) is Outcome.Ok)
        val other = ProxySecret.of("another secret entirely", "test")!!.signer()
        assertEquals(ProxyFrame.Problem.SIGNATURE, (ProxyFrame.decode("xena:zchat", bytes, other) as Outcome.Err).error)
    }

    @Test
    fun theSignerIsSafeOnManyThreads() {
        val signer = ProxySecret.of("correct horse battery staple", "test")!!.signer()
        val expected = signer.sign(byteArrayOf(9))
        val threads = (1..8).map { Thread { repeat(2_000) { check(signer.sign(byteArrayOf(9)).contentEquals(expected)) } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
    }

    @Test
    fun aMadeSecretIsWrittenAndReadBack() {
        assertNull(ProxySecret.read(folder))
        val made = ProxySecret.create(folder)
        val text = folder.resolve(ProxySecret.FILE).readText().trim()
        assertTrue(text.length >= 40, text)
        val read = assertNotNull(ProxySecret.read(folder))
        assertContentEquals(made.signer().sign(byteArrayOf(1)), read.signer().sign(byteArrayOf(1)))
        assertFalse(Files.list(folder).use { files -> files.anyMatch { it.fileName.toString().endsWith(".tmp") } })
    }

    @Test
    fun aShortSecretIsRefused() {
        folder.resolve(ProxySecret.FILE).writeText("hunter2\n")
        assertFailsWith<IOException> { ProxySecret.read(folder) }
    }

    @Test
    fun velocitysForwardingSecretWhenItsModern() {
        val toml = folder.resolve("velocity.toml")
        toml.writeText(
            """
            # Velocity's own comments
            config-version = "2.7"
            player-info-forwarding-mode = "MODERN"
            forwarding-secret-file = "secret.txt"

            [servers]
            lobby = "127.0.0.1:30066"
            """.trimIndent(),
        )
        assertNull(ProxySecret.velocity(toml, emptyMap()), "no secret file yet")
        folder.resolve("secret.txt").writeText("Velocity-made1\n")
        val fromFile = assertNotNull(ProxySecret.velocity(toml, emptyMap()))
        assertContentEquals(ProxySecret.of("Velocity-made1", "x")!!.signer().sign(byteArrayOf(1)), fromFile.signer().sign(byteArrayOf(1)))
        val fromEnvironment = assertNotNull(ProxySecret.velocity(toml, mapOf("VELOCITY_FORWARDING_SECRET" to "from-env-secret")))
        assertEquals("VELOCITY_FORWARDING_SECRET", fromEnvironment.source)
        toml.writeText("player-info-forwarding-mode = \"legacy\"\n")
        assertNull(ProxySecret.velocity(toml, emptyMap()))
    }

    @Test
    fun papersCopyOfTheSecretWhenVelocityIsOn() {
        val yml = folder.resolve("paper-global.yml")
        yml.writeText("proxies:\n  velocity:\n    enabled: true\n    online-mode: true\n    secret: 'Velocity-made1'\n")
        val secret = assertNotNull(ProxySecret.paper(yml, JvmYaml))
        assertContentEquals(ProxySecret.of("Velocity-made1", "x")!!.signer().sign(byteArrayOf(1)), secret.signer().sign(byteArrayOf(1)))
        yml.writeText("proxies:\n  velocity:\n    enabled: false\n    secret: 'Velocity-made1'\n")
        assertNull(ProxySecret.paper(yml, JvmYaml))
        assertNull(ProxySecret.paper(folder.resolve("missing.yml"), JvmYaml))
    }
}
