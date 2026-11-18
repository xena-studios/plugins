package co.xenastudios.xrewind.rollback;

import co.xenastudios.core.db.Database;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xrewind.XRewind;
import co.xenastudios.xrewind.api.RollbackResult;
import co.xenastudios.xrewind.api.event.RollbackEvent;
import co.xenastudios.xrewind.api.event.RollbackFinishedEvent;
import co.xenastudios.xrewind.config.XRConfig;
import co.xenastudios.xrewind.log.Entry;
import co.xenastudios.xrewind.query.Lookup;
import co.xenastudios.xrewind.query.Lookups;
import co.xenastudios.xrewind.query.Query;
import io.papermc.paper.math.Position;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Rollbacks, restores, previews and undo. One runs at a time, so two can't fight over
 * the same blocks; working out what to change happens off the server's threads.
 */
public final class Rollbacks {

    public static final String GLOBAL_PERMISSION = "xrewind.rollback.global";

    private final XRewind plugin;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<UUID, Plan> previews = new ConcurrentHashMap<>();
    // Weak, so a player who left doesn't keep theirs.
    private final Map<CommandSender, Plan> undos = Collections.synchronizedMap(new WeakHashMap<>());
    // The API's rollbacks still running, to fail if xRewind stops before they finish.
    private final Set<CompletableFuture<RollbackResult>> pending = ConcurrentHashMap.newKeySet();

    public Rollbacks(XRewind plugin) {
        this.plugin = plugin;
    }

    private XRConfig.RollbackMessages messages() {
        return plugin.config().messages().rollback();
    }

    /** Rolls back (or with {@code restore}, puts back) what the query matches, or previews it. */
    public void run(CommandSender sender, Query query, boolean restore) {
        XRConfig.Rollback settings = plugin.config().rollback();
        XRConfig.RollbackMessages messages = messages();
        if (query.seconds() == 0) {
            messages.needTime().send(sender);
            return;
        }
        Lookup.Center center = Lookups.centerOf(sender);
        int radius = query.radius();
        if (center == null && (radius >= 0 || radius == Query.UNSET || radius == Query.WORLD && query.world() == null)) {
            plugin.config().messages().lookup().invalid().send(sender,
                Placeholders.text("reason", "From the console, use r:#world with w:<world>, or r:#global."));
            return;
        }
        if (radius == Query.UNSET) {
            radius = settings.defaultRadius();
            query = withRadius(query, radius);
        }
        if ((radius < 0 || radius > settings.maxRadius()) && !sender.hasPermission(GLOBAL_PERMISSION)) {
            messages.tooFar().send(sender, Placeholders.text("max", settings.maxRadius()));
            return;
        }
        Player viewer = Lookups.playerOf(sender);
        if (query.preview() && viewer == null) {
            plugin.config().errorMessages().playerOnly().send(sender);
            return;
        }
        Database database = plugin.storage().database();
        if (database == null) {
            plugin.config().messages().lookup().notReady().send(sender);
            return;
        }
        if (!query.preview() && !running.compareAndSet(false, true)) {
            messages.busy().send(sender);
            return;
        }
        Query asked = query;
        plugin.tasks().async(() -> {
            try {
                Plan plan = plan(database, asked, center, restore);
                if (plan.changes().isEmpty()) {
                    reply(sender, () -> messages.nothing().send(sender));
                    release(asked);
                } else if (asked.preview()) {
                    preview(viewer, plan);
                } else {
                    plugin.tasks().global(() -> {
                        if (allowed(plan, sender, null)) {
                            execute(sender, plan, asked.silent(), null);
                        }
                    });
                }
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "A rollback for " + sender.getName() + " failed", e);
                reply(sender, () -> plugin.config().errorMessages().failed().send(sender));
                release(asked);
            }
        });
    }

    // Off the server's threads: everything logged so far, then what to change.
    private Plan plan(Database database, Query query, Lookup.Center center, boolean restore) throws SQLException {
        plugin.writer().flush(2_000);
        return Planner.plan(
            query.wantsBlocks() ? Lookup.allBlocks(database, query, center, restore) : List.of(),
            query.wantsContainers() ? Lookup.allContainers(database, query, center, restore) : List.of(),
            restore);
    }

    /**
     * For the API: what rolling back (or restoring) would do, worked out off the server's
     * threads, as for a command. No radius limit: the calling plugin decides.
     */
    public CompletableFuture<Plan> plan(Query query, Lookup.Center center, boolean restore) {
        Database database = plugin.storage().database();
        if (database == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("xRewind's database isn't ready yet"));
        }
        CompletableFuture<Plan> future = new CompletableFuture<>();
        plugin.tasks().async(() -> {
            try {
                future.complete(plan(database, query, center, restore));
            } catch (SQLException | RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * For the API: applies a plan on the global thread, under the same one-at-a-time rule
     * as the commands. The caller claimed the plan; the claim is given back if it isn't
     * applied after all. Fails at once if another is running, if a listener cancels it,
     * and if xRewind stops before it's done.
     */
    public CompletableFuture<RollbackResult> apply(ApiPlan plan) {
        if (!running.compareAndSet(false, true)) {
            plan.unclaim();
            return CompletableFuture.failedFuture(new IllegalStateException("Another rollback is running; try again when it's done"));
        }
        CompletableFuture<RollbackResult> future = new CompletableFuture<>();
        pending.add(future);
        future.whenComplete((result, error) -> pending.remove(future));
        try {
            plugin.tasks().global(() -> {
                if (allowed(plan.plan(), null, future)) {
                    execute(null, plan.plan(), true, future);
                } else {
                    plan.unclaim();
                }
            });
        } catch (RuntimeException e) {
            // The scheduler refuses a plugin that's being disabled.
            running.set(false);
            plan.unclaim();
            future.completeExceptionally(new IllegalStateException("xRewind is stopping", e));
        }
        return future;
    }

    /** Fails the API's rollbacks still running, as xRewind stops: they'd never finish. */
    public void shutdown() {
        for (CompletableFuture<RollbackResult> future : List.copyOf(pending)) {
            future.completeExceptionally(new IllegalStateException("xRewind was disabled before the rollback finished"));
        }
    }

    // Only a request that claimed the lock gives it back: a preview never claims it.
    private void release(Query asked) {
        if (!asked.preview()) {
            running.set(false);
        }
    }

    /** Applies the player's preview. */
    public void apply(Player player) {
        Plan plan = previews.get(player.getUniqueId());
        if (plan == null) {
            messages().noPreview().send(player);
        } else if (!running.compareAndSet(false, true)) {
            messages().busy().send(player);
        } else if (allowed(plan, player, null)) {
            // Taken only once it goes ahead: a cancelled one is still there for /xr cancel.
            previews.remove(player.getUniqueId(), plan);
            execute(player, plan, false, null);
        }
    }

    /** Drops the player's preview, showing them the real blocks again. */
    public void cancel(Player player) {
        Plan plan = previews.remove(player.getUniqueId());
        if (plan == null) {
            messages().noPreview().send(player);
            return;
        }
        World world = player.getWorld();
        Map<Long, Map<Position, BlockData>> byChunk = new HashMap<>();
        for (Plan.Change change : plan.changes()) {
            if (!change.isItems() && change.world().equals(world.getName())) {
                byChunk.computeIfAbsent(((long) (change.x() >> 4) << 32) ^ (change.z() >> 4 & 0xFFFFFFFFL), key -> new HashMap<>())
                    .put(Position.block(change.x(), change.y(), change.z()), null);
            }
        }
        for (Map<Position, BlockData> chunk : byChunk.values()) {
            Position any = chunk.keySet().iterator().next();
            Bukkit.getRegionScheduler().execute(plugin, world, any.blockX() >> 4, any.blockZ() >> 4, () -> {
                chunk.replaceAll((position, ignored) -> world.getBlockData(position.blockX(), position.blockY(), position.blockZ()));
                player.getScheduler().run(plugin, task -> player.sendMultiBlockChange(chunk), null);
            });
        }
        messages().cancelled().send(player);
    }

    /** Reverts the sender's last rollback or restore. */
    public void undo(CommandSender sender) {
        Plan plan = undos.get(sender);
        if (plan == null) {
            messages().noUndo().send(sender);
        } else if (!running.compareAndSet(false, true)) {
            messages().busy().send(sender);
        } else if (allowed(plan, sender, null)) {
            // Taken only once it goes ahead: a cancelled one can be tried again.
            undos.remove(sender, plan);
            execute(sender, plan, false, null);
        }
    }

    /**
     * Asks other plugins whether it may go ahead, with running claimed; on a no, gives
     * running back and fails the API's future. The event is built only when something
     * listens for it.
     */
    private boolean allowed(Plan plan, CommandSender sender, CompletableFuture<RollbackResult> future) {
        if (RollbackEvent.getHandlerList().getRegisteredListeners().length == 0
            || new RollbackEvent(ApiPlan.spent(plan), sender).callEvent()) {
            return true;
        }
        running.set(false);
        if (future != null) {
            future.completeExceptionally(new CancellationException("A RollbackEvent listener cancelled it"));
        }
        return false;
    }

    private void preview(Player player, Plan plan) {
        // Previews of players who left without applying or cancelling go, as a new one comes.
        previews.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        previews.put(player.getUniqueId(), plan);
        Map<Position, BlockData> fake = new HashMap<>();
        for (Plan.Change change : plan.changes()) {
            if (!change.isItems() && change.world().equals(player.getWorld().getName())) {
                fake.put(Position.block(change.x(), change.y(), change.z()), change.target());
            }
        }
        reply(player, () -> {
            player.sendMultiBlockChange(fake);
            messages().preview().send(player, Placeholders.text("count", String.format("%,d", plan.changes().size())));
        });
    }

    /**
     * On the global thread (or the command's), with running already claimed and
     * {@link #allowed} asked.
     *
     * @param sender who ran the command, or null for the API
     * @param future completed with what it did, for the API; or null
     */
    private void execute(CommandSender sender, Plan plan, boolean silent, CompletableFuture<RollbackResult> future) {
        XRConfig.RollbackMessages messages = messages();
        String verb = switch (plan.kind()) {
            case ROLLBACK -> messages.verbs().rollback();
            case RESTORE -> messages.verbs().restore();
            case UNDO -> messages.verbs().undo();
        };
        ScheduledTask[] progress = new ScheduledTask[1];
        AtomicBoolean finished = new AtomicBoolean();
        Applier applier = new Applier(plugin, plugin.isFolia(), plugin.config().rollback().msPerTick(), plan, result -> {
            finished.set(true);
            // Only the rows of changes really made: skipped, unreadable or unreached ones stay
            // as they were, so a later rollback can still reach them.
            plugin.queue().add(new Entry.MarkRolledBack("block", result.blockIds(), plan.markRolledBack()));
            plugin.queue().add(new Entry.MarkRolledBack("container", result.containerIds(), plan.markRolledBack()));
            Plan undo = new Plan(Plan.Kind.UNDO, result.previous(), !plan.markRolledBack(), 0);
            if (sender == null) {
                // The API's caller keeps the undo plan; nothing is remembered here.
            } else if (plan.kind() == Plan.Kind.UNDO) {
                undos.remove(sender);
            } else {
                undos.put(sender, undo);
            }
            running.set(false);
            if (future != null || RollbackFinishedEvent.getHandlerList().getRegisteredListeners().length > 0) {
                RollbackResult done = new RollbackResult(result.applied(), plan.skipped() + result.missingWorld(),
                    Duration.ofNanos(result.nanos()), ApiPlan.fresh(undo));
                plugin.tasks().global(() -> {
                    if (RollbackFinishedEvent.getHandlerList().getRegisteredListeners().length > 0) {
                        new RollbackFinishedEvent(ApiPlan.spent(plan), done, sender).callEvent();
                    }
                    if (future != null) {
                        future.complete(done);
                    }
                });
            }
            if (progress[0] != null) {
                try {
                    progress[0].cancel();
                } catch (RuntimeException ignored) {
                    // It stops itself on its next run.
                }
            }
            if (!silent) {
                reply(sender, () -> {
                    messages.done().send(sender, Placeholders.text("verb", verb),
                        Placeholders.text("count", String.format("%,d", result.applied())),
                        Placeholders.text("seconds", seconds(result.nanos() / 1e9)));
                    int unread = plan.skipped() + result.missingWorld();
                    if (unread > 0) {
                        messages.skipped().send(sender, Placeholders.text("count", unread));
                    }
                });
            }
        });
        if (sender instanceof Player player && !silent) {
            progress[0] = player.getScheduler().runAtFixedRate(plugin, task -> {
                if (!finished.get()) {
                    player.sendActionBar(messages.progress().render(
                Placeholders.text("verb", verb), Placeholders.text("done", String.format("%,d", applier.applied())),
                        Placeholders.text("total", String.format("%,d", applier.total()))));
                }
            }, null, 20, 20);
        }
        applier.start();
    }

    /** Seconds for a message: two decimals under a second, so a quick one reads 0.04, not 0.0. */
    public static String seconds(double seconds) {
        return String.format(java.util.Locale.ROOT, seconds < 1 ? "%.2f" : "%.1f", seconds);
    }

    private void reply(CommandSender sender, Runnable send) {
        plugin.tasks().sender(sender, send);
    }

    private static Query withRadius(Query query, int radius) {
        return new Query(query.users(), query.excludedUsers(), query.seconds(), radius, query.world(), query.actions(),
            query.blocks(), query.excludedBlocks(), query.preview(), query.count(), query.silent());
    }

    /** For tests: whether one is running. */
    boolean isRunning() {
        return running.get();
    }
}
