package co.xenastudios.core.loader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The family's library settings, {@code plugins/Xena/libraries.yml} or {@code -D} flags, and the
 * cache they describe, on platforms that have no YAML of their own for the shim to read them with
 * (the proxies): {@code mirror} ({@code -Dxena.libraries.mirror}), one Maven repository or a list,
 * and {@code loader} ({@code -Dxena.libraries.loader}). The file is read as the little YAML it
 * needs: {@code key: value} lines, and {@code key:} followed by {@code - item} lines. Bukkit's
 * shim reads the same file with Bukkit's YAML ({@code LoaderSettings}).
 */
public final class LibrarySettings {

    /** The family's shared folder, beside each plugin's own. */
    public static final String SHARED = "Xena";
    static final String FILE = "libraries.yml";

    private final Map<String, String> values;
    private final Map<String, List<String>> lists;

    private LibrarySettings(Map<String, String> values, Map<String, List<String>> lists) {
        this.values = values;
        this.lists = lists;
    }

    /** {@code plugins/Xena/libraries.yml} in {@code plugins}, read; empty when there's none or it can't be read. */
    public static LibrarySettings read(File plugins) {
        File file = new File(new File(plugins, SHARED), FILE);
        if (!file.isFile()) {
            return parse("");
        }
        try {
            return parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return parse("");
        }
    }

    static LibrarySettings parse(String text) {
        Map<String, String> values = new HashMap<>();
        Map<String, List<String>> lists = new HashMap<>();
        String listing = null;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("- ") && listing != null) {
                lists.get(listing).add(unquote(line.substring(2).trim()));
                continue;
            }
            int colon = line.indexOf(':');
            listing = null;
            if (colon <= 0 || Character.isWhitespace(raw.charAt(0))) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (value.isEmpty()) {
                listing = key;
                lists.put(key, new ArrayList<String>());
            } else {
                values.put(key, unquote(value));
            }
        }
        return new LibrarySettings(values, lists);
    }

    private static String unquote(String value) {
        int comment = value.indexOf(" #");
        if (comment >= 0 && !value.startsWith("\"") && !value.startsWith("'")) {
            value = value.substring(0, comment).trim();
        }
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** The family's cache in {@code plugins/Xena/libraries/}, with the owner's mirrors. */
    public LibraryCache cache(File plugins) {
        List<String> configured = lists.get("mirror");
        if (configured == null && values.get("mirror") != null) {
            configured = Collections.singletonList(values.get("mirror"));
        }
        return cache(plugins, configured);
    }

    /**
     * The family's cache in {@code plugins/Xena/libraries/}: from the mirrors in
     * {@code -Dxena.libraries.mirror} if it's set, else {@code configured} (the file's), else
     * {@link LibraryCache#DEFAULT_MIRRORS}.
     */
    public static LibraryCache cache(File plugins, List<String> configured) {
        File shared = new File(plugins, SHARED);
        String help = "If this server can't reach Maven Central, set mirror: in " + new File(shared, FILE)
            + " to a Maven repository it can reach.";
        return new LibraryCache(new File(shared, "libraries").toPath(), mirrors(configured), help);
    }

    private static List<String> mirrors(List<String> configured) {
        String flag = System.getProperty("xena.libraries.mirror");
        if (flag != null && !flag.trim().isEmpty()) {
            List<String> mirrors = new ArrayList<>();
            Collections.addAll(mirrors, flag.split(","));
            return mirrors;
        }
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        return LibraryCache.DEFAULT_MIRRORS;
    }

    /** How jars are added, as the owner chose ({@code -Dxena.libraries.loader} first), or null for the default. */
    public Strategy strategy(Logger log) {
        String name = System.getProperty("xena.libraries.loader", values.get("loader"));
        try {
            return Strategy.parse(name);
        } catch (IllegalArgumentException e) {
            log.warning("loader: " + e.getMessage() + ", so auto is used.");
            return null;
        }
    }
}
