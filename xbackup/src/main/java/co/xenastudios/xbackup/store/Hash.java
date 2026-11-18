package co.xenastudios.xbackup.store;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A SHA-256, the name of everything in a repository: a blob is named by the hash of its
 * content before compression; a pack, index or snapshot file by the hash of its bytes as
 * stored. Kept as four longs, so it's cheap to compare and to keep millions of.
 */
public final class Hash implements Comparable<Hash> {

    public static final int BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    final long a;
    final long b;
    final long c;
    final long d;

    Hash(long a, long b, long c, long d) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
    }

    /** The hash from a 32-byte digest. */
    public static Hash of(byte[] digest) {
        if (digest.length != BYTES) {
            throw new IllegalArgumentException("a hash is 32 bytes, not " + digest.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(digest);
        return new Hash(buffer.getLong(), buffer.getLong(), buffer.getLong(), buffer.getLong());
    }

    /** Reads 32 bytes. */
    public static Hash read(ByteBuffer buffer) {
        return new Hash(buffer.getLong(), buffer.getLong(), buffer.getLong(), buffer.getLong());
    }

    /** The SHA-256 of these bytes. */
    public static Hash sha256(byte[] data) {
        return sha256(data, 0, data.length);
    }

    public static Hash sha256(byte[] data, int offset, int length) {
        MessageDigest digest = digest();
        digest.update(data, offset, length);
        return of(digest.digest());
    }

    /** A fresh SHA-256 digest. */
    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM has SHA-256", e);
        }
    }

    /** Reads 64 hex digits. */
    public static Hash parse(String hex) {
        if (hex.length() != BYTES * 2) {
            throw new IllegalArgumentException("not a hash: " + hex);
        }
        return of(HEX.parseHex(hex));
    }

    /** Whether the text is 64 lowercase hex digits. */
    public static boolean isHex(String text) {
        if (text.length() != BYTES * 2) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!(ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f')) {
                return false;
            }
        }
        return true;
    }

    public void write(ByteBuffer buffer) {
        buffer.putLong(a).putLong(b).putLong(c).putLong(d);
    }

    public byte[] bytes() {
        ByteBuffer buffer = ByteBuffer.allocate(BYTES);
        write(buffer);
        return buffer.array();
    }

    public String hex() {
        return HEX.formatHex(bytes());
    }

    /** The first 8 hex digits, as people see snapshot ids. */
    public String shortHex() {
        return hex().substring(0, 8);
    }

    /** The first 8 bytes, for compact sets where a rare false match is harmless. */
    public long prefix() {
        return a;
    }

    @Override
    public int compareTo(Hash other) {
        int r = Long.compareUnsigned(a, other.a);
        if (r == 0) {
            r = Long.compareUnsigned(b, other.b);
        }
        if (r == 0) {
            r = Long.compareUnsigned(c, other.c);
        }
        if (r == 0) {
            r = Long.compareUnsigned(d, other.d);
        }
        return r;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Hash h && a == h.a && b == h.b && c == h.c && d == h.d;
    }

    @Override
    public int hashCode() {
        return (int) (a ^ (a >>> 32));
    }

    @Override
    public String toString() {
        return hex();
    }
}
