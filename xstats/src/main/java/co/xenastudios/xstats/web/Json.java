package co.xenastudios.xstats.web;

/**
 * Writes JSON, as the web API sends it. Strings are escaped so nothing a player or an
 * owner typed can end a string early; {@code <}, {@code >} and {@code &} are escaped too,
 * so the JSON is safe even if something ever puts it inside HTML.
 */
public final class Json {

    private final StringBuilder out = new StringBuilder(256);
    private boolean comma;

    public Json object() {
        separate();
        out.append('{');
        comma = false;
        return this;
    }

    public Json end() {
        out.append('}');
        comma = true;
        return this;
    }

    public Json array() {
        separate();
        out.append('[');
        comma = false;
        return this;
    }

    public Json endArray() {
        out.append(']');
        comma = true;
        return this;
    }

    /** A key; the next value written is its value. */
    public Json key(String key) {
        separate();
        string(key);
        out.append(':');
        comma = false;
        return this;
    }

    public Json value(String value) {
        separate();
        if (value == null) {
            out.append("null");
        } else {
            string(value);
        }
        comma = true;
        return this;
    }

    public Json value(long value) {
        separate();
        out.append(value);
        comma = true;
        return this;
    }

    public Json value(boolean value) {
        separate();
        out.append(value);
        comma = true;
        return this;
    }

    public Json field(String key, String value) {
        return key(key).value(value);
    }

    public Json field(String key, long value) {
        return key(key).value(value);
    }

    public Json field(String key, boolean value) {
        return key(key).value(value);
    }

    private void separate() {
        if (comma) {
            out.append(',');
        }
    }

    private void string(String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                case ' ' -> out.append("\\u2028");
                case ' ' -> out.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
