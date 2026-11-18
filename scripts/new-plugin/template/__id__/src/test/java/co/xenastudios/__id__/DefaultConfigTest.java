package co.xenastudios.{{id}};

import co.xenastudios.core.config.ConfigLoader;
import co.xenastudios.core.testing.Golden;
import co.xenastudios.{{id}}.config.{{Class}}Config;
import org.junit.jupiter.api.Test;

/** The docs show the default config straight from this file, so they can't drift from the code. */
class DefaultConfigTest {

    @Test
    void docsShowTheDefaultConfig() throws Exception {
        Golden.check("docs/src/generated/{{id}}/config.yml", ConfigLoader.defaultText({{Class}}Config.SPEC));
    }
}
