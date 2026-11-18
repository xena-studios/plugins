package co.xenastudios.xbackup.backup;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which paths below the server folder a backup takes: the sources it includes, minus the
 * owner's exclude patterns and xBackup's own folders. Paths are relative, with {@code /}.
 */
final class Selection {

    enum Choice {
        /** Back it up, and everything in it. */
        INCLUDE,
        /** A folder on the way to something included: look inside, take only that. */
        DESCEND,
        SKIP
    }

    private final List<String> includes;
    private final List<Pattern> excludes = new ArrayList<>();
    private final Set<Path> skip;
    // The skipped paths' real places, to find them again through a source that is a link.
    private final Set<Path> realSkip = new java.util.HashSet<>();

    /**
     * @param includes relative paths to take; "" for everything
     * @param excludes glob patterns matched against relative paths, such as {@code logs/**}
     * @param skip absolute paths never taken: the repository, the restore folders
     */
    Selection(List<String> includes, List<String> excludes, Set<Path> skip) {
        this.includes = List.copyOf(includes);
        for (String pattern : excludes) {
            String glob = pattern.strip().replace('\\', '/');
            if (glob.isEmpty()) {
                continue;
            }
            this.excludes.add(glob(glob));
        }
        this.skip = new java.util.HashSet<>();
        for (Path path : skip) {
            Path absolute = path.toAbsolutePath().normalize();
            this.skip.add(absolute);
            try {
                realSkip.add(absolute.toRealPath());
            } catch (java.io.IOException e) {
                realSkip.add(absolute);
            }
        }
    }

    /** Whether the path is one of the sources itself (not just inside one). */
    boolean isSource(String path) {
        return includes.contains(path);
    }

    /**
     * A source reached through a link: anything skipped whose real place is inside the
     * link's target is skipped under the link's path too, such as the repository inside a
     * plugins folder that is a link.
     */
    void alias(Path link, Path target) {
        Path logical = link.toAbsolutePath().normalize();
        for (Path real : realSkip) {
            if (real.startsWith(target)) {
                skip.add(logical.resolve(target.relativize(real).toString()).normalize());
            }
        }
    }

    /**
     * A glob as a regex over {@code /}-separated paths, the same on every system: {@code *}
     * is anything but {@code /}, {@code **} is anything, {@code ?} one character, and
     * {@code {a,b}} either. {@code **}{@code /} also matches nothing, so {@code **}{@code /x}
     * matches {@code x} at the top too.
     */
    static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        int braces = 0;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                i++;
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                    i++;
                    regex.append("(?:.*/)?");
                } else {
                    regex.append(".*");
                }
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else if (c == '{') {
                braces++;
                regex.append("(?:");
            } else if (c == '}' && braces > 0) {
                braces--;
                regex.append(')');
            } else if (c == ',' && braces > 0) {
                regex.append('|');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (braces != 0) {
            throw new IllegalArgumentException("\"" + glob + "\" has a { without a }");
        }
        return Pattern.compile(regex.toString());
    }

    Choice choose(String path, Path absolute) {
        if (skip.contains(absolute.toAbsolutePath().normalize()) || excluded(path)) {
            return Choice.SKIP;
        }
        Choice choice = Choice.SKIP;
        for (String include : includes) {
            if (include.isEmpty() || path.equals(include) || path.startsWith(include + "/")) {
                return Choice.INCLUDE;
            }
            if (include.startsWith(path + "/")) {
                choice = Choice.DESCEND;
            }
        }
        return choice;
    }

    boolean excluded(String path) {
        for (Pattern pattern : excludes) {
            if (pattern.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }
}
