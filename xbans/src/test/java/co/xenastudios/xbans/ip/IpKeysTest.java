package co.xenastudios.xbans.ip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** IP keys: the address, or a salted hash that still matches itself. */
class IpKeysTest {

    @TempDir
    Path folder;

    @Test
    void rawKeysAreTheAddress() throws Exception {
        IpKeys raw = IpKeys.raw();
        assertEquals("203.0.113.9", raw.key("203.0.113.9"));
        assertEquals("203.0.113.9", raw.key(InetAddress.getByName("203.0.113.9")));
        assertEquals(raw.key("2001:db8::1"), raw.key("2001:0db8:0000:0000:0000:0000:0000:0001"));
    }

    @Test
    void loopbackIsNeverKept() throws Exception {
        assertNull(IpKeys.raw().key(InetAddress.getByName("127.0.0.1")));
        assertNull(IpKeys.raw().key((InetAddress) null));
        assertFalse(IpKeys.punishable("127.0.0.1"));
        assertFalse(IpKeys.punishable("::1"));
        assertTrue(IpKeys.punishable("203.0.113.9"));
    }

    @Test
    void hashesMatchThemselvesNotTheAddress() throws Exception {
        byte[] salt = IpKeys.salt(folder);
        assertArrayEquals(salt, IpKeys.salt(folder), "the salt is kept");
        IpKeys hashed = IpKeys.hashed(salt);
        String key = hashed.key("203.0.113.9");
        assertEquals(32, key.length());
        assertEquals(key, hashed.key(InetAddress.getByName("203.0.113.9")));
        assertNotEquals(key, hashed.key("203.0.113.10"));
        assertNotEquals(key, IpKeys.hashed(new byte[32]).key("203.0.113.9"), "another salt, another hash");
        assertEquals(key.substring(0, 8) + "…", IpKeys.shown(key));
    }

    @Test
    void onlyAddressesLookLikeAddresses() {
        assertTrue(IpKeys.looksLikeAddress("1.2.3.4"));
        assertTrue(IpKeys.looksLikeAddress("2001:db8::1"));
        // Not a byte: Java would look it up as a host name.
        assertFalse(IpKeys.looksLikeAddress("1.2.3.400"));
        assertFalse(IpKeys.looksLikeAddress("Steve"));
        assertFalse(IpKeys.looksLikeAddress("server:survival"));
    }
}
