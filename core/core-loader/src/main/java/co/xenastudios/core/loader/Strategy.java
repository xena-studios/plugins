package co.xenastudios.core.loader;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * How downloaded jars are made visible to the plugin's classes on a server that doesn't put
 * them there itself, as the M0 spike proved on Spigot 1.8.8 to 1.16.4 (sandbox/m0/bukkit). On
 * those servers {@link #REFLECT} and {@link #LOOKUP} add them to the server's class loader,
 * which every plugin's shares, rather than the plugin's ({@link Startup#definesForOthers}).
 */
public enum Strategy {

    /**
     * {@code URLClassLoader.addURL} on the plugin's own class loader, by reflection. Silent on
     * Java 8; Java 9 to 15 print an illegal-access warning and 16+ refuse it unless the server
     * was started with {@code --add-opens java.base/java.net=ALL-UNNAMED}, so after 8 it's
     * tried only when {@link #LOOKUP} fails.
     */
    REFLECT("URLClassLoader.addURL"),

    /**
     * The same {@code addURL}, through the JDK's trusted lookup ({@code IMPL_LOOKUP}, read with
     * {@code sun.misc.Unsafe}). Works on Java 9 to 25; 24 and later print the JDK's warning
     * about {@code Unsafe}, and a later JDK may refuse it.
     */
    LOOKUP("the JDK's trusted lookup"),

    /**
     * No injection: the plugin's own classes and the libraries load in a child-first class
     * loader of the plugin's. Works everywhere, but those classes aren't the ones the server
     * gave other plugins, so an API other plugins call can't be among them. The last resort.
     */
    ISOLATED("a class loader of the plugin's own");

    private final String description;

    Strategy(String description) {
        this.description = description;
    }

    /** How it's done, for the log. */
    public String description() {
        return description;
    }

    /** Needs the plugin's class loader to be a {@code URLClassLoader}. */
    public boolean injects() {
        return this != ISOLATED;
    }

    /**
     * The strategies to try in order.
     *
     * @param java the running Java's feature version
     * @param urlClassLoader whether the plugin's class loader is a {@code URLClassLoader}
     * @param forced the one an owner chose, or null for the default
     */
    public static List<Strategy> order(int java, boolean urlClassLoader, Strategy forced) {
        if (forced != null) {
            return Collections.singletonList(forced);
        }
        if (!urlClassLoader) {
            return Collections.singletonList(ISOLATED);
        }
        return java <= 8 ? Arrays.asList(REFLECT, LOOKUP, ISOLATED) : Arrays.asList(LOOKUP, REFLECT, ISOLATED);
    }

    /**
     * {@code reflect}, {@code lookup} or {@code isolated} (any case), or null for {@code auto},
     * blank or null.
     *
     * @throws IllegalArgumentException for anything else
     */
    public static Strategy parse(String name) {
        if (name == null || name.trim().isEmpty() || name.trim().equalsIgnoreCase("auto")) {
            return null;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("\"" + name.trim() + "\" isn't auto, reflect, lookup or isolated", e);
        }
    }
}
