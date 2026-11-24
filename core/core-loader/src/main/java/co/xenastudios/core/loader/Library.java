package co.xenastudios.core.loader;

/**
 * A jar on Maven Central the plugin needs at runtime, pinned by version and SHA-256 when the
 * plugin was built, with a class of its own to look for: when that class is already visible,
 * the server (or another plugin) has the library and it isn't downloaded.
 */
public final class Library {

    private final String group;
    private final String artifact;
    private final String version;
    private final String sha256;
    private final String probe;

    public Library(String group, String artifact, String version, String sha256, String probe) {
        this.group = group;
        this.artifact = artifact;
        this.version = version;
        this.sha256 = sha256;
        this.probe = probe;
    }

    public String group() {
        return group;
    }

    public String artifact() {
        return artifact;
    }

    public String version() {
        return version;
    }

    /** The jar's SHA-256, lowercase hex. */
    public String sha256() {
        return sha256;
    }

    /** A class in the jar, by its binary name. */
    public String probe() {
        return probe;
    }

    /** {@code group:artifact:version}. */
    public String coordinates() {
        return group + ':' + artifact + ':' + version;
    }

    public String fileName() {
        return artifact + '-' + version + ".jar";
    }

    /** Where the jar is in a Maven repository, and in the shared cache: {@code org/jetbrains/kotlin/...}. */
    public String path() {
        return group.replace('.', '/') + '/' + artifact + '/' + version + '/' + fileName();
    }

    @Override
    public String toString() {
        return artifact + ' ' + version;
    }
}
