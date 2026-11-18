package co.xenastudios.xbackup.store;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One backup: when it was taken, the tree it saved, and what it cost. Written last, as
 * {@code snapshots/<id>}, once everything it points to is safely stored, so a snapshot
 * that exists is always whole.
 *
 * <p>Plain text, one {@code key=value} a line, so it can be read by eye in an emergency.
 *
 * @param id the SHA-256 of the file; null until written
 * @param parent the snapshot it was compared against, or null
 * @param files how many files it holds
 * @param bytes the size of those files
 * @param added how many new bytes it stored (after deduplication and compression)
 * @param pauseMillis how long the server's thread spent saving for it
 * @param autosaveOffMillis how long autosave was off for it
 * @param warnings what it couldn't do, such as a database it couldn't copy safely
 */
public record Snapshot(Hash id, Instant time, Instant finished, Hash tree, Hash parent, String reason, long files,
                       long bytes, long added, long pauseMillis, long autosaveOffMillis, String source,
                       List<String> warnings) {

    static final int VERSION = 1;
    private static final String HEADER = "xbackup-snapshot";

    public Snapshot {
        warnings = List.copyOf(warnings);
    }

    /** Where snapshots live: {@code snapshots/ab12...}. */
    public static String fileName(Hash id) {
        return "snapshots/" + id.hex();
    }

    public Snapshot withId(Hash newId) {
        return new Snapshot(newId, time, finished, tree, parent, reason, files, bytes, added, pauseMillis,
            autosaveOffMillis, source, warnings);
    }

    public long durationMillis() {
        return finished.toEpochMilli() - time.toEpochMilli();
    }

    byte[] encode() {
        StringBuilder out = new StringBuilder();
        line(out, HEADER, Integer.toString(VERSION));
        line(out, "time", time.toString());
        line(out, "finished", finished.toString());
        line(out, "tree", tree.hex());
        if (parent != null) {
            line(out, "parent", parent.hex());
        }
        line(out, "reason", reason);
        line(out, "files", Long.toString(files));
        line(out, "bytes", Long.toString(bytes));
        line(out, "added", Long.toString(added));
        line(out, "pause-ms", Long.toString(pauseMillis));
        line(out, "autosave-off-ms", Long.toString(autosaveOffMillis));
        line(out, "source", source);
        for (String warning : warnings) {
            line(out, "warning", warning);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void line(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(escape(value)).append('\n');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> next;
                });
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    static Snapshot decode(Hash id, byte[] bytes) throws CorruptException {
        String what = "snapshot " + id.shortHex();
        Map<String, String> values = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new CorruptException(what + " has a line that isn't key=value");
            }
            String key = line.substring(0, eq);
            String value = unescape(line.substring(eq + 1));
            if (key.equals("warning")) {
                warnings.add(value);
            } else {
                values.put(key, value);
            }
        }
        String version = values.get(HEADER);
        if (version == null) {
            throw new CorruptException(what + " isn't a snapshot");
        }
        if (!version.equals(Integer.toString(VERSION))) {
            throw new CorruptException(what + " is snapshot version " + version + "; this xBackup reads " + VERSION);
        }
        try {
            return new Snapshot(id, Instant.parse(required(values, "time", what)),
                Instant.parse(required(values, "finished", what)), Hash.parse(required(values, "tree", what)),
                values.containsKey("parent") ? Hash.parse(values.get("parent")) : null,
                values.getOrDefault("reason", ""), number(values, "files"), number(values, "bytes"),
                number(values, "added"), number(values, "pause-ms"), number(values, "autosave-off-ms"),
                values.getOrDefault("source", ""), warnings);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new CorruptException(what + ": " + e.getMessage(), e);
        }
    }

    private static String required(Map<String, String> values, String key, String what) throws CorruptException {
        String value = values.get(key);
        if (value == null) {
            throw new CorruptException(what + " has no " + key);
        }
        return value;
    }

    private static long number(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null ? 0 : Long.parseLong(value);
    }
}
