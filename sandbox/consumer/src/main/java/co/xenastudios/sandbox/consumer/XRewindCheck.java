package co.xenastudios.sandbox.consumer;

import co.xenastudios.xrewind.api.Actor;
import co.xenastudios.xrewind.api.Query;
import co.xenastudios.xrewind.api.XRewindApi;
import java.time.Duration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * xRewind's API on a real server, Paper or Folia: a block is removed and logged by a
 * plugin, looked up, and rolled back, through the threads a real server uses.
 */
final class XRewindCheck {

    private XRewindCheck() {}

    static void run(Plugin plugin) {
        XRewindApi api = XRewindApi.get();
        World world = Bukkit.getWorlds().getFirst();
        Location at = new Location(world, 200, 70, 200);
        Actor sandbox = Actor.source("sandbox");
        world.getChunkAtAsync(at).thenAccept(chunk -> Bukkit.getRegionScheduler().execute(plugin, at, () -> {
            at.getBlock().setType(Material.AIR);
            api.logRemoval(sandbox, at, Material.GOLD_BLOCK.createBlockData());
            api.lookup(Query.builder().users("#sandbox").world(world.getName()).build())
                .thenCompose(entries -> {
                    plugin.getLogger().info("XREWIND LOOKUP " + entries.size());
                    return api.planRollback(Query.builder().users("#sandbox").time(Duration.ofHours(1)).radius(at, 0).build());
                })
                .thenCompose(api::apply)
                .thenAccept(result -> Bukkit.getRegionScheduler().execute(plugin, at, () -> plugin.getLogger().info(
                    "XREWIND API " + (at.getBlock().getType() == Material.GOLD_BLOCK ? "OK" : "FAILED") + " " + result.applied())))
                .exceptionally(error -> {
                    plugin.getLogger().info("XREWIND API FAILED " + error);
                    return null;
                });
        }));
    }
}
