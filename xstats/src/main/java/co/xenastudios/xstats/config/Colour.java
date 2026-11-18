package co.xenastudios.xstats.config;

import co.xenastudios.core.config.Codec;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A colour for the website, written {@code #RRGGBB}. Nothing else gets through, so a
 * colour can't carry anything into the site's CSS.
 */
public final class Colour {

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    private final String hex;

    private Colour(String hex) {
        this.hex = hex.toLowerCase(Locale.ROOT);
    }

    public static Colour of(String hex) {
        if (!HEX.matcher(hex).matches()) {
            throw new IllegalArgumentException("isn't a colour written as #RRGGBB, such as #3EC407");
        }
        return new Colour(hex);
    }

    public String hex() {
        return hex;
    }

    public static final Codec<Colour> CODEC = new Codec<>() {
        @Override
        public Colour decode(Object raw) {
            return of(raw.toString().strip());
        }

        @Override
        public Object encode(Colour value) {
            return value.hex;
        }
    };

    @Override
    public boolean equals(Object other) {
        return other instanceof Colour colour && colour.hex.equals(hex);
    }

    @Override
    public int hashCode() {
        return hex.hashCode();
    }

    @Override
    public String toString() {
        return hex;
    }
}
