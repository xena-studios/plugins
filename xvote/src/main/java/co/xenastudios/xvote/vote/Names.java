package co.xenastudios.xvote.vote;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Names as lists send them. Lists send whatever the player typed: Bedrock players often
 * leave off Floodgate's prefix, and gamertags can have spaces, which Floodgate turns into
 * underscores.
 */
public final class Names {

    private static final Pattern NAME = Pattern.compile("\\w{1,16}");

    private Names() {}

    /** The name tidied up as Floodgate would have it, or null if it can't be a player's name. */
    public static String clean(String sent, String bedrockPrefix) {
        String name = sent.strip().replace(' ', '_');
        if (NAME.matcher(name).matches()) {
            return name;
        }
        if (!bedrockPrefix.isEmpty() && name.startsWith(bedrockPrefix)
            && NAME.matcher(name.substring(bedrockPrefix.length())).matches()) {
            return name;
        }
        return null;
    }

    /** The names to look a voter up by, in order: as sent, then with Bedrock's prefix. */
    public static Set<String> lookups(String name, String bedrockPrefix) {
        Set<String> names = new LinkedHashSet<>();
        names.add(name);
        if (!bedrockPrefix.isEmpty() && !name.startsWith(bedrockPrefix)) {
            names.add(bedrockPrefix + name);
        }
        return names;
    }

    /**
     * The name keys of unmatched votes a joining player gets: their own name, and for a
     * Bedrock player, their name without the prefix, which is what they likely typed.
     */
    public static Set<String> matchKeys(String playerName, String bedrockPrefix) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(playerName.toLowerCase(Locale.ROOT));
        if (!bedrockPrefix.isEmpty() && playerName.startsWith(bedrockPrefix) && playerName.length() > bedrockPrefix.length()) {
            keys.add(playerName.substring(bedrockPrefix.length()).toLowerCase(Locale.ROOT));
        }
        return keys;
    }
}
