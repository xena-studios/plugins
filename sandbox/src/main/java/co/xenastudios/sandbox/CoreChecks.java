package co.xenastudios.sandbox;

import static co.xenastudios.sandbox.Checks.require;

import co.xenastudios.core.player.Profiles;
import co.xenastudios.core.player.Warmups;
import co.xenastudios.core.player.Warmups.Cancel;
import co.xenastudios.core.player.Warmups.Warmup;
import co.xenastudios.core.task.Tasks;
import co.xenastudios.core.text.Message;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Pig;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;

/** Live-server checks for the core's player helpers. */
final class CoreChecks {

    private CoreChecks() {}

    /**
     * A pig that can't move by itself stands in for a player: a warmup that runs out, then
     * one cancelled by damage, one by moving and one by the pig being removed. All on the
     * pig's region thread, as on Folia.
     */
    static CompletableFuture<?> warmups(Plugin plugin) {
        World world = Bukkit.getWorlds().getFirst();
        Location at = world.getSpawnLocation().toBlockLocation().add(0.5, 3, 0.5);
        Executor region = work -> Bukkit.getRegionScheduler().execute(plugin, at, work);
        Warmups warmups = new Warmups(plugin);
        Pig[] pig = new Pig[1];
        return world.getChunkAtAsync(at).thenApplyAsync(chunk -> {
            world.addPluginChunkTicket(chunk.getX(), chunk.getZ(), plugin);
            pig[0] = world.spawn(at, Pig.class, spawned -> {
                spawned.setAI(false);
                spawned.setGravity(false);
                spawned.setSilent(true);
                spawned.setPersistent(false);
            });
            CompletableFuture<Void> finished = new CompletableFuture<>();
            warmups.start(pig[0], Warmup.<Pig>of(1, p -> finished.complete(null))
                .onCancel((p, reason) -> finished.completeExceptionally(new AssertionError("cancelled: " + reason)))
                .countdown(Message.of("<seconds>s"))
                .cancelOnDamage(true));
            require(warmups.waiting(pig[0].getUniqueId()), "not waiting");
            require(warmups.listening() && damageListened(plugin), "no damage listener while a warmup waits");
            return finished;
        }, region).thenCompose(finished -> finished).thenApplyAsync(done -> {
            require(!warmups.listening() && !damageListened(plugin), "the damage listener stayed after the warmup finished");

            List<Cancel> reasons = new ArrayList<>();
            warmups.start(pig[0], Warmup.<Pig>of(10, p -> reasons.add(null)).onCancel((p, reason) -> reasons.add(reason))
                .cancelOnDamage(true));
            pig[0].damage(1);
            require(reasons.equals(List.of(Cancel.DAMAGED)), "damage gave " + reasons);
            require(!damageListened(plugin), "the damage listener stayed after damage cancelled it");

            CompletableFuture<Cancel> moved = new CompletableFuture<>();
            warmups.start(pig[0], Warmup.<Pig>of(10, p -> moved.complete(null)).onCancel((p, reason) -> moved.complete(reason)));
            require(!damageListened(plugin), "a warmup damage doesn't cancel registered a listener");
            pig[0].teleportAsync(pig[0].getLocation().add(2, 0, 0));
            return moved;
        }, region).thenCompose(moved -> moved).thenApplyAsync(reason -> {
            require(reason == Cancel.MOVED, "moving gave " + reason);
            CompletableFuture<Cancel> gone = new CompletableFuture<>();
            warmups.start(pig[0], Warmup.<Pig>of(10, p -> gone.complete(null)).onCancel((p, why) -> gone.complete(why)));
            pig[0].remove();
            return gone;
        }, region).thenCompose(gone -> gone).thenAccept(reason -> {
            require(reason == Cancel.GONE, "removal gave " + reason);
            require(!warmups.waiting(pig[0].getUniqueId()), "still waiting");
        }).whenCompleteAsync((result, error) -> {
            warmups.clear();
            if (pig[0] != null) {
                pig[0].remove();
            }
            world.removePluginChunkTicket(at.getBlockX() >> 4, at.getBlockZ() >> 4, plugin);
        }, region);
    }

    private static boolean damageListened(Plugin plugin) {
        return Arrays.stream(EntityDamageEvent.getHandlerList().getRegisteredListeners())
            .anyMatch(listener -> listener.getPlugin() == plugin);
    }

    /** Only from memory: anything further would ask Mojang, which CI shouldn't. */
    static void profiles(Plugin plugin) {
        Profiles profiles = new Profiles(new Tasks(plugin));
        require(profiles.cached("Nobody_Here_1").isEmpty(), "found a player who never joined");
        require(profiles.byName("not a name!").join().isEmpty(), "found an invalid name");
    }
}
