package co.xenastudios.xrewind.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a lookup, rollback or restore is about: the same parameters as {@code /xr lookup}
 * ({@code u:}, {@code t:}, {@code r:}, {@code a:}, {@code i:}, {@code e:}, {@code w:}).
 * Build one with {@link #builder()}. Immutable, so it can be kept and used again.
 *
 * <pre>{@code
 * Query query = Query.builder()
 *     .users("Steve")
 *     .time(Duration.ofHours(2))
 *     .radius(player.getLocation(), 20)
 *     .actions(Action.BREAK, Action.PLACE)
 *     .build();
 * }</pre>
 *
 * @since 1.1.0
 */
public final class Query {

    /**
     * Where a query looks.
     *
     * @since 1.1.0
     */
    public enum Area {
        /** Within a radius of a block ({@code r:<blocks>}). */
        RADIUS,
        /** One whole world ({@code r:#world w:<world>}). */
        WORLD,
        /** Every world ({@code r:#global}). */
        GLOBAL
    }

    /** The most entries one lookup returns. */
    private static final int MAX_LIMIT = 10_000;

    private final List<String> users;
    private final List<String> excludedUsers;
    private final Duration time;
    private final Area area;
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final int radius;
    private final Set<Action> actions;
    private final Set<Material> blocks;
    private final Set<Material> excludedBlocks;
    private final int limit;

    private Query(Builder builder) {
        this.users = List.copyOf(builder.users);
        this.excludedUsers = List.copyOf(builder.excludedUsers);
        this.time = builder.time;
        this.area = builder.area;
        this.world = builder.world;
        this.x = builder.x;
        this.y = builder.y;
        this.z = builder.z;
        this.radius = builder.radius;
        this.actions = Collections.unmodifiableSet(EnumSet.copyOf(builder.actions.isEmpty() ? EnumSet.noneOf(Action.class) : builder.actions));
        this.blocks = Collections.unmodifiableSet(new LinkedHashSet<>(builder.blocks));
        this.excludedBlocks = Collections.unmodifiableSet(new LinkedHashSet<>(builder.excludedBlocks));
        this.limit = builder.limit;
    }

    /**
     * A new builder. With nothing set, a query covers every world, all time, anyone, and
     * the kinds of change {@code /xr lookup} covers without {@code a:} (blocks, containers
     * and kills).
     *
     * @return the builder
     * @since 1.1.0
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * The players and sources it's limited to.
     *
     * @return names in lowercase, sources with their {@code #}; empty for anyone
     * @since 1.1.0
     */
    public @NotNull List<String> users() {
        return users;
    }

    /**
     * The players and sources it leaves out.
     *
     * @return names in lowercase, sources with their {@code #}
     * @since 1.1.0
     */
    public @NotNull List<String> excludedUsers() {
        return excludedUsers;
    }

    /**
     * How far back it looks.
     *
     * @return the time, or empty for all time
     * @since 1.1.0
     */
    public @NotNull Optional<Duration> time() {
        return Optional.ofNullable(time);
    }

    /**
     * Where it looks.
     *
     * @return the area
     * @since 1.1.0
     */
    public @NotNull Area area() {
        return area;
    }

    /**
     * The world it looks in.
     *
     * @return the world's name, or null for {@link Area#GLOBAL}
     * @since 1.1.0
     */
    public @Nullable String world() {
        return world;
    }

    /**
     * The x of the centre of a {@link Area#RADIUS} query.
     *
     * @return the block's x; 0 for other areas
     * @since 1.1.0
     */
    public int centerX() {
        return x;
    }

    /**
     * The y of the centre of a {@link Area#RADIUS} query.
     *
     * @return the block's y; 0 for other areas
     * @since 1.1.0
     */
    public int centerY() {
        return y;
    }

    /**
     * The z of the centre of a {@link Area#RADIUS} query.
     *
     * @return the block's z; 0 for other areas
     * @since 1.1.0
     */
    public int centerZ() {
        return z;
    }

    /**
     * The radius of a {@link Area#RADIUS} query, in blocks.
     *
     * @return the radius; 0 for other areas
     * @since 1.1.0
     */
    public int radius() {
        return radius;
    }

    /**
     * The kinds of change it's limited to.
     *
     * @return the actions; empty for those {@code /xr lookup} covers without {@code a:}
     * @since 1.1.0
     */
    public @NotNull Set<Action> actions() {
        return actions;
    }

    /**
     * The blocks it's limited to ({@code i:}).
     *
     * @return the blocks; empty for any
     * @since 1.1.0
     */
    public @NotNull Set<Material> blocks() {
        return blocks;
    }

    /**
     * The blocks it leaves out ({@code e:}).
     *
     * @return the blocks
     * @since 1.1.0
     */
    public @NotNull Set<Material> excludedBlocks() {
        return excludedBlocks;
    }

    /**
     * The most entries a lookup returns, newest first. Rollbacks and restores ignore it.
     *
     * @return the limit
     * @since 1.1.0
     */
    public int limit() {
        return limit;
    }

    /**
     * The query as {@code /xr lookup} parameters, such as
     * {@code u:steve t:7200s r:20 a:break,place}, for logs and messages.
     *
     * @return the parameters
     * @since 1.1.0
     */
    @Override
    public @NotNull String toString() {
        List<String> parts = new ArrayList<>();
        if (!users.isEmpty()) {
            parts.add("u:" + String.join(",", users));
        }
        if (time != null) {
            parts.add("t:" + time.toSeconds() + "s");
        }
        switch (area) {
            case RADIUS -> parts.add("r:" + radius + " (" + world + " " + x + " " + y + " " + z + ")");
            case WORLD -> parts.add("r:#world w:" + world);
            case GLOBAL -> parts.add("r:#global");
            default -> throw new IllegalStateException();
        }
        if (!actions.isEmpty()) {
            parts.add("a:" + String.join(",", actions.stream().map(action -> action.name().toLowerCase(Locale.ROOT)).toList()));
        }
        if (!blocks.isEmpty()) {
            parts.add("i:" + String.join(",", blocks.stream().map(block -> block.getKey().getKey()).toList()));
        }
        List<String> excluded = new ArrayList<>(excludedUsers);
        excludedBlocks.forEach(block -> excluded.add(block.getKey().getKey()));
        if (!excluded.isEmpty()) {
            parts.add("e:" + String.join(",", excluded));
        }
        return String.join(" ", parts);
    }

    /**
     * Builds a {@link Query}. Each method returns the builder, for chaining.
     *
     * @since 1.1.0
     */
    public static final class Builder {

        private final List<String> users = new ArrayList<>();
        private final List<String> excludedUsers = new ArrayList<>();
        private Duration time;
        private Area area = Area.GLOBAL;
        private String world;
        private int x;
        private int y;
        private int z;
        private int radius;
        private final Set<Action> actions = EnumSet.noneOf(Action.class);
        private final Set<Material> blocks = new LinkedHashSet<>();
        private final Set<Material> excludedBlocks = new LinkedHashSet<>();
        private int limit = 100;

        private Builder() {}

        /**
         * Only changes by these players or sources ({@code u:}).
         *
         * @param names player names, or sources such as {@code #tnt} or {@code #xplots}
         * @return this builder
         * @since 1.1.0
         */
        public @NotNull Builder users(@NotNull String... names) {
            for (String name : names) {
                users.add(name.toLowerCase(Locale.ROOT));
            }
            return this;
        }

        /**
         * Leaves out changes by these players or sources ({@code e:}).
         *
         * @param names player names, or sources such as {@code #tnt}
         * @return this builder
         * @since 1.1.0
         */
        public @NotNull Builder excludeUsers(@NotNull String... names) {
            for (String name : names) {
                excludedUsers.add(name.toLowerCase(Locale.ROOT));
            }
            return this;
        }

        /**
         * Only changes this recent ({@code t:}). Rollbacks and restores need one.
         *
         * @param time how far back, at least a second
         * @return this builder
         * @since 1.1.0
         */
        public @NotNull Builder time(@NotNull Duration time) {
            if (time.toSeconds() < 1) {
                throw new IllegalArgumentException("A query's time is at least a second");
            }
            this.time = time;
            return this;
        }

        /**
         * Only changes within {@code blocks} of a spot ({@code r:}).
         *
         * @param center the centre, in a loaded world
         * @param blocks the radius, from 0
         * @return this builder
         * @since 1.1.0
         */
        public @NotNull Builder radius(@NotNull Location center, int blocks) {
            World in = Objects.requireNonNull(center.getWorld(), "the centre must be in a world");
            return radius(in.getName(), center.getBlockX(), center.getBlockY(), center.getBlockZ(), blocks);
        }

        /**
         * Only changes within {@code blocks} of a block ({@code r:}).
         *
         * @param world the world's name
         * @param x the centre's x
         * @param y the centre's y
         * @param z the centre's z
         * @param blocks the radius, from 0
         * @return this builder
         * @since 1.1.0
         */
        public @NotNull Builder radius(@NotNull String world, int x, int y, int z, int blocks) {
            if (blocks < 0) {
                throw new IllegalArgumentException("A radius can't be negative");
            }
            this.area = Area.RADIUS;
            this.world = Objects.requireNonNull(world, "world");
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = blocks;
            return this;
        }

        /**
         * Only changes in one world ({@code r:#world w:}).
         *
         * @param world the world's name
         * @return this builder
         * @since 1.1.0
         */
        public @NotNull Builder world(@NotNull String world) {
            this.area = Area.WORLD;
            this.world = Objects.requireNonNull(world, "world");
            this.x = 0;
            this.y = 0;
            this.z = 0;
            this.radius = 0;
            return this;
        }

        /**
         * Changes in every world ({@code r:#global}): the default.
         *
         * @return this builder
         * @since 1.1.0
         */
        public @NotNull Builder global() {
            this.area = Area.GLOBAL;
            this.world = null;
            this.x = 0;
            this.y = 0;
            this.z = 0;
            this.radius = 0;
            return this;
        }

        /**
         * Only these kinds of change ({@code a:}).
         *
         * @param kinds the actions
         * @return this builder
         * @since 1.1.0
         */
        public @NotNull Builder actions(@NotNull Action... kinds) {
            Collections.addAll(actions, kinds);
            return this;
        }

        /**
         * Only changes to these blocks ({@code i:}).
         *
         * @param materials blocks, matched against what was broken or placed, or items, matched
         *     against what went in or out of a container
         * @return this builder
         * @since 1.1.0
         */
        public @NotNull Builder blocks(@NotNull Material... materials) {
            for (Material material : materials) {
                blocks.add(block(material));
            }
            return this;
        }

        /**
         * Leaves out changes to these blocks ({@code e:}).
         *
         * @param materials blocks, matched against what was broken or placed, or items, matched
         *     against what went in or out of a container
         * @return this builder
         * @since 1.1.0
         */
        public @NotNull Builder excludeBlocks(@NotNull Material... materials) {
            for (Material material : materials) {
                excludedBlocks.add(block(material));
            }
            return this;
        }

        /**
         * The most entries a lookup returns, newest first: 100 unless set. Rollbacks and
         * restores ignore it.
         *
         * @param entries from 1 to 10,000
         * @return this builder
         * @since 1.1.0
         */
        public @NotNull Builder limit(int entries) {
            if (entries < 1 || entries > MAX_LIMIT) {
                throw new IllegalArgumentException("A lookup's limit is from 1 to " + MAX_LIMIT);
            }
            this.limit = entries;
            return this;
        }

        /**
         * The query.
         *
         * @return the query
         * @since 1.1.0
         */
        public @NotNull Query build() {
            return new Query(this);
        }

        private static Material block(Material material) {
            if (!material.isBlock() && !material.isItem()) {
                throw new IllegalArgumentException(material + " isn't a block or an item");
            }
            return material;
        }
    }
}
