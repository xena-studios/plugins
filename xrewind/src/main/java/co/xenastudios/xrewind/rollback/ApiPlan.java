package co.xenastudios.xrewind.rollback;

import java.util.ArrayList;
import java.util.List;

/** One of xRewind's plans as the API shows it. Its lists are copies, made when asked for. */
public record ApiPlan(Plan plan) implements co.xenastudios.xrewind.api.Plan {

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
