package co.xenastudios.xstats.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The reverse proxies whose word on a visitor's address is believed: single addresses and
 * CIDR ranges (Cloudflare publishes its own as ranges), and the one header that carries it.
 * Nothing here looks a name up in DNS: only IP addresses written out are read.
 */
final class Proxies {

    static final String FORWARDED_FOR = "x-forwarded-for";
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");
    private static final Pattern HEADER = Pattern.compile("[A-Za-z0-9-]{1,64}");

    /** An address, and how many of its leading bits a range shares (all of them for one address). */
    private record Range(byte[] address, int bits) {
        boolean contains(byte[] other) {
            if (other.length != address.length) {
                return false;
            }
            int whole = bits / 8;
            for (int i = 0; i < whole; i++) {
                if (address[i] != other[i]) {
                    return false;
                }
            }
            int rest = bits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (address[whole] & mask) == (other[whole] & mask);
        }
    }

    private final List<Range> ranges;
    private final String header;

    private Proxies(List<Range> ranges, String header) {
        this.ranges = ranges;
        this.header = header;
    }

    /**
     * @param entries {@code web.trusted-proxies}: addresses or CIDR ranges
     * @param header {@code web.client-ip-header}: X-Forwarded-For, or one a proxy sets alone (CF-Connecting-IP)
     */
    static Proxies of(List<String> entries, String header, Logger logger) {
        List<Range> ranges = new ArrayList<>();
        for (String entry : entries) {
            String text = entry.strip();
            int slash = text.indexOf('/');
            String host = slash < 0 ? text : text.substring(0, slash);
            byte[] address = literal(host);
            int bits = -1;
            if (address != null) {
                try {
                    bits = slash < 0 ? address.length * 8 : Integer.parseInt(text.substring(slash + 1));
                } catch (NumberFormatException e) {
                    bits = -1;
                }
            }
            if (address == null || bits < 0 || bits > address.length * 8) {
                logger.warning("config.yml: web.trusted-proxies: \"" + text + "\" isn't an IP address or a range such as"
                    + " 173.245.48.0/20; it's ignored.");
                continue;
            }
            ranges.add(new Range(address, bits));
        }
        String name = header == null ? "" : header.strip().toLowerCase(Locale.ROOT);
        if (!HEADER.matcher(name).matches()) {
            logger.warning("config.yml: web.client-ip-header: \"" + header + "\" isn't a header name; using X-Forwarded-For.");
            name = FORWARDED_FOR;
        }
        return new Proxies(List.copyOf(ranges), name);
    }

    /** An IP address written out, as bytes, or null for anything else: never a DNS lookup. */
    static byte[] literal(String text) {
        if (text == null || !(IPV4.matcher(text).matches() || IPV6.matcher(text).matches())) {
            return null;
        }
        try {
            // Only ever given a literal here, which getByName reads without asking DNS.
            return InetAddress.getByName(text).getAddress();
        } catch (UnknownHostException | SecurityException e) {
            return null;
        }
    }

    String header() {
        return header;
    }

    boolean any() {
        return !ranges.isEmpty();
    }

    boolean trusted(byte[] address) {
        if (address == null) {
            return false;
        }
        for (Range range : ranges) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    boolean trusted(String address) {
        return trusted(literal(address));
    }
}
