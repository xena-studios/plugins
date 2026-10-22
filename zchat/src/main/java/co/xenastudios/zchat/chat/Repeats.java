package co.xenastudios.zchat.chat;

/** How alike two messages are, for anti-repeat. */
final class Repeats {

    /** Messages with fewer letters than this, like "gg", are never repeats. */
    static final int SHORTEST = 3;

    private Repeats() {}

    /** The message as anti-repeat compares it: folded, with the spaces taken out. */
    static String key(String message) {
        return Normal.of(message).text.replace(" ", "");
    }

    /** Similarity from 0 to 100: 100 is identical. */
    static int similarity(String a, String b) {
        if (a.equals(b)) {
            return 100;
        }
        int longest = Math.max(a.length(), b.length());
        if (longest == 0) {
            return 100;
        }
        return (int) Math.round(100.0 * (longest - distance(a, b)) / longest);
    }

    // Levenshtein distance in two rows: chat messages are at most 256 characters.
    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            char c = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                int replace = previous[j - 1] + (c == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(replace, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
