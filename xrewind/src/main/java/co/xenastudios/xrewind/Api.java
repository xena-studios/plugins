package co.xenastudios.xrewind;

import co.xenastudios.core.Module;
import co.xenastudios.core.db.Database;
import co.xenastudios.xrewind.api.Actor;
import co.xenastudios.xrewind.api.Entry;
import co.xenastudios.xrewind.api.Plan;
import co.xenastudios.xrewind.api.Query;
import co.xenastudios.xrewind.api.RollbackResult;
import co.xenastudios.xrewind.api.XRewindApi;
import co.xenastudios.xrewind.api.event.LookupEvent;
import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.query.ApiTypes;
import co.xenastudios.xrewind.query.Lookup;
import co.xenastudios.xrewind.rollback.ApiPlan;
import co.xenastudios.xrewind.storage.Clock;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;

/**
 * xRewind's developer API, in the services manager while xRewind is enabled. Logging goes
 * through the listeners' queue; lookups and rollbacks through the same background tasks,
 * tick budget and lock as the commands, so a plugin can't make xRewind heavier than its
 * own commands are.
 */
final class Api implements XRewindApi, Module {

    /** The longest the writer is held for a backup before it carries on by itself. */
    private static final long MAX_HOLD_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final XRewind xrewind;
    private final BlockData air = Material.AIR.createBlockData();

    Api(XRewind xrewind) {
        this.xrewind = xrewind;
    }

    @Override
    public void enable() {
        xrewind.getServer().getServicesManager().register(XRewindApi.class, this, xrewind, ServicePriority.Normal);
    }

    @Override
    public void disable() {
        xrewind.getServer().getServicesManager().unregister(XRewindApi.class, this);
    }

    @Override
    public void logPlacement(Actor actor, Location location, BlockData placed) {
        block(actor, location, Actions.PLACE, air, placed.clone());
    }

    @Override
    public void logRemoval(Actor actor, Location location, BlockData removed) {
        block(actor, location, Actions.BREAK, removed.clone(), air);
    }

    @Override
    public void logChange(Actor actor, Location location, BlockData before, BlockData after) {
        block(actor, location, after.getMaterial().isAir() ? Actions.BREAK : Actions.PLACE, before.clone(), after.clone());
    }

    private void block(Actor actor, Location location, byte action, BlockData before, BlockData after) {
        xrewind.queue().add(new co.xenastudios.xrewind.log.Entry.BlockChange(Clock.now(), actor(actor), world(location),
            location.getBlockX(), location.getBlockY(), location.getBlockZ(), action, before, after));
    }

    @Override
    public void logContainerChange(Actor actor, Location location, ItemStack item, int amount) {
        if (amount == 0) {
            throw new IllegalArgumentException("Log a container change of at least one item");
        }
        xrewind.queue().add(new co.xenastudios.xrewind.log.Entry.ContainerChange(Clock.now(), actor(actor), world(location),
            location.getBlockX(), location.getBlockY(), location.getBlockZ(), amount > 0 ? Actions.ADD : Actions.REMOVE,
            item.asOne(), Math.abs(amount)));
    }

    private static co.xenastudios.xrewind.log.Actor actor(Actor actor) {
        return new co.xenastudios.xrewind.log.Actor(actor.uuid(), actor.name());
    }

    private static String world(Location location) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("The location must be in a world");
        }
        return world.getName();
    }

    @Override
    public CompletableFuture<List<Entry>> lookup(Query query) {
        Database database = xrewind.storage().database();
        if (database == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("xRewind's database isn't ready yet"));
        }
        co.xenastudios.xrewind.query.Query asked = ApiTypes.query(query);
        Lookup.Center center = ApiTypes.center(query);
        CompletableFuture<List<Entry>> future = new CompletableFuture<>();
        // The same background tasks as /xr lookup.
        xrewind.tasks().async(() -> {
            try {
                if (LookupEvent.getHandlerList().getRegisteredListeners().length > 0) {
                    new LookupEvent(null, query).callEvent();
                }
                xrewind.writer().flush(1_000);
                Lookup.Result result = Lookup.page(database, asked, center, 1, query.limit());
                future.complete(result.rows().stream().map(ApiTypes::entry).toList());
            } catch (SQLException | RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Plan> planRollback(Query query) {
        return plan(query, false);
    }

    @Override
    public CompletableFuture<Plan> planRestore(Query query) {
        return plan(query, true);
    }

    private CompletableFuture<Plan> plan(Query query, boolean restore) {
        if (query.time().isEmpty()) {
            throw new IllegalArgumentException("A rollback or restore needs a time, as /xr rollback does: Query.builder().time(...)");
        }
        return rollbacks().plan(ApiTypes.query(query), ApiTypes.center(query), restore).thenApply(ApiPlan::new);
    }

    @Override
    public CompletableFuture<RollbackResult> apply(Plan plan) {
        if (!(Objects.requireNonNull(plan, "plan") instanceof ApiPlan ours)) {
            throw new IllegalArgumentException("Only plans from xRewind's API can be applied");
        }
        return rollbacks().apply(ours.plan());
    }

    private co.xenastudios.xrewind.rollback.Rollbacks rollbacks() {
        co.xenastudios.xrewind.rollback.Rollbacks rollbacks = xrewind.rollbacks();
        if (rollbacks == null) {
            throw new IllegalStateException("xRewind didn't finish starting; the console says why");
        }
        return rollbacks;
    }

    @Override
    public CompletableFuture<Boolean> flush() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        xrewind.tasks().async(() -> {
            xrewind.writer().flush(10_000);
            future.complete(xrewind.queue().size() == 0 && xrewind.writer().problem() == null);
        });
        return future;
    }

    @Override
    public CompletableFuture<Void> pause() {
        return xrewind.writer().hold(MAX_HOLD_NANOS);
    }

    @Override
    public void resume() {
        xrewind.writer().resume();
    }
}
