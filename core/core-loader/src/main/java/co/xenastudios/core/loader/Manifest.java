package co.xenastudios.core.loader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads {@value #RESOURCE}, which the build writes into the plugin's jar (build-logic's
 * {@code PinnedLibraries}): a {@code xena-libraries 1} line, then one
 * {@code group:artifact:version sha256 probe} line per jar. {@code #} starts a comment.
 */
public final class Manifest {

    /** Where the list is in the plugin's jar. */
    public static final String RESOURCE = "META-INF/xena/libraries.txt";

    private static final String HEADER = "xena-libraries 1";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private Manifest() {}

    /** The plugin's libraries, or none if its jar has no list. */
    public static List<Library> read(ClassLoader loader) throws IOException {
        InputStream in = loader.getResourceAsStream(RESOURCE);
        if (in == null) {
            return Collections.emptyList();
        }
        try (InputStream stream = in) {
            return parse(stream);
        }
    }

    public static List<Library> parse(InputStream in) throws IOException {
        List<Library> libraries = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        boolean header = false;
        int number = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            number++;
            int comment = line.indexOf('#');
            String text = (comment < 0 ? line : line.substring(0, comment)).trim();
            if (text.isEmpty()) {
                continue;
            }
            if (!header) {
                if (!text.equals(HEADER)) {
                    throw new IOException(RESOURCE + " starts with \"" + text + "\", not \"" + HEADER
                        + "\": the plugin was built for another version of the loader");
                }
                header = true;
                continue;
            }
            String[] parts = SPACES.split(text);
            String[] gav = parts[0].split(":", -1);
            if (parts.length != 3 || gav.length != 3 || gav[0].isEmpty() || gav[1].isEmpty() || gav[2].isEmpty()
                    || !SHA256.matcher(parts[1]).matches()) {
                throw new IOException(RESOURCE + " line " + number + " isn't \"group:artifact:version sha256 probe\": " + text);
            }
            libraries.add(new Library(gav[0], gav[1], gav[2], parts[1], parts[2]));
        }
        return libraries;
    }
}
