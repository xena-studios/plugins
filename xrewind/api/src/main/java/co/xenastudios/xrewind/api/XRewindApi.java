package co.xenastudios.xrewind.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

/**
 * xRewind's developer API: log changes your plugin makes, look up the history, roll back
 * and restore, and hold the writer for a backup. Like CoreProtect's API, with xRewind's
 * threading.
 *
 * <p>Get it with {@link #get()}, or from Bukkit's services manager. It's registered
 * while xRewind is enabled. Logging may be called from any thread and costs about as
 * much as xRewind's own listeners (a queued record). Everything that reads the database
 * returns a {@link CompletableFuture} that completes off the server's threads: never
 * call {@code join()} or {@code get()} on it from a server thread. Lookups and rollbacks
 * use the same background thread, per-tick budget and one-at-a-time rule as
 * xRewind's commands.
 *
 * @since 1.1.0
 */
public interface XRewindApi {

    /**
     * xRewind's API, from Bukkit's services manager.
     *
     * @return the API
     * @throws IllegalStateException if xRewind isn't installed and enabled
     * @since 1.1.0
     */
    static XRewindApi get() {
        XRewindApi api = Bukkit.getServicesManager().load(XRewindApi.class);
        if (api == null) {
            throw new IllegalStateException("xRewind isn't enabled. Add it to depend (or softdepend and check first).");
        }
        return api;
    }

    /**
     * Logs a block placed, where there was air. Any thread: queued for xRewind's writer,
     * never blocks. Not logged in a world xRewind is told to leave out.
     *
     * @param actor who placed it
     * @param location the block, in a loaded world
     * @param placed the block placed; copied
     * @since 1.1.0
     */
    void logPlacement(Actor actor, Location location, BlockData placed);

    /**
     * Logs a block removed, leaving air. Any thread, as {@link #logPlacement}. What the
     * block held (a chest's items, a sign's text) isn't logged; log a container's items
     * with {@link #logContainerChange} first if they matter.
     *
     * @param actor who removed it
     * @param location the block, in a loaded world
     * @param removed the block as it was; copied
     * @since 1.1.0
     */
    void logRemoval(Actor actor, Location location, BlockData removed);

    /**
     * Logs a block changed from one state to another, such as a plot reset. It's logged as
     * a removal when {@code after} is air, and a placement otherwise. Any thread, as
     * {@link #logPlacement}.
     *
     * @param actor who changed it
     * @param location the block, in a loaded world
     * @param before the block as it was; copied
     * @param after the block as it is now; copied
     * @since 1.1.0
     */
    void logChange(Actor actor, Location location, BlockData before, BlockData after);

    /**
     * Logs items put into or taken out of a container. Any thread, as
     * {@link #logPlacement}.
     *
     * @param actor who moved them
     * @param location the container, in a loaded world
     * @param item the item; its amount is ignored, and it's copied
     * @param amount how many were put in; negative for taken out; not 0
     * @since 1.1.0
     */
    void logContainerChange(Actor actor, Location location, ItemStack item, int amount);

    /**
     * Looks up the changes matching a query, newest first, up to its
     * {@link Query#limit() limit}. Anything logged before the call is included. Fires
     * {@link co.xenastudios.xrewind.api.event.LookupEvent}.
     *
     * @param query what to look for
     * @return the entries; completes off the server's threads, or fails if the database
     *     isn't ready or can't be read
     * @since 1.1.0
     */
    CompletableFuture<List<Entry>> lookup(Query query);

    /**
     * Works out what rolling back the matching block and container changes would do,
     * without changing anything: a preview to show, or to {@link #apply(Plan) apply}.
     *
     * @param query what to roll back; it needs a {@link Query.Builder#time time}
     * @return the plan; completes off the server's threads
     * @throws IllegalArgumentException if the query has no time
     * @since 1.1.0
     */
    CompletableFuture<Plan> planRollback(Query query);

    /**
     * As {@link #planRollback}, for a restore: making rolled-back changes again.
     *
     * @param query what to restore; it needs a {@link Query.Builder#time time}
     * @return the plan; completes off the server's threads
     * @throws IllegalArgumentException if the query has no time
     * @since 1.1.0
     */
    CompletableFuture<Plan> planRestore(Query query);

    /**
     * Applies a plan, a few milliseconds of each tick ({@code rollback.ms-per-tick}) on
     * the right threads, as {@code /xr rollback} does. Only one rollback, restore or undo
     * runs at a time, xRewind's commands included. Fires
     * {@link co.xenastudios.xrewind.api.event.RollbackEvent} first and
     * {@link co.xenastudios.xrewind.api.event.RollbackFinishedEvent} after.
     *
     * <p>To undo it, apply the result's {@link RollbackResult#undo() undo plan}.
     *
     * @param plan a plan from this API, or a result's undo plan
     * @return what it did; completes once every change is made. Fails with an
     *     {@link IllegalStateException} if another rollback is running, and with a
     *     {@link java.util.concurrent.CancellationException} if a listener cancelled it
     * @since 1.1.0
     */
    CompletableFuture<RollbackResult> apply(Plan plan);

    /**
     * Writes everything logged so far. Any thread.
     *
     * @return completes off the server's threads once it's written (true), or after 10
     *     seconds or if the database can't be reached (false)
     * @since 1.1.0
     */
    CompletableFuture<Boolean> flush();

    /**
     * Holds xRewind's writer between batches, so nothing is written to the database until
     * {@link #resume()}: for a backup that mustn't copy the database partway through a
     * write. Changes keep being logged to memory meanwhile, and are written after. Keep it
     * short: the writer resumes on its own after 60 seconds. Any thread. Lookups and
     * rollbacks still read, and a rollback marks what it changed once writing resumes.
     *
     * @return completes once the writer has finished the batch it was writing and is held:
     *     on the writer's thread, or at once if it isn't running
     * @since 1.1.0
     */
    CompletableFuture<Void> pause();

    /**
     * Lets the writer go on after {@link #pause()}. Does nothing if it isn't held. Any
     * thread.
     *
     * @since 1.1.0
     */
    void resume();
}
