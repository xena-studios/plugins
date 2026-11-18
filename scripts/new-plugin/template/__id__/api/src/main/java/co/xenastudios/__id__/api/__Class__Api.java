package co.xenastudios.{{id}}.api;

import org.bukkit.Bukkit;

/**
 * {{display}}'s developer API.
 *
 * <p>Get it with {@link #get()}, or from Bukkit's services manager. It's registered
 * while {{display}} is enabled.
 *
 * <p>Every method says how it runs: either it may be called from any thread, answers from
 * memory and returns immediately, or it returns a {@link java.util.concurrent.CompletableFuture}
 * that completes off the calling thread. Nothing here ever reads the database on the
 * calling thread.
 *
 * @since 1.0.0
 */
public interface {{Class}}Api {

    /**
     * {{display}}'s API, from Bukkit's services manager.
     *
     * @return the API
     * @throws IllegalStateException if {{display}} isn't installed and enabled
     * @since 1.0.0
     */
    static {{Class}}Api get() {
        {{Class}}Api api = Bukkit.getServicesManager().load({{Class}}Api.class);
        if (api == null) {
            throw new IllegalStateException("{{display}} isn't enabled. Add it to depend (or softdepend and check first).");
        }
        return api;
    }

    /**
     * {{display}}'s version, such as {@code 1.0.0}. From memory, on any thread.
     *
     * @return the version
     * @since 1.0.0
     */
    String version();
}
