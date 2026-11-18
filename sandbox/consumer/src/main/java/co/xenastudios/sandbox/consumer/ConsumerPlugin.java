package co.xenastudios.sandbox.consumer;

import co.xenastudios.sandbox.api.SandboxApi;
import org.bukkit.plugin.java.JavaPlugin;

/** Never released. An ordinary plugin.yml plugin using the sandbox's API. */
public final class ConsumerPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("API CONSUMER " + SandboxApi.get().ping());
    }
}
