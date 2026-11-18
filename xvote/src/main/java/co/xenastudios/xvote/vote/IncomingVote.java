package co.xenastudios.xvote.vote;

import co.xenastudios.xvote.api.VoteSource;
import java.util.regex.Pattern;

/**
 * A vote as it arrived, before it's checked or stored. Every field is cleaned as it's
 * made: control characters (line breaks, escapes) are taken out, since they come from the
 * network and end up in the console and in commands, and each is cut to
 * {@link #MAX_FIELD} characters.
 *
 * @param site the site's service name, as the list sent it
 * @param username the name the list sent
 * @param address the voter's address as the list sent it, or empty
 * @param timestamp the list's own timestamp, unchanged, or empty
 * @param source where it came from
 * @param receivedAt when this server got it, in epoch milliseconds: what xVote counts by
 */
public record IncomingVote(String site, String username, String address, String timestamp, VoteSource source, long receivedAt) {

    /** Longest site name, user name, address or timestamp kept; lists don't send more. */
    public static final int MAX_FIELD = 64;

    // Control and format characters, and Unicode's own line and paragraph breaks.
    private static final Pattern UNPRINTABLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

    public IncomingVote {
        site = clean(site);
        username = clean(username).strip();
        address = clean(address);
        timestamp = clean(timestamp);
    }

    /** Text from the network, safe to log: control characters taken out, cut to {@link #MAX_FIELD}. */
    public static String clean(String value) {
        if (value == null) {
            return "";
        }
        String printable = UNPRINTABLE.matcher(value).replaceAll("");
        return printable.length() > MAX_FIELD ? printable.substring(0, MAX_FIELD) : printable;
    }
}
