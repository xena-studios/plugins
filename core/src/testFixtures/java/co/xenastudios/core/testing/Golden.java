package co.xenastudios.core.testing;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Golden files: text the build generates and commits, such as each plugin's default
 * config for the docs. A test fails when the generated text drifts from the file;
 * {@code ./gradlew test -PupdateGolden} rewrites the files.
 */
public final class Golden {

    private Golden() {}

    /** Checks {@code actual} against a file, relative to the repository root. */
    public static void check(String relativePath, String actual) throws IOException {
        Path file = Path.of(System.getProperty("xena.repoRoot", ".")).resolve(relativePath);
        if (Boolean.getBoolean("xena.updateGolden")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, actual, StandardCharsets.UTF_8);
            return;
        }
        String expected = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        if (!actual.equals(expected)) {
            fail(relativePath + " is out of date. If the change is intended, run ./gradlew test -PupdateGolden and commit the file.");
        }
    }
}
