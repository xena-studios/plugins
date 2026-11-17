package co.xenastudios.xrewind;

import co.xenastudios.core.Setup;
import co.xenastudios.core.XenaPlugin;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.xrewind.config.XRConfig;
import co.xenastudios.xrewind.storage.Storage;

/** xRewind: every change, logged. Any of it, undone. Not final: MockBukkit subclasses it in tests. */
public class XRewind extends XenaPlugin<XRConfig> {

    private Storage storage;

    @Override
    protected ConfigSpec<XRConfig> configSpec() {
        return XRConfig.SPEC;
    }

    @Override
    protected void setup(Setup setup, XRConfig config) {
        storage().sync(config.storage());
    }

    public Storage storage() {
        if (storage == null) {
            storage = new Storage(tasks(), getLogger(), getDataFolder().toPath());
        }
        return storage;
    }
}
