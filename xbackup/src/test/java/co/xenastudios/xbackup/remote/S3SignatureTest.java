package co.xenastudios.xbackup.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xbackup.store.Throttle;
import java.net.URI;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The hand-written SigV4 signer against AWS's own published example. */
class S3SignatureTest {

    @Test
    void signsAwsDocumentedGetObjectExample() {
        // "Example: GET Object" from AWS's Signature Version 4 docs for S3.
        RemoteConfig settings = new RemoteConfig("s3", true, "", "https://s3.amazonaws.com", "us-east-1",
            "examplebucket", false, "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "", 0, "", "",
            "explicit", "", "");
        S3Backend backend = new S3Backend("test", settings, "", Throttle.NONE);
        Map<String, String> headers = backend.sign("GET", URI.create("https://examplebucket.s3.amazonaws.com/test.txt"),
            Map.of("Range", "bytes=0-9"), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ZonedDateTime.of(2013, 5, 24, 0, 0, 0, 0, ZoneOffset.UTC));
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
            + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
            + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", headers.get("Authorization"));
    }

    @Test
    void encodesLikeS3Expects() {
        assertEquals("a%20b/c~d.e_f-g", Http.encode("a b/c~d.e_f-g", true));
        assertEquals("a%2Fb", Http.encode("a/b", false));
        assertTrue(Http.encode("ü", true).equals("%C3%BC"));
    }
}
