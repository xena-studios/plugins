package co.xenastudios.sandbox;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Never released. Boots on a real server in CI, and `/sandbox selftest` runs
 * checks that need a live server, logging SELFTEST PASS or SELFTEST FAIL.
 */
public final class SandboxPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
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
        List<String> failures = new ArrayList<>();
        for (Check check : Checks.all(this)) {
            long start = System.nanoTime();
            try {
                check.run();
                getLogger().info("PASS %s (%.2f ms)".formatted(check.name(), (System.nanoTime() - start) / 1e6));
            } catch (Throwable t) {
                failures.add(check.name());
                getLogger().severe("FAIL %s: %s".formatted(check.name(), t));
            }
        }
        getLogger().info(failures.isEmpty() ? "SELFTEST PASS" : "SELFTEST FAIL " + failures);
    }

    /** One live-server check. Throws to fail. */
    interface Check {
        String name();

        void run() throws Exception;
    }
}
