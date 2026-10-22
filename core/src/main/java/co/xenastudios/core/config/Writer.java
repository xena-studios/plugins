package co.xenastudios.core.config;

import co.xenastudios.core.text.Message;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Adds settings to an existing file through Bukkit, keeping the owner's values and comments. */
final class Writer {

    static final String VERSION_KEY = "config-version";

    private Writer() {}

    /**
     * Replaces the header and the comments on every setting the spec knows with the
     * current ones, so a carried-forward file doesn't keep an old version's notes.
     * Entries of maps, such as link commands, are the owner's and keep theirs.
     */
    static void recomment(ConfigSpec<?> spec, YamlConfiguration yaml) {
        yaml.options().setHeader(spec.header);
        yaml.setComments(VERSION_KEY, List.of("Managed by the plugin, to carry your settings forward on updates. Don't edit it."));
        recomment(yaml, spec.defaults.getClass(), 0);
    }

    private static void recomment(ConfigurationSection section, Class<?> type, int depth) {
        for (RecordComponent component : type.getRecordComponents()) {
            String key = Names.key(component.getName());
            if (!section.contains(key)) {
                continue;
            }
            boolean isSection = component.getType().isRecord() || component.getType() == Map.class;
            Comment comment = component.getAnnotation(Comment.class);
            List<String> lines = new ArrayList<>();
            if (depth == 0 || (isSection && comment != null)) {
                lines.add(null);
            }
            if (comment != null) {
                lines.addAll(Arrays.asList(comment.value()));
            }
            section.setComments(key, lines);
            section.setInlineComments(key, List.of());
            if (component.getType().isRecord() && section.get(key) instanceof ConfigurationSection child) {
                recomment(child, component.getType(), depth + 1);
            }
        }
    }

    /** Adds one setting, with its comment, to an existing file. */
    static void add(ConfigSpec<?> spec, ConfigurationSection parent, String key, RecordComponent component, Object value) {
        write(spec, parent, key, component, component.getGenericType(), value, depth(parent));
    }

    private static void writeRecord(ConfigSpec<?> spec, ConfigurationSection target, Record value, int depth) {
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            Object componentValue;
            try {
                componentValue = component.getAccessor().invoke(value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            write(spec, target, Names.key(component.getName()), component, component.getGenericType(), componentValue, depth);
        }
    }

    private static void write(ConfigSpec<?> spec, ConfigurationSection target, String key, RecordComponent component, Type type, Object value, int depth) {
        boolean section = false;
        if (type instanceof Class<?> c && c.isRecord()) {
            writeRecord(spec, target.createSection(key), (Record) value, depth + 1);
            section = true;
        } else if (type instanceof ParameterizedType p && p.getRawType() == Map.class) {
            ConfigurationSection map = target.createSection(key);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                // Entries carry no comments: the map's own comment explains them once.
                writeUncommented(spec, map.createSection((String) entry.getKey()), (Record) entry.getValue());
            }
            section = true;
        } else if (value instanceof List<?> list) {
            target.set(key, list.stream().map(item -> scalar(spec, item)).toList());
        } else {
            target.set(key, scalar(spec, value));
        }
        Comment comment = component == null ? null : component.getAnnotation(Comment.class);
        List<String> lines = new ArrayList<>();
        // A blank line above each top-level setting and each commented section.
        if (depth == 0 || (section && comment != null)) {
            lines.add(null);
        }
        if (comment != null) {
            lines.addAll(Arrays.asList(comment.value()));
        }
        if (!lines.isEmpty()) {
            target.setComments(key, lines);
        }
    }

    private static void writeUncommented(ConfigSpec<?> spec, ConfigurationSection target, Record value) {
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            Object componentValue;
            try {
                componentValue = component.getAccessor().invoke(value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            String key = Names.key(component.getName());
            if (componentValue instanceof Record record) {
                writeUncommented(spec, target.createSection(key), record);
            } else if (componentValue instanceof List<?> list) {
                target.set(key, list.stream().map(item -> scalar(spec, item)).toList());
            } else {
                target.set(key, scalar(spec, componentValue));
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object scalar(ConfigSpec<?> spec, Object value) {
        Codec codec = spec.codec(value.getClass());
        if (codec != null) {
            return codec.encode(value);
        }
        if (value instanceof Message message) {
            return message.template();
        }
        if (value instanceof Enum<?> constant) {
            return constant.name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
        return value;
    }

    private static int depth(ConfigurationSection section) {
        String path = section.getCurrentPath();
        return path == null || path.isEmpty() ? 0 : path.split("\\.").length;
    }

    static String enumNames(Class<?> type) {
        return Arrays.stream(type.getEnumConstants())
            .map(constant -> ((Enum<?>) constant).name().toLowerCase(Locale.ROOT).replace('_', '-'))
            .collect(Collectors.joining(", "));
    }

    static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
