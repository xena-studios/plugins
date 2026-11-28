package co.xenastudios.xlimbo.bukkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The rules that keep xLimbo from deleting anything it didn't make, kept apart from
 * Bukkit so they can be tested on real folders. xLimbo marks every world it makes with
 * {@value #MARKER}; only a marked folder that isn't loaded is ever deleted.
 */
final class WorldFolders {

    static final String MARKER = "xlimbo.dat";
    // Letters, digits, _, - and . only, and not just dots: never a path like ../world.
    private static final Pattern PLAIN_NAME = Pattern.compile("(?!\\.+$)[A-Za-z0-9_.-]+");

    private WorldFolders() {}

    static boolean isPlainName(String name) {
        return name != null && PLAIN_NAME.matcher(name).matches();
    }

    /**
     * Where an unloaded world named {@code name} would live, or null for a name that isn't
     * plain. Minecraft 1.21 keeps each world in its own folder in the world container; 26.1
     * and later keep extra worlds inside the main one, under dimensions/minecraft/, lowercased.
     */
    static Path folder(String name, Path container, String mainWorld) {
        if (!isPlainName(name)) {
            return null;
        }
        Path dimensions = container.resolve(mainWorld).resolve("dimensions");
        if (Files.isDirectory(dimensions)) {
            return dimensions.resolve("minecraft").resolve(name.toLowerCase(Locale.ROOT));
        }
        return container.resolve(name);
    }

    /** A folder with anything in it: a world's, in either layout. */
    static boolean hasWorld(Path folder) {
        if (!Files.isDirectory(folder)) {
            return false;
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.findAny().isPresent();
        } catch (IOException e) {
            // Can't tell: treat it as a world, so it's never mistaken for free space.
            return true;
        }
    }

    static boolean isOurs(Path folder) {
        return Files.isRegularFile(folder.resolve(MARKER));
    }

    /** The floor a marked world was made with, or "" for an unmarked one. */
    static String readShape(Path folder) {
        try {
            return Files.readString(folder.resolve(MARKER), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    static void mark(Path folder, String shape) throws IOException {
        Files.writeString(folder.resolve(MARKER), shape + "\n", StandardCharsets.UTF_8);
    }

    /**
     * Deletes a world folder xLimbo made: never an unmarked one, and never one that's
     * loaded (the server would write it back). Links inside are removed, never followed.
     *
     * @return whether it was deleted
     * @throws IOException if part of it couldn't be
     */
    static boolean delete(Path folder, Predicate<String> loaded) throws IOException {
        if (!isOurs(folder) || loaded.test(folder.getFileName().toString())) {
            return false;
        }
        // A world folder that's itself a link (a limbo kept on a RAM disk, say) is emptied
        // where it points, and the link and that folder are kept, so the setup survives.
        boolean linked = Files.isSymbolicLink(folder);
        Path root = linked ? folder.toRealPath() : folder;
        // Files.walk doesn't follow links, so a link inside to somewhere else is deleted as a link.
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!(linked && file.equals(root))) {
                    Files.delete(file);
                }
            }
        }
        return true;
    }
}
