package co.xenastudios.xbans.ip;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Turns an IP address into the key xBans stores: the address as written, or, with hashing
 * on, a salted hash of it (HMAC-SHA256, the first 16 bytes in hex). The same address always
 * gives the same key, so IP bans and alts work either way; a hash can't be turned back into
 * the address without the salt, which lives in a file beside the config, never in the
 * database.
 */
public final class IpKeys {

    /** The file the salt is kept in, in xBans' folder. */
    public static final String SALT_FILE = "ip-salt.txt";
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]{2,45}");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

    private final boolean hash;
    private final byte[] salt;

    private IpKeys(boolean hash, byte[] salt) {
        this.hash = hash;
        this.salt = salt;
    }

    public static IpKeys raw() {
        return new IpKeys(false, null);
    }

    public static IpKeys hashed(byte[] salt) {
        return new IpKeys(true, salt.clone());
    }

    /**
     * Reads the salt from xBans' folder.
     *
     * @param create make a random one if there's none: only for a database whose IPs aren't
     *     hashed yet. One already hashed needs the salt it was hashed with, and a new one
     *     would quietly stop every IP ban and alt from matching.
     * @throws IOException if it can't be read, isn't a salt, or is missing and mustn't be made
     */
    public static byte[] salt(Path folder, boolean create) throws IOException {
        Path file = folder.resolve(SALT_FILE);
        if (Files.exists(file)) {
            String text = Files.readString(file, StandardCharsets.US_ASCII).strip();
            if (!HEX.matcher(text).matches()) {
                throw new IOException(SALT_FILE + " should hold 64 hex digits. Put back the one this database's IPs"
                    + " were hashed with (from another server of the network, or a backup).");
            }
            return HexFormat.of().parseHex(text);
        }
        if (!create) {
            throw new IOException(SALT_FILE + " is missing, but this database's IPs are hashed with it. Copy it into"
                + " xBans' folder from the server that hashed them (or from a backup); without it no IP ban or alt matches.");
        }
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        Files.createDirectories(folder);
        Files.writeString(file, HexFormat.of().formatHex(salt) + "\n", StandardCharsets.US_ASCII);
        return salt;
    }

    public boolean hashing() {
        return hash;
    }

    /** The key for an address, or null for one never worth keeping (loopback, the "any" address). */
    public String key(InetAddress address) {
        if (address == null || address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            return null;
        }
        return key(address.getHostAddress());
    }

    /** The key for an address written out, as staff type it or a file holds it. */
    public String key(String address) {
        String normal = normalise(address);
        if (!hash) {
            return normal;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] digest = mac.doFinal(normal.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This Java has no HmacSHA256", e);
        }
    }

    /** A check of the salt, stored in the database so servers with different salts notice. */
    public String fingerprint() {
        return hash ? key("0.0.0.0") : "raw";
    }

    /** How a key is shown to staff who may see addresses (/iphistory): the address, or the start of its hash. */
    public static String shown(String key) {
        if (key == null) {
            return "?";
        }
        return looksLikeAddress(key) ? key : key.substring(0, Math.min(8, key.length())) + "…";
    }

    /**
     * How a key is named where anyone may read it: the name of an IP punishment with no
     * account, which broadcasts and ban lists show. An address loses all but its first half
     * ({@code 203.0.x.x}, {@code 2001:db8:…}); a hash shows its start, as {@link #shown}.
     */
    public static String label(String key) {
        if (key == null) {
            return "?";
        }
        if (!looksLikeAddress(key)) {
            return shown(key);
        }
        String normal = normalise(key);
        if (IPV4.matcher(normal).matches()) {
            String[] parts = normal.split("\\.");
            return parts[0] + "." + parts[1] + ".x.x";
        }
        String[] groups = normal.split(":", -1);
        return groups.length < 2 ? "…" : groups[0] + ":" + groups[1] + ":…";
    }

    /** Whether the text is an IP address (v4, or v6 with colons). Doesn't look anything up. */
    public static boolean looksLikeAddress(String text) {
        if (IPV4.matcher(text).matches()) {
            // Each part must be a byte, or Java would take it for a host name and look it up.
            for (String part : text.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return true;
        }
        return text.indexOf(':') >= 0 && IPV6.matcher(text).matches();
    }

    /** The address as Java writes it, so 1.2.3.4 and ::1 forms compare equal. */
    static String normalise(String address) {
        if (!looksLikeAddress(address)) {
            return address;
        }
        try {
            // A literal: never a DNS lookup.
            return InetAddress.getByName(address).getHostAddress();
        } catch (UnknownHostException e) {
            return address;
        }
    }

    /** Whether the text is an address worth punishing: not loopback, not "any". */
    public static boolean punishable(String address) {
        if (!looksLikeAddress(address)) {
            return false;
        }
        try {
            InetAddress parsed = InetAddress.getByName(address);
            return !parsed.isLoopbackAddress() && !parsed.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
