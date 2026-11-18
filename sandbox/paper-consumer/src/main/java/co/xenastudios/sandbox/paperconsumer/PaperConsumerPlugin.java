package co.xenastudios.sandbox.paperconsumer;

import co.xenastudios.sandbox.api.SandboxApi;
import org.bukkit.plugin.java.JavaPlugin;

/** Never released. A Paper plugin using the sandbox's API. */
public final class PaperConsumerPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("PAPER API CONSUMER " + SandboxApi.get().ping());
    }
}
