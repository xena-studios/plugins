package co.xenastudios.xrewind.query;

import co.xenastudios.xrewind.api.Action;
import co.xenastudios.xrewind.api.Entry;
import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.storage.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;

/** Turns the API's queries and entries into xRewind's own, and back. */
public final class ApiTypes {

    private ApiTypes() {}

    /** The API's query as xRewind's own: the same parameters /xr lookup parses. */
    public static Query query(co.xenastudios.xrewind.api.Query query) {
        int radius = switch (query.area()) {
            case RADIUS -> query.radius();
            case WORLD -> Query.WORLD;
            case GLOBAL -> Query.GLOBAL;
        };
        Set<Query.Action> actions = EnumSet.noneOf(Query.Action.class);
        query.actions().forEach(action -> actions.add(Query.Action.valueOf(action.name())));
        return new Query(query.users(), query.excludedUsers(), query.time().map(Duration::toSeconds).orElse(0L), radius,
            query.area() == co.xenastudios.xrewind.api.Query.Area.WORLD ? query.world() : null, Set.copyOf(actions),
            names(query.blocks()), names(query.excludedBlocks()), false, false, false);
    }

    /** Where the API's query measures its radius from, or null for a whole world or every world. */
    public static Lookup.Center center(co.xenastudios.xrewind.api.Query query) {
        return query.area() == co.xenastudios.xrewind.api.Query.Area.RADIUS
            ? new Lookup.Center(query.world(), query.centerX(), query.centerY(), query.centerZ()) : null;
    }

    /** A command's query as the API's, for LookupEvent. */
    public static co.xenastudios.xrewind.api.Query api(Query query, Lookup.Center center, int limit) {
        var builder = co.xenastudios.xrewind.api.Query.builder()
            .users(query.users().toArray(String[]::new))
            .excludeUsers(query.excludedUsers().toArray(String[]::new))
            .limit(limit);
        if (query.seconds() > 0) {
            builder.time(Duration.ofSeconds(query.seconds()));
        }
        String world = query.world() != null ? query.world() : center == null ? null : center.world();
        if (query.radius() >= 0 && center != null) {
            builder.radius(world, center.x(), center.y(), center.z(), query.radius());
        } else if (query.radius() != Query.GLOBAL && world != null) {
            builder.world(world);
        }
        for (Query.Action action : query.actions()) {
            builder.actions(Action.valueOf(action.name()));
        }
        builder.blocks(materials(query.blocks())).excludeBlocks(materials(query.excludedBlocks()));
        return builder.build();
    }

    /** A row as the API shows it. */
    public static Entry entry(Row row) {
        return new Entry(Instant.ofEpochMilli(Clock.toMillis(row.time())), row.user(), row.world(), row.x(), row.y(), row.z(),
            action(row), row.thing() == null ? "" : row.thing(), row.table() == Row.Table.BLOCK ? row.after() : null,
            row.table() == Row.Table.CONTAINER ? row.amount() : 0, row.rolledBack());
    }

    private static Action action(Row row) {
        byte action = row.action();
        return switch (row.table()) {
            case BLOCK -> action == Actions.BREAK ? Action.BREAK : action == Actions.CLICK ? Action.CLICK : Action.PLACE;
            case CONTAINER -> action == Actions.ADD ? Action.ADD : Action.REMOVE;
            case KILL -> Action.KILL;
            case TEXT -> action == Actions.SIGN ? Action.SIGN : action == Actions.CHAT ? Action.CHAT : Action.COMMAND;
            case SESSION -> action == Actions.JOIN ? Action.JOIN : Action.QUIT;
        };
    }

    private static List<String> names(Set<Material> materials) {
        List<String> names = new ArrayList<>(materials.size());
        materials.forEach(material -> names.add(material.getKey().getKey()));
        return List.copyOf(names);
    }

    private static Material[] materials(List<String> names) {
        return names.stream().map(Material::matchMaterial).filter(material -> material != null && material.isBlock())
            .toArray(Material[]::new);
    }
}
