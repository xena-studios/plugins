package co.xenastudios.xbackup.remote.lib

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** FTPS's certificate pin, as owners copy fingerprints (1.x's FtpTrustTest). */
class FtpTrustTest {
    // Any real certificate will do: the first of the JVM's own trusted ones.
    private fun someCertificate(): X509Certificate {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return (factory.trustManagers[0] as X509TrustManager).acceptedIssuers[0]
    }

    private fun hex(bytes: ByteArray, separator: String, upper: Boolean) =
        bytes.joinToString(separator) { (it.toInt() and 0xFF).toString(16).padStart(2, '0').let { h -> if (upper) h.uppercase() else h } }

    @Test
    fun fingerprintsReadAsOwnersCopyThem() {
        val hash = MessageDigest.getInstance("SHA-256").digest(someCertificate().encoded)
        val colons = hex(hash, ":", true)
        assertContentEquals(hash, FtpBackend.fingerprint(colons, "r"))
        assertContentEquals(hash, FtpBackend.fingerprint("SHA256:$colons", "r"))
        assertContentEquals(hash, FtpBackend.fingerprint(hex(hash, "", false), "r"))
        assertContentEquals(hash, FtpBackend.fingerprint("SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(hash), "r"))
        assertFailsWith<IllegalArgumentException> { FtpBackend.fingerprint("SHA256:nope", "r") }
    }

    @Test
    fun aPinnedCertificateIsTheOnlyOneTrusted() {
        val certificate = someCertificate()
        val hash = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        FtpBackend.trust(hash, "r").checkServerTrusted(arrayOf(certificate), "RSA")
        val other = hash.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val refused = assertFailsWith<CertificateException> { FtpBackend.trust(other, "r").checkServerTrusted(arrayOf(certificate), "RSA") }
        assertTrue("SHA256:" in refused.message!!, "says what it saw: ${refused.message}")
    }

    @Test
    fun withoutAPinJavasTrustDecides() {
        val system = FtpBackend.trust(null, "r")
        assertTrue(system.acceptedIssuers.isNotEmpty(), "the JVM's trusted certificates, not \"accept anything\"")
        assertFailsWith<Exception> { system.checkServerTrusted(arrayOf(), "RSA") }
    }
}
