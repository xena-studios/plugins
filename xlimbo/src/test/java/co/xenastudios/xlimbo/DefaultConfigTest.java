package co.xenastudios.xlimbo;

import co.xenastudios.core.config.ConfigLoader;
import co.xenastudios.core.testing.Golden;
import co.xenastudios.xlimbo.config.XLConfig;
import org.junit.jupiter.api.Test;

/** The docs show the default config straight from this file, so they can't drift from the code. */
class DefaultConfigTest {

    @Test
    void docsShowTheDefaultConfig() throws Exception {
        Golden.check("docs/src/generated/xlimbo/config.yml", ConfigLoader.defaultText(XLConfig.SPEC));
    }
}
