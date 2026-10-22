package co.xenastudios.xlimbo.world;

import co.xenastudios.xlimbo.config.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The limbo world: made on startup, kept matching the config, and rebuilt when its name
 * or floor changes.
 *
 * <p>xLimbo only ever deletes, unloads or rebuilds a world it made itself, which it
 * marks with a {@value #MARKER} file. A world it didn't make, such as the server's main
 * world given xLimbo's generator in bukkit.yml, is used as it is and never touched.
 *
 * <p>Every method runs on the main thread, except {@link #spawn()} and {@link #spot()},
 * which any thread may call.
 */
public final class LimboWorld {

    /** Marks a world folder xLimbo made, with the floor it was made with. */
    static final String MARKER = "xlimbo.dat";

    /** A world name that's only ever one folder: never "..", never a path. */
    private static final Pattern PLAIN_NAME = Pattern.compile("(?!\\.+$)[A-Za-z0-9_.-]+");

    /** Chunks kept loaded around spawn reach at most this far, in chunks. */
    private static final int MAX_READY_RADIUS = 8;

    /**
     * Where arrivals go, published whole so a player logging in during a rebuild never
     * sees half of it.
     *
     * @param owned whether xLimbo made the world, and so may rebuild it
     * @param shape the floor it was made with
     */
    public record Spot(World world, int floorY, int radius, boolean owned, String shape) {}

    private final Plugin plugin;
    private final Logger logger;
    private volatile Spot spot;
    private String warnedAbout;
    private boolean adoptUnmarked;

    public LimboWorld(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Whether a world folder without xLimbo's marker is still xLimbo's: true just after
     * updating from 1.x, which made its world without one.
     */
    public void adoptUnmarked(boolean adopt) {
        this.adoptUnmarked = adopt;
    }

    /** Where arrivals go now, or null while there's no world. Safe from any thread. */
    public Spot spot() {
        return spot;
    }

    /** The world, or null before it's made. */
    public World world() {
        Spot current = spot;
        return current == null ? null : current.world();
    }

    public boolean isLimbo(World other) {
        Spot current = spot;
        return current != null && other != null && other.equals(current.world());
    }

    public int floorY() {
        Spot current = spot;
        return current == null ? Integer.MIN_VALUE : current.floorY();
    }

    /** A random spot on the floor within the spawn radius, or null while there's no world. Safe from any thread. */
    public Location spawn() {
        Spot current = spot;
        if (current == null) {
            return null;
        }
        int radius = current.radius();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double x = (radius == 0 ? 0 : random.nextInt(-radius, radius + 1)) + 0.5;
        double z = (radius == 0 ? 0 : random.nextInt(-radius, radius + 1)) + 0.5;
        return new Location(current.world(), x, current.floorY() + 1, z, random.nextFloat(-180, 180), 0);
    }

    /** The generator for a world the server loads itself, from bukkit.yml. */
    public VoidGenerator generatorFor(Settings.World settings) {
        return new VoidGenerator(settings.floorY(), settings.floorBlock());
    }

    private static String shape(Settings.World settings) {
        return settings.floorBlock().getKey() + "@" + settings.floorY();
    }

    /** Makes the server's limbo world match the settings: creating, rebuilding, switching or adjusting it. */
    public void sync(Settings.World settings) {
        Spot current = spot;
        String shape = shape(settings);
        if (current != null && current.world().getName().equals(settings.name())) {
            if (current.owned() && !current.shape().equals(shape)) {
                rebuildInPlace(current, settings);
            } else {
                if (!current.owned() && !current.shape().equals(shape)) {
                    warnOnce(settings.name() + " isn't a world xLimbo made, so its floor can't change while the server runs; restart to apply floor-block and floor-y.");
                }
                publish(current.world(), settings, current.owned(), current.shape());
            }
            return;
        }
        World next = Bukkit.getWorld(settings.name());
        boolean owned;
        String madeWith;
        if (next != null) {
            // Already loaded: the main world with xLimbo's generator, or one we made before.
            owned = isOurs(next.getWorldFolder().toPath());
            madeWith = owned ? readShape(next.getWorldFolder().toPath()) : shape;
            if (!(next.getGenerator() instanceof VoidGenerator)) {
                warnOnce("world " + settings.name() + " doesn't use xLimbo's generator, so it has no void floor; give it \"generator: xLimbo\" in bukkit.yml, or pick a new name.");
            }
        } else {
            next = create(settings);
            if (next == null) {
                return;
            }
            owned = true;
            madeWith = shape;
        }
        publish(next, settings, owned, madeWith);
        if (current != null) {
            leave(current, next);
        }
    }

    // Same world, new floor: everyone steps out to the main world while it's remade.
    private void rebuildInPlace(Spot current, Settings.World settings) {
        World old = current.world();
        World staging = Bukkit.getWorlds().getFirst();
        List<Player> moving = new ArrayList<>(old.getPlayers());
        for (Player player : moving) {
            player.teleport(staging.getSpawnLocation());
        }
        spot = null;
        unload(old, false);
        World made = create(settings);
        if (made == null) {
            return;
        }
        publish(made, settings, true, shape(settings));
        moveIn(moving);
        logger.info("Rebuilt world " + settings.name() + (moving.isEmpty() ? "."
            : " and moved " + moving.size() + (moving.size() == 1 ? " player" : " players") + " back."));
    }

    // Switching worlds: players move to the new one, and the old one is let go of if it was ours.
    private void leave(Spot old, World next) {
        List<Player> moving = new ArrayList<>(old.world().getPlayers());
        for (Player player : moving) {
            player.teleport(spawn());
        }
        old.world().removePluginChunkTickets(plugin);
        if (old.owned()) {
            unload(old.world(), old.world().isAutoSave());
        }
        logger.info("Switched the limbo to " + next.getName() + (moving.isEmpty() ? "."
            : " and moved " + moving.size() + (moving.size() == 1 ? " player" : " players") + " there."));
    }

    private void moveIn(List<Player> players) {
        for (Player player : players) {
            if (player.isOnline()) {
                player.teleportAsync(spawn());
            }
        }
    }

    /**
     * Makes the world: fresh if it's temporary, its floor changed, or it's to reset; from
     * its folder otherwise. Refuses a folder xLimbo didn't make, rather than mix floors
     * into someone else's world or delete it.
     */
    private World create(Settings.World settings) {
        Path folder = folder(settings.name());
        if (folder == null) {
            logger.severe("world.name \"" + settings.name() + "\" can only use letters, digits, _, - and .; pick another.");
            return null;
        }
        if (hasWorld(folder)) {
            if (!isOurs(folder) && adoptUnmarked) {
                logger.info("Taking over " + settings.name() + ", the world xLimbo 1.x made.");
                try {
                    // Made with the floor its config, now carried forward, describes.
                    Files.writeString(folder.resolve(MARKER), shape(settings) + "\n", StandardCharsets.UTF_8);
                } catch (IOException e) {
                    logger.warning("couldn't mark world " + settings.name() + " as xLimbo's: " + e.getMessage());
                }
            }
            if (!isOurs(folder)) {
                logger.severe("There's already a world called " + settings.name() + " that xLimbo didn't make. "
                    + "xLimbo won't touch it: pick another world.name, or load that world and give it xLimbo's generator.");
                return null;
            }
            boolean fresh = !settings.autoSave() || settings.resetOnStartup() || !shape(settings).equals(readShape(folder));
            if (fresh) {
                delete(folder);
            }
        }
        World made;
        try {
            made = new WorldCreator(settings.name())
                .generator(generatorFor(settings))
                .environment(World.Environment.NORMAL)
                .generateStructures(false)
                .createWorld();
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Couldn't create world " + settings.name(), e);
            return null;
        }
        if (made == null) {
            logger.severe("The server wouldn't create world " + settings.name() + ".");
            return null;
        }
        try {
            Files.writeString(made.getWorldFolder().toPath().resolve(MARKER), shape(settings) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warning("couldn't mark world " + settings.name() + " as xLimbo's: " + e.getMessage());
        }
        return made;
    }

    // Applies the settings that change in place, then publishes where arrivals go.
    private void publish(World target, Settings.World settings, boolean owned, String shape) {
        int halfBorder = settings.border().size() / 2;
        // Keep arrivals at least two chunks inside the border.
        int radius = Math.max(0, Math.min(settings.border().spawnRadius(), halfBorder - 32));
        if (radius < settings.border().spawnRadius()) {
            warnOnce("world.border.spawn-radius is too close to the border; using " + radius + ".");
        }
        if (owned) {
            target.setAutoSave(settings.autoSave());
        }
        target.getWorldBorder().setCenter(0, 0);
        target.getWorldBorder().setSize(settings.border().size());
        if (settings.viewDistance() >= 2) {
            target.setViewDistance(settings.viewDistance());
        }
        if (settings.simulationDistance() >= 2) {
            target.setSimulationDistance(settings.simulationDistance());
        }
        // Clear skies for as long as the server runs.
        target.setStorm(false);
        target.setThundering(false);
        target.setClearWeatherDuration(Integer.MAX_VALUE);
        // The floor the world really has: for one the server loaded, the generator's.
        int floorY = target.getGenerator() instanceof VoidGenerator generator ? generator.floorY() : settings.floorY();
        Spot previous = spot;
        spot = new Spot(target, floorY, radius, owned, shape);
        if (previous == null || previous.world() != target || previous.radius() != radius) {
            keepSpawnReady(target, radius);
        }
    }

    // Keeps the chunks arrivals land in loaded, so a whole network arriving at once
    // never waits for generation. Capped, since a huge radius would be millions.
    private void keepSpawnReady(World target, int radius) {
        target.removePluginChunkTickets(plugin);
        int chunks = Math.min(MAX_READY_RADIUS, (radius >> 4) + 1);
        int total = (2 * chunks + 1) * (2 * chunks + 1);
        AtomicInteger left = new AtomicInteger(total);
        long started = System.nanoTime();
        for (int x = -chunks; x <= chunks; x++) {
            for (int z = -chunks; z <= chunks; z++) {
                target.getChunkAtAsync(x, z).thenAccept(chunk -> {
                    chunk.addPluginChunkTicket(plugin);
                    if (left.decrementAndGet() == 0) {
                        logger.info("World " + target.getName() + " is ready: " + total + " chunks around spawn loaded in "
                            + (System.nanoTime() - started) / 1_000_000 + " ms.");
                    }
                });
            }
        }
    }

    private void unload(World target, boolean save) {
        target.removePluginChunkTickets(plugin);
        if (!Bukkit.unloadWorld(target, save)) {
            logger.warning("couldn't unload world " + target.getName() + "; it stays loaded until a restart.");
        }
    }

    /**
     * Where a world named {@code name} lives or would live, or null for a name that isn't a
     * plain folder name. Minecraft 1.21 keeps each world in its own folder beside the main
     * one; 26.1 and later keep extra worlds inside it, under dimensions/minecraft/.
     */
    private static Path folder(String name) {
        if (!PLAIN_NAME.matcher(name).matches()) {
            return null;
        }
        World loaded = Bukkit.getWorld(name);
        if (loaded != null) {
            return loaded.getWorldFolder().toPath();
        }
        Path container = Bukkit.getWorldContainer().toPath();
        Path dimensions = container.resolve(Bukkit.getWorlds().getFirst().getName()).resolve("dimensions");
        if (Files.isDirectory(dimensions)) {
            return dimensions.resolve("minecraft").resolve(name.toLowerCase(Locale.ROOT));
        }
        return container.resolve(name);
    }

    // A folder with anything in it: a world's, in either layout.
    private static boolean hasWorld(Path folder) {
        if (!Files.isDirectory(folder)) {
            return false;
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.findAny().isPresent();
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean isOurs(Path folder) {
        return Files.isRegularFile(folder.resolve(MARKER));
    }

    private static String readShape(Path folder) {
        try {
            return Files.readString(folder.resolve(MARKER), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    // Deletes a world folder xLimbo made. Never a loaded world's: the server would write it back.
    private void delete(Path folder) {
        if (!isOurs(folder) || Bukkit.getWorld(folder.getFileName().toString()) != null) {
            return;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        } catch (IOException e) {
            logger.warning("couldn't delete the old world " + folder.getFileName() + ": " + e.getMessage());
        }
    }

    private void warnOnce(String warning) {
        if (!warning.equals(warnedAbout)) {
            warnedAbout = warning;
            logger.warning(warning);
        }
    }
}
