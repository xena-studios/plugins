package co.xenastudios.xrewind.rollback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One of xRewind's plans as the API shows it. Its lists are copies, made when asked for.
 * Applied once at most: applying it twice would make its changes twice, on top of
 * whatever was there by then.
 */
public final class ApiPlan implements co.xenastudios.xrewind.api.Plan {

    private final Plan plan;
    private final AtomicBoolean used;

    private ApiPlan(Plan plan, boolean used) {
        this.plan = plan;
        this.used = new AtomicBoolean(used);
    }

    /** A plan a plugin may apply, once. */
    public static ApiPlan fresh(Plan plan) {
        return new ApiPlan(plan, false);
    }

    /** A plan being applied or already applied, for an event: applying it fails. */
    public static ApiPlan spent(Plan plan) {
        return new ApiPlan(plan, true);
    }

    public Plan plan() {
        return plan;
    }

    /** Claims the plan for applying; false if it was claimed already. */
    public boolean claim() {
        return used.compareAndSet(false, true);
    }

    /** Gives the claim back: the plan wasn't applied after all (busy, or a listener cancelled it). */
    public void unclaim() {
        used.set(false);
    }

    @Override
    public Kind kind() {
        return switch (plan.kind()) {
            case ROLLBACK -> Kind.ROLLBACK;
            case RESTORE -> Kind.RESTORE;
            case UNDO -> Kind.UNDO;
        };
    }

    @Override
    public List<BlockChange> blocks() {
        List<BlockChange> blocks = new ArrayList<>();
        for (Plan.Change change : plan.changes()) {
            if (!change.isItems()) {
                blocks.add(new BlockChange(change.world(), change.x(), change.y(), change.z(), change.target().clone()));
            }
        }
        return List.copyOf(blocks);
    }

    @Override
    public List<ItemChange> items() {
        List<ItemChange> items = new ArrayList<>();
        for (Plan.Change change : plan.changes()) {
            // A container whose additions and removals even out has nothing to do.
            if (change.isItems() && change.item() != null && change.delta() != 0) {
                items.add(new ItemChange(change.world(), change.x(), change.y(), change.z(), change.item().asOne(), change.delta()));
            }
        }
        return List.copyOf(items);
    }

    @Override
    public int unreadable() {
        return plan.skipped();
    }
}
