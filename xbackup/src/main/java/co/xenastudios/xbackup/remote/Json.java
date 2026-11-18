package co.xenastudios.xbackup.remote;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for the cloud drives' replies and requests: objects, arrays, strings,
 * numbers, booleans and null. Written here because the command-line restore runs without
 * the server's Gson.
 */
final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /** Parses a document: maps, lists, strings, doubles or longs, booleans and null. */
    static Object parse(String text) throws IOException {
        Json json = new Json(text);
        json.space();
        Object value = json.value();
        json.space();
        if (json.pos != text.length()) {
            throw json.error("text after the end");
        }
        return value;
    }

    /** Parses an object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) throws IOException {
        Object value = parse(text);
        if (!(value instanceof Map<?, ?>)) {
            throw new IOException("expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private IOException error(String what) {
        return new IOException("bad JSON at " + pos + ": " + what);
    }

    private void space() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private Object value() throws IOException {
        if (pos >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Object literal(String word, Object value) throws IOException {
        if (!text.startsWith(word, pos)) {
            throw error("expected " + word);
        }
        pos += word.length();
        return value;
    }

    private Map<String, Object> object() throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        space();
        if (pos < text.length() && text.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            space();
            if (pos >= text.length() || text.charAt(pos) != '"') {
                throw error("expected a key");
            }
            String key = string();
            space();
            if (pos >= text.length() || text.charAt(pos) != ':') {
                throw error("expected :");
            }
            pos++;
            space();
            map.put(key, value());
            space();
            if (pos >= text.length()) {
                throw error("unexpected end");
            }
            char c = text.charAt(pos++);
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("expected , or }");
            }
        }
    }

    private List<Object> array() throws IOException {
        List<Object> list = new ArrayList<>();
        pos++;
        space();
        if (pos < text.length() && text.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            space();
            list.add(value());
            space();
            if (pos >= text.length()) {
                throw error("unexpected end");
            }
            char c = text.charAt(pos++);
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("expected , or ]");
            }
        }
    }

    private String string() throws IOException {
        StringBuilder out = new StringBuilder();
        pos++;
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= text.length()) {
                break;
            }
            char e = text.charAt(pos++);
            switch (e) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw error("short \\u escape");
                    }
                    out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> out.append(e);
            }
        }
        throw error("unterminated string");
    }

    private Object number() throws IOException {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        String number = text.substring(start, pos);
        if (number.isEmpty()) {
            throw error("unexpected character");
        }
        try {
            if (number.contains(".") || number.contains("e") || number.contains("E")) {
                return Double.parseDouble(number);
            }
            return Long.parseLong(number);
        } catch (NumberFormatException e) {
            throw error("bad number " + number);
        }
    }

    /** Writes a string as a JSON string, escaping everything outside printable ASCII. */
    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7E) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** Writes a flat object of strings, numbers and booleans. */
    static String write(Map<String, ?> values) {
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (out.length() > 1) {
                out.append(',');
            }
            out.append(quote(entry.getKey())).append(':');
            Object value = entry.getValue();
            if (value instanceof String string) {
                out.append(quote(string));
            } else if (value instanceof List<?> list) {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    out.append(list.get(i) instanceof String s ? quote(s) : String.valueOf(list.get(i)));
                }
                out.append(']');
            } else {
                out.append(value);
            }
        }
        return out.append('}').toString();
    }

    static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    static long number(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        return value instanceof Number n ? n.longValue() : fallback;
    }
}
