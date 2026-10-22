package co.xenastudios.core.config;

import co.xenastudios.core.text.Message;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes the default config the way a person would: strings in double quotes, short
 * lists inline, a comment above each setting and a blank line between sections.
 * (Bukkit's writer leaves strings bare and puts every list item on its own line.)
 */
final class Emitter {

    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z0-9_-]+");
    private static final String INDENT = "  ";

    private final ConfigSpec<?> spec;
    private final StringBuilder out = new StringBuilder();

    private Emitter(ConfigSpec<?> spec) {
        this.spec = spec;
    }

    static String render(ConfigSpec<?> spec) {
        Emitter emitter = new Emitter(spec);
        for (String line : spec.header) {
            emitter.out.append(line.isEmpty() ? "#" : "# " + line).append('\n');
        }
        if (!spec.header.isEmpty()) {
            emitter.out.append('\n');
        }
        emitter.comment(0, "Managed by the plugin, to carry your settings forward on updates. Don't edit it.");
        emitter.out.append(Writer.VERSION_KEY).append(": ").append(spec.version).append('\n');
        emitter.record(spec.defaults, 0, true);
        return emitter.out.toString();
    }

    private void record(Record value, int depth, boolean comments) {
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            Object child = get(component, value);
            boolean section = child instanceof Record || child instanceof Map<?, ?>;
            Comment comment = comments ? component.getAnnotation(Comment.class) : null;
            // A blank line above each top-level setting and each commented section.
            if (depth == 0 || (section && comment != null)) {
                out.append('\n');
            }
            if (comment != null) {
                for (String line : comment.value()) {
                    comment(depth, line);
                }
            }
            String key = key(Names.key(component.getName()));
            if (child instanceof Record nested) {
                indent(depth).append(key).append(":\n");
                record(nested, depth + 1, comments);
            } else if (child instanceof Map<?, ?> map) {
                if (!(component.getGenericType() instanceof ParameterizedType)) {
                    throw new IllegalStateException(component + " isn't a typed map");
                }
                indent(depth).append(key).append(map.isEmpty() ? ": {}\n" : ":\n");
                // Entries carry no comments: the map's own comment explains them once.
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    indent(depth + 1).append(key(entry.getKey().toString())).append(":\n");
                    record((Record) entry.getValue(), depth + 2, false);
                }
            } else {
                indent(depth).append(key).append(": ").append(scalarOrList(child)).append('\n');
            }
        }
    }

    private String scalarOrList(Object value) {
        if (value instanceof List<?> list) {
            StringBuilder flow = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                flow.append(i == 0 ? "" : ", ").append(scalar(list.get(i)));
            }
            return flow.append(']').toString();
        }
        return scalar(value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String scalar(Object value) {
        if (value == null) {
            throw new IllegalStateException("a default is null");
        }
        Codec codec = spec.codec(value.getClass());
        if (codec != null) {
            value = codec.encode(value);
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof Double d) {
            return d.toString();
        }
        if (value instanceof Message message) {
            return quote(message.template());
        }
        if (value instanceof Enum<?> constant) {
            return quote(constant.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        return quote(value.toString());
    }

    private void comment(int depth, String line) {
        indent(depth).append(line.isEmpty() ? "#" : "# " + line).append('\n');
    }

    private StringBuilder indent(int depth) {
        return out.append(INDENT.repeat(depth));
    }

    private static String key(String key) {
        return PLAIN_KEY.matcher(key).matches() ? key : quote(key);
    }

    static String quote(String text) {
        StringBuilder quoted = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format("\\x%02x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    private static Object get(RecordComponent component, Record from) {
        try {
            return component.getAccessor().invoke(from);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
