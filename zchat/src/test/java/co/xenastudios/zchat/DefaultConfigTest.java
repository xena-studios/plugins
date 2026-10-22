package co.xenastudios.zchat;

import co.xenastudios.core.config.ConfigLoader;
import co.xenastudios.core.testing.Golden;
import co.xenastudios.zchat.config.ZConfig;
import org.junit.jupiter.api.Test;

/** The docs show the default config straight from this file, so they can't drift from the code. */
class DefaultConfigTest {

    @Test
    void docsShowTheDefaultConfig() throws Exception {
        Golden.check("docs/src/generated/zchat/config.yml", ConfigLoader.defaultText(ZConfig.SPEC));
    }
}
