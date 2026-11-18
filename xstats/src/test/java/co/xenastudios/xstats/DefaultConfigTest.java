package co.xenastudios.xstats;

import co.xenastudios.core.config.ConfigLoader;
import co.xenastudios.core.testing.Golden;
import co.xenastudios.xstats.config.XSConfig;
import org.junit.jupiter.api.Test;

/** The docs show the default config straight from this file, so they can't drift from the code. */
class DefaultConfigTest {

    @Test
    void docsShowTheDefaultConfig() throws Exception {
        Golden.check("docs/src/generated/xstats/config.yml", ConfigLoader.defaultText(XSConfig.SPEC));
    }
}
