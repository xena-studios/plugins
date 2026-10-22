package co.xenastudios.core.config;

import co.xenastudios.core.text.Message;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Reads YAML into a config record. A value that's missing or can't be used falls back
 * to its default; an unusable one is also reported as a warning.
 */
final class Reader {

    /** A setting the file doesn't have, to add with its default. */
    record Missing(ConfigurationSection parent, String key, RecordComponent component, Object value) {}

    /** A setting left empty ({@code permission:}), to write back as an explicit blank. */
    record Blank(ConfigurationSection parent, String key, Object value) {}

    private final ConfigSpec<?> spec;
    // The file as SnakeYAML read it. Bukkit drops keys with no value, so this is how
    // "permission:" (meaning blank) is told apart from a setting that isn't there.
    private final Object rawRoot;
    final List<String> warnings = new ArrayList<>();
    final List<Missing> missing = new ArrayList<>();
    final List<Blank> blanks = new ArrayList<>();

    Reader(ConfigSpec<?> spec, Object rawRoot) {
        this.spec = spec;
        this.rawRoot = rawRoot;
    }

    <R extends Record> R readRoot(Class<R> type, ConfigurationSection root, R defaults) {
        return readRecord(type, root, defaults, "", true);
    }

    // trackMissing is off inside map entries: a link command that leaves out its
    // aliases gets the template's, without the file growing an "aliases: []" line.
    private <R extends Record> R readRecord(Class<R> type, Object raw, R fallback, String path, boolean trackMissing) {
        if (!(raw instanceof ConfigurationSection section)) {
            warn(path, raw, "should be a section of settings");
            return fallback;
        }
        RecordComponent[] components = type.getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            types[i] = component.getType();
            String key = Names.key(component.getName());
            Object defaultValue = get(component, fallback);
            if (!section.contains(key)) {
                boolean empty = isBlank(join(path, key));
                Object blank = empty ? blank(component.getType()) : null;
                if (blank != null) {
                    values[i] = blank;
                    blanks.add(new Blank(section, key, blank));
                    continue;
                }
                values[i] = defaultValue;
                if (empty) {
                    // Often a colour like "highlight: #B48CFF", which YAML reads as a comment.
                    warn(join(path, key), null, "is empty; write \"\" for none, and quote values that start with #");
                    continue;
                }
                if (trackMissing) {
                    missing.add(new Missing(section, key, component, defaultValue));
                }
                continue;
            }
            values[i] = read(component.getGenericType(), component, section.get(key), defaultValue, join(path, key), trackMissing);
        }
        try {
            return type.getDeclaredConstructor(types).newInstance(values);
        } catch (InvocationTargetException e) {
            // The record rejected the combination, such as a minimum above the maximum.
            if (e.getCause() instanceof IllegalArgumentException reason) {
                warnings.add("%s: %s; using the defaults for this section.".formatted(display(path), reason.getMessage()));
                return fallback;
            }
            throw new IllegalStateException("couldn't build " + type.getName(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("couldn't build " + type.getName(), e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object read(Type type, RecordComponent component, Object raw, Object fallback, String path, boolean trackMissing) {
        if (raw == null) {
            warn(path, null, "is empty");
            return fallback;
        }
        if (type instanceof Class<?> c && c.isRecord()) {
            return readRecord((Class) c, raw, (Record) fallback, path, trackMissing);
        }
        if (type instanceof ParameterizedType p && p.getRawType() == List.class) {
            return readList((Class<?>) p.getActualTypeArguments()[0], raw, fallback, path);
        }
        if (type instanceof ParameterizedType p && p.getRawType() == Map.class) {
            return readMap((Class) p.getActualTypeArguments()[1], raw, fallback, path);
        }
        try {
            Object value = scalar((Class<?>) type, raw);
            Range range = component == null ? null : component.getAnnotation(Range.class);
            if (range != null && value instanceof Number number) {
                double d = number.doubleValue();
                if (d < range.min() || d > range.max()) {
                    throw new IllegalArgumentException("must be " + bounds(range));
                }
            }
            return value;
        } catch (IllegalArgumentException e) {
            warn(path, raw, e.getMessage());
            return fallback;
        }
    }

    private Object readList(Class<?> element, Object raw, Object fallback, String path) {
        // A single value where a list belongs is a list of one: "aliases: gm".
        List<?> items = raw instanceof List<?> list ? list : List.of(raw);
        List<Object> values = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            try {
                if (items.get(i) == null) {
                    throw new IllegalArgumentException("is empty");
                }
                values.add(scalar(element, items.get(i)));
            } catch (IllegalArgumentException e) {
                // Drop just the bad item.
                warn(path + "[" + i + "]", items.get(i), e.getMessage() + ", so it's skipped");
            }
        }
        return List.copyOf(values);
    }

    private <R extends Record> Object readMap(Class<R> valueType, Object raw, Object fallback, String path) {
        if (!(raw instanceof ConfigurationSection section)) {
            warn(path, raw, "should be a section");
            return fallback;
        }
        @SuppressWarnings("unchecked")
        R template = (R) spec.templates.get(valueType);
        Map<String, R> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object entry = section.get(key);
            if (!(entry instanceof ConfigurationSection)) {
                warn(join(path, key), entry, "should be a section of settings, so it's skipped");
                continue;
            }
            values.put(key, readRecord(valueType, entry, template, join(path, key), false));
        }
        return Collections.unmodifiableMap(values);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object scalar(Class<?> type, Object raw) {
        if (raw instanceof ConfigurationSection || raw instanceof List<?> || raw instanceof Map<?, ?>) {
            throw new IllegalArgumentException("should be a single value");
        }
        Codec<?> codec = spec.codec(type);
        if (codec != null) {
            return codec.decode(raw);
        }
        if (type == String.class) {
            return raw.toString();
        }
        if (type == Message.class) {
            return Message.of(raw.toString());
        }
        if (type == boolean.class) {
            if (raw instanceof Boolean b) {
                return b;
            }
            return switch (raw.toString().toLowerCase(Locale.ROOT)) {
                case "true", "yes", "on" -> true;
                case "false", "no", "off" -> false;
                default -> throw new IllegalArgumentException("should be true or false");
            };
        }
        if (type == int.class || type == long.class) {
            long value = wholeNumber(raw);
            if (type == int.class && (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)) {
                throw new IllegalArgumentException("is too large");
            }
            return type == int.class ? (Object) (int) value : (Object) value;
        }
        if (type == double.class) {
            if (raw instanceof Number n) {
                return n.doubleValue();
            }
            try {
                return Double.parseDouble(raw.toString().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("should be a number");
            }
        }
        if (type.isEnum()) {
            String name = raw.toString().trim().replace('-', '_').toUpperCase(Locale.ROOT);
            for (Object constant : type.getEnumConstants()) {
                if (((Enum) constant).name().equals(name)) {
                    return constant;
                }
            }
            throw new IllegalArgumentException("should be one of " + Writer.enumNames(type));
        }
        throw new IllegalStateException("no reader for " + type);
    }

    private static long wholeNumber(Object raw) {
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.longValue();
        }
        try {
            return Long.parseLong(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("should be a whole number");
        }
    }

    private void warn(String path, Object raw, String reason) {
        String value = raw == null ? "" : " (\"" + raw + "\")";
        warnings.add("%s%s %s; using the default.".formatted(display(path), value, reason));
    }

    private static String bounds(Range range) {
        if (range.min() == Double.NEGATIVE_INFINITY) {
            return "at most " + Writer.number(range.max());
        }
        if (range.max() == Double.POSITIVE_INFINITY) {
            return "at least " + Writer.number(range.min());
        }
        return "from " + Writer.number(range.min()) + " to " + Writer.number(range.max());
    }

    /** Whether the file has this key with nothing after it. */
    private boolean isBlank(String path) {
        Object node = rawRoot;
        String[] keys = path.split("\\.");
        for (int i = 0; i < keys.length; i++) {
            if (!(node instanceof Map<?, ?> map) || !map.containsKey(keys[i])) {
                return false;
            }
            node = map.get(keys[i]);
            if (i == keys.length - 1) {
                return node == null;
            }
        }
        return false;
    }

    /** What an empty value means for a type, or null if it means nothing (such as an empty number). */
    private static Object blank(Class<?> type) {
        if (type == String.class) {
            return "";
        }
        if (type == Message.class) {
            return Message.empty();
        }
        if (type == List.class) {
            return List.of();
        }
        if (type == Map.class) {
            return Map.of();
        }
        return null;
    }

    private static Object get(RecordComponent component, Record from) {
        try {
            return component.getAccessor().invoke(from);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static String display(String path) {
        return path.isEmpty() ? "The config" : path;
    }
}
