package co.xenastudios.xutilities;

import co.xenastudios.core.config.ConfigLoader;
import co.xenastudios.core.testing.Golden;
import co.xenastudios.xutilities.config.XConfig;
import org.junit.jupiter.api.Test;

/** The docs show the default config straight from this file, so they can't drift from the code. */
class DefaultConfigTest {

    @Test
    void docsShowTheDefaultConfig() throws Exception {
        Golden.check("docs/src/generated/xutilities/config.yml", ConfigLoader.defaultText(XConfig.SPEC));
    }
}
