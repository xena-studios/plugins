package co.xenastudios.xbackup.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The owner-only YAML files that hold secrets ({@code secrets.yml}, {@code tokens.yml}), read
 * and written without a server, so the command-line restore reads them where they are
 * instead of keeping copies of its own. Two levels, by remote name:
 *
 * <pre>
 * offsite:
 *   password: "..."
 * </pre>
 *
 * <p>Values are plain, 'single-quoted' or "double-quoted" on one line, as people type them
 * and as this class writes them. Anything fancier (a block scalar, a value over several lines)
 * is refused with the line it's on, rather than read wrong.
 */
public final class SecretFile {

    /** The remotes' passwords and secret keys, beside config.yml. */
    public static final String SECRETS = "secrets.yml";

    private SecretFile() {}

    /** The file's values by remote, then by key; empty if there's no file. */
    public static Map<String, Map<String, String>> read(Path file) throws IOException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return new LinkedHashMap<>();
        }
        Map<String, Map<String, String>> values = new LinkedHashMap<>();
        Map<String, String> current = null;
        List<String> lines = text.lines().toList();
        for (int n = 0; n < lines.size(); n++) {
            String line = lines.get(n);
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.equals("---") || trimmed.equals("{}")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            int colon = keyEnd(trimmed);
            if (colon < 0) {
                throw problem(file, n);
            }
            String key = unquote(trimmed.substring(0, colon).strip(), file, n);
            String rest = trimmed.substring(colon + 1).strip();
            if (indent == 0) {
                if (!rest.isEmpty() && !rest.startsWith("#") && !rest.equals("{}")) {
                    throw problem(file, n);
                }
                current = values.computeIfAbsent(key, k -> new LinkedHashMap<>());
            } else if (current == null) {
                throw problem(file, n);
            } else {
                current.put(key, scalar(rest, file, n));
            }
        }
        return values;
    }

    /** Writes the file owner-only, every value double-quoted on one line, under the header. */
    public static void write(Path file, String header, Map<String, Map<String, String>> values) throws IOException {
        StringBuilder text = new StringBuilder();
        header.lines().forEach(line -> text.append(line.isEmpty() ? "#" : "# " + line).append('\n'));
        for (Map.Entry<String, Map<String, String>> remote : values.entrySet()) {
            if (remote.getValue().isEmpty()) {
                continue;
            }
            text.append(quote(remote.getKey())).append(":\n");
            for (Map.Entry<String, String> value : remote.getValue().entrySet()) {
                text.append("  ").append(quote(value.getKey())).append(": ").append(quote(value.getValue())).append('\n');
            }
        }
        OwnerOnlyFile.write(file, text.toString());
    }

    // Where the key's colon is: the first ": " (or a colon at the end) outside quotes.
    private static int keyEnd(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"') {
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                quote = i == 0 ? c : 0;
            } else if (c == ':' && (i + 1 == line.length() || line.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String scalar(String text, Path file, int n) throws IOException {
        if (text.isEmpty() || text.startsWith("#")) {
            return "";
        }
        char first = text.charAt(0);
        if (first == '"' || first == '\'') {
            int end = closing(text, first);
            if (end < 0) {
                throw problem(file, n);
            }
            String after = text.substring(end + 1).strip();
            if (!after.isEmpty() && !after.startsWith("#")) {
                throw problem(file, n);
            }
            return unquote(text.substring(0, end + 1), file, n);
        }
        if (first == '|' || first == '>' || first == '{' || first == '[' || first == '&' || first == '*'
            || first == '!') {
            throw problem(file, n);
        }
        int comment = text.indexOf(" #");
        String value = (comment < 0 ? text : text.substring(0, comment)).strip();
        return value.equals("~") || value.equals("null") ? "" : value;
    }

    private static int closing(String text, char quote) {
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote == '"' && c == '\\') {
                i++;
            } else if (c == quote) {
                if (quote == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String unquote(String text, Path file, int n) throws IOException {
        if (text.length() < 2 || text.charAt(0) != text.charAt(text.length() - 1)
            || text.charAt(0) != '"' && text.charAt(0) != '\'') {
            return text;
        }
        String inner = text.substring(1, text.length() - 1);
        if (text.charAt(0) == '\'') {
            return inner.replace("''", "'");
        }
        StringBuilder out = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= inner.length()) {
                throw problem(file, n);
            }
            char e = inner.charAt(i);
            switch (e) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case '0' -> out.append('\0');
                case '"', '\\', '/', ' ' -> out.append(e);
                case 'x', 'u', 'U' -> {
                    int digits = e == 'x' ? 2 : e == 'u' ? 4 : 8;
                    try {
                        out.appendCodePoint(Integer.parseInt(inner.substring(i + 1, i + 1 + digits), 16));
                    } catch (NumberFormatException | IndexOutOfBoundsException bad) {
                        throw problem(file, n);
                    }
                    i += digits;
                }
                default -> throw problem(file, n);
            }
        }
        return out.toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static IOException problem(Path file, int n) {
        return new IOException("line " + (n + 1) + " of " + file.getFileName() + " isn't a simple \"key: value\" the"
            + " command-line restore can read. Write each value on one line, in double quotes.");
    }
}
