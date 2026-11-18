package co.xenastudios.xbackup.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A plugin's excludes for its own source never leave out anything else on the server. */
class ScopedExcludesTest {

    @Test
    void excludesStayInsideTheirSource() {
        assertEquals(List.of("plugins/Shop/cache/**", "plugins/Shop/**/*.log", "plugins/Shop/tmp", "plugins/Shop/x"),
            Engine.scoped("plugins/Shop", List.of("plugins/Shop/cache/**", "**/*.log", "/tmp", "x", " ")));
        assertEquals(List.of(), Engine.scoped(null, List.of("**")), "a source outside the server folder");
        assertEquals(List.of(), Engine.scoped("", List.of("**")), "never the whole server");
    }
}
