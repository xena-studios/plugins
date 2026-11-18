package co.xenastudios.core.text;

/**
 * Text as a double-quoted JSON string, which YAML's double-quoted strings read the same
 * way. Escapes everything a reader could trip on: quotes, backslashes, control characters
 * and DEL, and also {@code < > &} and the line and paragraph separators, so the result is
 * safe to put inside an HTML page or a script.
 */
public final class Quoted {

    private Quoted() {}

    /** {@code value} quoted, as a new string. */
    public static String of(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        append(out, value);
        return out.toString();
    }

    /** Appends {@code value}, quoted, to {@code out}. */
    public static void append(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<', '>', '&', '\u2028', '\u2029', '\u007f' -> unicode(out, c);
                default -> {
                    if (c < 0x20) {
                        unicode(out, c);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void unicode(StringBuilder out, char c) {
        String hex = Integer.toHexString(c);
        out.append("\\u");
        for (int pad = hex.length(); pad < 4; pad++) {
            out.append('0');
        }
        out.append(hex);
    }
}
