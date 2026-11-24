package co.xenastudios.core.loader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The libraries a plugin fetches only when a feature asks for one, such as a JDBC driver for
 * a server that has none: SQLite on Fabric, MySQL on a server without Connector/J. Pinned at
 * build time like the startup libraries ({@code onDemand} in the xena.fetched-libraries
 * convention, hashes in gradle/library-pins.txt), listed in {@value #RESOURCE} in the same
 * format as {@link Manifest#RESOURCE}, and kept in the same shared {@link LibraryCache}, so a
 * driver one plugin of the family fetched is there for the others.
 *
 * Nothing here loads a class: {@link #fetch} gives the jar's path, and the caller loads it in a
 * class loader of its own (core-jvm's {@code Drivers}). Fetching downloads, so it's called off
 * the server's threads.
 */
public final class OnDemandLibraries {

    /** Where the list is in the plugin's jar. */
    public static final String RESOURCE = "META-INF/xena/on-demand-libraries.txt";

    private final List<Library> pinned;
    private final LibraryCache cache;

    public OnDemandLibraries(List<Library> pinned, LibraryCache cache) {
        this.pinned = Collections.unmodifiableList(new ArrayList<>(pinned));
        this.cache = cache;
    }

    /** The plugin's on-demand libraries, from its jar through {@code loader}; none if its jar has no list. */
    public static OnDemandLibraries read(ClassLoader loader, LibraryCache cache) throws IOException {
        InputStream in = loader.getResourceAsStream(RESOURCE);
        if (in == null) {
            return new OnDemandLibraries(Collections.<Library>emptyList(), cache);
        }
        try (InputStream stream = in) {
            return new OnDemandLibraries(Manifest.parse(stream), cache);
        }
    }

    /** Every library it can fetch. */
    public List<Library> pinned() {
        return pinned;
    }

    /** The pinned library with this group and artifact, whatever its version, or null if the plugin pins none. */
    public Library find(String group, String artifact) {
        for (Library library : pinned) {
            if (library.group().equals(group) && library.artifact().equals(artifact)) {
                return library;
            }
        }
        return null;
    }

    /**
     * The jar of the pinned library with this group and artifact: from the cache if it's there
     * with the pinned hash, downloaded otherwise.
     *
     * @throws LibraryException when the plugin pins no such library, or it can't be downloaded,
     *     saying what the owner can do
     */
    public Path fetch(String group, String artifact) throws LibraryException {
        Library library = find(group, artifact);
        if (library == null) {
            throw new LibraryException("This plugin's build pins no " + group + ':' + artifact + " to download"
                + (pinned.isEmpty() ? "" : " (it pins " + pinned + ")") + ".");
        }
        return cache.fetch(library).jar();
    }
}
