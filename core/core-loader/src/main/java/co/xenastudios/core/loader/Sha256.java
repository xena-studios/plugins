package co.xenastudios.core.loader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 as lowercase hex, the form the build pins it in. */
public final class Sha256 {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Sha256() {}

    /** A new digest; every Java has SHA-256. */
    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this Java has no SHA-256", e);
        }
    }

    public static String of(Path file) throws IOException {
        MessageDigest digest = digest();
        byte[] chunk = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                digest.update(chunk, 0, n);
            }
        }
        return hex(digest.digest());
    }

    public static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = HEX[(bytes[i] >> 4) & 0xf];
            out[2 * i + 1] = HEX[bytes[i] & 0xf];
        }
        return new String(out);
    }
}
