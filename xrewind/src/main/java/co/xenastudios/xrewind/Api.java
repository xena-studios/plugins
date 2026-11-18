package co.xenastudios.xrewind;

import co.xenastudios.core.Module;
import co.xenastudios.core.db.Database;
import co.xenastudios.xrewind.api.Actor;
import co.xenastudios.xrewind.api.Entry;
import co.xenastudios.xrewind.api.Plan;
import co.xenastudios.xrewind.api.Query;
import co.xenastudios.xrewind.api.RollbackResult;
import co.xenastudios.xrewind.api.WriterHold;
import co.xenastudios.xrewind.api.XRewindApi;
import co.xenastudios.xrewind.api.event.LookupEvent;
import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.log.WriteGate;
import co.xenastudios.xrewind.query.ApiTypes;
import co.xenastudios.xrewind.query.Lookup;
import co.xenastudios.xrewind.rollback.ApiPlan;
import co.xenastudios.xrewind.rollback.Rollbacks;
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

    /** The longest one hold keeps the writer waiting before it carries on by itself. */
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
        Objects.requireNonNull(query, "query");
        Database database = xrewind.storage().database();
        if (database == null) {
            return notReady();
        }
        co.xenastudios.xrewind.query.Query asked = ApiTypes.query(query);
        Lookup.Center center = ApiTypes.center(query);
        CompletableFuture<List<Entry>> future = new CompletableFuture<>();
        // The same background tasks as /xr lookup.
        async(future, () -> {
            try {
                // What runs, as the commands describe theirs. Built only when listened for.
                if (LookupEvent.getHandlerList().getRegisteredListeners().length > 0) {
                    new LookupEvent(null, ApiTypes.api(asked, center, query.limit()), 1).callEvent();
                }
                xrewind.writer().flush(1_000);
                // No count of every match, as a command's page shows: nothing would read it.
                future.complete(Lookup.newest(database, asked, center, query.limit()).stream().map(ApiTypes::entry).toList());
            } catch (SQLException | RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private static <T> CompletableFuture<T> notReady() {
        return CompletableFuture.failedFuture(new IllegalStateException("xRewind's database isn't ready yet"));
    }

    // On xRewind's background tasks; fails the future if the scheduler refuses, as it does
    // for a plugin being disabled.
    private void async(CompletableFuture<?> future, Runnable work) {
        try {
            xrewind.tasks().async(work);
        } catch (RuntimeException e) {
            future.completeExceptionally(new IllegalStateException("xRewind is stopping", e));
        }
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
        if (Objects.requireNonNull(query, "query").time().isEmpty()) {
            throw new IllegalArgumentException("A rollback or restore needs a time, as /xr rollback does: Query.builder().time(...)");
        }
        Rollbacks rollbacks = xrewind.rollbacks();
        if (rollbacks == null) {
            return notStarted();
        }
        return rollbacks.plan(ApiTypes.query(query), ApiTypes.center(query), restore).thenApply(ApiPlan::fresh);
    }

    @Override
    public CompletableFuture<RollbackResult> apply(Plan plan) {
        if (!(Objects.requireNonNull(plan, "plan") instanceof ApiPlan ours)) {
            throw new IllegalArgumentException("Only plans from xRewind's API can be applied");
        }
        Rollbacks rollbacks = xrewind.rollbacks();
        if (rollbacks == null) {
            return notStarted();
        }
        if (!ours.claim()) {
            throw new IllegalStateException("This plan was applied already. Plan again, or apply the result's undo plan.");
        }
        return rollbacks.apply(ours);
    }

    private static <T> CompletableFuture<T> notStarted() {
        return CompletableFuture.failedFuture(new IllegalStateException("xRewind didn't finish starting; the console says why"));
    }

    @Override
    public CompletableFuture<Boolean> flush() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        async(future, () -> future.complete(xrewind.writer().flush(10_000)));
        return future;
    }

    @Override
    public CompletableFuture<WriterHold> pause() {
        WriteGate.Hold hold = xrewind.writer().gate().hold(MAX_HOLD_NANOS);
        WriterHold token = new Token(hold);
        // Ready on whichever thread finished the last write: handed over to the background
        // tasks, so a caller's thenRun never runs on the writer's thread.
        return hold.ready().thenApplyAsync(ignored -> token, this::onBackground);
    }

    // An Executor for completing futures; inline if the scheduler refuses (xRewind stopping).
    private void onBackground(Runnable work) {
        try {
            xrewind.tasks().async(work);
        } catch (RuntimeException e) {
            work.run();
        }
    }

    /** One plugin's hold on the writer. */
    private record Token(WriteGate.Hold hold) implements WriterHold {

        @Override
        public void release() {
            hold.release();
        }

        @Override
        public boolean isHeld() {
            return hold.held();
        }

        @Override
        public void close() {
            hold.release();
        }
    }
}
