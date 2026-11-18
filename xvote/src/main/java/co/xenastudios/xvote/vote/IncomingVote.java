package co.xenastudios.xvote.vote;

import co.xenastudios.xvote.api.VoteSource;

/**
 * A vote as it arrived, before it's checked or stored.
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

    public IncomingVote {
        site = clip(site);
        username = clip(username).strip();
        address = clip(address);
        timestamp = clip(timestamp);
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > MAX_FIELD ? value.substring(0, MAX_FIELD) : value;
    }
}
