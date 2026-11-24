package co.xenastudios.core.loader;

import java.io.IOException;
import java.io.InputStream;

/** Which Java the server runs, and which Java a class file needs. */
public final class JavaVersion {

    private JavaVersion() {}

    /** The running Java's feature version: 8 for 1.8, 17 for 17. */
    public static int current() {
        return parse(System.getProperty("java.specification.version", "1.8"));
    }

    /** {@code "1.8"} is 8, {@code "17"} is 17, {@code "21.0.1"} is 21; anything else is 8. */
    public static int parse(String version) {
        String rest = version.startsWith("1.") ? version.substring(2) : version;
        int end = 0;
        while (end < rest.length() && Character.isDigit(rest.charAt(end))) {
            end++;
        }
        return end == 0 ? 8 : Integer.parseInt(rest.substring(0, end));
    }

    /**
     * The Java version a class file needs, from its header (major version 52 is Java 8), or
     * 0 if the stream isn't a class file.
     */
    public static int ofClassFile(InputStream in) throws IOException {
        byte[] header = new byte[8];
        int read = 0;
        while (read < header.length) {
            int n = in.read(header, read, header.length - read);
            if (n < 0) {
                return 0;
            }
            read += n;
        }
        boolean magic = (header[0] & 0xff) == 0xca && (header[1] & 0xff) == 0xfe
            && (header[2] & 0xff) == 0xba && (header[3] & 0xff) == 0xbe;
        return magic ? ((header[6] & 0xff) << 8 | (header[7] & 0xff)) - 44 : 0;
    }
}
