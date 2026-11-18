package co.xenastudios.sandbox;

import co.xenastudios.sandbox.api.SandboxApi;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Never released. Boots on a real server in CI, and `/sandbox selftest` runs
 * checks that need a live server, logging SELFTEST PASS or SELFTEST FAIL.
 */
public final class SandboxPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // For the consumer plugins, which check that other plugins can use an API.
        SandboxApi api = () -> "OK";
        getServer().getServicesManager().register(SandboxApi.class, api, this, ServicePriority.Normal);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register(
                Commands.literal("sandbox")
                    .requires(source -> source.getSender().hasPermission("sandbox.admin"))
                    .then(Commands.literal("selftest").executes(context -> {
                        // Commands dispatched while another is running are queued until it
                        // finishes, so run the checks on the next tick, outside this one.
                        getServer().getGlobalRegionScheduler().run(this, task -> selftest());
                        return 1;
                    }))
                    .build(),
                "Runs the sandbox's live-server checks.",
                List.of()));
    }

    private void selftest() {
        next(Checks.all(this), 0, new ArrayList<>());
    }

    /** Runs the checks one after another; a check that waits for the server finishes before the next starts. */
    private void next(List<Check> checks, int index, List<String> failures) {
        if (index == checks.size()) {
            getLogger().info(failures.isEmpty() ? "SELFTEST PASS" : "SELFTEST FAIL " + failures);
            return;
        }
        Check check = checks.get(index);
        long start = System.nanoTime();
        CompletableFuture<?> done;
        try {
            done = check.start();
        } catch (Throwable t) {
            done = CompletableFuture.failedFuture(t);
        }
        done.orTimeout(30, TimeUnit.SECONDS).whenComplete((result, error) ->
            getServer().getGlobalRegionScheduler().execute(this, () -> {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                if (cause == null) {
                    getLogger().info("PASS %s (%.2f ms)".formatted(check.name(), (System.nanoTime() - start) / 1e6));
                } else {
                    failures.add(check.name());
                    getLogger().severe("FAIL %s: %s".formatted(check.name(), cause));
                }
                next(checks, index + 1, failures);
            }));
    }

    /** One live-server check. Fails by throwing, or by failing the future it returns. */
    interface Check {
        String name();

        CompletableFuture<?> start() throws Exception;
    }
}
