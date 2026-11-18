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
 * <p>Errors: a mistake in the call (null, a query without a time where one's needed, a
 * plan applied twice) throws at once. Anything that depends on xRewind's state at the
 * time (its database not ready yet, another rollback running, a listener cancelling,
 * xRewind stopping) fails the returned future instead, so it can be handled where the
 * result is.
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
     * {@link Query#limit() limit}. It first waits up to a second for what was logged
     * before the call to be written, so a change logged a moment ago is usually included;
     * if the database is slow or failing it looks anyway, without what isn't written yet.
     * Fires {@link co.xenastudios.xrewind.api.event.LookupEvent}.
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
     * @return the plan; completes off the server's threads, or fails if the database
     *     isn't ready or can't be read
     * @throws IllegalArgumentException if the query has no time
     * @since 1.1.0
     */
    CompletableFuture<Plan> planRollback(Query query);

    /**
     * As {@link #planRollback}, for a restore: making rolled-back changes again.
     *
     * @param query what to restore; it needs a {@link Query.Builder#time time}
     * @return the plan; completes off the server's threads, or fails as
     *     {@link #planRollback}'s does
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
     * <p>A plan is applied once at most: applying it again would make its changes again,
     * on top of whatever is there by then. To roll back the same thing later, plan again.
     * To undo it, apply the result's {@link RollbackResult#undo() undo plan}. A plan that
     * wasn't applied (another rollback was running, or a listener cancelled it) can be
     * applied again.
     *
     * @param plan a plan from this API, or a result's undo plan
     * @return what it did; completes once every change is made. Fails with an
     *     {@link IllegalStateException} if another rollback is running or xRewind stops
     *     before it's done, and with a {@link java.util.concurrent.CancellationException}
     *     if a listener cancelled it
     * @throws IllegalArgumentException if the plan isn't one of this API's, such as the
     *     plan an event shows
     * @throws IllegalStateException if the plan was applied already, or is being applied
     * @since 1.1.0
     */
    CompletableFuture<RollbackResult> apply(Plan plan);

    /**
     * Writes everything logged before the call. Any thread. What's logged after doesn't
     * count, so it completes on a busy server too.
     *
     * @return completes off the server's threads: true once everything logged before the
     *     call is written and writes are going through; false after 10 seconds (while
     *     {@link #pause() paused}, say), or if the database can't be reached
     * @since 1.1.0
     */
    CompletableFuture<Boolean> flush();

    /**
     * Stops xRewind's database writes until the hold is let go: for a backup that mustn't
     * copy the database partway through a write. The writer and a purge each finish the
     * transaction they're in, then wait. Changes keep being logged to memory meanwhile,
     * and are written after. Lookups and rollbacks still read, and a rollback marks what
     * it changed once writing resumes. Any thread.
     *
     * <p>Each call is its own hold, so two plugins pausing at once don't end each other's.
     * Keep it short: a hold lets go by itself 60 seconds after it's handed over. Holds that
     * overlap can't stop writes for good: once writes have been held for 60 seconds, new
     * holds are refused until a batch has been written. Cancelling the future, or giving up
     * on it with {@code orTimeout}, lets the hold go.
     *
     * @return the hold, once nothing is writing; completes off the server's threads. Fails
     *     with a {@link java.util.concurrent.TimeoutException} if the writes running don't
     *     finish within 60 seconds, and with an {@link IllegalStateException} if holds have
     *     kept writes stopped for 60 seconds already
     * @since 1.1.0
     */
    CompletableFuture<WriterHold> pause();
}
