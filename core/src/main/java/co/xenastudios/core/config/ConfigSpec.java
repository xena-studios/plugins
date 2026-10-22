package co.xenastudios.core.config;

import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.SoundEffect;
import co.xenastudios.core.text.TextStyle;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * A plugin's config, declared once: a record type whose components are the settings,
 * and an instance of it holding the defaults. From this the core writes the default
 * file, reads and checks the live one, and carries old files forward.
 *
 * <p>Settings can be {@code boolean}, {@code int}, {@code long}, {@code double},
 * {@code String}, {@link Message}, {@link TextStyle}, {@link SoundEffect}, enums, nested records, {@code List}s of those
 * scalars, {@code Map<String, R>} of records with a {@linkplain Builder#template
 * template}, and any type with a {@linkplain Builder#codec codec}.
 *
 * @param <C> the config's record type
 */
public final class ConfigSpec<C extends Record> {

    final Class<C> type;
    final C defaults;
    final int version;
    final List<String> header;
    final TreeMap<Integer, Migration> migrations;
    final Map<Class<?>, Codec<?>> codecs;
    final Map<Class<?>, Record> templates;
    final Map<String, List<String>> formerDefaults;

    private ConfigSpec(Builder<C> builder) {
        this.type = builder.type;
        this.defaults = builder.defaults;
        this.version = builder.version;
        this.header = List.copyOf(builder.header);
        this.migrations = new TreeMap<>(builder.migrations);
        this.codecs = Map.copyOf(builder.codecs);
        this.templates = Map.copyOf(builder.templates);
        this.formerDefaults = Map.copyOf(builder.formerDefaults);
        check(type, type.getSimpleName());
        for (String path : formerDefaults.keySet()) {
            if (Writer.defaultAt(defaults, path) == null) {
                throw new IllegalStateException("former default " + path + " isn't a setting");
            }
        }
        for (int from = 1; from < version; from++) {
            if (!migrations.containsKey(from)) {
                throw new IllegalStateException("no migration from config version " + from);
            }
        }
    }

    public static <C extends Record> Builder<C> builder(Class<C> type, C defaults) {
        return new Builder<>(type, defaults);
    }

    public C defaults() {
        return defaults;
    }

    public int version() {
        return version;
    }

    @SuppressWarnings("unchecked")
    <T> Codec<T> codec(Class<T> type) {
        return (Codec<T>) codecs.get(type);
    }

    // Fails at startup (and in unit tests) on a setting type the core can't read.
    private void check(Type type, String path) {
        if (type instanceof Class<?> c) {
            if (c.isRecord()) {
                for (RecordComponent component : c.getRecordComponents()) {
                    check(component.getGenericType(), path + "." + Names.key(component.getName()));
                }
            } else if (!isScalar(c)) {
                throw new IllegalStateException(path + ": the config can't hold a " + c.getName());
            }
        } else if (type instanceof ParameterizedType p && p.getRawType() == List.class) {
            Type element = p.getActualTypeArguments()[0];
            if (!(element instanceof Class<?> c && isScalar(c))) {
                throw new IllegalStateException(path + ": lists can only hold scalars, not " + element);
            }
        } else if (type instanceof ParameterizedType p && p.getRawType() == Map.class) {
            Type[] arguments = p.getActualTypeArguments();
            if (arguments[0] != String.class || !(arguments[1] instanceof Class<?> value) || !value.isRecord()) {
                throw new IllegalStateException(path + ": maps must be Map<String, a record>");
            }
            if (!templates.containsKey(value)) {
                throw new IllegalStateException(path + ": " + value.getSimpleName() + " needs a template");
            }
            check(value, path + ".<name>");
        } else {
            throw new IllegalStateException(path + ": the config can't hold a " + type);
        }
    }

    boolean isScalar(Class<?> c) {
        return c == boolean.class || c == int.class || c == long.class || c == double.class
            || c == String.class || c == Message.class || c.isEnum() || codecs.containsKey(c);
    }

    public static final class Builder<C extends Record> {

        private final Class<C> type;
        private final C defaults;
        private int version = 1;
        private final List<String> header = new ArrayList<>();
        private final Map<Integer, Migration> migrations = new HashMap<>();
        private final Map<Class<?>, Codec<?>> codecs = new HashMap<>();
        private final Map<Class<?>, Record> templates = new HashMap<>();
        private final Map<String, List<String>> formerDefaults = new HashMap<>();

        private Builder(Class<C> type, C defaults) {
            this.type = Objects.requireNonNull(type);
            this.defaults = Objects.requireNonNull(defaults);
            codecs.put(TextStyle.class, TextStyle.CODEC);
            codecs.put(SoundEffect.class, SoundEffect.CODEC);
        }

        /** Lines written at the top of the default config. */
        public Builder<C> header(String... lines) {
            header.addAll(List.of(lines));
            return this;
        }

        /** The current config version. Each older version needs a {@link #migrate migration}. */
        public Builder<C> version(int version) {
            this.version = version;
            return this;
        }

        /** How to carry a config from {@code fromVersion} to the next version. */
        public Builder<C> migrate(int fromVersion, Migration migration) {
            migrations.put(fromVersion, migration);
            return this;
        }

        public <T> Builder<C> codec(Class<T> type, Codec<T> codec) {
            codecs.put(type, codec);
            return this;
        }

        /**
         * The defaults for settings missing from one entry of a {@code Map<String, R>}, such
         * as a link command that leaves out its aliases.
         */
        public <R extends Record> Builder<C> template(Class<R> type, R template) {
            templates.put(type, template);
            return this;
        }

        /**
         * What a setting's default used to be. When an old file is carried forward, a
         * setting still on one of these was never changed by its owner, so it gets the
         * current default instead: owners who never touched the messages get the new ones.
         * It happens only while carrying a file forward, so a changed default needs a
         * new config version to reach existing files.
         */
        public Builder<C> formerDefault(String path, String... values) {
            formerDefaults.computeIfAbsent(path, key -> new ArrayList<>()).addAll(List.of(values));
            return this;
        }

        /** Passes the builder through a function, to keep long declarations readable. */
        public Builder<C> apply(UnaryOperator<Builder<C>> step) {
            return step.apply(this);
        }

        public ConfigSpec<C> build() {
            return new ConfigSpec<>(this);
        }
    }
}
