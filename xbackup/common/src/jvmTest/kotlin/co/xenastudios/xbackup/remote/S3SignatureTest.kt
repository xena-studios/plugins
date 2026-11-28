package co.xenastudios.xbackup.remote

import co.xenastudios.xbackup.store.Throttle
import java.net.URI
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SigV4, written by hand, against AWS's own worked examples for S3 ("Signature Calculations for
 * the Authorization Header: Transferring Payload in a Single Chunk", Amazon S3 API reference):
 * the GET of an object with a range, and the PUT with a storage class.
 */
class S3SignatureTest {
    private val s3 = S3Backend(
        "test",
        RemoteConfig(
            type = "s3", enabled = true, path = "", url = "https://s3.amazonaws.com", region = "us-east-1", bucket = "examplebucket",
            pathStyle = false, username = "AKIAIOSFODNN7EXAMPLE", password = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            host = "", port = 0, privateKey = "", hostKey = "", security = "", clientId = "", clientSecret = "",
        ),
        "", Throttle(0),
    )
    private val time = ZonedDateTime.of(2013, 5, 24, 0, 0, 0, 0, ZoneOffset.UTC)

    private fun signature(headers: Map<String, String>) = headers.getValue("Authorization").substringAfter("Signature=")

    @Test
    fun getObject() {
        val signed = s3.sign(
            "GET", URI.create("https://examplebucket.s3.amazonaws.com/test.txt"), mapOf("Range" to "bytes=0-9"),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", time,
        )
        assertEquals("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", signature(signed))
        assertEquals("20130524T000000Z", signed["x-amz-date"])
        assert("Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request" in signed.getValue("Authorization"))
        assert("SignedHeaders=host;range;x-amz-content-sha256;x-amz-date" in signed.getValue("Authorization"))
    }

    @Test
    fun putObject() {
        val signed = s3.sign(
            "PUT", URI.create("https://examplebucket.s3.amazonaws.com/test%24file.text"),
            mapOf("Date" to "Fri, 24 May 2013 00:00:00 GMT", "x-amz-storage-class" to "REDUCED_REDUNDANCY"),
            "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", time,
        )
        assertEquals("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd", signature(signed))
    }

    @Test
    fun theSecretNeverShowsInTheSettingsText() {
        val config = RemoteConfig("s3", true, "", "", "", "", false, "id", "the-secret", "", 0, "", "", "", "", "other-secret")
        assert("the-secret" !in config.toString() && "other-secret" !in config.toString())
    }
}
