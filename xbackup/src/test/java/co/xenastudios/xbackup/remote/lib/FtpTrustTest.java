package co.xenastudios.xbackup.remote.lib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HexFormat;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

/** FTPS checks the server's certificate: Java's trust and the host name, or a pinned fingerprint. */
class FtpTrustTest {

    // Any real certificate will do: the first of the JVM's own trusted ones.
    private static X509Certificate someCertificate() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        return ((X509TrustManager) factory.getTrustManagers()[0]).getAcceptedIssuers()[0];
    }

    @Test
    void fingerprintsReadAsOwnersCopyThem() throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(someCertificate().getEncoded());
        String hex = HexFormat.ofDelimiter(":").withUpperCase().formatHex(hash);
        assertArrayEquals(hash, FtpBackend.fingerprint(hex, "r"));
        assertArrayEquals(hash, FtpBackend.fingerprint("SHA256:" + hex, "r"));
        assertArrayEquals(hash, FtpBackend.fingerprint(HexFormat.of().formatHex(hash), "r"));
        assertArrayEquals(hash, FtpBackend.fingerprint("SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(hash), "r"));
        assertThrows(IllegalArgumentException.class, () -> FtpBackend.fingerprint("SHA256:nope", "r"));
    }

    @Test
    void aPinnedCertificateIsTheOnlyOneTrusted() throws Exception {
        X509Certificate certificate = someCertificate();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        X509TrustManager pinned = FtpBackend.trust(hash, "r");
        assertDoesNotThrow(() -> pinned.checkServerTrusted(new X509Certificate[] {certificate}, "RSA"));
        byte[] other = hash.clone();
        other[0] ^= 1;
        CertificateException refused = assertThrows(CertificateException.class,
            () -> FtpBackend.trust(other, "r").checkServerTrusted(new X509Certificate[] {certificate}, "RSA"));
        assertTrue(refused.getMessage().contains("SHA256:"), "says what it saw: " + refused.getMessage());
    }

    @Test
    void withoutAPinJavasTrustDecides() throws Exception {
        X509TrustManager system = FtpBackend.trust(null, "r");
        assertTrue(system.getAcceptedIssuers().length > 0, "the JVM's trusted certificates, not \"accept anything\"");
        assertThrows(Exception.class, () -> system.checkServerTrusted(new X509Certificate[0], "RSA"));
    }
}
