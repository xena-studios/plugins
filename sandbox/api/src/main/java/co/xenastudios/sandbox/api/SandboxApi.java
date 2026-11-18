package co.xenastudios.sandbox.api;

import org.bukkit.Bukkit;

/**
 * The sandbox's API. It exists so CI can check that a plugin's API, shipped inside its
 * jar on the {@code xena.api} convention, can be used by other plugins: an ordinary
 * {@code plugin.yml} plugin and a Paper plugin.
 */
public interface SandboxApi {

    /**
     * The sandbox's API, from Bukkit's services manager.
     *
     * @return the API
     * @throws IllegalStateException if the sandbox isn't enabled
     */
    static SandboxApi get() {
        SandboxApi api = Bukkit.getServicesManager().load(SandboxApi.class);
        if (api == null) {
            throw new IllegalStateException("The sandbox isn't enabled.");
        }
        return api;
    }

    /**
     * A fixed answer, so a caller can tell the call reached the sandbox.
     *
     * @return {@code "OK"}
     */
    String ping();
}
