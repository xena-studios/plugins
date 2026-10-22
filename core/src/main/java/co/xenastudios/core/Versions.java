package co.xenastudios.core;

/** Compares versions like {@code 2.0.0}, {@code 2.1} and {@code 2.0.0-beta.2}. */
final class Versions {

    private Versions() {}

    static boolean isPrerelease(String version) {
        return version.indexOf('-') >= 0;
    }

    /** Negative if {@code a} is older than {@code b}, positive if newer, 0 if the same. */
    static int compare(String a, String b) {
        String[] aParts = a.split("-", 2);
        String[] bParts = b.split("-", 2);
        int release = compareDotted(aParts[0], bParts[0]);
        if (release != 0) {
            return release;
        }
        // 2.0.0-beta.1 comes before 2.0.0.
        if (aParts.length != bParts.length) {
            return aParts.length == 1 ? 1 : -1;
        }
        return aParts.length == 1 ? 0 : compareDotted(aParts[1], bParts[1]);
    }

    private static int compareDotted(String a, String b) {
        String[] aParts = a.split("\\.");
        String[] bParts = b.split("\\.");
        for (int i = 0; i < Math.max(aParts.length, bParts.length); i++) {
            String x = i < aParts.length ? aParts[i] : "0";
            String y = i < bParts.length ? bParts[i] : "0";
            int result = isNumber(x) && isNumber(y)
                ? Long.compare(Long.parseLong(x), Long.parseLong(y))
                : x.compareTo(y);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static boolean isNumber(String part) {
        return !part.isEmpty() && part.length() < 18 && part.chars().allMatch(Character::isDigit);
    }
}
